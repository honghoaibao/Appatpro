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
import com.atpro.notification.AtProNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Nguyên nhân lỗi tạm của lần chạy này (nếu có) — hiện ở màn Cài đặt khi `Result.retry()`. */
    private var retryDetail: String? = null

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(SyncStatusCodes.INPUT_MANUAL, false)
        // Lần chạy định kỳ: luôn kéo dữ liệu từ máy chủ dù không có thay đổi chờ gửi.
        val forceRemoteCheck = inputData.getBoolean(SyncStatusCodes.INPUT_FORCE_REMOTE_CHECK, false)
        SyncStatusStore.init(applicationContext)
        val previous = SyncStatusStore.state.value.outcome
        SyncStatusStore.markRunning()
        var result: Result? = null
        try {
            // Lần chạy định kỳ và lần chạy theo thay đổi dùng hai tên công việc khác nhau: khoá tiến trình
            // bảo đảm không bao giờ có hai vòng đọc–gộp–áp dụng chạy chồng lên nhau trên cùng một CSDL.
            val finished = runLock.withLock { runSync(manual, forceRemoteCheck) }
            result = finished
            return finished
        } finally {
            // result == null: coroutine bị huỷ → chỉ bỏ cờ đang chạy, không ghi kết quả.
            reportOutcome(result, manual, previous)
        }
    }

    private fun reportOutcome(result: Result?, manual: Boolean, previous: SyncOutcome) {
        if (result == null) {
            SyncStatusStore.stopRunning()
            return
        }
        if (result is Result.Success) {
            val outcome = SyncOutcome.fromStatusCode(result.outputData.getString(SyncStatusCodes.OUTPUT_STATUS))
            val pending = result.outputData.getInt(SyncStatusCodes.OUTPUT_PENDING_COUNT, 0)
            val conflictDetails = result.outputData.getString(SyncStatusCodes.OUTPUT_CONFLICT_DETAILS)
            SyncStatusStore.record(outcome, pending, conflictDetails = conflictDetails)
            if (SyncNotifyPolicy.shouldNotify(previous, outcome, manual)) {
                AtProNotificationManager.notifySync(outcome, manual)
            }
        } else {
            // Result.retry(): lỗi tạm, WorkManager tự thử lại — không báo thông báo.
            SyncStatusStore.record(null, null, conflictDetails = retryDetail)
        }
    }

    private suspend fun runSync(manual: Boolean, forceRemoteCheck: Boolean): Result {
        val app = applicationContext as? AtProApplication
            ?: return completed(SyncStatusCodes.APPLICATION_UNAVAILABLE)

        return try {
            EarningsStore.init(applicationContext)
            // v1.13.0: nén thu nhập cũ thành dòng tổng trước khi chụp snapshot (idempotent, tự đánh dấu dirty nếu có đổi).
            EarningsStore.compactIfNeeded()

            // Startup restoration is local-only. Refresh is attempted only after an HTTP 401.
            val initialSession = app.authRepository.restoreSession()
                ?: return completed(SyncStatusCodes.SIGNED_OUT)

            val database = AtProDatabase.getInstance(applicationContext)
            val outboxDao = database.syncOutboxDao()
            if (!app.backupOwnershipManager.isBoundToCurrentUser(initialSession.user.id)) {
                return completed(SyncStatusCodes.OWNER_NOT_BOUND)
            }

            val pendingCount = outboxDao.getPendingCount()
            val earningsDirty = EarningsStore.isDirty()
            // Người dùng bấm "Đồng bộ ngay": vẫn chạy một vòng đọc–gộp–gửi để kéo dữ liệu từ thiết bị khác về.
            if (pendingCount == 0 && !earningsDirty && !manual && !forceRemoteCheck) {
                return completed(SyncStatusCodes.NO_PENDING_DATA)
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
                        return completed(SyncStatusCodes.OUTBOX_HEAD_UNAVAILABLE, pendingCount)
                    is SyncBatchPlan.WaitingForRetry -> return Result.retry()
                    is SyncBatchPlan.InvalidEvent ->
                        return completed(SyncStatusCodes.INVALID_OUTBOX_EVENT, pendingCount)
                    is SyncBatchPlan.OversizedFirstEvent ->
                        return completed(SyncStatusCodes.OVERSIZED_EVENT, pendingCount)
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
                            return completed(SyncStatusCodes.AUTH_OWNER_CHANGED, pendingCount)
                        }
                        activeSession = refresh.value
                        casResult = casCoordinator.run(activeSession)
                    }
                    is AuthResult.Failure -> {
                        return if (refresh.retryable) {
                            Result.retry()
                        } else {
                            completed(SyncStatusCodes.AUTH_REFRESH_FAILED, pendingCount)
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
                                completed(SyncStatusCodes.SYNC_COMMITTED)
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
                            completed(
                                SyncStatusCodes.ACKNOWLEDGEMENT_REJECTED,
                                outboxDao.getPendingCount(),
                                conflictDetails = SyncFailureSummary.reason(acknowledged.reason),
                            )
                    }
                }
                // Máy này không có thay đổi chờ gửi và đã khớp đúng revision/checksum trên server: không tạo revision mới,
                // không có gì để xác nhận (Outbox/earnings rỗng). Cùng ngữ nghĩa với NO_PENDING_DATA → outcome UP_TO_DATE.
                is BackupCasResult.UpToDate -> completed(SyncStatusCodes.NO_PENDING_DATA)
                is BackupCasResult.RetryLater -> Result.retry()
                is BackupCasResult.Failed -> {
                    val detail = SyncFailureSummary.remote(result.failure.code, result.failure.httpStatus)
                    if (result.failure.retryable) {
                        retryDetail = detail
                        Result.retry()
                    } else {
                        completed(SyncStatusCodes.REMOTE_REJECTED, outboxDao.getPendingCount(), detail)
                    }
                }
                is BackupCasResult.Conflicted ->
                    completed(
                        SyncStatusCodes.MERGE_CONFLICT,
                        outboxDao.getPendingCount(),
                        conflictDetails = BackupMergeConflictSummary.format(result.conflicts),
                    )
                is BackupCasResult.Rejected ->
                    completed(
                        SyncStatusCodes.CAS_REJECTED,
                        outboxDao.getPendingCount(),
                        conflictDetails = SyncFailureSummary.reason(result.reason),
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Local validation/storage errors fail closed. Nothing is acknowledged on this path.
            // Chi tiết đã làm sạch (tên lớp + thông điệp ngắn) để biết VÌ SAO lỗi — trước đây bị nuốt hoàn toàn.
            completed(
                SyncStatusCodes.LOCAL_SYNC_FAILED,
                conflictDetails = SyncFailureSummary.exception(error),
            )
        }
    }

    private companion object {
        /** Một vòng đồng bộ tại một thời điểm trong tiến trình (WorkManager chạy worker cùng tiến trình). */
        val runLock = Mutex()
    }

    private fun completed(
        status: String,
        pendingCount: Int = 0,
        conflictDetails: String? = null,
    ): Result {
        val output = Data.Builder()
            .putString(SyncStatusCodes.OUTPUT_STATUS, status)
            .putInt(SyncStatusCodes.OUTPUT_PENDING_COUNT, pendingCount)
        conflictDetails?.takeIf { it.isNotBlank() }?.let {
            output.putString(SyncStatusCodes.OUTPUT_CONFLICT_DETAILS, it)
        }
        return Result.success(output.build())
    }

}
