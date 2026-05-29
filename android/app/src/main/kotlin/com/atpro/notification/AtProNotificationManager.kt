package com.atpro.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atpro.data.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * NotificationManager.kt — Phase 2
 * Thay thế ui/notifications.py (Telegram + Discord)
 *
 * Events được notify:
 *   - Farm bắt đầu / kết thúc
 *   - Account checkpoint / banned
 *   - Session summary mỗi account
 *   - Lỗi nghiêm trọng
 */
object AtProNotificationManager {

    const val TAG = "AtProNotif"

    // Config — load từ Supabase/local khi khởi động
    var telegramToken:  String = ""
    var telegramChatId: String = ""
    var discordWebhook: String = ""
    var enableTelegram: Boolean = true
    var enableDiscord:  Boolean = true

    // ── Public API ────────────────────────────────────────────

    suspend fun notifyFarmStarted(accountCount: Int) {
        val msg = buildMessage(
            emoji = ">>",
            title = "Farm Started",
            lines = listOf(
                "DEV: Device: ${getDeviceName()}",
                "ACCS: Accounts: $accountCount",
                "⏰ Time: ${currentTime()}",
            ),
            color = 0x6C63FF,
        )
        send(msg)
    }

    suspend fun notifyFarmCompleted(
        accountCount: Int,
        totalLikes: Int,
        totalFollows: Int,
        totalVideos: Int,
        durationMinutes: Int,
    ) {
        val msg = buildMessage(
            emoji = "OK:",
            title = "Farm Completed",
            lines = listOf(
                "DEV: Device: ${getDeviceName()}",
                "ACCS: Accounts: $accountCount",
                "LIKE: Likes: $totalLikes",
                "FOLLOW: Follows: $totalFollows",
                "▶ Videos: $totalVideos",
                "⏱ Duration: ${durationMinutes}m",
                "⏰ Finished: ${currentTime()}",
            ),
            color = 0x10B981,
        )
        send(msg)
    }

    suspend fun notifySessionDone(
        account: String,
        likes: Int,
        follows: Int,
        videos: Int,
    ) {
        val msg = buildMessage(
            emoji = "STAT:",
            title = "Session Done: @$account",
            lines = listOf(
                "LIKE: Likes: $likes",
                "FOLLOW: Follows: $follows",
                "▶ Videos: $videos",
            ),
            color = 0x6C63FF,
        )
        send(msg)
    }

    suspend fun notifyCheckpoint(account: String) {
        val msg = buildMessage(
            emoji = "WARN:",
            title = "Checkpoint Detected!",
            lines = listOf(
                "ACC: Account: @$account",
                "DEV: Device: ${getDeviceName()}",
                "⏰ Time: ${currentTime()}",
                "HINT: Hành động: Tài khoản bị đánh dấu checkpoint",
            ),
            color = 0xF59E0B,
        )
        send(msg)
    }

    suspend fun notifyBanned(account: String) {
        val msg = buildMessage(
            emoji = "BLOCK:",
            title = "Account Banned!",
            lines = listOf(
                "ACC: Account: @$account",
                "DEV: Device: ${getDeviceName()}",
                "⏰ Time: ${currentTime()}",
            ),
            color = 0xEF4444,
        )
        send(msg)
    }

    suspend fun notifyError(error: String, account: String? = null) {
        val lines = mutableListOf(
            "DEV: Device: ${getDeviceName()}",
            "CRASH: Error: $error",
            "⏰ Time: ${currentTime()}",
        )
        account?.let { lines.add(1, "ACC: Account: @$it") }

        val msg = buildMessage(
            emoji = "ERR:",
            title = "Error Occurred",
            lines = lines,
            color = 0xEF4444,
        )
        send(msg)
    }

    // ── Send both channels ────────────────────────────────────

    private suspend fun send(msg: NotificationMessage) {
        if (enableTelegram && telegramToken.isNotEmpty() && telegramChatId.isNotEmpty()) {
            sendTelegram(msg)
        }
        if (enableDiscord && discordWebhook.isNotEmpty()) {
            sendDiscord(msg)
        }
    }

    // ── Telegram ──────────────────────────────────────────────

    private suspend fun sendTelegram(msg: NotificationMessage) = withContext(Dispatchers.IO) {
        try {
            val text = buildString {
                append("${msg.emoji} *${escapeMarkdown(msg.title)}*\n\n")
                msg.lines.forEach { append("${escapeMarkdown(it)}\n") }
                append("\n`AT PRO Android`")
            }

            val body = buildJsonObject {
                put("chat_id", telegramChatId)
                put("text", text)
                put("parse_mode", "MarkdownV2")
                put("disable_notification", false)
            }.toString()

            post("https://api.telegram.org/bot$telegramToken/sendMessage", body)
            Log.i(TAG, "OK: Telegram sent: ${msg.title}")
        } catch (e: Exception) {
            Log.e(TAG, "ERR: Telegram failed: ${e.message}")
        }
    }

    // ── Discord ───────────────────────────────────────────────

    private suspend fun sendDiscord(msg: NotificationMessage) = withContext(Dispatchers.IO) {
        try {
            val embed = buildJsonObject {
                put("title", "${msg.emoji} ${msg.title}")
                put("description", msg.lines.joinToString("\n"))
                put("color", msg.color)
                put("footer", buildJsonObject {
                    put("text", "AT PRO Android • ${currentTime()}")
                })
                put("timestamp", isoTimestamp())
            }

            val body = buildJsonObject {
                put("username", "AT PRO")
                put("embeds", buildJsonArray { add(embed) })
            }.toString()

            post(discordWebhook, body)
            Log.i(TAG, "OK: Discord sent: ${msg.title}")
        } catch (e: Exception) {
            Log.e(TAG, "ERR: Discord failed: ${e.message}")
        }
    }

    // ── HTTP helper ───────────────────────────────────────────

    private fun post(urlStr: String, body: String): Int {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 6000
        conn.readTimeout    = 8000
        conn.doOutput       = true
        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    // ── Message builder ───────────────────────────────────────

    private fun buildMessage(
        emoji: String,
        title: String,
        lines: List<String>,
        color: Int,
    ) = NotificationMessage(emoji, title, lines, color)

    private data class NotificationMessage(
        val emoji: String,
        val title: String,
        val lines: List<String>,
        val color: Int,
    )

    // ── Helpers ───────────────────────────────────────────────

    private fun currentTime(): String =
        SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date())

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun getDeviceName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    // Escape Telegram MarkdownV2 special chars
    private fun escapeMarkdown(text: String): String =
        text.replace("_", "\\_").replace("*", "\\*")
            .replace("[", "\\[").replace("]", "\\]")
            .replace("(", "\\(").replace(")", "\\)")
            .replace("~", "\\~").replace("`", "\\`")
            .replace(">", "\\>").replace("#", "\\#")
            .replace("+", "\\+").replace("-", "\\-")
            .replace("=", "\\=").replace("|", "\\|")
            .replace("{", "\\{").replace("}", "\\}")
            .replace(".", "\\.").replace("!", "\\!")

    // ── In-app Android notifications ────────────────────────

    private var notifId = 2000

    fun notifyInApp(context: Context, title: String, body: String, channel: String = "atpro_done") {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val pi = PendingIntent.getActivity(
                context, 0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(notifId++, notif)
        } catch (e: Exception) {
            Log.e(TAG, "notifyInApp: ${e.message}")
        }
    }

    // ── Load config từ Room DB ────────────────────────────────
    suspend fun loadConfig() {
        val repo = LocalRepository.getInstance(com.atpro.AtProApplication.ctx)
        telegramToken  = repo.getConfig("telegram_token")
        telegramChatId = repo.getConfig("telegram_chat_id")
        discordWebhook = repo.getConfig("discord_webhook")
        enableTelegram = repo.getConfigBool("enable_telegram", true)
        enableDiscord  = repo.getConfigBool("enable_discord", true)
        Log.i(TAG, "Config loaded. TG=${telegramToken.isNotEmpty()}, DC=${discordWebhook.isNotEmpty()}")
    }
}
