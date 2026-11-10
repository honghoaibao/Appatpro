package com.atpro.sync

import com.atpro.auth.domain.AuthSession
import kotlinx.coroutines.CancellationException

fun interface BackupSnapshotProvider {
    suspend fun buildSnapshot(): BackupSnapshot
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
        val snapshot: BackupSnapshot,
        val revision: Long,
        val attempts: Int,
        val checksum: String,
    ) : BackupCasResult
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
) {
    suspend fun run(session: AuthSession): BackupCasResult {
        // Keep the owner identity and JWT together as one AuthRepository-issued value.
        val ownerId = BackupMetadataRules.normalizeAuthUserId(session.user.id)
            ?: return BackupCasResult.Rejected("A valid authenticated owner is required")
        val token = session.accessToken.trim().takeIf { it.isNotEmpty() }
            ?: return BackupCasResult.Rejected("An authenticated access token is required")
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
            val local = try {
                snapshotProvider.buildSnapshot().also {
                    BackupSnapshotValidator.validate(it)
                    if (it.formatVersion != BackupSnapshot.CURRENT_FORMAT_VERSION) {
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

            val remote = when (val read = remoteStore.read(ownerId, token)) {
                BackupRemoteReadResult.Missing -> null
                is BackupRemoteReadResult.Failed -> return BackupCasResult.Failed(read.failure)
                is BackupRemoteReadResult.Found -> read.record
            }
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
            val expectedRevision = remote?.revision ?: 0L
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

            when (val commit = remoteStore.commit(expectedRevision, encoded, token)) {
                is BackupRemoteCommitResult.Committed -> {
                    if (commit.revision != expectedRevision + 1L) {
                        return BackupCasResult.Failed(
                            BackupRemoteFailure("invalid_committed_revision", retryable = false)
                        )
                    }
                    return BackupCasResult.Committed(candidate, commit.revision, attempt, encoded.checksum)
                }
                BackupRemoteCommitResult.RevisionConflict -> Unit
                is BackupRemoteCommitResult.Failed -> return BackupCasResult.Failed(commit.failure)
            }
        }
        return BackupCasResult.RetryLater(MAX_CAS_ATTEMPTS)
    }

    companion object {
        const val MAX_CAS_ATTEMPTS = 3
        private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")
    }
}
