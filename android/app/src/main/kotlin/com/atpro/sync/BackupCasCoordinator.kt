package com.atpro.sync

import com.atpro.auth.domain.AuthSession
import kotlinx.coroutines.CancellationException

fun interface BackupSnapshotProvider {
    suspend fun buildSnapshot(): BackupSnapshotBuildResult
}

/** Local Room owner binding must be confirmed before any remote request. */
fun interface BackupOwnerBindingValidator {
    suspend fun isBoundToOwner(ownerUserId: String): Boolean
}

interface BackupSnapshotCodecPort {
    fun encode(snapshot: BackupSnapshot): EncodedBackupPayload
    fun decode(payload: String, checksum: String, schemaVersion: Int): BackupSnapshot
}

object AndroidBackupSnapshotCodec : BackupSnapshotCodecPort {
    override fun encode(snapshot: BackupSnapshot): EncodedBackupPayload = BackupCodec.encode(snapshot)

    override fun decode(
        payload: String,
        checksum: String,
        schemaVersion: Int,
    ): BackupSnapshot = BackupCodec.decode(payload, checksum, schemaVersion)
}

sealed interface BackupCasResult {
    data class Committed(
        val buildResult: BackupSnapshotBuildResult,
        /** The merged candidate that the server confirmed, not merely the local source snapshot. */
        val snapshot: BackupSnapshot,
        val expectedRemoteRevision: Long,
        val revision: Long,
        val attempts: Int,
        val checksum: String,
    ) : BackupCasResult
    data class UpToDate(val revision: Long) : BackupCasResult
    data class RestoreRequired(
        val snapshot: BackupSnapshot,
        val checkpoint: BackupRestoreCheckpoint,
    ) : BackupCasResult
    data object EmptyLocalNoRemoteBackup : BackupCasResult
    data object SessionInvalidated : BackupCasResult
    data class Conflicted(val conflicts: List<BackupMergeConflict>) : BackupCasResult
    data class RetryLater(val attempts: Int) : BackupCasResult
    data class Failed(val failure: BackupRemoteFailure) : BackupCasResult
    data class Rejected(val reason: String) : BackupCasResult
}

/**
 * Bounded read/merge/CAS loop. Every conflict retry re-reads the remote row and rebuilds
 * the local snapshot. It never acknowledges Outbox/earnings, updates the local revision
 * checkpoint, applies restore data or activates a worker.
 */
class BackupCasCoordinator(
    private val remoteStore: BackupRemoteStore,
    private val snapshotProvider: BackupSnapshotProvider,
    private val ownerBindingValidator: BackupOwnerBindingValidator,
    private val codec: BackupSnapshotCodecPort = AndroidBackupSnapshotCodec,
    private val executionGuard: SyncSessionGuard? = null,
) {
    suspend fun run(
        session: AuthSession,
        executionContext: SyncExecutionContext? = null,
    ): BackupCasResult {
        // Keep the owner identity and JWT together as one AuthRepository-issued value.
        val ownerId = BackupMetadataRules.normalizeAuthUserId(session.user.id)
            ?: return BackupCasResult.Rejected("A valid authenticated owner is required")
        val token = session.accessToken.trim().takeIf { it.isNotEmpty() }
            ?: return BackupCasResult.Rejected("An authenticated access token is required")
        if (executionGuard != null) {
            val context = executionContext ?: return BackupCasResult.SessionInvalidated
            if (context.ownerUserId != ownerId || context.installationId.isBlank() ||
                !executionGuard.isCurrent(context)
            ) return BackupCasResult.SessionInvalidated
        }
        val isLocallyBound = try {
            ownerBindingValidator.isBoundToOwner(ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!isLocallyBound) {
            return BackupCasResult.Rejected("Local backup ownership does not match the authenticated user")
        }

        repeat(MAX_CAS_ATTEMPTS) { index ->
            val attempt = index + 1
            val localBuild = try {
                snapshotProvider.buildSnapshot().also {
                    if (it.ownerUserId != ownerId || it.localExpectedRevision < 0L ||
                        it.outboxEvents.size > SyncBatchPolicy.MAX_EVENTS_PER_BATCH
                    ) {
                        throw BackupSnapshotException("Local snapshot metadata does not match the authenticated owner")
                    }
                    BackupSnapshotValidator.validate(it.snapshot)
                    if (it.snapshot.formatVersion != BackupSnapshot.CURRENT_FORMAT_VERSION) {
                        throw BackupSnapshotException("Local snapshot must use v3")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return BackupCasResult.Failed(
                    BackupRemoteFailure("local_snapshot_invalid", retryable = false)
                )
            }

            if (executionContext != null &&
                localBuild.snapshot.sourceInstallId != executionContext.installationId
            ) return BackupCasResult.SessionInvalidated
            if (!isExecutionCurrent(executionContext)) return BackupCasResult.SessionInvalidated

            when (BackupAcknowledgementPolicy.plan(localBuild, localBuild.snapshot)) {
                is BackupAcknowledgementPlanResult.Rejected ->
                    return BackupCasResult.Failed(
                        BackupRemoteFailure("local_outbox_capture_invalid", retryable = false)
                    )
                is BackupAcknowledgementPlanResult.Ready -> Unit
            }

            val local = localBuild.snapshot
            if (!isExecutionCurrent(executionContext)) return BackupCasResult.SessionInvalidated
            val remote = when (val read = remoteStore.read(ownerId, token)) {
                BackupRemoteReadResult.Missing -> null
                is BackupRemoteReadResult.Failed -> return BackupCasResult.Failed(read.failure)
                is BackupRemoteReadResult.Found -> read.record
            }
            if (!isExecutionCurrent(executionContext)) return BackupCasResult.SessionInvalidated
            if (remote != null && (
                    remote.revision < 0L ||
                        remote.schemaVersion !in setOf(
                            BackupSnapshot.LEGACY_FORMAT_VERSION,
                            BackupSnapshot.CURRENT_FORMAT_VERSION,
                        ) ||
                        remote.payload.isBlank() ||
                        remote.payload.length % 4 != 0 ||
                        !BASE64_PATTERN.matches(remote.payload) ||
                        remote.payload.toByteArray(Charsets.UTF_8).size >
                            BackupCodec.MAX_ENCODED_PAYLOAD_BYTES ||
                        !BackupMetadataRules.isValidChecksum(remote.checksum)
                    )
            ) {
                return BackupCasResult.Failed(
                    BackupRemoteFailure("invalid_remote_record", retryable = false)
                )
            }

            val expectedRevision = remote?.revision ?: 0L
            if (expectedRevision < localBuild.localExpectedRevision) {
                return BackupCasResult.Failed(
                    BackupRemoteFailure("remote_revision_behind_local_checkpoint", retryable = false)
                )
            }

            // Never create a cloud row from a blank installation. An empty local state with
            // a non-zero checkpoint is already rejected above when the remote row is missing.
            if (remote == null && BackupLocalStatePolicy.isTrulyEmpty(localBuild)) {
                return BackupCasResult.EmptyLocalNoRemoteBackup
            }

            // A genuinely empty local installation must restore a validated remote snapshot
            // before any upload decision, even when its stored checkpoint/checksum looks current.
            if (remote != null && BackupLocalStatePolicy.isTrulyEmpty(localBuild)) {
                val remoteSnapshot = try {
                    codec.decode(remote.payload, remote.checksum, remote.schemaVersion).also {
                        BackupSnapshotValidator.validate(it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return BackupCasResult.Failed(
                        BackupRemoteFailure("remote_snapshot_invalid", retryable = false)
                    )
                }
                val merged = when (val merge = try {
                    BackupMergeEngine.merge(local, remoteSnapshot)
                } catch (_: Exception) {
                    return BackupCasResult.Failed(
                        BackupRemoteFailure("snapshot_merge_invalid", retryable = false)
                    )
                }) {
                    is BackupMergeResult.Conflicted -> return BackupCasResult.Conflicted(merge.conflicts)
                    is BackupMergeResult.Merged -> merge.snapshot
                }
                return BackupCasResult.RestoreRequired(
                    snapshot = merged,
                    checkpoint = BackupRestoreCheckpoint(
                        expectedLocalRevision = localBuild.localExpectedRevision,
                        remoteRevision = remote.revision,
                        remoteChecksum = remote.checksum,
                        remoteSchemaVersion = remote.schemaVersion,
                    ),
                )
            }

            // An app-entry/periodic check must not create a new cloud revision when this
            // installation has no pending mutations and already holds this exact v3 revision.
            // A checksum mismatch or legacy schema follows the normal decode/merge/CAS path.
            if (remote != null &&
                expectedRevision == localBuild.localExpectedRevision &&
                localBuild.outboxEvents.isEmpty() &&
                !localBuild.earningsDirty &&
                remote.schemaVersion == BackupSnapshot.CURRENT_FORMAT_VERSION &&
                localBuild.localExpectedChecksum != null &&
                remote.checksum == localBuild.localExpectedChecksum
            ) {
                return BackupCasResult.UpToDate(expectedRevision)
            }

            val candidate = if (remote == null) {
                local
            } else {
                val remoteSnapshot = try {
                    codec.decode(remote.payload, remote.checksum, remote.schemaVersion).also {
                        BackupSnapshotValidator.validate(it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return BackupCasResult.Failed(
                        BackupRemoteFailure("remote_snapshot_invalid", retryable = false)
                    )
                }
                when (val merge = try {
                    BackupMergeEngine.merge(local, remoteSnapshot)
                } catch (_: Exception) {
                    return BackupCasResult.Failed(
                        BackupRemoteFailure("snapshot_merge_invalid", retryable = false)
                    )
                }) {
                    is BackupMergeResult.Conflicted -> return BackupCasResult.Conflicted(merge.conflicts)
                    is BackupMergeResult.Merged -> merge.snapshot
                }
            }

            val encoded = try {
                codec.encode(candidate)
            } catch (_: Exception) {
                return BackupCasResult.Failed(
                    BackupRemoteFailure("snapshot_encode_failed", retryable = false)
                )
            }
            if (expectedRevision == Long.MAX_VALUE ||
                encoded.schemaVersion != BackupSnapshot.CURRENT_FORMAT_VERSION ||
                encoded.encodedBytes <= 0 ||
                encoded.encodedBytes != encoded.payload.toByteArray(Charsets.UTF_8).size ||
                encoded.encodedBytes > BackupCodec.MAX_ENCODED_PAYLOAD_BYTES ||
                encoded.uncompressedBytes <= 0 ||
                encoded.uncompressedBytes > BackupCodec.MAX_UNCOMPRESSED_BYTES ||
                !BASE64_PATTERN.matches(encoded.payload) ||
                encoded.payload.length % 4 != 0 ||
                !BackupMetadataRules.isValidChecksum(encoded.checksum)
            ) {
                return BackupCasResult.Failed(
                    BackupRemoteFailure("snapshot_commit_bounds_invalid", retryable = false)
                )
            }

            val commitResult = if (executionGuard != null && executionContext != null) {
                when (val guarded = executionGuard.withLease(executionContext) {
                    remoteStore.commit(expectedRevision, encoded, token)
                }) {
                    SyncGuardExecution.Invalidated -> return BackupCasResult.SessionInvalidated
                    is SyncGuardExecution.Completed -> guarded.value
                }
            } else {
                remoteStore.commit(expectedRevision, encoded, token)
            }

            when (val commit = commitResult) {
                is BackupRemoteCommitResult.Committed -> {
                    if (commit.revision != expectedRevision + 1L) {
                        return BackupCasResult.Failed(
                            BackupRemoteFailure("invalid_committed_revision", retryable = false)
                        )
                    }
                    return BackupCasResult.Committed(
                        buildResult = localBuild,
                        snapshot = candidate,
                        expectedRemoteRevision = expectedRevision,
                        revision = commit.revision,
                        attempts = attempt,
                        checksum = encoded.checksum,
                    )
                }
                BackupRemoteCommitResult.RevisionConflict -> Unit
                is BackupRemoteCommitResult.Failed -> return BackupCasResult.Failed(commit.failure)
            }
        }
        return BackupCasResult.RetryLater(MAX_CAS_ATTEMPTS)
    }

    private suspend fun isExecutionCurrent(context: SyncExecutionContext?): Boolean =
        when {
            executionGuard == null -> true
            context == null -> false
            else -> executionGuard.isCurrent(context)
        }

    companion object {
        const val MAX_CAS_ATTEMPTS = 3
        private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")
    }
}
