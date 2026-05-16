package com.atpro.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.atpro.AtProApplication
import com.atpro.accessibility.TikTokAccessibilityService
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.TimeUnit

object ScheduledFarmManager {
    const val TAG = "Scheduler"
    private const val SCHEDULE_PREFIX  = "sched_"
    private const val WORK_TAG_PREFIX  = "atpro_farm_"

    @Serializable
    data class FarmSchedule(
        val id:         String       = UUID.randomUUID().toString(),
        val label:      String       = "Farm",
        val hourOfDay:  Int          = 8,
        val minute:     Int          = 0,
        val daysOfWeek: List<Int>    = listOf(2, 3, 4, 5, 6, 7, 1),
        val enabled:    Boolean      = true,
        val accounts:   List<String> = emptyList(),
    )

    fun setSchedule(context: Context, schedule: FarmSchedule) {
        cancelSchedule(context, schedule.id)
        if (!schedule.enabled) return

        val wm = WorkManager.getInstance(context)
        schedule.daysOfWeek.forEach { day ->
            val delayMs = initialDelayMs(day, schedule.hourOfDay, schedule.minute)
            val data    = workDataOf(
                "schedule_id" to schedule.id,
                "label"       to schedule.label,
            )
            val work = PeriodicWorkRequestBuilder<FarmWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag("${WORK_TAG_PREFIX}${schedule.id}_day$day")
                .build()

            wm.enqueueUniquePeriodicWork(
                "${WORK_TAG_PREFIX}${schedule.id}_day$day",
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }

        // Persist
        CoroutineScope(Dispatchers.IO).launch {
            AtProApplication.repo().setConfig(
                "$SCHEDULE_PREFIX${schedule.id}", Json.encodeToString(schedule))
        }
        Log.i(TAG, "Schedule set: ${schedule.label}")
    }

    fun cancelSchedule(context: Context, scheduleId: String) {
        val wm = WorkManager.getInstance(context)
        (1..7).forEach { day ->
            wm.cancelUniqueWork("${WORK_TAG_PREFIX}${scheduleId}_day$day")
        }
        CoroutineScope(Dispatchers.IO).launch {
            AtProApplication.repo().setConfig("$SCHEDULE_PREFIX$scheduleId", "")
        }
    }

    suspend fun getSchedules(): List<FarmSchedule> {
        val db = com.atpro.db.AtProDatabase.getInstance(AtProApplication.ctx)
        return db.configDao().getAll()
            .filter { it.key.startsWith(SCHEDULE_PREFIX) && it.value.isNotEmpty() }
            .mapNotNull { runCatching { Json.decodeFromString<FarmSchedule>(it.value) }.getOrNull() }
    }

    // Called from FarmWorker.doWork() — MUST be synchronous/blocking so WorkManager
    // knows the real completion status and can retry on failure (Fix 5)
    suspend fun triggerFarm(scheduleId: String): Boolean {
        val repo = AtProApplication.repo()
        val raw  = repo.getConfig("$SCHEDULE_PREFIX$scheduleId")
        if (raw.isEmpty()) {
            Log.w(TAG, "Schedule $scheduleId not found"); return false
        }
        val schedule = runCatching { Json.decodeFromString<FarmSchedule>(raw) }
            .getOrNull() ?: return false

        val service = TikTokAccessibilityService.instance
        if (service == null)        { Log.w(TAG, "Service not running"); return false }
        if (service.engine.isFarming) { Log.w(TAG, "Already farming");  return false }

        val accounts = if (schedule.accounts.isNotEmpty()) schedule.accounts
            else repo.getActiveAccounts().map { it.username }

        if (accounts.isEmpty()) { Log.w(TAG, "No active accounts"); return false }

        Log.i(TAG, "Scheduled farm: ${accounts.size} accounts")
        service.engine.startFarm(accounts)
        return true
    }

    private fun initialDelayMs(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().let { now ->
            Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(Calendar.WEEK_OF_YEAR, 1)
            }.timeInMillis - now.timeInMillis
        }.coerceAtLeast(0)
}

// ── FarmWorker — Fix 5: use CoroutineWorker properly ─────────

class FarmWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString("schedule_id")
            ?: return Result.failure()

        // Fix 5: await the actual trigger — WorkManager sees real result
        // If triggerFarm returns false → retry (not failure, service may start later)
        return try {
            val started = ScheduledFarmManager.triggerFarm(scheduleId)
            if (started) Result.success()
            else Result.retry()   // service not ready → WorkManager will retry
        } catch (e: Exception) {
            Log.e("FarmWorker", "doWork error: ${e.message}")
            Result.retry()
        }
    }
}

// Boot receiver — WorkManager handles reschedule, this is just for compat
class FarmAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("FarmAlarmReceiver", "Boot — WorkManager handles scheduling")
    }
}
