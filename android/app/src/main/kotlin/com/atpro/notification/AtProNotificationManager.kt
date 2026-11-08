package com.atpro.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.atpro.R
import com.atpro.data.LocalRepository
import com.atpro.ui.dashboard.DashboardActivity
import java.util.Calendar

/**
 * AtProNotificationManager — v1.12.0 (nâng cấp từ v1.1.2).
 *
 * Thông báo hệ thống Android thuần — không còn Telegram / Discord.
 *
 * Nâng cấp so với bản cũ (6 thông báo, ID tăng mãi, biểu tượng mặc định, không kiểm tra quyền):
 *  • 4 nhóm có công tắc riêng + kênh riêng ([NotifCategory]): cảnh báo · phiên · từng tài khoản · nhiệm vụ & xu.
 *  • Thêm thông báo cho chế độ KIẾM TIỀN: bắt đầu, từng tài khoản (job/xu), tổng kết (số job, xu, số dư),
 *    Follow4Follow hết phiên, đạt giới hạn ngày, nhiệm vụ dừng giữa chừng.
 *  • Giờ yên tĩnh: vẫn hiện nhưng không âm/rung ([NotificationPolicy]).
 *  • Chống lặp cảnh báo (cùng loại + cùng acc trong 10 phút), ID ổn định theo khoá → cập nhật thay vì chồng lên.
 *  • Nhóm thông báo "từng tài khoản" + dòng tổng; chạm mở đúng tab (Nhật ký / Dịch vụ) qua [NotificationNavigator].
 *  • Kiểm tra quyền POST_NOTIFICATIONS + kênh bị tắt trước khi đăng; biểu tượng riêng `ic_stat_atpro`.
 *  • Thông báo đang chạy (foreground) hiện tiến độ: xem [setRunStatus] — `FarmForegroundService` đọc.
 *
 * Channels (tạo bởi [ensureChannels], gọi từ AtProApplication):
 *   atpro_alert2 (HIGH)    — cảnh báo        (kênh cũ `atpro_alert` bị xoá)
 *   atpro_done   (DEFAULT) — phiên nuôi, tổng kết nhiệm vụ
 *   atpro_task   (LOW)     — từng tài khoản làm nhiệm vụ
 *   atpro_farm   (LOW)     — từng tài khoản nuôi xong
 *
 * Config: khoá `enable_system_notifications`, `notif_alerts`, `notif_sessions`, `notif_accounts`, `notif_tasks`,
 *         `notif_quiet_enabled`, `notif_quiet_start`, `notif_quiet_end`.
 */
object AtProNotificationManager {

    const val TAG = "AtProNotif"

    const val CH_ALERT = "atpro_alert2"
    const val CH_DONE  = "atpro_done"
    const val CH_TASK  = "atpro_task"
    const val CH_FARM  = "atpro_farm"

    private const val GROUP_ACCOUNTS = "atpro_group_accounts"
    private const val SUMMARY_ID     = 2999
    private const val ALERT_COOLDOWN_MS = 10 * 60_000L

    /** Tuỳ chọn hiện hành — ConfigViewModel gán lại mỗi lần lưu cài đặt. */
    @Volatile var prefs: NotificationPrefs = NotificationPrefs()

    /** Tương thích bản cũ: công tắc tổng. */
    var enableSystemNotifications: Boolean
        get() = prefs.enabled
        set(v) { prefs = prefs.copy(enabled = v) }

    private val alertThrottle = NotificationThrottle(ALERT_COOLDOWN_MS)

    // ── Public API — nuôi TikTok ──────────────────────────────

    fun notifyFarmStarted(accountCount: Int) =
        post(NotifCategory.SESSION, "farm_start", NotificationText.farmStarted(accountCount), id = 2001, openTab = "logs")

    fun notifyFarmCompleted(accountCount: Int, totalLikes: Int, totalFollows: Int, totalVideos: Int, durationMinutes: Int) =
        post(NotifCategory.SESSION, "farm_done", NotificationText.farmCompleted(accountCount, totalLikes, totalFollows, totalVideos, durationMinutes), id = 2002, openTab = "logs")

    fun notifySessionDone(account: String, likes: Int, follows: Int, videos: Int) =
        post(NotifCategory.ACCOUNT, "acc_$account", NotificationText.sessionDone(account, likes, follows, videos), group = true)

    fun notifyCheckpoint(account: String) =
        post(NotifCategory.ALERT, "checkpoint:$account", NotificationText.checkpoint(account), openTab = "accounts")

    fun notifyBanned(account: String) =
        post(NotifCategory.ALERT, "banned:$account", NotificationText.banned(account), openTab = "accounts")

    fun notifyError(error: String, account: String? = null) =
        post(NotifCategory.ALERT, "error:${account.orEmpty()}:${error.take(40)}", NotificationText.error(error, account), openTab = "logs")

    // ── Public API — nhiệm vụ kiếm tiền (v1.12.0) ─────────────

    fun notifyTaskStarted(accountNames: List<String>) =
        post(NotifCategory.TASK, "task_start", NotificationText.taskStarted(accountNames), id = 2003, openTab = "logs")

    fun notifyTaskAccountDone(account: String, jobs: Int, likes: Int, follows: Int, reward: Double) =
        post(NotifCategory.TASK, "task_acc_$account", NotificationText.taskAccountDone(account, jobs, likes, follows, reward), group = true)

    fun notifyTaskCompleted(accounts: Int, jobs: Int, likes: Int, follows: Int, reward: Double, minutes: Int, balance: String?) =
        post(NotifCategory.TASK, "task_done", NotificationText.taskCompleted(accounts, jobs, likes, follows, reward, minutes, balance), id = 2004, openTab = "services")

    fun notifyF4fSessionExpired() =
        post(NotifCategory.ALERT, "f4f_expired", NotificationText.f4fSessionExpired(), id = 2005, openTab = "services")

    fun notifyDailyLimit(account: String, detail: String) =
        post(NotifCategory.ALERT, "limit:$account", NotificationText.dailyLimit(account, detail), openTab = "services")

    fun notifyTaskAborted(reason: String) =
        post(NotifCategory.ALERT, "task_abort:${reason.take(40)}", NotificationText.taskAborted(reason), id = 2006, openTab = "logs")

    /** Thông báo thử — bỏ qua công tắc nhóm/giờ yên tĩnh; trả false nếu hệ thống đang chặn (quyền / kênh / thông báo bị tắt). */
    fun sendTest(ctx: Context): Boolean {
        if (!canPost(ctx, CH_DONE)) return false
        post(NotifCategory.SESSION, "test", NotifText("Thông báo hoạt động ✓", "AT PRO có thể gửi thông báo cho bạn bình thường."), id = 2900, force = true)
        return true
    }

    /** Phiên mới: gỡ các thông báo "từng tài khoản" của phiên trước cho đỡ rối. */
    fun startRun() {
        try {
            val nm = NotificationManagerCompat.from(com.atpro.AtProApplication.ctx)
            nm.cancel(SUMMARY_ID)
            alertThrottle.reset()
            RunStatusHolder.clear()
        } catch (e: Exception) {
            Log.w(TAG, "startRun: ${e.message}")
        }
    }

    // ── Tiến độ cho thông báo đang chạy (foreground) ──────────

    /** Dòng tiến độ hiện trên thông báo "AT PRO đang chạy". Gọi thoải mái — chỉ cập nhật tối đa 1 lần / 3 giây. */
    fun setRunStatus(title: String, line: String, done: Int = 0, total: Int = 0) {
        RunStatusHolder.set(RunStatus(title, line, done, total))
    }

    fun clearRunStatus() = RunStatusHolder.clear()

    // ── Đăng thông báo ────────────────────────────────────────

    private var autoId = 3000

    private fun post(
        category: NotifCategory,
        key: String,
        text: NotifText,
        id: Int? = null,
        group: Boolean = false,
        openTab: String? = null,
        force: Boolean = false,
    ) {
        try {
            val now = System.currentTimeMillis()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val decision = if (force) NotifDecision.POST else NotificationPolicy.decide(prefs, category, hour)
            if (decision == NotifDecision.DROP) return
            if (category == NotifCategory.ALERT && !alertThrottle.allow(key, now)) return

            val ctx = com.atpro.AtProApplication.ctx
            val channel = channelOf(category)
            if (!canPost(ctx, channel)) return

            val b = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_atpro)
                .setColor(ACCENT)
                .setContentTitle(text.title)
                .setContentText(text.body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(now)
                .setCategory(if (category == NotifCategory.ALERT) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .setPriority(priorityOf(category))
                .setContentIntent(openApp(ctx, openTab, requestCode = key.hashCode()))
            if (decision == NotifDecision.POST_SILENT) b.setSilent(true)
            if (group) b.setGroup(GROUP_ACCOUNTS)

            val nm = NotificationManagerCompat.from(ctx)
            // ID ổn định theo khoá: cùng loại/acc thì cập nhật thay vì chồng thêm một thông báo mới.
            val notifId = id ?: (2100 + (key.hashCode() and 0x7FFF))
            nm.notify(notifId, b.build())
            if (group) postGroupSummary(ctx, nm)
        } catch (e: SecurityException) {
            Log.w(TAG, "post: thiếu quyền thông báo — ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "post failed: ${e.message}")
        }
    }

    /** Dòng tổng cho nhóm "từng tài khoản" (bắt buộc để Android gộp nhóm). */
    private fun postGroupSummary(ctx: Context, nm: NotificationManagerCompat) {
        val s = NotificationCompat.Builder(ctx, CH_TASK)
            .setSmallIcon(R.drawable.ic_stat_atpro)
            .setColor(ACCENT)
            .setContentTitle("AT PRO")
            .setContentText("Kết quả từng tài khoản")
            .setGroup(GROUP_ACCOUNTS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx, "logs", requestCode = SUMMARY_ID))
            .build()
        nm.notify(SUMMARY_ID, s)
    }

    private fun channelOf(c: NotifCategory) = when (c) {
        NotifCategory.ALERT   -> CH_ALERT
        NotifCategory.SESSION -> CH_DONE
        NotifCategory.ACCOUNT -> CH_FARM
        NotifCategory.TASK    -> CH_TASK
    }

    private fun priorityOf(c: NotifCategory) = when (c) {
        NotifCategory.ALERT   -> NotificationCompat.PRIORITY_HIGH
        NotifCategory.SESSION -> NotificationCompat.PRIORITY_DEFAULT
        NotifCategory.ACCOUNT, NotifCategory.TASK -> NotificationCompat.PRIORITY_LOW
    }

    /** Chạm thông báo → mở app, đúng tab nếu có ([NotificationNavigator]). */
    private fun openApp(ctx: Context, tab: String?, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (tab != null) putExtra(NotificationNavigator.EXTRA_OPEN_TAB, tab)
        }
        return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ── Quyền + kênh ──────────────────────────────────────────

    /** Có thể đăng không: quyền POST_NOTIFICATIONS (Android 13+), thông báo của app chưa bị tắt, kênh chưa bị tắt. */
    fun canPost(ctx: Context, channelId: String? = null): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        if (channelId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Tạo kênh (idempotent). Gọi sớm từ AtProApplication. Kênh cũ `atpro_alert` (mức DEFAULT) bị thay bằng `atpro_alert2` (HIGH). */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel("atpro_alert")
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Cảnh báo", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Checkpoint, tài khoản bị khoá, Follow4Follow hết phiên, đạt giới hạn, lỗi" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Kết quả phiên", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Bắt đầu / hoàn thành phiên nuôi, tổng kết nhiệm vụ kiếm tiền" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TASK, "Nhiệm vụ kiếm tiền", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Kết quả từng tài khoản khi làm nhiệm vụ (không phát âm)" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FARM, "Từng tài khoản nuôi", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Mỗi tài khoản nuôi xong (không phát âm)" }
        )
        Log.i(TAG, "Notification channels ready")
    }

    // ── Config ────────────────────────────────────────────────

    suspend fun loadConfig() {
        val repo = LocalRepository.getInstance(com.atpro.AtProApplication.ctx)
        prefs = NotificationPrefs(
            enabled        = repo.getConfigBool("enable_system_notifications", true),
            alerts         = repo.getConfigBool("notif_alerts", true),
            sessions       = repo.getConfigBool("notif_sessions", true),
            accounts       = repo.getConfigBool("notif_accounts", true),
            tasks          = repo.getConfigBool("notif_tasks", true),
            quietEnabled   = repo.getConfigBool("notif_quiet_enabled", false),
            quietStartHour = repo.getConfigInt("notif_quiet_start", 23),
            quietEndHour   = repo.getConfigInt("notif_quiet_end", 7),
        )
        Log.i(TAG, "Config loaded. $prefs")
    }

    private const val ACCENT = 0xFF2F7BFF.toInt()
}

/** Điểm điều hướng từ thông báo: Activity ghi vào đây, MainScreen đọc rồi chuyển tab. */
object NotificationNavigator {
    const val EXTRA_OPEN_TAB = "atpro_open_tab"

    private val _requested = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val requested: kotlinx.coroutines.flow.StateFlow<String?> = _requested

    fun request(tab: String?) { _requested.value = tab }
    fun consume() { _requested.value = null }
}

/** Tiến độ hiện trên thông báo "đang chạy". */
data class RunStatus(val title: String, val line: String, val done: Int, val total: Int)

object RunStatusHolder {
    @Volatile var current: RunStatus? = null
        private set
    @Volatile private var listener: ((RunStatus?) -> Unit)? = null
    @Volatile private var lastPushMs = 0L

    fun set(s: RunStatus) {
        current = s
        val now = System.currentTimeMillis()
        if (now - lastPushMs >= 3_000L) { lastPushMs = now; listener?.invoke(s) }   // chống dồn: tối đa 1 cập nhật / 3s
    }

    fun clear() { current = null; listener?.invoke(null) }

    fun setListener(l: ((RunStatus?) -> Unit)?) { listener = l }
}
