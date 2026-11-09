package com.atpro.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.TotalsRow
import com.atpro.db.entity.*
import com.atpro.sync.AccountStatContributionPolicy
import com.atpro.sync.BackupAccountStatContribution
import com.atpro.sync.BackupConfigPolicy
import com.atpro.sync.SyncWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
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

    private val appContext = context.applicationContext
    private val db      = AtProDatabase.getInstance(appContext)
    private val accounts = db.accountDao()
    private val sessions = db.sessionDao()
    private val logs     = db.logDao()
    private val configs  = db.configDao()
    private val syncOutbox = db.syncOutboxDao()
    private val statContributions = db.accountStatContributionDao()

    /**
     * Schedule only after the Room mutation/Outbox transaction has committed.
     * WorkManager KEEP coalesces rapid writes; scheduling failure never rolls back local data.
     */
    private suspend fun requestBackupPreflight() {
        try {
            SyncWorkScheduler.scheduleIfPending(appContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule backup preflight", error)
        }
    }

    /** Call only from the same Room transaction as the domain mutation. */
    private suspend fun enqueueUpsert(dataset: String, entityKey: String, payloadJson: String) {
        syncOutbox.deletePendingUpserts(dataset, entityKey)
        syncOutbox.insert(
            SyncOutboxEntity(
                dataset = dataset,
                entityKey = entityKey,
                operation = SyncOutboxOperation.UPSERT,
                payloadJson = payloadJson,
            )
        )
    }

    /** Tombstones are intentionally not coalesced away. */
    private suspend fun enqueueDelete(dataset: String, entityKey: String) {
        syncOutbox.insert(
            SyncOutboxEntity(
                dataset = dataset,
                entityKey = entityKey,
                operation = SyncOutboxOperation.DELETE,
            )
        )
    }

    /**
     * Legacy databases are seeded by MIGRATION_3_4. This fallback adopts the existing
     * aggregate only when the account has no contribution rows at all.
     */
    private suspend fun ensureCurrentInstallationContribution(account: AccountEntity) {
        val metadata = db.syncMetadataDao().get()
            ?: error("Sync metadata row is missing; cannot identify this installation")
        val existing = statContributions.getForAccount(account.username)
        val baseline = if (existing.isEmpty()) {
            AccountStatContributionEntity(
                accountId = account.username,
                installationId = metadata.installationId,
                sessionsCount = account.sessionsCount.toLong().coerceAtLeast(0L),
                totalLikes = account.totalLikes.toLong().coerceAtLeast(0L),
                totalFollows = account.totalFollows.toLong().coerceAtLeast(0L),
                totalVideos = account.totalVideos.toLong().coerceAtLeast(0L),
                updatedAt = account.statsUpdatedAt,
            )
        } else {
            AccountStatContributionEntity(
                accountId = account.username,
                installationId = metadata.installationId,
                updatedAt = account.statsUpdatedAt,
            )
        }
        statContributions.ensureBaseline(baseline)
    }

    private suspend fun enqueueAccountUpsert(account: AccountEntity) {
        ensureCurrentInstallationContribution(account)
        val contributionArray = JSONArray()
        statContributions.getForAccount(account.username).forEach { row ->
            contributionArray.put(
                JSONObject()
                    .put("installationId", row.installationId)
                    .put("sessionsCount", row.sessionsCount)
                    .put("totalLikes", row.totalLikes)
                    .put("totalFollows", row.totalFollows)
                    .put("totalVideos", row.totalVideos)
                    .put("updatedAt", row.updatedAt)
            )
        }
        val payload = JSONObject()
            .put("username", account.username)
            .put("status", account.status)
            .put("checkpoint", account.checkpoint)
            .put("createdAt", account.createdAt)
            .put("statusUpdatedAt", account.statusUpdatedAt)
            .put("statsUpdatedAt", account.statsUpdatedAt)
            .put("statContributions", contributionArray)
            .toString()
        enqueueUpsert("accounts", account.username, payload)
    }

    private suspend fun enqueueClosedSessionUpsert(session: SessionEntity) {
        if (session.endedAt == null) return
        val payload = JSONObject()
            .put("syncId", session.syncId)
            .put("accountId", session.accountId)
            .put("startedAt", session.startedAt)
            .put("endedAt", session.endedAt)
            .put("likes", session.likes)
            .put("follows", session.follows)
            .put("comments", session.comments)
            .put("videosWatched", session.videosWatched)
            .put("durationSecs", session.durationSecs)
            .toString()
        enqueueUpsert("closedSessions", session.syncId, payload)
    }

    // ── Accounts ─────────────────────────────────────────────

    fun observeAccounts(): Flow<List<AccountEntity>> =
        accounts.observeAll()

    override suspend fun getAccounts(): List<AccountEntity> =
        accounts.getAll()

    suspend fun getActiveAccounts(): List<AccountEntity> =
        accounts.getActive()

    override suspend fun addAccount(username: String) {
        val account = AccountEntity(username = username.trim().removePrefix("@"))
        db.withTransaction {
            accounts.insertIfAbsent(account)
            accounts.getByUsername(account.username)?.let {
                ensureCurrentInstallationContribution(it)
                enqueueAccountUpsert(it)
            }
        }
        requestBackupPreflight()
    }

    suspend fun deleteAccount(username: String) {
        val normalized = username.trim().removePrefix("@")
        db.withTransaction {
            accounts.delete(normalized)
            enqueueDelete("accounts", normalized)
        }
        requestBackupPreflight()
    }

    /** Update account status directly (e.g. "banned") from AccountsScreen actions. */
    suspend fun updateStatus(username: String, status: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            accounts.updateStatus(
                username = username,
                status = status,
                checkpoint = status == "checkpoint",
                now = now,
            )
            accounts.getByUsername(username)?.let { enqueueAccountUpsert(it) }
        }
        requestBackupPreflight()
    }

    override suspend fun setCheckpoint(username: String, isCheckpoint: Boolean) {
        val status = if (isCheckpoint) "checkpoint" else "active"
        updateStatus(username, status)
    }

    suspend fun setBanned(username: String) =
        updateStatus(username, "banned")

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
        require(likes >= 0 && follows >= 0 && videos >= 0 && comments >= 0) {
            "Session counters must be non-negative before they can be accumulated"
        }
        val now     = System.currentTimeMillis()
        val started = sessionStartMap.remove(sessionId) ?: now
        val durationSecs = ((now - started) / 1000).toInt().coerceAtLeast(0)

        db.withTransaction {
            sessions.close(sessionId, now, durationSecs, likes, follows, comments, videos)
            sessions.getById(sessionId)?.let { enqueueClosedSessionUpsert(it) }

            accounts.getByUsername(accountId)?.let { account ->
                ensureCurrentInstallationContribution(account)
                val metadata = db.syncMetadataDao().get()
                    ?: error("Sync metadata row is missing; cannot identify this installation")
                val changed = statContributions.addSessionContribution(
                    accountId = accountId,
                    installationId = metadata.installationId,
                    likes = likes.toLong(),
                    follows = follows.toLong(),
                    videos = videos.toLong(),
                    updatedAt = now,
                )
                check(changed == 1) { "Could not update the current installation account contribution" }

                val totals = AccountStatContributionPolicy.aggregate(
                    statContributions.getForAccount(accountId).map {
                        BackupAccountStatContribution(
                            accountId = it.accountId,
                            installationId = it.installationId,
                            sessionsCount = it.sessionsCount,
                            totalLikes = it.totalLikes,
                            totalFollows = it.totalFollows,
                            totalVideos = it.totalVideos,
                            updatedAt = it.updatedAt,
                        )
                    }
                )
                accounts.updateAggregatedStats(
                    username = accountId,
                    sessionsCount = AccountStatContributionPolicy.toDisplayInt(totals.sessionsCount),
                    totalLikes = AccountStatContributionPolicy.toDisplayInt(totals.totalLikes),
                    totalFollows = AccountStatContributionPolicy.toDisplayInt(totals.totalFollows),
                    totalVideos = AccountStatContributionPolicy.toDisplayInt(totals.totalVideos),
                    statsUpdatedAt = totals.updatedAt,
                    updatedAt = now,
                )
                accounts.getByUsername(accountId)?.let { enqueueAccountUpsert(it) }
            }
        }
        requestBackupPreflight()

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

    // v1.10.0 — gom ghi log theo lô: mỗi dòng log trước đây là MỘT lần ghi Room (và làm Flow `observeLogs`
    // phát lại 200 dòng cho màn Nhật ký) → tốn CPU/pin khi engine log dày. Nay xếp hàng và ghi một lượt/1,2s.
    private val logQueue = ConcurrentLinkedQueue<FarmLogEntity>()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var logFlushScheduled = false

    override suspend fun log(
        message:   String,
        level:     String,
        accountId: String?,
    ) {
        logQueue.add(FarmLogEntity(message = message, level = level, accountId = accountId))
        if (logQueue.size >= 40) { flushLogs(); return }
        if (!logFlushScheduled) {
            logFlushScheduled = true
            logScope.launch {
                delay(1_200L)
                logFlushScheduled = false
                flushLogs()
            }
        }
    }

    /** Ghi toàn bộ log đang chờ trong một giao dịch. An toàn gọi nhiều lần / song song. */
    suspend fun flushLogs() {
        val batch = ArrayList<FarmLogEntity>(32)
        while (true) { batch.add(logQueue.poll() ?: break) }
        if (batch.isNotEmpty()) logs.insertAll(batch)
    }

    suspend fun clearLogs() { logQueue.clear(); logs.clearAll() }

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

    suspend fun setConfig(key: String, value: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            configs.set(ConfigEntity(key = key, value = value, updatedAt = now))
            when (val dataset = BackupConfigPolicy.datasetFor(key)) {
                "safeConfigs" -> {
                    val payload = JSONObject()
                        .put("key", key)
                        .put("value", value)
                        .put("updatedAt", now)
                        .toString()
                    enqueueUpsert(dataset, key, payload)
                }
                "schedules" -> {
                    val scheduleId = key.removePrefix("sched_")
                    if (value.isEmpty()) {
                        enqueueDelete(dataset, scheduleId)
                    } else {
                        val payload = JSONObject()
                            .put("scheduleId", scheduleId)
                            .put("value", value)
                            .put("updatedAt", now)
                            .toString()
                        enqueueUpsert(dataset, scheduleId, payload)
                    }
                }
                else -> Unit
            }
        }
        if (BackupConfigPolicy.datasetFor(key) != null) requestBackupPreflight()
    }

    suspend fun deleteConfig(key: String) {
        db.withTransaction {
            configs.delete(key)
            when (val dataset = BackupConfigPolicy.datasetFor(key)) {
                "safeConfigs" -> enqueueDelete(dataset, key)
                "schedules" -> enqueueDelete(dataset, key.removePrefix("sched_"))
                else -> Unit
            }
        }
        if (BackupConfigPolicy.datasetFor(key) != null) requestBackupPreflight()
    }

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

    override suspend fun loadFarmConfig(): FarmConfig {
        // v1.11.0: bộ thiết lập nuôi acc của chế độ kiếm tiền mặc định = giá trị nuôi acc thường đang dùng,
        // nên nâng cấp lên không làm đổi hành vi cho tới khi người dùng chỉnh riêng.
        val likeRateNow  = getConfigDouble("like_rate",      0.3).toFloat()
        val watchMinNow  = getConfigDouble("watch_time_min", 3.0)
        val watchMaxNow  = getConfigDouble("watch_time_max", 8.0)
        return buildFarmConfig(likeRateNow, watchMinNow, watchMaxNow)
    }

    private suspend fun buildFarmConfig(likeRateNow: Float, watchMinNow: Double, watchMaxNow: Double): FarmConfig = FarmConfig(
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
        // v1.2.9: Rate-limit comment theo giờ
        maxCommentsPerHour        = getConfigInt   ("max_comments_per_hour",    20),
        // v1.2.9: Xem bình luận (thụ động, không gõ)
        commentViewRate           = getConfigDouble("comment_view_rate",       0.08).toFloat(),
        commentViewScrollMin      = getConfigInt   ("comment_view_scroll_min", 1),
        commentViewScrollMax      = getConfigInt   ("comment_view_scroll_max", 5),
        // Existing delays
        delayAfterLike            = getConfigDouble("delay_after_like",         0.5),
        delayAfterFollow          = getConfigDouble("delay_after_follow",       1.0),
        delayAfterSwitchClick     = getConfigDouble("delay_switch_click",       3.0),
        // Behavior
        skipLive                  = getConfigBool  ("skip_live",                true),
        skipAds                   = getConfigBool  ("skip_ads",                 true),
        // v1.2.6 — Toggle chuẩn hoá acc
        normalizeEnabled          = getConfigBool  ("normalize_enabled",        true),
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
        // [v1.2.0] Tim video theo nội dung
        likeByCaption             = getConfigBool  ("like_by_caption",           false),
        captionKeywords           = getConfig      ("caption_keywords",          "")
                                        .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        likeByHashtag             = getConfigBool  ("like_by_hashtag",           false),
        hashtagKeywords           = getConfig      ("hashtag_keywords",          "")
                                        .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        minLikesToLike            = getConfigInt   ("min_likes_to_like",         0),   // v1.11.0
        // [v1.2.0] Tìm kiếm theo từ khoá
        searchEnabled             = getConfigBool  ("search_enabled",            false),
        searchKeywords            = getConfig      ("search_keywords",           "")
                                        .split(",").map { it.trim() }.filter { it.isNotEmpty() },
        searchVideosPerSession    = getConfigInt   ("search_videos_per_session",  3),
        searchRate                = getConfigDouble("search_rate",               0.05).toFloat(),
        // [v1.2.1] Task mode
        taskJobType               = when (getConfig("task_job_type", "BOTH").uppercase()) {
                                        "LIKE"   -> TaskJobType.LIKE
                                        "FOLLOW" -> TaskJobType.FOLLOW
                                        else     -> TaskJobType.BOTH
                                    },
        taskFarmBeforeJobSecs     = getConfigInt   ("task_farm_before_job_secs",  60),
        taskJobDelaySecs          = getConfigInt   ("task_job_delay_secs",         4),
        taskJobsPerAccount        = getConfigInt   ("task_jobs_per_account",       5),
        taskMaxConsecFailures     = getConfigInt   ("task_max_consec_failures",    3),
        // [v1.3.3] Nuôi acc ngắn sau job + nuôi acc lâu định kỳ
        taskFarmAfterJobMinSecs   = getConfigInt   ("task_farm_after_job_min_secs", 5),
        taskFarmAfterJobMaxSecs   = getConfigInt   ("task_farm_after_job_max_secs", 20),
        taskLongFarmEveryNJobs    = getConfigInt   ("task_long_farm_every_n_jobs",  3),
        taskLongFarmDurationSecs  = getConfigInt   ("task_long_farm_duration_secs", 180),
        // [v1.12.0] Chọn tài khoản nhận nhiệm vụ
        taskAccountMode           = getConfig      ("task_account_mode",          "AUTO").trim().uppercase().ifEmpty { "AUTO" },
        taskSelectedAccounts      = getConfig      ("task_selected_accounts",     "")
                                        .split(",", ";", "\n").map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct(),
        // [v1.11.0] Nuôi acc trong lúc kiếm tiền
        taskFarmLikeRate          = getConfigDouble("task_farm_like_rate",        likeRateNow.toDouble()).toFloat(),
        taskFarmWatchMinSecs      = getConfigInt   ("task_farm_watch_min_secs",   watchMinNow.toInt().coerceAtLeast(1)),
        taskFarmWatchMaxSecs      = getConfigInt   ("task_farm_watch_max_secs",   watchMaxNow.toInt().coerceAtLeast(2)),
        taskFarmMinLikesToLike    = getConfigInt   ("task_farm_min_likes",        0),
        taskFarmLikeByHashtag     = getConfigBool  ("task_farm_like_by_hashtag",  false),
        taskFarmHashtags          = getConfig      ("task_farm_hashtags",         "")
                                        .split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() },
        taskFarmLikeByCaption     = getConfigBool  ("task_farm_like_by_caption",  false),
        taskFarmCaptionKeywords   = getConfig      ("task_farm_caption_keywords", "")
                                        .split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() },
        // [v1.2.4] Demo nuôi acc
        facebookNurtureDurationSecs = getConfigInt   ("fb_nurture_duration_secs",     180),
        facebookLikeRate            = getConfigDouble("fb_like_rate",                 0.10).toFloat(),
        // v1.2.9: Khoảng thời gian đọc bài Facebook
        facebookReadTimeMinSecs     = getConfigInt   ("fb_read_time_min_secs",        8),
        facebookReadTimeMaxSecs     = getConfigInt   ("fb_read_time_max_secs",        25),
        // v1.3.0: Xem bình luận + Reels Facebook
        facebookCommentViewRate          = getConfigDouble("fb_comment_view_rate",            0.05).toFloat(),
        facebookReelsViewRate            = getConfigDouble("fb_reels_view_rate",              0.05).toFloat(),
        facebookReelsViewDurationMinSecs = getConfigInt   ("fb_reels_view_duration_min_secs", 600),
        facebookReelsViewDurationMaxSecs = getConfigInt   ("fb_reels_view_duration_max_secs", 1200),
        facebookReelsLikeRate            = getConfigDouble("fb_reels_like_rate",              0.20).toFloat(),
        xNurtureDurationSecs        = getConfigInt   ("x_nurture_duration_secs",      120),
        xLikeRate                   = getConfigDouble("x_like_rate",                  0.25).toFloat(),
        xRetweetRate                = getConfigDouble("x_retweet_rate",               0.05).toFloat(),
        xReplyViewRate              = getConfigDouble("x_reply_view_rate",            0.05).toFloat(),  // v1.3.0
        instagramNurtureDurationSecs= getConfigInt   ("ig_nurture_duration_secs",     180),
        instagramLikeRate           = getConfigDouble("ig_like_rate",                 0.30).toFloat(),
        instagramFollowRate         = getConfigDouble("ig_follow_rate",               0.08).toFloat(),
        instagramCommentViewRate    = getConfigDouble("ig_comment_view_rate",         0.05).toFloat(), // v1.3.0
        threadsNurtureDurationSecs  = getConfigInt   ("threads_nurture_duration_secs", 120),
        threadsLikeRate             = getConfigDouble("threads_like_rate",             0.20).toFloat(),
        threadsReplyViewRate        = getConfigDouble("threads_reply_view_rate",       0.05).toFloat(), // v1.3.0
        snapchatNurtureDurationSecs = getConfigInt   ("snap_nurture_duration_secs",    90),
        snapchatStoryViewSecs       = getConfigInt   ("snap_story_view_secs",          8),
        snapchatLikeRate            = getConfigDouble("snap_like_rate",                0.20).toFloat(),  // v1.3.0
        discordWebhookUrl           = getConfig      ("discord_webhook_url",           ""),
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
