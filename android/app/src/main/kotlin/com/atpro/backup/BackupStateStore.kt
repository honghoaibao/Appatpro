package com.atpro.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Small local sync state. A generation counter protects edits made during an upload. */
object BackupStateStore {
    private const val PREFS = "atpro_cloud_backup_state"
    private const val KEY_OFFLINE = "offline_mode"
    private const val KEY_GENERATION = "dirty_generation"
    private const val KEY_SYNCED_GENERATION = "synced_generation"
    private const val KEY_REMOTE_REVISION = "remote_revision"
    private const val KEY_CONFLICT = "has_conflict"
    private val lock = Any()

    fun isOfflineMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OFFLINE, false)

    fun setOfflineMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OFFLINE, enabled).apply()
    }

    fun generation(context: Context): Long = prefs(context).getLong(KEY_GENERATION, 0L)
    fun syncedGeneration(context: Context): Long = prefs(context).getLong(KEY_SYNCED_GENERATION, 0L)
    fun remoteRevision(context: Context): Long = prefs(context).getLong(KEY_REMOTE_REVISION, 0L)
    fun hasConflict(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFLICT, false)
    fun isDirty(context: Context): Boolean = generation(context) > syncedGeneration(context)

    fun setConflict(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFLICT, value).apply()
    }

    fun markDirty(context: Context) {
        val app = context.applicationContext
        val shouldSchedule = synchronized(lock) {
            val p = prefs(app)
            val wasClean = p.getLong(KEY_GENERATION, 0L) <= p.getLong(KEY_SYNCED_GENERATION, 0L)
            p.edit().putLong(KEY_GENERATION, p.getLong(KEY_GENERATION, 0L) + 1L).apply()
            wasClean
        }
        if (shouldSchedule && com.atpro.auth.SupabaseConfig.isConfigured) {
            BackupWorkScheduler.enqueue(app)
        }
    }

    fun markSynced(context: Context, capturedGeneration: Long, newRemoteRevision: Long): Boolean {
        val app = context.applicationContext
        val stillClean = synchronized(lock) {
            val p = prefs(app)
            val currentGeneration = p.getLong(KEY_GENERATION, 0L)
            if (currentGeneration == capturedGeneration) {
                p.edit()
                    .putLong(KEY_SYNCED_GENERATION, capturedGeneration)
                    .putLong(KEY_REMOTE_REVISION, newRemoteRevision)
                    .putBoolean(KEY_CONFLICT, false)
                    .apply()
                true
            } else {
                p.edit().putLong(KEY_REMOTE_REVISION, newRemoteRevision).apply()
                false
            }
        }
        if (!stillClean && com.atpro.auth.SupabaseConfig.isConfigured) {
            // Queue a follow-up behind the currently running unique worker.
            BackupWorkScheduler.enqueue(app, 0L, appendIfRunning = true)
        }
        return stillClean
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object BackupWorkScheduler {
    private const val UNIQUE_WORK_NAME = "atpro_cloud_backup"

    fun enqueue(
        context: Context,
        delaySeconds: Long = 15L,
        appendIfRunning: Boolean = false,
    ) {
        if (!com.atpro.auth.SupabaseConfig.isConfigured) return
        val request = OneTimeWorkRequestBuilder<BackupSyncWorker>()
            .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            if (appendIfRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
