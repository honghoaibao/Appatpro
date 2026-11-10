package com.atpro.sync

import android.content.Context
import androidx.room.withTransaction
import com.atpro.auth.domain.AuthSession
import com.atpro.data.EarningsDirtyState
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import kotlinx.coroutines.CancellationException

data class BackupAcknowledgementPlan(
    val eventIds: List<String>,
    val requiredTombstones: List<BackupTombstone>,
    val blockedEventId: String?,
)

sealed interface BackupAcknowledgementPlanResult {
    data class Ready(val plan: BackupAcknowledgementPlan) : BackupAcknowledgementPlanResult
    data class Rejected(val reason: String) : BackupAcknowledgementPlanResult
}

/** Determines exactly which captured Outbox prefix is represented by the committed snapshot. */
object BackupAcknowledgementPolicy {
    fun plan(
        build: BackupSnapshotBuildResult,
        committedSnapshot: BackupSnapshot,
    ): BackupAcknowledgementPlanResult {
        val events = build.outboxEvents
        if (events.size > SyncBatchPolicy.MAX_EVENTS_PER_BATCH) {
            return BackupAcknowledgementPlanResult.Rejected("captured_outbox_limit_exceeded")
        }
        if (events.map { it.eventId }.toSet().size != events.size ||
            events.any {
                !BackupMetadataRules.isCanonicalUuid(it.eventId) ||
                    it.sequence <= 0L || it.dataset.isBlank() || it.entityKey.isBlank() ||
                    it.entityKey != it.entityKey.trim() ||
                    it.operation != "UPSERT" && it.operation != "DELETE"
            } ||
            events.zipWithNext().any { (left, right) -> right.sequence <= left.sequence }
        ) {
            return BackupAcknowledgementPlanResult.Rejected("captured_outbox_identity_invalid")
        }

        val datasets = committedSnapshot.datasets
        val tombstonesByKey = datasets.tombstones.groupBy { it.dataset to it.entityKey }
        val tombstonesById = datasets.tombstones.associateBy { it.tombstoneId }
        val ackIds = ArrayList<String>(events.size)
        val requiredTombstones = LinkedHashMap<String, BackupTombstone>()
        var blockedEventId: String? = null

        for (event in events) {
            val tombstonesForKey = tombstonesByKey[event.dataset to event.entityKey].orEmpty()
            val matchingDeleteTombstone = tombstonesById[event.eventId]?.takeIf {
                it.dataset == event.dataset && it.entityKey == event.entityKey
            }
            val tombstone = when (event.operation) {
                "DELETE" -> matchingDeleteTombstone
                else -> tombstonesForKey.firstOrNull()
            }
            val liveEntityExists = when (event.dataset) {
                "accounts" -> datasets.accounts.any {
                    it.username.trim().removePrefix("@") == event.entityKey
                }
                "closedSessions" -> datasets.closedSessions.any { it.syncId == event.entityKey }
                "safeConfigs" -> datasets.safeConfigs.any { it.key == event.entityKey }
                "schedules" -> datasets.schedules.any { it.scheduleId == event.entityKey }
                else -> false
            }
            val represented = when (event.operation) {
                "UPSERT" -> liveEntityExists || tombstone != null
                "DELETE" -> tombstone != null &&
                    tombstone.sourceInstallId == build.snapshot.sourceInstallId
                else -> false
            }
            if (!represented) {
                blockedEventId = event.eventId
                break
            }
            ackIds += event.eventId
            tombstone?.let { requiredTombstones[it.tombstoneId] = it }
        }

        return BackupAcknowledgementPlanResult.Ready(
            BackupAcknowledgementPlan(
                eventIds = ackIds,
                requiredTombstones = requiredTombstones.values.sortedBy { it.tombstoneId },
                blockedEventId = blockedEventId,
            )
        )
    }
}

sealed interface BackupRoomAcknowledgementResult {
    data class Applied(val deletedEventCount: Int) : BackupRoomAcknowledgementResult
    data class Conflict(val reason: String) : BackupRoomAcknowledgementResult
}

internal interface BackupAcknowledgementRestorePort {
    suspend fun restore(snapshot: BackupSnapshot, ownerUserId: String)
}

internal interface BackupAcknowledgementRoomPort {
    suspend fun acknowledge(
        ownerUserId: String,
        committed: BackupCasResult.Committed,
        plan: BackupAcknowledgementPlan,
    ): BackupRoomAcknowledgementResult
}

internal interface BackupAcknowledgementEarningsPort {
    fun acknowledgeThrough(generation: Long): Boolean
    fun dirtyState(): EarningsDirtyState
}

sealed interface BackupAcknowledgementResult {
    data class Acknowledged(
        val deletedOutboxEvents: Int,
        val earningsGeneration: Long,
    ) : BackupAcknowledgementResult

    data class Partial(
        val deletedOutboxEvents: Int,
        val blockedEventId: String,
        val earningsGeneration: Long,
    ) : BackupAcknowledgementResult

    data class RetryRequired(val reason: String) : BackupAcknowledgementResult
    data class Rejected(val reason: String) : BackupAcknowledgementResult
}

/**
 * Performs local acknowledgements only after the server CAS result is confirmed.
 * Room checkpoint + exact Outbox deletion share one transaction. Earnings dirty-generation
 * acknowledgement is separate and monotonic; tombstone rows themselves are never deleted.
 */
class BackupAcknowledgementCoordinator internal constructor(
    private val restore: BackupAcknowledgementRestorePort,
    private val room: BackupAcknowledgementRoomPort,
    private val earnings: BackupAcknowledgementEarningsPort,
    private val refreshOwnership: suspend (String) -> Unit = {},
) {
    constructor(
        context: Context,
        ownershipManager: BackupOwnershipManager,
    ) : this(
        LocalBackupRestorePort(context.applicationContext),
        RoomBackupAcknowledgementPort(AtProDatabase.getInstance(context.applicationContext)),
        object : BackupAcknowledgementEarningsPort {
            override fun acknowledgeThrough(generation: Long): Boolean =
                EarningsStore.acknowledgeThrough(generation)

            override fun dirtyState(): EarningsDirtyState = EarningsStore.dirtyState()
        },
        { ownerId -> ownershipManager.refresh(ownerId) },
    ) {
        EarningsStore.init(context.applicationContext)
    }

    suspend fun acknowledge(
        session: AuthSession,
        committed: BackupCasResult.Committed,
    ): BackupAcknowledgementResult {
        val ownerId = BackupMetadataRules.normalizeAuthUserId(session.user.id)
            ?: return BackupAcknowledgementResult.Rejected("invalid_authenticated_owner")
        val build = committed.buildResult
        if (build.ownerUserId != ownerId ||
            committed.revision <= 0L ||
            committed.expectedRemoteRevision < build.localExpectedRevision ||
            committed.expectedRemoteRevision == Long.MAX_VALUE ||
            committed.revision != committed.expectedRemoteRevision + 1L ||
            build.localExpectedRevision < 0L ||
            build.earningsGeneration < 0L ||
            !BackupMetadataRules.isValidChecksum(committed.checksum) ||
            committed.snapshot.formatVersion != BackupSnapshot.CURRENT_FORMAT_VERSION ||
            committed.snapshot.sourceInstallId != build.snapshot.sourceInstallId ||
            committed.attempts !in 1..BackupCasCoordinator.MAX_CAS_ATTEMPTS
        ) {
            return BackupAcknowledgementResult.Rejected("committed_snapshot_metadata_invalid")
        }
        try {
            BackupSnapshotValidator.validate(committed.snapshot)
        } catch (_: Exception) {
            return BackupAcknowledgementResult.Rejected("committed_snapshot_invalid")
        }

        val plan = when (val result = BackupAcknowledgementPolicy.plan(build, committed.snapshot)) {
            is BackupAcknowledgementPlanResult.Rejected ->
                return BackupAcknowledgementResult.Rejected(result.reason)
            is BackupAcknowledgementPlanResult.Ready -> result.plan
        }

        try {
            restore.restore(committed.snapshot, ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupAcknowledgementResult.RetryRequired("local_restore_failed")
        }

        val roomResult = try {
            room.acknowledge(ownerId, committed, plan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupAcknowledgementResult.RetryRequired("room_acknowledgement_failed")
        }
        val deletedCount = when (roomResult) {
            is BackupRoomAcknowledgementResult.Conflict ->
                return BackupAcknowledgementResult.RetryRequired(roomResult.reason)
            is BackupRoomAcknowledgementResult.Applied -> roomResult.deletedEventCount
        }

        try {
            refreshOwnership(ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable checkpoint is committed. A stale in-memory ownership flow is recoverable.
        }

        try {
            earnings.acknowledgeThrough(build.earningsGeneration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Verify the durable generation below; an earlier replay may already have acked it.
        }
        val earningsState = try {
            earnings.dirtyState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackupAcknowledgementResult.RetryRequired("earnings_ack_state_unavailable")
        }
        if (earningsState.acknowledgedGeneration < build.earningsGeneration) {
            return BackupAcknowledgementResult.RetryRequired("earnings_acknowledgement_pending")
        }

        return if (plan.blockedEventId != null) {
            BackupAcknowledgementResult.Partial(
                deletedOutboxEvents = deletedCount,
                blockedEventId = plan.blockedEventId,
                earningsGeneration = build.earningsGeneration,
            )
        } else {
            BackupAcknowledgementResult.Acknowledged(
                deletedOutboxEvents = deletedCount,
                earningsGeneration = build.earningsGeneration,
            )
        }
    }
}

private class RoomBackupAcknowledgementPort(
    private val database: AtProDatabase,
) : BackupAcknowledgementRoomPort {
    override suspend fun acknowledge(
        ownerUserId: String,
        committed: BackupCasResult.Committed,
        plan: BackupAcknowledgementPlan,
    ): BackupRoomAcknowledgementResult {
        return try {
            database.withTransaction {
                val build = committed.buildResult
                val metadataDao = database.syncMetadataDao()
                val metadata = metadataDao.get()
                    ?: return@withTransaction BackupRoomAcknowledgementResult.Conflict("sync_metadata_missing")
                if (!BackupMetadataRules.isStructurallyValid(metadata)) {
                    return@withTransaction BackupRoomAcknowledgementResult.Conflict("sync_metadata_invalid")
                }
                if (metadata.ownerUserId != ownerUserId ||
                    metadata.installationId != committed.snapshot.sourceInstallId
                ) {
                    return@withTransaction BackupRoomAcknowledgementResult.Conflict("owner_or_installation_changed")
                }

                val alreadyCommitted =
                    metadata.lastRemoteRevision == committed.revision &&
                        metadata.lastChecksum == committed.checksum &&
                        metadata.formatVersion == committed.snapshot.formatVersion
                if (!alreadyCommitted && metadata.lastRemoteRevision != build.localExpectedRevision) {
                    return@withTransaction BackupRoomAcknowledgementResult.Conflict("local_revision_changed")
                }

                val capturedById = build.outboxEvents.associateBy { it.eventId }
                if (plan.eventIds.any { it !in capturedById }) {
                    return@withTransaction BackupRoomAcknowledgementResult.Conflict("ack_event_not_in_captured_snapshot")
                }
                val outboxDao = database.syncOutboxDao()
                val currentRows = if (plan.eventIds.isEmpty()) {
                    emptyList()
                } else {
                    outboxDao.getByEventIds(plan.eventIds)
                }
                for (row in currentRows) {
                    val captured = capturedById[row.eventId]
                        ?: return@withTransaction BackupRoomAcknowledgementResult.Conflict("ack_event_identity_missing")
                    if (row.sequence != captured.sequence ||
                        row.dataset != captured.dataset ||
                        row.entityKey != captured.entityKey ||
                        row.operation != captured.operation
                    ) {
                        return@withTransaction BackupRoomAcknowledgementResult.Conflict("ack_event_identity_changed")
                    }
                }

                val requiredTombstones = plan.requiredTombstones
                val tombstoneRows = if (requiredTombstones.isEmpty()) {
                    emptyList()
                } else {
                    database.backupTombstoneDao().getByIds(requiredTombstones.map { it.tombstoneId })
                }
                if (tombstoneRows.size != requiredTombstones.size) {
                    return@withTransaction BackupRoomAcknowledgementResult.Conflict("captured_tombstone_missing")
                }
                val tombstonesById = tombstoneRows.associateBy { it.tombstoneId }
                for (captured in requiredTombstones) {
                    val row = tombstonesById[captured.tombstoneId]
                    if (row == null ||
                        row.dataset != captured.dataset ||
                        row.entityKey != captured.entityKey ||
                        row.sourceInstallId != captured.sourceInstallId ||
                        row.deletedAt != captured.deletedAt
                    ) {
                        return@withTransaction BackupRoomAcknowledgementResult.Conflict("captured_tombstone_changed")
                    }
                }

                if (!alreadyCommitted) {
                    val changed = metadataDao.recordCommittedRevision(
                        ownerUserId = ownerUserId,
                        expectedRevision = build.localExpectedRevision,
                        committedRevision = committed.revision,
                        checksum = committed.checksum,
                        formatVersion = committed.snapshot.formatVersion,
                        confirmedAt = System.currentTimeMillis(),
                    )
                    if (changed != 1) throw AcknowledgementTransactionConflict("local_revision_compare_and_set_failed")
                }

                val deleted = if (plan.eventIds.isEmpty()) 0 else outboxDao.deleteAcknowledged(plan.eventIds)
                if (deleted != currentRows.size) {
                    throw AcknowledgementTransactionConflict("outbox_exact_delete_count_mismatch")
                }
                BackupRoomAcknowledgementResult.Applied(deleted)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AcknowledgementTransactionConflict) {
            BackupRoomAcknowledgementResult.Conflict(error.message ?: "room_acknowledgement_conflict")
        } catch (_: Exception) {
            BackupRoomAcknowledgementResult.Conflict("room_acknowledgement_failed")
        }
    }
}

private class AcknowledgementTransactionConflict(message: String) : Exception(message)


private class LocalBackupRestorePort(context: Context) : BackupAcknowledgementRestorePort {
    private val coordinator = BackupRestoreCoordinator(context.applicationContext)

    override suspend fun restore(snapshot: BackupSnapshot, ownerUserId: String) {
        coordinator.restore(snapshot, ownerUserId)
    }
}
