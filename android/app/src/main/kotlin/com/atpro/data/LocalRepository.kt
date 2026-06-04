package com.atpro.data

import android.content.Context
import android.util.Log
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.TotalsRow
import com.atpro.db.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * LocalRepository.kt — Phase 3
 * Single source of truth cho toàn bộ app.
 * Thay thế SupabaseManager — lưu 100% local, không cần internet.
 *
 * Data access pattern:
 *   - observe*()  → Flow (realtime update Flutter qua EventChannel)
 *   - get*()      → suspend (one-shot query)
 *   - save/insert → suspend
 */
class LocalRepository(context: Context) : IFarmRepository {

    companion object {
        const val TAG = "LocalRepo"

        @Volatile
        private var INSTANCE: LocalRepository? = null

        fun getInstance(context: Context): LocalRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val db      = AtProDatabase.getInstance(context)
    private val accounts = db.accountDao()
    private val sessions = db.sessionDao()
    private val logs     = db.logDao()
    private val configs  = db.configDao()

    // ── Accounts ─────────────────────────────────────────────

    fun observeAccounts(): Flow<List<AccountEntity>> =
        accounts.observeAll()

    override suspend fun getAccounts(): List<AccountEntity> =
        accounts.getAll()

    suspend fun getActiveAccounts(): List<AccountEntity> =
        accounts.getActive()

    override suspend fun addAccount(username: String) {
        accounts.upsert(AccountEntity(username = username.trim().removePrefix("@")))
    }

    suspend fun deleteAccount(username: String) =
        accounts.delete(username)

    /** Update account status directly (e.g. "banned") from AccountsScreen actions. */
    suspend fun updateStatus(username: String, status: String) =
        db.accountDao().updateStatus(
            username   = username,
            status     = status,
            checkpoint = status == "checkpoint",
        )

    override suspend fun setCheckpoint(username: String, isCheckpoint: Boolean) {
        val status = if (isCheckpoint) "checkpoint" else "active"
        accounts.updateStatus(username, status, isCheckpoint)
    }

    suspend fun setBanned(username: String) =
        accounts.updateStatus(username, "banned", false)

    // ── Sessions ─────────────────────────────────────────────

    fun observeRecentSessions(limit: Int = 50): Flow<List<SessionEntity>> =
        sessions.observeRecent(limit)

    // startSession moved into closeSession fix block

    // startedAt map: sessionId → startedAt timestamp
    private val sessionStartMap = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    override suspend fun startSession(accountId: String): Long {
        val id = sessions.insert(SessionEntity(accountId = accountId))
        sessionStartMap[id] = System.currentTimeMillis()
        return id
    }

    override suspend fun closeSession(
        sessionId:  Long,
        accountId:  String,   // truyền thẳng accountId — không query lại
        likes:      Int,
        follows:    Int,
        videos:     Int,
        comments:   Int,
    ) {
        val now     = System.currentTimeMillis()
        val started = sessionStartMap.remove(sessionId) ?: now
        val durationSecs = ((now - started) / 1000).toInt().coerceAtLeast(0)

        sessions.close(sessionId, now, durationSecs, likes, follows, comments, videos)  // [v1.1.4 FIX]
        accounts.addStats(accountId, likes, follows, videos)  // dùng accountId được truyền vào

        Log.i(TAG, "Session $sessionId @$accountId closed — " +
            "likes=$likes follows=$follows videos=$videos duration=${durationSecs}s")
    }

    suspend fun getDailyStats(days: Int = 30): List<DailyStatRow> {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return sessions.getDailyStats(since)
    }

    suspend fun getTotals(days: Int = 30): TotalsRow {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return sessions.getTotals(since)
    }

    // ── Logs ─────────────────────────────────────────────────

    fun observeLogs(limit: Int = 200): Flow<List<FarmLogEntity>> =
        logs.observeRecent(limit)

    override suspend fun log(
        message:   String,
        level:     String,
        accountId: String?,
    ) {
        logs.insert(FarmLogEntity(message = message, level = level, accountId = accountId))
    }

    suspend fun clearLogs() = logs.clearAll()

    /** Dọn log cũ hơn 7 ngày và session cũ hơn 90 ngày */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val sevenDaysAgo   = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val ninetyDaysAgo  = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        logs.deleteOlderThan(sevenDaysAgo)
        sessions.deleteOlderThan(ninetyDaysAgo)
        Log.i(TAG, "Cleanup done")
    }

    // ── Config ────────────────────────────────────────────────

    suspend fun getConfig(key: String, default: String = ""): String =
        configs.get(key) ?: default

    suspend fun setConfig(key: String, value: String) =
        configs.set(ConfigEntity(key = key, value = value))

    suspend fun getConfigInt(key: String, default: Int): Int =
        configs.get(key)?.toIntOrNull() ?: default

    suspend fun getConfigDouble(key: String, default: Double): Double =
        configs.get(key)?.toDoubleOrNull() ?: default

    suspend fun getConfigBool(key: String, default: Boolean): Boolean =
        when (configs.get(key)) {
            "true"  -> true
            "false" -> false
            else    -> default
        }

    // ── FarmConfig convenience loader ─────────────────────────

    override suspend fun loadFarmConfig(): FarmConfig = FarmConfig(
        minutesPerAccount         = getConfigInt   ("minutes_per_account",      5),
        videoWatchTimeMin         = getConfigDouble("watch_time_min",           3.0),
        videoWatchTimeMax         = getConfigDouble("watch_time_max",           8.0),
        likeRate                  = getConfigDouble("like_rate",                0.3).toFloat(),
        followRate                = getConfigDouble("follow_rate",              0.15).toFloat(),
        // [v2.0] Comment
        commentRate               = getConfigDouble("comment_rate",             0.0).toFloat(),
        commentTexts              = getConfig      ("comment_texts", "")
                                        .split("||")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() },
        delayAfterComment         = getConfigDouble("delay_after_comment",      1.5),
        // Existing delays
        delayAfterLike            = getConfigDouble("delay_after_like",         0.5),
        delayAfterFollow          = getConfigDouble("delay_after_follow",       1.0),
        delayAfterSwitchClick     = getConfigDouble("delay_switch_click",       3.0),
        // Behavior
        skipLive                  = getConfigBool  ("skip_live",                true),
        skipAds                   = getConfigBool  ("skip_ads",                 true),
        // [v1.1.8] Like content-aware gate
        likeAdsEnabled            = getConfigBool  ("like_ads",                 false),
        enableRestBetweenAccounts = getConfigBool  ("enable_rest",              false),
        restDurationMinutes       = getConfigInt   ("rest_minutes",             2),
        enableVerifyAccount       = getConfigBool  ("verify_account",           true),
        // [v2.0] Human timing
        occasionalPauseChance     = getConfigDouble("occasional_pause_chance",  0.06).toFloat(),
        // Recovery
        maxBackAttempts           = getConfigInt   ("max_back_attempts",        5),
        // [v2.0] Watchdog
        watchdogTimeoutSecs       = getConfigInt   ("watchdog_timeout_secs",    120),
        // [v1.1.9+] Tab ghé thăm
        inboxViewRate             = getConfigDouble("inbox_view_rate",           0.0).toFloat(),
        inboxViewDurationSecs     = getConfigInt   ("inbox_view_duration_secs",  15),
        shopViewRate              = getConfigDouble("shop_view_rate",            0.0).toFloat(),
        shopScrollCount           = getConfigInt   ("shop_scroll_count",         3),
    )

    /** Lưu danh sách comment, phân cách bằng "||" trong DB. */
    suspend fun setCommentTexts(texts: List<String>) =
        setConfig("comment_texts", texts.joinToString("||"))

    // ── Export CSV ────────────────────────────────────────────

    suspend fun exportSessionsCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("account,started_at,likes,follows,videos_watched,duration_secs")
        sessions.getRecent(limit = 10_000).forEach { s ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(s.startedAt))
            sb.appendLine("${s.accountId},$date,${s.likes},${s.follows},${s.videosWatched},${s.durationSecs}")
        }
        sb.toString()
    }

    suspend fun exportAccountsCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("username,status,checkpoint,sessions,total_likes,total_follows,total_videos")
        accounts.getAll().forEach { a ->
            sb.appendLine("${a.username},${a.status},${a.checkpoint},${a.sessionsCount},${a.totalLikes},${a.totalFollows},${a.totalVideos}")
        }
        sb.toString()
    }
}
