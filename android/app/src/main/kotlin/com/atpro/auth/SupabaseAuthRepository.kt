package com.atpro.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class AuthHttpResponse(val statusCode: Int, val body: String)

internal interface AuthHttpTransport {
    fun execute(path: String, body: String? = null, accessToken: String? = null): AuthHttpResponse
}

/** Minimal HTTPS transport; no additional networking dependency is added to the APK. */
internal class UrlConnectionAuthTransport : AuthHttpTransport {
    override fun execute(path: String, body: String?, accessToken: String?): AuthHttpResponse {
        val connection = (URL(SupabaseConfig.BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return AuthHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/**
 * Supabase email/password authentication.
 *
 * Core app behavior remains local-first: creating an AT PRO account is optional,
 * and no automation feature is blocked when the user is signed out or offline.
 */
class SupabaseAuthRepository internal constructor(
    private val storage: AuthSessionStorage,
    private val transport: AuthHttpTransport,
) {
    private val _state = MutableStateFlow<SupabaseAuthState>(SupabaseAuthState.SignedOut)
    val state: StateFlow<SupabaseAuthState> = _state.asStateFlow()

    /** Restore the encrypted local session off the main thread, then refresh only near expiry. */
    suspend fun restoreSavedSession() = withContext(Dispatchers.IO) {
        val session = storage.load() ?: return@withContext
        _state.value = SupabaseAuthState.SignedIn(session.user)
        if (session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1_000L + REFRESH_EARLY_SECONDS) {
            getValidAccessToken()
        }
    }

    suspend fun signUp(email: String, password: String): AuthActionResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()

        val response = request("/auth/v1/signup", payload)
            ?: return@withContext AuthActionResult.Failure("Không thể kết nối Supabase. Kiểm tra Internet rồi thử lại.")
        if (response.statusCode !in 200..299) {
            return@withContext AuthActionResult.Failure(
                SupabaseAuthJson.errorMessage(response.body, response.statusCode)
            )
        }

        val session = SupabaseAuthJson.parseSession(response.body)
        val user = SupabaseAuthJson.parseUser(response.body)
        if (session != null) {
            storage.save(session)
            _state.value = SupabaseAuthState.SignedIn(session.user)
        }
        AuthActionResult.Success(
            user = session?.user ?: user,
            needsEmailConfirmation = session == null,
        )
    }

    suspend fun signIn(email: String, password: String): AuthActionResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()

        val response = request("/auth/v1/token?grant_type=password", payload)
            ?: return@withContext AuthActionResult.Failure("Không thể kết nối Supabase. Kiểm tra Internet rồi thử lại.")
        if (response.statusCode !in 200..299) {
            return@withContext AuthActionResult.Failure(
                SupabaseAuthJson.errorMessage(response.body, response.statusCode)
            )
        }

        val session = SupabaseAuthJson.parseSession(response.body)
            ?: return@withContext AuthActionResult.Failure("Supabase không trả về phiên đăng nhập hợp lệ.")
        storage.save(session)
        _state.value = SupabaseAuthState.SignedIn(session.user)
        AuthActionResult.Success(session.user)
    }

    /** Returns a valid access token for the future backup client; refresh only near expiry. */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val session = storage.load() ?: return@withContext null
        val now = System.currentTimeMillis() / 1_000L
        if (session.expiresAtEpochSeconds > now + REFRESH_EARLY_SECONDS) {
            return@withContext session.accessToken
        }

        val payload = buildJsonObject { put("refresh_token", session.refreshToken) }.toString()
        val response = request("/auth/v1/token?grant_type=refresh_token", payload)
            ?: return@withContext null

        if (response.statusCode !in 200..299) {
            if (response.statusCode == 400 || response.statusCode == 401) clearSession()
            return@withContext null
        }

        val refreshed = SupabaseAuthJson.parseSession(response.body) ?: return@withContext null
        storage.save(refreshed)
        _state.value = SupabaseAuthState.SignedIn(refreshed.user)
        refreshed.accessToken
    }

    /** Logout always clears local credentials, even when the device is offline. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val session = storage.load()
        if (session != null) {
            request("/auth/v1/logout", body = null, accessToken = session.accessToken)
        }
        clearSession()
    }

    private fun request(path: String, body: String?, accessToken: String? = null): AuthHttpResponse? =
        runCatching { transport.execute(path, body, accessToken) }.getOrNull()

    private fun clearSession() {
        storage.clear()
        _state.value = SupabaseAuthState.SignedOut
    }

    companion object {
        private const val REFRESH_EARLY_SECONDS = 60L

        @Volatile private var instance: SupabaseAuthRepository? = null

        fun getInstance(context: Context): SupabaseAuthRepository =
            instance ?: synchronized(this) {
                instance ?: SupabaseAuthRepository(
                    storage = SecureAuthSessionStorage(context.applicationContext),
                    transport = UrlConnectionAuthTransport(),
                ).also { instance = it }
            }
    }
}
