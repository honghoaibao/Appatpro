package com.atpro.tasks.f4f

import com.atpro.tasks.TaskResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.math.abs

/**
 * Response Follow4Follow đã bóc "phong bì" (envelope):
 * `{status_code, message, errors, data}` — `status_code` trong JSON có thể KHÁC mã HTTP
 * (đã quan sát HTTP 200 + status_code 400) nên phải kiểm cả hai.
 */
data class F4fResponse(
    val httpCode: Int,
    /** `status_code` trong JSON; null nếu body không có. */
    val apiCode: Int?,
    val message: String,
    val data: JsonElement?,
    val errors: JsonElement?,
    /** Cờ `success` (skip/report có thể trả `success:true`) — web coi `status_code==200 || success`. */
    val successFlag: Boolean?,
    val retryAfterSecs: Int,
    /** Không kết nối được (mất mạng, timeout…). */
    val networkError: Boolean = false,
    /** Có response nhưng body không phải JSON. */
    val malformed: Boolean = false,
) {
    val isError: Boolean
        get() = networkError || malformed ||
            ((httpCode >= 400 || (apiCode ?: 0) >= 400) && successFlag != true)

    val isRateLimited: Boolean get() = httpCode == 429 || apiCode == 429

    /** 401 hoặc message "cần đăng nhập" → token hết hạn/không hợp lệ. */
    val isUnauthorized: Boolean
        get() {
            if (httpCode == 401 || apiCode == 401) return true
            val m = message.lowercase()
            return "cần đăng nhập" in m || "unauthenticated" in m
        }

    /** `data` dạng object (null nếu không phải object). */
    val dataObj: JsonObject? get() = data.asObj()

    /**
     * Mô tả lỗi cho người dùng: message của server; nếu rỗng thì lấy lỗi validation đầu tiên
     * (`errors: {field: [msg,…]}`) hoặc mã HTTP.
     */
    fun describe(): String {
        if (message.isNotBlank()) return message
        val firstError = firstValidationMessage()
        if (firstError != null) return firstError
        return "Lỗi HTTP $httpCode"
    }

    private fun firstValidationMessage(): String? {
        val obj = (errors.nonNull() ?: data.asObj()?.get("errors")).asObj() ?: return null
        for ((field, v) in obj) {
            val msg = v.asArr()?.firstOrNull().asStr() ?: v.asStr()
            if (!msg.isNullOrBlank()) return "$field: $msg"
        }
        return null
    }
}

/**
 * Đổi response lỗi thành [TaskResult.Error] dùng chung cho provider và service:
 * mất mạng → lỗi thường; 401 → `isAuthError` (+ [onAuthExpired]); 429 → `cooldownSecs` (kẹp 3–300s);
 * còn lại → message server kèm gợi ý ([F4fHints]).
 */
internal fun F4fResponse.toTaskError(onAuthExpired: () -> Unit = {}): TaskResult.Error {
    val msg = describe()
    return when {
        networkError   -> TaskResult.Error(msg)
        isUnauthorized -> {
            onAuthExpired()
            TaskResult.Error(msg, isAuthError = true)
        }
        isRateLimited  -> TaskResult.Error(
            msg.ifBlank { "Bạn thao tác hơi nhanh" },
            cooldownSecs = retryAfterSecs.coerceIn(3, 300),
        )
        else           -> TaskResult.Error(F4fHints.withHint(msg))
    }
}

/**
 * Client HTTP cho `https://api.follow4follow.net` — tự gắn đủ 3 header bắt buộc trên MỌI request
 * (`Authorization: Bearer`, `x-ts`, `x-sign`) + Origin/Referer/User-Agent như bản web.
 *
 * - Không bao giờ ném exception: lỗi mạng/JSON hỏng trả về [F4fResponse] có cờ tương ứng.
 * - Đồng hồ máy lệch → học độ lệch từ header `Date` của server và thử lại 1 lần (server chỉ
 *   chấp nhận x-ts trong khoảng ±60s).
 * - KHÔNG ghi log token/mật khẩu ở bất kỳ đâu.
 */
class F4fApiClient(
    private val transport: F4fTransport,
    private val tokenProvider: () -> String?,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    @Volatile private var clockOffsetSecs: Long = 0L

    val hasToken: Boolean get() = !tokenProvider().isNullOrBlank()

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): F4fResponse =
        call("GET", path, query, null, authenticated = true)

    suspend fun post(path: String, body: JsonObject? = null, authenticated: Boolean = true): F4fResponse =
        call("POST", path, emptyMap(), body, authenticated)

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
        authenticated: Boolean,
    ): F4fResponse {
        var response = once(method, path, query, body, authenticated)
        // Lệch đồng hồ: lần đầu x-ts sai → server từ chối; sau khi biết độ lệch thì thử lại đúng 1 lần.
        if (response.isError && !response.networkError && !response.malformed &&
            response.httpCode in 400..499 && response.httpCode != 429 && response.httpCode != 422 &&
            abs(clockOffsetSecs) > SKEW_RETRY_SECS
        ) {
            response = once(method, path, query, body, authenticated)
        }
        return response
    }

    private suspend fun once(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
        authenticated: Boolean,
    ): F4fResponse {
        val signed = F4fSigner.sign(method, path, nowSecs() + clockOffsetSecs)
        val headers = LinkedHashMap<String, String>().apply {
            put("Accept", "application/json")
            if (method == "POST" || method == "PUT") put("Content-Type", "application/json")
            put("Origin", ORIGIN)
            put("Referer", "$ORIGIN/")
            put("User-Agent", USER_AGENT)
            put("x-ts", signed.ts.toString())
            put("x-sign", signed.sign)
            val token = if (authenticated) tokenProvider() else null
            if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
        }
        val raw = try {
            transport.execute(F4fHttpRequest(method, path, query, headers, body?.toString()))
        } catch (e: IOException) {
            return networkFailure("Lỗi mạng: ${e.message ?: e.javaClass.simpleName}")
        }
        raw.serverTimeSecs?.let { clockOffsetSecs = it - nowSecs() }
        return parse(raw.httpCode, raw.body)
    }

    private fun networkFailure(message: String) = F4fResponse(
        httpCode = 0, apiCode = null, message = message, data = null, errors = null,
        successFlag = null, retryAfterSecs = 0, networkError = true,
    )

    private fun parse(httpCode: Int, text: String): F4fResponse {
        val root = parseJson(text).asObj()
            ?: return F4fResponse(
                httpCode = httpCode, apiCode = null,
                message = if (text.isBlank()) "Server trả phản hồi rỗng (HTTP $httpCode)"
                          else "Server trả phản hồi không phải JSON (HTTP $httpCode)",
                data = null, errors = null, successFlag = null, retryAfterSecs = 0, malformed = true,
            )
        val data = root["data"].nonNull()
        val retry = root["retry_after"].asInt() ?: data.asObj()?.get("retry_after").asInt() ?: 0
        return F4fResponse(
            httpCode = httpCode,
            apiCode = root["status_code"].asInt(),
            message = root["message"].asStr().orEmpty(),
            data = data,
            errors = root["errors"].nonNull(),
            successFlag = root["success"].asBool(),
            retryAfterSecs = retry,
        )
    }

    companion object {
        const val BASE_URL = "https://api.follow4follow.net"
        const val ORIGIN = "https://follow4follow.net"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36"

        /** Lệch giờ quá ngưỡng này (giây) thì mới thử lại bằng giờ đã hiệu chỉnh. */
        private const val SKEW_RETRY_SECS = 30L
    }
}
