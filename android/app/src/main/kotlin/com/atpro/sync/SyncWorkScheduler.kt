package com.atpro.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Đồng bộ định kỳ: kéo dữ liệu từ thiết bị khác về và gửi phần còn sót, kể cả khi không có thay đổi mới. */
    const val PERIODIC_WORK_NAME = "atpro_backup_sync_periodic"
    const val PERIODIC_INTERVAL_MINUTES = 30L

    /** Tag used to identify a queued manual sync; WorkInfo exposes tags, not request inputData. */
    private const val MANUAL_WORK_TAG = "atpro_backup_sync_manual"

    /** Hai lần bấm "Đồng bộ ngay" cách nhau tối thiểu — mỗi vòng thành công tăng revision trên máy chủ. */
    const val MANUAL_MIN_INTERVAL_MS = 30_000L

    /** Prevent two rapid taps/coroutines from passing the persisted cooldown check together. */
    private val manualRequestMutex = Mutex()

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
     * Đăng ký đồng bộ tự động định kỳ (idempotent — KEEP giữ lịch đang có, không đặt lại đồng hồ mỗi lần mở app).
     * Chỉ chạy khi có mạng; cùng đường đọc–gộp–CAS với lần chạy thủ công, nên không tạo revision mới khi đã khớp.
     */
    fun ensurePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncStatusCodes.INPUT_FORCE_REMOTE_CHECK to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5L, TimeUnit.MINUTES)
            .addTag(PERIODIC_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Người dùng bấm "Đồng bộ ngay": chạy ngay (không đợi debounce), kể cả khi không có thay đổi chờ gửi,
     * để kéo dữ liệu từ thiết bị khác về. Dùng chung tên công việc với lần chạy nền nên không bao giờ có hai
     * vòng đồng bộ chạy song song: đang chạy thì từ chối, đang chờ debounce thì thay bằng lần chạy ngay.
     */
    suspend fun requestManual(context: Context): ManualSyncRequest = withContext(Dispatchers.IO) {
        manualRequestMutex.withLock {
            val appContext = context.applicationContext
            SyncStatusStore.init(appContext)
            val workManager = WorkManager.getInstance(appContext)
            val workInfos = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()

            val manualAlreadyActive = workInfos.any { info ->
                info.state == WorkInfo.State.RUNNING ||
                    ((info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED) &&
                        MANUAL_WORK_TAG in info.tags)
            }
            if (manualAlreadyActive) return@withLock ManualSyncRequest.ALREADY_RUNNING

            val now = System.currentTimeMillis()
            if (!ManualSyncPolicy.canRequest(
                    lastRequestAtMs = SyncStatusStore.lastManualRequestAt(),
                    nowMs = now,
                    minIntervalMs = MANUAL_MIN_INTERVAL_MS,
                )
            ) {
                return@withLock ManualSyncRequest.TOO_SOON
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(SyncStatusCodes.INPUT_MANUAL to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_NAME)
                .addTag(MANUAL_WORK_TAG)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            SyncStatusStore.markManualRequest(now)
            SyncStatusStore.markRunning(now)
            ManualSyncRequest.STARTED
        }
    }

    /** Đăng xuất: bỏ lần chạy đang chờ của tài khoản cũ. */
    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
