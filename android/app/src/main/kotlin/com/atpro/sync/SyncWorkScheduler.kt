package com.atpro.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Kết quả của yêu cầu "Đồng bộ ngay". */
enum class ManualSyncRequest { STARTED, ALREADY_RUNNING, TOO_SOON }

/**
 * Enqueues one debounced, network-constrained backup job when durable backup data exists.
 * KEEP coalesces rapid local writes into one pending request; this is not periodic polling.
 */
object SyncWorkScheduler {
    const val UNIQUE_WORK_NAME = "atpro_backup_sync"
    const val DEBOUNCE_SECONDS = 20L

    /** Hai lần bấm "Đồng bộ ngay" cách nhau tối thiểu — mỗi vòng thành công tăng revision trên máy chủ. */
    const val MANUAL_MIN_INTERVAL_MS = 30_000L

    suspend fun scheduleIfPending(context: Context): Boolean {
        val appContext = context.applicationContext
        EarningsStore.init(appContext)

        val database = AtProDatabase.getInstance(appContext)
        val hasOutboxChanges = database.syncOutboxDao().getPendingCount() > 0
        val hasDirtyEarnings = EarningsStore.isDirty()
        if (!hasOutboxChanges && !hasDirtyEarnings) return false

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    /**
     * Người dùng bấm "Đồng bộ ngay": chạy ngay (không đợi debounce), kể cả khi không có thay đổi chờ gửi,
     * để kéo dữ liệu từ thiết bị khác về. Dùng chung tên công việc với lần chạy nền nên không bao giờ có hai
     * vòng đồng bộ chạy song song: đang chạy thì từ chối, đang chờ debounce thì thay bằng lần chạy ngay.
     */
    suspend fun requestManual(context: Context): ManualSyncRequest = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        SyncStatusStore.init(appContext)
        val workManager = WorkManager.getInstance(appContext)

        val running = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING }
        if (running) return@withContext ManualSyncRequest.ALREADY_RUNNING

        val now = System.currentTimeMillis()
        if (now - SyncStatusStore.state.value.lastAttemptAt < MANUAL_MIN_INTERVAL_MS) {
            return@withContext ManualSyncRequest.TOO_SOON
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncStatusCodes.INPUT_MANUAL to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        SyncStatusStore.markRunning(now)
        ManualSyncRequest.STARTED
    }

    /** Đăng xuất: bỏ lần chạy đang chờ của tài khoản cũ. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
