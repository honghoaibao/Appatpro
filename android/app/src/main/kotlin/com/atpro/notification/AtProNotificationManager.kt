package com.atpro.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atpro.MainActivity
import com.atpro.data.LocalRepository
import java.text.SimpleDateFormat
import java.util.*

/**
 * AtProNotificationManager — v1.1.2
 *
 * Thông báo hệ thống Android thuần — không còn Telegram / Discord.
 *
 * Channels (tạo bởi AtProApplication):
 *   atpro_done  (IMPORTANCE_DEFAULT) — Farm bắt đầu / kết thúc
 *   atpro_alert (IMPORTANCE_DEFAULT) — Checkpoint, ban, lỗi
 *   atpro_farm  (IMPORTANCE_LOW)     — Session xong từng acc (silent)
 *
 * Config:
 *   enable_system_notifications (Bool, mặc định true)
 */
object AtProNotificationManager {

    const val TAG = "AtProNotif"

    var enableSystemNotifications: Boolean = true

    // ── Public API ────────────────────────────────────────────

    fun notifyFarmStarted(accountCount: Int) {
        if (!enableSystemNotifications) return
        notifyInApp(
            title   = "AT PRO — Bắt đầu nuôi",
            body    = "$accountCount tài khoản • ${currentTime()}",
            channel = "atpro_done",
        )
    }

    fun notifyFarmCompleted(
        accountCount: Int,
        totalLikes: Int,
        totalFollows: Int,
        totalVideos: Int,
        durationMinutes: Int,
    ) {
        if (!enableSystemNotifications) return
        notifyInApp(
            title   = "AT PRO — Nuôi hoàn tất",
            body    = "$accountCount acc · Like: $totalLikes · Follow: $totalFollows · Video: $totalVideos · ${durationMinutes}m",
            channel = "atpro_done",
        )
    }

    fun notifySessionDone(
        account: String,
        likes: Int,
        follows: Int,
        videos: Int,
    ) {
        if (!enableSystemNotifications) return
        notifyInApp(
            title   = "Xong: @$account",
            body    = "Like: $likes · Follow: $follows · Video: $videos",
            channel = "atpro_farm",
        )
    }

    fun notifyCheckpoint(account: String) {
        if (!enableSystemNotifications) return
        notifyInApp(
            title   = "Checkpoint — @$account",
            body    = "Tài khoản bị đánh dấu checkpoint • ${currentTime()}",
            channel = "atpro_alert",
        )
    }

    fun notifyBanned(account: String) {
        if (!enableSystemNotifications) return
        notifyInApp(
            title   = "Tài khoản bị ban — @$account",
            body    = "Phát hiện lúc ${currentTime()}",
            channel = "atpro_alert",
        )
    }

    fun notifyError(error: String, account: String? = null) {
        if (!enableSystemNotifications) return
        val body = if (account != null) "@$account — $error" else error
        notifyInApp(
            title   = "AT PRO — Lỗi",
            body    = body,
            channel = "atpro_alert",
        )
    }

    // ── In-app notification ───────────────────────────────────

    private var notifId = 2000

    private fun notifyInApp(title: String, body: String, channel: String) {
        try {
            val ctx = com.atpro.AtProApplication.ctx
            val nm  = ctx.getSystemService(NotificationManager::class.java)
            val pi  = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(notifId++, notif)
        } catch (e: Exception) {
            Log.e(TAG, "notifyInApp failed: ${e.message}")
        }
    }

    // ── Config ────────────────────────────────────────────────

    suspend fun loadConfig() {
        val repo = LocalRepository.getInstance(com.atpro.AtProApplication.ctx)
        enableSystemNotifications = repo.getConfigBool("enable_system_notifications", true)
        Log.i(TAG, "Config loaded. systemNotif=$enableSystemNotifications")
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date())
}
