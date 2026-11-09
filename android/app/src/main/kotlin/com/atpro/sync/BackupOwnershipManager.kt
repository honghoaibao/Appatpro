package com.atpro.sync

import androidx.room.withTransaction
import com.atpro.db.AtProDatabase
import com.atpro.db.entity.SyncMetadataEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BackupOwnershipState {
    data object Checking : BackupOwnershipState
    data object SignedOut : BackupOwnershipState
    data class NeedsConfirmation(val lastRemoteRevision: Long) : BackupOwnershipState
    data class Linked(
        val lastRemoteRevision: Long,
        val lastSuccessfulSyncAt: Long?,
    ) : BackupOwnershipState
    data object DifferentAccount : BackupOwnershipState
    data object InvalidAuthIdentity : BackupOwnershipState
    data object MetadataUnavailable : BackupOwnershipState
}

sealed interface BackupOwnerBindingResult {
    data object Bound : BackupOwnerBindingResult
    data object AlreadyBound : BackupOwnerBindingResult
    data object ConfirmationRequired : BackupOwnerBindingResult
    data object SignedOut : BackupOwnerBindingResult
    data object InvalidAuthIdentity : BackupOwnerBindingResult
    data object DifferentAccount : BackupOwnerBindingResult
    data object MetadataUnavailable : BackupOwnerBindingResult
}

sealed interface RemoteRevisionCommitResult {
    data object Recorded : RemoteRevisionCommitResult
    data object SignedOut : RemoteRevisionCommitResult
    data object InvalidAuthIdentity : RemoteRevisionCommitResult
    data object OwnerMismatch : RemoteRevisionCommitResult
    data object StaleLocalRevision : RemoteRevisionCommitResult
    data object InvalidRevision : RemoteRevisionCommitResult
    data object InvalidChecksum : RemoteRevisionCommitResult
    data object InvalidSchemaVersion : RemoteRevisionCommitResult
    data object MetadataUnavailable : RemoteRevisionCommitResult
}

/**
 * Owns the local backup ownership boundary and the durable checkpoint written after
 * a future server-confirmed revision commit. No method in this class performs network I/O.
 *
 * There is deliberately no unbind/reassign-owner API. A different Auth user is blocked
 * until a separate, explicit account-switch workflow is designed.
 */
class BackupOwnershipManager(
    private val database: AtProDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<BackupOwnershipState>(BackupOwnershipState.Checking)
    val state: StateFlow<BackupOwnershipState> = mutableState.asStateFlow()

    suspend fun refresh(authUserId: String?) = mutex.withLock {
        mutableState.value = resolveState(authUserId)
    }

    /** Fresh ownership check for future sync entry points; false fails closed. */
    suspend fun isBoundToCurrentUser(authUserId: String?): Boolean {
        val ownerId = BackupMetadataRules.normalizeAuthUserId(authUserId) ?: return false
        val metadata = readMetadataOrNull() ?: return false
        return BackupMetadataRules.isStructurallyValid(metadata) && metadata.ownerUserId == ownerId
    }

    /**
     * Bind only if the UI has just received a positive confirmation from the user.
     * Calling with false or without an Auth user never changes Room.
     */
    suspend fun bindToCurrentUser(
        authUserId: String?,
        confirmedByUser: Boolean,
    ): BackupOwnerBindingResult = mutex.withLock {
        val ownerId = BackupMetadataRules.normalizeAuthUserId(authUserId)
            ?: return@withLock if (authUserId.isNullOrBlank()) {
                BackupOwnerBindingResult.SignedOut
            } else {
                BackupOwnerBindingResult.InvalidAuthIdentity
            }

        if (!confirmedByUser) return@withLock BackupOwnerBindingResult.ConfirmationRequired

        val outcome = database.withTransaction {
            val dao = database.syncMetadataDao()
            val metadata = dao.get() ?: return@withTransaction BindingDecision.MissingMetadata
            if (!BackupMetadataRules.isStructurallyValid(metadata)) {
                return@withTransaction BindingDecision.MissingMetadata
            }

            val existingOwner = metadata.ownerUserId
            when {
                existingOwner == null -> {
                    val now = nowEpochMillis()
                    val updated = dao.bindOwnerIfUnbound(
                        ownerUserId = ownerId,
                        boundAt = now,
                        updatedAt = now,
                    )
                    if (updated == 1) {
                        BindingDecision.Bound
                    } else {
                        val latestOwner = dao.get()?.ownerUserId
                        if (latestOwner == ownerId) BindingDecision.AlreadyBound
                        else if (latestOwner == null) BindingDecision.MissingMetadata
                        else BindingDecision.DifferentAccount
                    }
                }
                BackupMetadataRules.normalizeAuthUserId(existingOwner) == null ->
                    BindingDecision.MissingMetadata
                existingOwner == ownerId -> BindingDecision.AlreadyBound
                else -> BindingDecision.DifferentAccount
            }
        }

        mutableState.value = resolveState(authUserId)
        when (outcome) {
            BindingDecision.Bound -> BackupOwnerBindingResult.Bound
            BindingDecision.AlreadyBound -> BackupOwnerBindingResult.AlreadyBound
            BindingDecision.DifferentAccount -> BackupOwnerBindingResult.DifferentAccount
            BindingDecision.MissingMetadata -> BackupOwnerBindingResult.MetadataUnavailable
        }
    }

    /**
     * Persist the new local revision only after a future RPC reports a committed R -> R+1.
     * It uses owner + expected-revision predicates so a late response cannot overwrite
     * a newer local checkpoint or attach itself to a different Auth user.
     */
    suspend fun recordCommittedSnapshot(
        authUserId: String?,
        expectedCurrentRevision: Long,
        committedRevision: Long,
        checksum: String,
        schemaVersion: Int,
    ): RemoteRevisionCommitResult = mutex.withLock {
        val ownerId = BackupMetadataRules.normalizeAuthUserId(authUserId)
            ?: return@withLock if (authUserId.isNullOrBlank()) {
                RemoteRevisionCommitResult.SignedOut
            } else {
                RemoteRevisionCommitResult.InvalidAuthIdentity
            }

        if (!BackupMetadataRules.isNextRevision(expectedCurrentRevision, committedRevision)) {
            return@withLock RemoteRevisionCommitResult.InvalidRevision
        }
        if (!BackupMetadataRules.isValidChecksum(checksum)) {
            return@withLock RemoteRevisionCommitResult.InvalidChecksum
        }
        if (schemaVersion <= 0) {
            return@withLock RemoteRevisionCommitResult.InvalidSchemaVersion
        }

        val outcome = database.withTransaction {
            val dao = database.syncMetadataDao()
            val metadata = dao.get() ?: return@withTransaction CommitDecision.MissingMetadata
            if (!BackupMetadataRules.isStructurallyValid(metadata)) {
                return@withTransaction CommitDecision.MissingMetadata
            }
            if (metadata.ownerUserId != ownerId) {
                return@withTransaction CommitDecision.OwnerMismatch
            }
            if (metadata.lastRemoteRevision != expectedCurrentRevision) {
                return@withTransaction CommitDecision.StaleRevision
            }

            val confirmedAt = nowEpochMillis()
            val changed = dao.recordCommittedRevision(
                ownerUserId = ownerId,
                expectedRevision = expectedCurrentRevision,
                committedRevision = committedRevision,
                checksum = checksum,
                formatVersion = schemaVersion,
                confirmedAt = confirmedAt,
            )
            if (changed == 1) CommitDecision.Recorded else CommitDecision.StaleRevision
        }

        mutableState.value = resolveState(authUserId)
        when (outcome) {
            CommitDecision.Recorded -> RemoteRevisionCommitResult.Recorded
            CommitDecision.OwnerMismatch -> RemoteRevisionCommitResult.OwnerMismatch
            CommitDecision.StaleRevision -> RemoteRevisionCommitResult.StaleLocalRevision
            CommitDecision.MissingMetadata -> RemoteRevisionCommitResult.MetadataUnavailable
        }
    }

    private suspend fun resolveState(authUserId: String?): BackupOwnershipState {
        val ownerId = BackupMetadataRules.normalizeAuthUserId(authUserId)
            ?: return if (authUserId.isNullOrBlank()) {
                BackupOwnershipState.SignedOut
            } else {
                BackupOwnershipState.InvalidAuthIdentity
            }

        val metadata = readMetadataOrNull()
            ?: return BackupOwnershipState.MetadataUnavailable
        if (!BackupMetadataRules.isStructurallyValid(metadata)) {
            return BackupOwnershipState.MetadataUnavailable
        }

        val boundOwner = metadata.ownerUserId
            ?: return BackupOwnershipState.NeedsConfirmation(metadata.lastRemoteRevision)
        return if (boundOwner == ownerId) {
            BackupOwnershipState.Linked(
                lastRemoteRevision = metadata.lastRemoteRevision,
                lastSuccessfulSyncAt = metadata.lastSuccessfulSyncAt,
            )
        } else {
            BackupOwnershipState.DifferentAccount
        }
    }


    private suspend fun readMetadataOrNull(): SyncMetadataEntity? = try {
        database.syncMetadataDao().get()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private enum class BindingDecision {
        Bound,
        AlreadyBound,
        DifferentAccount,
        MissingMetadata,
    }

    private enum class CommitDecision {
        Recorded,
        OwnerMismatch,
        StaleRevision,
        MissingMetadata,
    }
}
