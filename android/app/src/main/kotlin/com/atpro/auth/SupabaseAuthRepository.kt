package com.atpro.auth

import android.content.Context
import com.atpro.backup.BackupStateStore
import com.atpro.backup.BackupWorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SupabaseUser(val id: String, val email: String)

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val user: SupabaseUser,
)

sealed class AuthOperationResult {
    data class Success(val user: SupabaseUser) : AuthOperationResult()
    data class Error(val message: String) : AuthOperationResult()
    object EmailConfirmationRequired : AuthOperationResult()
}

/** Supabase email/password Auth REST endpoints. Passwords are never stored. */
class SupabaseAuthRepository(
    private val context: Context,
    private val http: SupabaseHttpClient,
    private val store: EncryptedSessionStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _session = MutableStateFlow(readStoredSession())
    val session: StateFlow<SupabaseSession?> = _session.asStateFlow()

    fun currentSession(): SupabaseSession? = _session.value

    suspend fun signUp(email: String, password: String): AuthOperationResult {
        val normalizedEmail = email.trim().lowercase()
        if (!isValidEmail(normalizedEmail)) return AuthOperationResult.Error("Email chưa hợp lệ")
        if (password.length < 8) return AuthOperationResult.Error("Mật khẩu cần ít nhất 8 ký tự")
        return executeAuth(
            "auth/v1/signup",
            buildJsonObject {
                put("email", normalizedEmail)
                put("password", password)
            }.toString(),
            confirmationAllowed = true,
        )
    }

    suspend fun signIn(email: String, password: String): AuthOperationResult {
        val normalizedEmail = email.trim().lowercase()
        if (!isValidEmail(normalizedEmail) || password.isBlank()) {
            return AuthOperationResult.Error("Nhập email và mật khẩu hợp lệ")
        }
        return executeAuth(
            "auth/v1/token?grant_type=password",
            buildJsonObject {
                put("email", normalizedEmail)
                put("password", password)
            }.toString(),
            confirmationAllowed = false,
        )
    }

    suspend fun refreshSession(force: Boolean = false): SupabaseSession? {
        val current = _session.value ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (!force && current.expiresAtEpochSeconds - now > REFRESH_EARLY_SECONDS) return current

        val response = runCatching {
            http.request(
                method = "POST",
                path = "auth/v1/token?grant_type=refresh_token",
                body = buildJsonObject { put("refresh_token", current.refreshToken) }.toString(),
            )
        }.getOrNull() ?: return current.takeIf { it.expiresAtEpochSeconds > now }

        if (!response.isSuccessful) {
            if (response.statusCode in 400..403) clearLocalSession()
            return _session.value?.takeIf { it.expiresAtEpochSeconds > now }
        }
        return runCatching { parseAndStoreSession(response.body) }.getOrNull()
    }

    suspend fun signOut() {
        val current = _session.value
        try {
            if (current != null && SupabaseConfig.isConfigured) {
                http.request("POST", "auth/v1/logout", accessToken = current.accessToken)
            }
        } catch (_: Exception) {
            // Local sign-out remains available offline.
        } finally {
            clearLocalSession()
        }
    }

    private suspend fun executeAuth(
        path: String,
        body: String,
        confirmationAllowed: Boolean,
    ): AuthOperationResult {
        if (!SupabaseConfig.isConfigured) {
            return AuthOperationResult.Error("Supabase chưa được cấu hình. Hãy dùng chế độ ngoại tuyến.")
        }
        val response = try {
            http.request(method = "POST", path = path, body = body)
        } catch (_: Exception) {
            return AuthOperationResult.Error("Không thể kết nối máy chủ. Kiểm tra Internet rồi thử lại.")
        }
        if (!response.isSuccessful) return AuthOperationResult.Error(parseError(response.body))

        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return AuthOperationResult.Error("Phản hồi đăng nhập không hợp lệ")
        val accessToken = root["access_token"]?.jsonPrimitive?.content
        val refreshToken = root["refresh_token"]?.jsonPrimitive?.content
        val userObject = root["user"]?.jsonObject
        val userId = userObject?.get("id")?.jsonPrimitive?.content
        val email = userObject?.get("email")?.jsonPrimitive?.content.orEmpty()

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || userId.isNullOrBlank()) {
            return if (confirmationAllowed) AuthOperationResult.EmailConfirmationRequired
            else AuthOperationResult.Error("Chưa có phiên đăng nhập. Hãy xác nhận email nếu được yêu cầu.")
        }

        val expiresIn = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: DEFAULT_EXPIRES_SECONDS
        val newSession = SupabaseSession(
            accessToken,
            refreshToken,
            System.currentTimeMillis() / 1000L + expiresIn,
            SupabaseUser(userId, email),
        )
        persist(newSession)
        BackupStateStore.setOfflineMode(context, false)
        BackupWorkScheduler.enqueue(context)
        return AuthOperationResult.Success(newSession.user)
    }

    private fun parseAndStoreSession(body: String): SupabaseSession {
        val root = json.parseToJsonElement(body).jsonObject
        val userObject = root["user"]?.jsonObject ?: error("Missing user")
        val user = SupabaseUser(
            userObject["id"]?.jsonPrimitive?.content ?: error("Missing user id"),
            userObject["email"]?.jsonPrimitive?.content.orEmpty(),
        )
        val access = root["access_token"]?.jsonPrimitive?.content ?: error("Missing access token")
        val refresh = root["refresh_token"]?.jsonPrimitive?.content ?: error("Missing refresh token")
        val expiresAt = root["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000L +
                (root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: DEFAULT_EXPIRES_SECONDS))
        return SupabaseSession(access, refresh, expiresAt, user).also(::persist)
    }

    private fun readStoredSession(): SupabaseSession? {
        val stored = store.read() ?: return null
        return runCatching {
            val root = json.parseToJsonElement(stored).jsonObject
            SupabaseSession(
                accessToken = root["access_token"]!!.jsonPrimitive.content,
                refreshToken = root["refresh_token"]!!.jsonPrimitive.content,
                expiresAtEpochSeconds = root["expires_at"]!!.jsonPrimitive.content.toLong(),
                user = SupabaseUser(
                    root["user_id"]!!.jsonPrimitive.content,
                    root["email"]?.jsonPrimitive?.content.orEmpty(),
                ),
            )
        }.getOrElse {
            store.clear()
            null
        }
    }

    private fun persist(session: SupabaseSession) {
        store.write(
            buildJsonObject {
                put("access_token", session.accessToken)
                put("refresh_token", session.refreshToken)
                put("expires_at", session.expiresAtEpochSeconds)
                put("user_id", session.user.id)
                put("email", session.user.email)
            }.toString()
        )
        _session.value = session
    }

    private fun clearLocalSession() {
        store.clear()
        _session.value = null
    }

    private fun parseError(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.get("msg")?.jsonPrimitive?.content
            ?: root?.get("message")?.jsonPrimitive?.content
            ?: root?.get("error_description")?.jsonPrimitive?.content
            ?: root?.get("error")?.jsonPrimitive?.content
        return message?.take(180) ?: "Đăng nhập thất bại. Kiểm tra thông tin rồi thử lại."
    }

    private fun isValidEmail(value: String): Boolean =
        value.length <= 254 && EMAIL_REGEX.matches(value)

    companion object {
        private const val REFRESH_EARLY_SECONDS = 90L
        private const val DEFAULT_EXPIRES_SECONDS = 3600L
        private val EMAIL_REGEX =
            Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}

object SupabaseServices {
    @Volatile private var authRepository: SupabaseAuthRepository? = null

    fun auth(context: Context): SupabaseAuthRepository =
        authRepository ?: synchronized(this) {
            authRepository ?: SupabaseAuthRepository(
                context.applicationContext,
                SupabaseHttpClient(SupabaseConfig.PROJECT_URL, SupabaseConfig.PUBLISHABLE_KEY),
                EncryptedSessionStore(context.applicationContext),
            ).also { authRepository = it }
        }
}
