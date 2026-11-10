package com.atpro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.atpro.AtProApplication
import com.atpro.auth.domain.AuthResult
import com.atpro.auth.domain.AuthSession
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import kotlinx.coroutines.CancellationException

/**
 * Executes one bounded cloud backup batch.
 *
 * Room remains the local source of truth. A worker run can only advance the user's remote
 * revision through the server CAS RPC, then apply the server-confirmed merge locally and
 * acknowledge exact captured Outbox IDs / Earnings generation. It never starts automation.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AtProApplication
            ?: return completed(STATUS_APPLICATION_UNAVAILABLE)

        return try {
            EarningsStore.init(applicationContext)

            // Startup restoration is local-only. Refresh is attempted only after an HTTP 401.
            val initialSession = app.authRepository.restoreSession()
                ?: return completed(STATUS_SIGNED_OUT)

            val database = AtProDatabase.getInstance(applicationContext)
            val outboxDao = database.syncOutboxDao()
            if (!app.backupOwnershipManager.isBoundToCurrentUser(initialSession.user.id)) {
                return completed(STATUS_OWNER_NOT_BOUND)
            }

            val pendingCount = outboxDao.getPendingCount()
            val earningsDirty = EarningsStore.isDirty()
            if (pendingCount == 0 && !earningsDirty) {
                return completed(STATUS_NO_PENDING_DATA)
            }

            if (pendingCount > 0) {
                val head = outboxDao.getPendingHead(SyncBatchPolicy.MAX_PENDING_EVENTS_TO_INSPECT)
                when (SyncBatchPolicy.plan(
                    pendingEvents = head.map {
                        SyncPendingEvent(
                            eventId = it.eventId,
                            sequence = it.sequence,
                            dataset = it.dataset,
                            operation = it.operation,
                            payloadBytes = it.payloadBytes,
                            nextAttemptAt = it.nextAttemptAt,
                        )
                    },
                    nowEpochMillis = System.currentTimeMillis(),
                )) {
                    SyncBatchPlan.Empty ->
                        return completed(STATUS_OUTBOX_HEAD_UNAVAILABLE, pendingCount)
                    is SyncBatchPlan.WaitingForRetry -> return Result.retry()
                    is SyncBatchPlan.InvalidEvent ->
                        return completed(STATUS_INVALID_OUTBOX_EVENT, pendingCount)
                    is SyncBatchPlan.OversizedFirstEvent ->
                        return completed(STATUS_OVERSIZED_EVENT, pendingCount)
                    is SyncBatchPlan.Candidate -> Unit
                }
            }

            val snapshotBuilder = BackupSnapshotBuilder(applicationContext)
            val casCoordinator = BackupCasCoordinator(
                remoteStore = SupabaseBackupRemoteStore(),
                snapshotProvider = BackupSnapshotProvider { snapshotBuilder.build() },
                ownerBindingValidator = BackupOwnerBindingValidator { ownerId ->
                    app.backupOwnershipManager.isBoundToCurrentUser(ownerId)
                },
            )
            val acknowledgementCoordinator = BackupAcknowledgementCoordinator(
                applicationContext,
                app.backupOwnershipManager,
            )

            var activeSession: AuthSession = initialSession
            var casResult = casCoordinator.run(activeSession)

            // One serialized refresh attempt per worker execution. Never switch Auth owners.
            if (casResult is BackupCasResult.Failed && casResult.failure.httpStatus == 401) {
                when (val refresh = app.authRepository.refreshSession()) {
                    is AuthResult.Success -> {
                        if (refresh.value.user.id != initialSession.user.id ||
                            !app.backupOwnershipManager.isBoundToCurrentUser(refresh.value.user.id)
                        ) {
                            return completed(STATUS_AUTH_OWNER_CHANGED, pendingCount)
                        }
                        activeSession = refresh.value
                        casResult = casCoordinator.run(activeSession)
                    }
                    is AuthResult.Failure -> {
                        return if (refresh.retryable) {
                            Result.retry()
                        } else {
                            completed(STATUS_AUTH_REFRESH_FAILED, pendingCount)
                        }
                    }
                }
            }

            when (val result = casResult) {
                is BackupCasResult.Committed -> {
                    when (val acknowledged = acknowledgementCoordinator.acknowledge(activeSession, result)) {
                        is BackupAcknowledgementResult.Acknowledged -> {
                            val remainingOutbox = outboxDao.getPendingCount()
                            val earningsStillDirty = EarningsStore.isDirty()
                            if (remainingOutbox > 0 || earningsStillDirty) {
                                // Continue remaining work under WorkManager's exponential backoff.
                                Result.retry()
                            } else {
                                completed(STATUS_SYNC_COMMITTED)
                            }
                        }
                        is BackupAcknowledgementResult.Partial -> {
                            val blocked = outboxDao.getByEventIds(listOf(acknowledged.blockedEventId))
                                .singleOrNull() ?: return Result.retry()
                            outboxDao.markRetry(
                                eventId = blocked.eventId,
                                nextAttemptAt = SyncRetryPolicy.nextAttemptAt(
                                    System.currentTimeMillis(),
                                    blocked.attempts,
                                ),
                                lastErrorCode = "snapshot_entity_not_represented",
                            )
                            Result.retry()
                        }
                        is BackupAcknowledgementResult.RetryRequired -> Result.retry()
                        is BackupAcknowledgementResult.Rejected ->
                            completed(STATUS_ACKNOWLEDGEMENT_REJECTED, outboxDao.getPendingCount())
                    }
                }
                is BackupCasResult.RetryLater -> Result.retry()
                is BackupCasResult.Failed -> {
                    if (result.failure.retryable) Result.retry()
                    else completed(STATUS_REMOTE_REJECTED, outboxDao.getPendingCount())
                }
                is BackupCasResult.Conflicted ->
                    completed(STATUS_MERGE_CONFLICT, outboxDao.getPendingCount())
                is BackupCasResult.Rejected ->
                    completed(STATUS_CAS_REJECTED, outboxDao.getPendingCount())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local validation/storage errors fail closed. Nothing is acknowledged on this path.
            completed(STATUS_LOCAL_SYNC_FAILED)
        }
    }

    private fun completed(
        status: String,
        pendingCount: Int = 0,
    ): Result = Result.success(
        Data.Builder()
            .putString(OUTPUT_STATUS, status)
            .putInt(OUTPUT_PENDING_COUNT, pendingCount)
            .build(),
    )

    private companion object {
        const val OUTPUT_STATUS = "sync_status"
        const val OUTPUT_PENDING_COUNT = "pending_count"

        const val STATUS_APPLICATION_UNAVAILABLE = "application_unavailable"
        const val STATUS_SIGNED_OUT = "signed_out"
        const val STATUS_OWNER_NOT_BOUND = "owner_not_bound"
        const val STATUS_NO_PENDING_DATA = "no_pending_data"
        const val STATUS_OUTBOX_HEAD_UNAVAILABLE = "outbox_head_unavailable"
        const val STATUS_INVALID_OUTBOX_EVENT = "invalid_outbox_event"
        const val STATUS_OVERSIZED_EVENT = "oversized_event"
        const val STATUS_AUTH_OWNER_CHANGED = "auth_owner_changed"
        const val STATUS_AUTH_REFRESH_FAILED = "auth_refresh_failed"
        const val STATUS_SYNC_COMMITTED = "sync_committed"
        const val STATUS_ACKNOWLEDGEMENT_REJECTED = "acknowledgement_rejected"
        const val STATUS_REMOTE_REJECTED = "remote_rejected"
        const val STATUS_MERGE_CONFLICT = "merge_conflict"
        const val STATUS_CAS_REJECTED = "cas_rejected"
        const val STATUS_LOCAL_SYNC_FAILED = "local_sync_failed"
    }
}
