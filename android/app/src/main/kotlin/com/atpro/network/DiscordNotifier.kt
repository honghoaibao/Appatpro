package com.atpro.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DiscordNotifier — v1.3.0.
 *
 * Gửi thông báo trạng thái nuôi acc (bắt đầu / hoàn thành / lỗi) qua Discord
 * Webhook do người dùng tự tạo (Server Settings → Integrations → Webhooks →
 * New Webhook → Copy Webhook URL) rồi dán vào Cài đặt.
 *
 * Dùng thẳng HttpURLConnection (giống UpdateChecker.kt) — không cần thêm thư
 * viện ngoài. Mọi lỗi mạng đều bị nuốt (không throw) vì đây chỉ là kênh thông
 * báo phụ, không được phép làm gián đoạn quá trình nuôi acc chính.
 */
object DiscordNotifier {

    private const val TAG = "DiscordNotifier"

    /**
     * Gửi 1 message text đơn giản.
     * @return true nếu Discord xác nhận nhận được (204 No Content).
     */
    suspend fun send(webhookUrl: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext false
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") &&
            !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")
        ) {
            Log.w(TAG, "URL không giống Discord webhook hợp lệ — bỏ qua gửi")
            return@withContext false
        }
        try {
            val conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod   = "POST"
                doOutput        = true
                connectTimeout  = 8_000
                readTimeout     = 8_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                // Discord giới hạn content tối đa 2000 ký tự — cắt an toàn ở 1900.
                put("content", message.take(1_900))
                put("username", "AT Pro")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            conn.disconnect()
            val ok = code in 200..299
            if (!ok) Log.w(TAG, "Discord webhook trả code $code")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Gửi Discord thất bại: ${e.message}")
            false
        }
    }
}
