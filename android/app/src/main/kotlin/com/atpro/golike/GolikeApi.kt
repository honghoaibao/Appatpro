package com.atpro.golike

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG      = "GolikeApi"
private const val BASE_URL = "https://app.golike.net/"

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient          = true
    coerceInputValues  = true
}

/**
 * GolikeApi — lightweight HTTP client dùng HttpURLConnection (không cần OkHttp).
 *
 * Endpoints theo tài liệu: .claude/context/golike_api.md
 */
object GolikeApi {

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(request: LoginRequest): GolikeResult<LoginResponse> =
        post("api/auto/login", jsonParser.encodeToString(request), token = null)

    // ── User ─────────────────────────────────────────────────────────────────

    suspend fun getMe(token: String): GolikeResult<MeResponse> =
        get("api/users/me", token)

    // ── Statistics ────────────────────────────────────────────────────────────

    suspend fun getStatistics(token: String): GolikeResult<StatisticsResponse> =
        get("api/statistics/report", token)

    // ── TikTok ────────────────────────────────────────────────────────────────

    suspend fun getTikTokAccounts(token: String): GolikeResult<TikTokAccountsResponse> =
        get("api/tiktok-account", token)

    suspend fun getTikTokJobs(token: String, uniqueUsername: String): GolikeResult<TikTokJobsResponse> =
        get(
            "api/advertising/publishers/tiktok/_private/get-jobs" +
                "?unique_username=${encode(uniqueUsername)}",
            token,
        )

    suspend fun completeTikTokJob(token: String, request: CompleteJobRequest): GolikeResult<CompleteJobResponse> =
        post(
            "api/advertising/publishers/tiktok/_private/complete-jobs",
            jsonParser.encodeToString(request),
            token,
        )

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend inline fun <reified T> get(endpoint: String, token: String?): GolikeResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(endpoint, token).apply {
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
    ): GolikeResult<T> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(endpoint, token).apply {
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

    private fun openConnection(endpoint: String, token: String?): HttpURLConnection =
        (URL("$BASE_URL$endpoint").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept",       "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000
            readTimeout    = 20_000
        }

    private inline fun <reified T> readResponse(conn: HttpURLConnection): GolikeResult<T> {
        return try {
            val code = conn.responseCode

            // Non-2xx: read error body for logging only — do NOT attempt JSON parse.
            // The server often returns an HTML error page which would cause a misleading
            // "Unexpected JSON token" exception to surface in the UI.
            if (code !in 200..299) {
                val errBody = conn.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    ?.take(200)
                    ?: ""
                Log.w(TAG, "HTTP $code — $errBody")
                return GolikeResult.Error("Lỗi máy chủ (HTTP $code)")
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
