package com.atpro.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import java.util.concurrent.TimeUnit

/**
 * Enqueues one debounced, network-constrained backup job when durable backup data exists.
 * KEEP coalesces rapid local writes into one pending request; this is not periodic polling.
 */
object SyncWorkScheduler {
    const val UNIQUE_WORK_NAME = "atpro_backup_sync"
    const val DEBOUNCE_SECONDS = 20L

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
}
