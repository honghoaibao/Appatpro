package com.atpro.golike

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG      = "GolikeApi"
private const val BASE_URL = "https://gateway.golike.net/"

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient          = true
    coerceInputValues  = true
}

/**
 * GolikeApi — lightweight HTTP client dùng HttpURLConnection (không cần OkHttp).
 *
 * Base URL: https://gateway.golike.net/ (API endpoint thực — theo smali htool)
 * Web UI:   https://app.golike.net/home (chỉ dùng trong WebView đăng nhập)
 */
object GolikeApi {

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(request: LoginRequest): GolikeResult<LoginResponse> =
        post("api/auto/login", jsonParser.encodeToString(request), token = null)

    // ── User ─────────────────────────────────────────────────────────────────

    suspend fun getMe(token: String, deviceId: String? = null): GolikeResult<MeResponse> =
        get("api/users/me", token, deviceId)

    // ── Statistics ────────────────────────────────────────────────────────────

    suspend fun getStatistics(token: String, deviceId: String? = null): GolikeResult<StatisticsResponse> =
        get("api/statistics/report", token, deviceId)

    // ── TikTok ────────────────────────────────────────────────────────────────

    suspend fun getTikTokAccounts(token: String, deviceId: String? = null): GolikeResult<TikTokAccountsResponse> =
        get("api/tiktok-account", token, deviceId)

    /**
     * v1.2.5 — golike.py `get_next_job()`: response trả MỘT job + `lock` CÙNG lúc.
     * Không có endpoint job-detail riêng (endpoint `api/jobs/tiktok/job-detail`
     * từng dùng ở v1.2.3 KHÔNG tồn tại trong golike.py — đã xóa).
     */
    suspend fun getTikTokJobs(token: String, accountId: Int, deviceId: String? = null): GolikeResult<TikTokJobsResponse> =
        get(
            "api/advertising/publishers/tiktok/jobs?account_id=$accountId&data=null",
            token,
            deviceId,
        )

    /**
     * v1.2.5 — golike.py `complete_job()`: body 5 field
     * (account_id, ads_id, object_id, type, link) — KHÔNG có "success".
     * Endpoint đúng: `api/advertising/publishers/tiktok/complete-jobs`.
     */
    suspend fun completeTikTokJob(token: String, request: CompleteJobRequest, deviceId: String? = null): GolikeResult<CompleteJobResponse> =
        post(
            "api/advertising/publishers/tiktok/complete-jobs",
            jsonParser.encodeToString(request),
            token,
            deviceId,
        )

    /**
     * v1.2.5 — golike.py `skip_job()`: body 3 field (account_id, ads_id, object_id).
     * Dùng khi job không thực hiện được (acc bị block, lock hết hạn...).
     */
    suspend fun skipTikTokJob(token: String, request: SkipJobRequest, deviceId: String? = null): GolikeResult<SkipJobResponse> =
        post(
            "api/advertising/publishers/tiktok/skip-jobs",
            jsonParser.encodeToString(request),
            token,
            deviceId,
        )

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * v1.2.3 — T token: thời gian phiên hiện tại (Unix epoch giây, dạng chuỗi)
     * được mã hoá Base64 LIÊN TIẾP 3 lần.
     *
     * Xác minh bằng cách giải mã ngược mẫu T token người dùng cung cấp:
     *   base64(base64(base64("1781173114"))) ⇒ "VFZSak5FMVVSVE5OZWtWNFRrRTlQUT09"
     *   (trùng ~13 ký tự đầu với mẫu thực tế "VFZSak5FMVVSVT..." / "VFZSak5FMVVSVES...")
     * → 13 ký tự đầu của T LUÔN là "VFZSak5FMVVSVE" vì các timestamp gần nhau
     *   (cùng đơn vị "178...") cho base64 prefix giống nhau.
     */
    private fun generateTToken(): String {
        val sessionTimeSecs = (System.currentTimeMillis() / 1000L).toString()
        var encoded = sessionTimeSecs
        repeat(3) {
            encoded = android.util.Base64.encodeToString(
                encoded.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
        }
        return encoded
    }

    /**
     * v1.2.3 — Device id fallback nếu GolikeRepository không truyền vào
     * (vd gọi trực tiếp GolikeApi không qua repository). Sinh 1 lần / process
     * lifetime — KHÔNG persist (repository nên truyền deviceId đã lưu để
     * G-Device-Id ổn định giữa các lần mở app).
     */
    private val fallbackDeviceId: String by lazy { java.util.UUID.randomUUID().toString() }

    private suspend inline fun <reified T> get(endpoint: String, token: String?, deviceId: String? = null): GolikeResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(endpoint, token, deviceId).apply {
                    requestMethod = "GET"
                }
                readResponse<T>(conn)
            } catch (e: Exception) {
                Log.e(TAG, "GET $endpoint — ${e.message}")
                GolikeResult.Error(e.message ?: "Network error")
            }
        }

    private suspend inline fun <reified T> post(
        endpoint: String,
        body:     String,
        token:    String?,
        deviceId: String? = null,
    ): GolikeResult<T> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(endpoint, token, deviceId).apply {
                requestMethod = "POST"
                doOutput      = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            readResponse<T>(conn)
        } catch (e: Exception) {
            Log.e(TAG, "POST $endpoint — ${e.message}")
            GolikeResult.Error(e.message ?: "Network error")
        }
    }

    private fun openConnection(endpoint: String, token: String?, deviceId: String? = null): HttpURLConnection =
        (URL("$BASE_URL$endpoint").openConnection() as HttpURLConnection).apply {

            // ── Base headers — theo golike.py _base_headers() ─────────────────────
            setRequestProperty("Accept",           "application/json, text/plain, */*")
            setRequestProperty("Accept-Language",  "vi-VN,vi;q=0.9,fr-FR;q=0.8,fr;q=0.7,en-US;q=0.6,en;q=0.5")
            setRequestProperty("Content-Type",     "application/json;charset=utf-8")
            setRequestProperty("Origin",           "https://app.golike.net")
            setRequestProperty("Referer",          "https://app.golike.net/")
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0 Mobile Safari/537.36"
            )
            // Sec- headers — CORS same-site context
            setRequestProperty("Sec-Ch-Ua",          "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"")
            setRequestProperty("Sec-Ch-Ua-Mobile",   "?1")
            setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"")
            setRequestProperty("Sec-Fetch-Dest",     "empty")
            setRequestProperty("Sec-Fetch-Mode",     "cors")
            setRequestProperty("Sec-Fetch-Site",     "same-site")

            // ── Auth headers — theo golike.py _auth_headers() ─────────────────────
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
                // NOTE: G-Auth KHÔNG có trong golike.py reference → xóa v1.2.5.
            }
            setRequestProperty("T",           generateTToken())
            setRequestProperty("G-Device-Id", deviceId ?: fallbackDeviceId)

            connectTimeout = 15_000
            readTimeout    = 20_000
        }

    /**
     * v1.2.5 — Theo golike.py `_handle_response()`:
     * - 401/403 → lỗi xác thực (token hết hạn/sai) → cần đăng nhập lại.
     * - Khác (400, 429, 5xx...) → CỐ GẮNG parse JSON body để lấy `message` +
     *   `cooldown` (golike.py dùng cooldown để biết chờ bao lâu trước khi retry).
     *   Nếu body không phải JSON hợp lệ (HTML error page...), fallback message chung.
     */
    private inline fun <reified T> readResponse(conn: HttpURLConnection): GolikeResult<T> {
        return try {
            val code = conn.responseCode

            if (code !in 200..299) {
                val errBody = conn.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    ?: ""
                Log.w(TAG, "HTTP $code — ${errBody.take(200)}")

                // Thử parse JSON để lấy message + cooldown thực (golike.py: data.get("message"), data.get("cooldown", 0))
                val (msg, cooldown) = try {
                    val errJson = jsonParser.parseToJsonElement(errBody).jsonObject
                    val m = errJson["message"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                        ?: "Lỗi máy chủ (HTTP $code)"
                    val c = errJson["cooldown"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    } ?: 0
                    Pair(m, c)
                } catch (_: Exception) {
                    Pair("Lỗi máy chủ (HTTP $code)", 0)
                }

                val isAuth = code == 401 || code == 403
                return GolikeResult.Error(msg, code, cooldown, isAuth)
            }

            val body = conn.inputStream
                .bufferedReader(Charsets.UTF_8)
                .readText()
            Log.d(TAG, "HTTP $code — ${body.take(120)}")
            GolikeResult.Success(jsonParser.decodeFromString<T>(body))
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            GolikeResult.Error(e.message ?: "Parse error")
        } finally {
            conn.disconnect()
        }
    }
}
