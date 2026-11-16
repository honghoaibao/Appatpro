package com.atpro.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
 * Schedules one serialized sync chain.
 *
 * - Local mutations use a 20-second debounce and KEEP an already queued worker.
 * - If a worker is currently running, at most one follow-up is appended. Additional writes
 *   are coalesced into that follow-up.
 * - A bootstrap request forces a remote preflight even when Outbox and Earnings are clean.
 * - Every request requires CONNECTED; WorkManager persists it while offline.
 */
object SyncWorkScheduler {
    const val UNIQUE_WORK_NAME = "atpro_backup_sync"
    const val DEBOUNCE_SECONDS = 20L

    private const val MANUAL_WORK_TAG = "atpro_backup_sync_manual"
    private const val BOOTSTRAP_WORK_TAG = "atpro_backup_sync_bootstrap"
    private const val FOLLOW_UP_WORK_TAG = "atpro_backup_sync_follow_up"

    /** Hai lần bấm "Đồng bộ ngay" cách nhau tối thiểu. */
    const val MANUAL_MIN_INTERVAL_MS = 30_000L

    /** Serialize query → policy → enqueue decisions within this app process. */
    private val queueMutex = Mutex()

    /**
     * Called after a Room + Outbox transaction commits, or after a file-backed Earnings write.
     * Returns true when a matching worker already exists or a request was enqueued.
     */
    suspend fun scheduleIfPending(context: Context): Boolean = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            val appContext = context.applicationContext
            EarningsStore.init(appContext)

            val database = AtProDatabase.getInstance(appContext)
            val hasOutboxChanges = database.syncOutboxDao().getPendingCount() > 0
            val hasDirtyEarnings = EarningsStore.isDirty()
            if (!hasOutboxChanges && !hasDirtyEarnings) return@withLock false

            val workManager = WorkManager.getInstance(appContext)
            val active = activeWork(workManager)
            val isRunning = active.any { it.state == WorkInfo.State.RUNNING }
            val hasQueuedFollowUp = active.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
            }
            when (
                SyncWorkSchedulingPolicy.forLocalMutation(
                    hasPendingData = true,
                    hasRunningWork = isRunning,
                    hasQueuedLocalFollowUp = hasQueuedFollowUp,
                )
            ) {
                SyncEnqueueAction.SKIP -> true
                SyncEnqueueAction.KEEP -> {
                    workManager.enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        buildRequest(initialDelaySeconds = DEBOUNCE_SECONDS),
                    )
                    true
                }
                SyncEnqueueAction.APPEND_AFTER_RUNNING -> {
                    workManager.enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        buildRequest(
                            initialDelaySeconds = DEBOUNCE_SECONDS,
                            tags = listOf(FOLLOW_UP_WORK_TAG),
                        ),
                    )
                    true
                }
                SyncEnqueueAction.REPLACE_PENDING_WITH_BOOTSTRAP ->
                    error("Local mutations cannot replace the queue with a bootstrap request")
            }
        }
    }

    /**
     * Reconcile a signed-in, already owner-bound installation with its cloud backup.
     *
     * Empty Outbox is not a reason to skip this request: it is how a clean/new device
     * discovers a remote backup. An existing running worker is never cancelled; one
     * bootstrap follow-up is appended instead.
     */
    suspend fun scheduleBootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            val appContext = context.applicationContext
            val workManager = WorkManager.getInstance(appContext)
            val active = activeWork(workManager)
            val isRunning = active.any { it.state == WorkInfo.State.RUNNING }
            // Manual sync also performs a forced remote read, so it satisfies a pending bootstrap.
            val hasActiveBootstrap = active.any {
                BOOTSTRAP_WORK_TAG in it.tags || MANUAL_WORK_TAG in it.tags
            }

            when (
                SyncWorkSchedulingPolicy.forBootstrap(
                    hasActiveBootstrap = hasActiveBootstrap,
                    hasRunningWork = isRunning,
                )
            ) {
                SyncEnqueueAction.SKIP -> true
                SyncEnqueueAction.APPEND_AFTER_RUNNING -> {
                    workManager.enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        buildRequest(
                            forceRemoteCheck = true,
                            tags = listOf(BOOTSTRAP_WORK_TAG),
                        ),
                    )
                    true
                }
                SyncEnqueueAction.REPLACE_PENDING_WITH_BOOTSTRAP -> {
                    workManager.enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        buildRequest(
                            forceRemoteCheck = true,
                            tags = listOf(BOOTSTRAP_WORK_TAG),
                        ),
                    )
                    true
                }
                SyncEnqueueAction.KEEP ->
                    error("Bootstrap requests never use KEEP")
            }
        }
    }

    /**
     * Người dùng bấm "Đồng bộ ngay": chạy ngay, kể cả khi không có thay đổi chờ gửi,
     * để kéo dữ liệu từ thiết bị khác về. Dùng chung tên công việc với lần chạy nền.
     */
    suspend fun requestManual(context: Context): ManualSyncRequest = withContext(Dispatchers.IO) {
        queueMutex.withLock {
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

            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(manual = true, tags = listOf(MANUAL_WORK_TAG)),
            )
            SyncStatusStore.markManualRequest(now)
            SyncStatusStore.markRunning(now)
            ManualSyncRequest.STARTED
        }
    }

    /** Đăng xuất: bỏ lần chạy đang chờ của tài khoản cũ. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun activeWork(workManager: WorkManager): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().filter {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED ||
                it.state == WorkInfo.State.RUNNING
        }

    private fun buildRequest(
        initialDelaySeconds: Long = 0L,
        manual: Boolean = false,
        forceRemoteCheck: Boolean = false,
        tags: List<String> = emptyList(),
    ): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val input = Data.Builder()
        if (manual) input.putBoolean(SyncStatusCodes.INPUT_MANUAL, true)
        if (forceRemoteCheck) input.putBoolean(SyncStatusCodes.INPUT_FORCE_REMOTE_CHECK, true)

        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
        if (initialDelaySeconds > 0L) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        tags.forEach(builder::addTag)
        if (manual || forceRemoteCheck) builder.setInputData(input.build())
        return builder.build()
    }
}
