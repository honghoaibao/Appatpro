package com.atpro.cloud.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small Supabase Auth REST client.
 *
 * It intentionally uses the platform HttpURLConnection already available in AT PRO:
 * no additional networking SDK, persistent socket, or background polling is introduced.
 * Construct this class only with the project's public/publishable key; never use service_role.
 */
class SupabaseAuthClient(
    private val projectUrl: String,
    private val publishableKey: String,
    private val sessionStore: SecureAuthSessionStore,
) {
    suspend fun signUp(email: String, password: String): AuthResponse = request(
        path = "/auth/v1/signup",
        body = JSONObject().put("email", email.trim()).put("password", password),
        existingAccessToken = null,
        persistSession = true,
    )

    suspend fun signIn(email: String, password: String): AuthResponse = request(
        path = "/auth/v1/token?grant_type=password",
        body = JSONObject().put("email", email.trim()).put("password", password),
        existingAccessToken = null,
        persistSession = true,
    )

    suspend fun refresh(): AuthResponse {
        val current = sessionStore.read()
            ?: return AuthResponse.Error("Phiên đăng nhập không còn tồn tại.")
        return request(
            path = "/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", current.refreshToken),
            existingAccessToken = null,
            persistSession = true,
        )
    }

    suspend fun signOut() {
        val session = sessionStore.read()
        try {
            if (session != null) {
                request(
                    path = "/auth/v1/logout",
                    body = null,
                    existingAccessToken = session.accessToken,
                    persistSession = false,
                )
            }
        } finally {
            sessionStore.clear()
        }
    }

    private suspend fun request(
        path: String,
        body: JSONObject?,
        existingAccessToken: String?,
        persistSession: Boolean,
    ): AuthResponse = withContext(Dispatchers.IO) {
        if (projectUrl.isBlank() || publishableKey.isBlank()) {
            return@withContext AuthResponse.Error("Supabase chưa được cấu hình.")
        }
        var connection: HttpURLConnection? = null
        try {
            val base = projectUrl.trim().trimEnd('/')
            connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "POST" else "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                existingAccessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)

            if (status !in 200..299) {
                val message = json.optString("msg")
                    .ifBlank { json.optString("message") }
                    .ifBlank { json.optString("error_description") }
                    .ifBlank { json.optString("error") }
                    .ifBlank { "Supabase Auth trả về HTTP $status." }
                return@withContext AuthResponse.Error(message, status)
            }

            val user = json.optJSONObject("user") ?: json
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
            val userId = user.optString("id")
            if (accessToken.isNotBlank() && refreshToken.isNotBlank() && userId.isNotBlank()) {
                val expiresIn = json.optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS)
                val expiresAt = json.optLong(
                    "expires_at",
                    System.currentTimeMillis() / 1000L + expiresIn,
                )
                val session = AuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenType = json.optString("token_type", "bearer"),
                    expiresAtEpochSeconds = expiresAt,
                    userId = userId,
                    email = user.optString("email").takeIf { it.isNotBlank() },
                )
                if (persistSession) sessionStore.save(session)
                return@withContext AuthResponse.Authenticated(session)
            }

            // Supabase may return a user without a session when email confirmation is required.
            AuthResponse.ConfirmationRequired(
                email = user.optString("email").ifBlank { null },
            )
        } catch (error: Exception) {
            AuthResponse.Error(error.message ?: "Không thể kết nối Supabase.")
        } finally {
            connection?.disconnect()
        }
    }

    sealed interface AuthResponse {
        data class Authenticated(val session: AuthSession) : AuthResponse
        data class ConfirmationRequired(val email: String?) : AuthResponse
        data class Error(val message: String, val httpStatus: Int? = null) : AuthResponse
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 15_000
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600L
    }
}
