package com.atpro.notification

import android.util.Log
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
            emoji = "🚀",
            title = "Farm Started",
            lines = listOf(
                "📱 Device: ${getDeviceName()}",
                "👥 Accounts: $accountCount",
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
            emoji = "✅",
            title = "Farm Completed",
            lines = listOf(
                "📱 Device: ${getDeviceName()}",
                "👥 Accounts: $accountCount",
                "❤️ Likes: $totalLikes",
                "➕ Follows: $totalFollows",
                "▶️ Videos: $totalVideos",
                "⏱️ Duration: ${durationMinutes}m",
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
            emoji = "📊",
            title = "Session Done: @$account",
            lines = listOf(
                "❤️ Likes: $likes",
                "➕ Follows: $follows",
                "▶️ Videos: $videos",
            ),
            color = 0x6C63FF,
        )
        send(msg)
    }

    suspend fun notifyCheckpoint(account: String) {
        val msg = buildMessage(
            emoji = "⚠️",
            title = "Checkpoint Detected!",
            lines = listOf(
                "👤 Account: @$account",
                "📱 Device: ${getDeviceName()}",
                "⏰ Time: ${currentTime()}",
                "💡 Hành động: Tài khoản bị đánh dấu checkpoint",
            ),
            color = 0xF59E0B,
        )
        send(msg)
    }

    suspend fun notifyBanned(account: String) {
        val msg = buildMessage(
            emoji = "🚫",
            title = "Account Banned!",
            lines = listOf(
                "👤 Account: @$account",
                "📱 Device: ${getDeviceName()}",
                "⏰ Time: ${currentTime()}",
            ),
            color = 0xEF4444,
        )
        send(msg)
    }

    suspend fun notifyError(error: String, account: String? = null) {
        val lines = mutableListOf(
            "📱 Device: ${getDeviceName()}",
            "💥 Error: $error",
            "⏰ Time: ${currentTime()}",
        )
        account?.let { lines.add(1, "👤 Account: @$it") }

        val msg = buildMessage(
            emoji = "❌",
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
            Log.i(TAG, "✅ Telegram sent: ${msg.title}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Telegram failed: ${e.message}")
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
            Log.i(TAG, "✅ Discord sent: ${msg.title}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Discord failed: ${e.message}")
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
