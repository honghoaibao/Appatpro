package com.atpro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.atpro.AtProApplication
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import kotlinx.coroutines.CancellationException

/**
 * Phase 3.4.6 worker: local-only preflight.
 *
 * It deliberately has no Supabase transport/repository dependency and never acknowledges
 * Outbox rows. Result.success means the preflight completed, not that a cloud backup occurred.
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

            // restoreSession() reads the encrypted local session only; it performs no HTTP request.
            val session = app.authRepository.restoreSession()
                ?: return completed(STATUS_SIGNED_OUT)

            val ownerMatches = app.backupOwnershipManager
                .isBoundToCurrentUser(session.user.id)
            if (!ownerMatches) return completed(STATUS_OWNER_NOT_BOUND)

            val dao = AtProDatabase.getInstance(applicationContext).syncOutboxDao()
            val pendingCount = dao.getPendingCount()
            val earningsDirty = EarningsStore.isDirty()
            if (pendingCount == 0 && !earningsDirty) {
                return completed(STATUS_NO_PENDING_DATA)
            }

            if (pendingCount == 0 && earningsDirty) {
                return completed(STATUS_EARNINGS_WAITING_FOR_REMOTE_EXECUTOR)
            }

            // Count across the entire Outbox so an account event beyond the inspected head
            // cannot be bypassed by a smaller batch of later dataset events.
            val accountEvents = dao.getPendingCountForDataset(DATASET_ACCOUNTS)
            val head = dao.getPendingHead(SyncBatchPolicy.MAX_PENDING_EVENTS_TO_INSPECT)
            val plan = SyncBatchPolicy.plan(
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
                pendingAccountEvents = accountEvents,
            )

            when (plan) {
                SyncBatchPlan.Empty -> completed(STATUS_NO_DUE_BATCH, pendingCount)
                is SyncBatchPlan.AccountMergeGate ->
                    completed(STATUS_ACCOUNT_MERGE_GATE, pendingCount)
                is SyncBatchPlan.WaitingForRetry ->
                    completed(STATUS_WAITING_FOR_RETRY, pendingCount)
                is SyncBatchPlan.InvalidEvent ->
                    completed(STATUS_INVALID_OUTBOX_EVENT, pendingCount)
                is SyncBatchPlan.OversizedFirstEvent ->
                    completed(STATUS_OVERSIZED_EVENT, pendingCount)
                is SyncBatchPlan.Candidate -> completed(
                    status = STATUS_REMOTE_EXECUTOR_DISABLED,
                    pendingCount = pendingCount,
                    candidateCount = plan.eventCount,
                    candidateBytes = plan.payloadBytes,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not spin on local validation/initialization errors. A later app start or
            // backed local mutation can enqueue another bounded preflight attempt.
            completed(STATUS_LOCAL_PREFLIGHT_FAILED)
        }
    }

    private fun completed(
        status: String,
        pendingCount: Int = 0,
        candidateCount: Int = 0,
        candidateBytes: Int = 0,
    ): Result = Result.success(
        Data.Builder()
            .putString(OUTPUT_STATUS, status)
            .putInt(OUTPUT_PENDING_COUNT, pendingCount)
            .putInt(OUTPUT_CANDIDATE_COUNT, candidateCount)
            .putInt(OUTPUT_CANDIDATE_BYTES, candidateBytes)
            .build(),
    )

    private companion object {
        const val DATASET_ACCOUNTS = "accounts"

        const val OUTPUT_STATUS = "sync_preflight_status"
        const val OUTPUT_PENDING_COUNT = "pending_count"
        const val OUTPUT_CANDIDATE_COUNT = "candidate_count"
        const val OUTPUT_CANDIDATE_BYTES = "candidate_payload_bytes"

        const val STATUS_APPLICATION_UNAVAILABLE = "application_unavailable"
        const val STATUS_SIGNED_OUT = "signed_out"
        const val STATUS_OWNER_NOT_BOUND = "owner_not_bound"
        const val STATUS_NO_PENDING_DATA = "no_pending_data"
        const val STATUS_EARNINGS_WAITING_FOR_REMOTE_EXECUTOR = "earnings_waiting_for_remote_executor"
        const val STATUS_NO_DUE_BATCH = "no_due_batch"
        const val STATUS_ACCOUNT_MERGE_GATE = "account_merge_gate"
        const val STATUS_WAITING_FOR_RETRY = "waiting_for_retry_window"
        const val STATUS_INVALID_OUTBOX_EVENT = "invalid_outbox_event"
        const val STATUS_OVERSIZED_EVENT = "oversized_event"
        const val STATUS_REMOTE_EXECUTOR_DISABLED = "remote_executor_disabled"
        const val STATUS_LOCAL_PREFLIGHT_FAILED = "local_preflight_failed"
    }
}
