package com.atpro.auth.data

import com.atpro.auth.domain.AuthRepository
import com.atpro.auth.domain.AuthResult
import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthSignUpResult
import com.atpro.auth.domain.AuthUser
import com.atpro.network.supabase.SupabaseHttpClient
import com.atpro.network.supabase.SupabaseHttpRequest
import com.atpro.network.supabase.SupabaseHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Supabase Auth implementation for the AT PRO account.
 *
 * Refresh-token rotation means a refresh token should not be exchanged concurrently.
 * A single mutex serializes login, signup, refresh, restore, and logout operations.
 * Only encrypted sessions are persisted; passwords are used for the request and
 * discarded immediately afterwards.
 */
class SupabaseAuthRepository(
    private val httpClient: SupabaseHttpClient = SupabaseHttpClient(),
    private val sessionStore: AuthSessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AuthRepository {

    private val sessionMutex = Mutex()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)

    override val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

    override suspend fun restoreSession(): AuthSession? = sessionMutex.withLock {
        val saved = try {
            withContext(ioDispatcher) { sessionStore.read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableSession.value = null
            return@withLock null
        }

        mutableSession.value = saved ?: return@withLock null

        // Refresh slightly before expiry so an API request does not race the expiry edge.
        if (saved.expiresAtEpochSeconds > nowEpochSeconds() + REFRESH_EARLY_SECONDS) {
            return@withLock saved
        }

        when (val result = refreshSessionLocked(saved)) {
            is AuthResult.Success -> result.value
            is AuthResult.Failure -> {
                // A transient network/rate-limit failure must not destroy a usable
                // refresh token. Keep the saved session so the caller can retry later.
                if (result.statusCode == INVALID_SESSION_STATUS ||
                    result.statusCode == UNAUTHORIZED_STATUS
                ) {
                    null
                } else {
                    saved
                }
            }
        }
    }

    override suspend fun signUp(
        email: String,
        password: String,
    ): AuthResult<AuthSignUpResult> {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains('@')) {
            return AuthResult.Failure("Vui lòng nhập địa chỉ email hợp lệ.")
        }
        if (password.isBlank()) {
            return AuthResult.Failure("Vui lòng nhập mật khẩu.")
        }

        return sessionMutex.withLock {
            safely {
                val body = JSONObject()
                    .put("email", normalizedEmail)
                    .put("password", password)

                val response = httpClient.execute(
                    SupabaseHttpRequest(
                        method = "POST",
                        path = SIGN_UP_PATH,
                        body = body.toString(),
                    ),
                )

                if (!response.isSuccessful) {
                    return@safely response.toAuthFailure()
                }

                val payload = JSONObject(response.body)
                val sessionPayload = payload.optJSONObject("session") ?: payload
                val createdSession = sessionPayload.toAuthSessionOrNull()
                val userPayload = sessionPayload.optJSONObject("user")
                    ?: payload.optJSONObject("user")
                    ?: payload
                val user = createdSession?.user ?: userPayload.toAuthUserOrNull()
                    ?: return@safely AuthResult.Failure(
                        "Supabase không trả về thông tin tài khoản hợp lệ.",
                        statusCode = response.statusCode,
                    )

                if (createdSession != null) {
                    persistSession(createdSession)
                }

                AuthResult.Success(
                    AuthSignUpResult(
                        user = user,
                        emailConfirmationRequired = createdSession == null,
                    ),
                )
            }
        }
    }

    override suspend fun signIn(
        email: String,
        password: String,
    ): AuthResult<AuthSession> {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains('@')) {
            return AuthResult.Failure("Vui lòng nhập địa chỉ email hợp lệ.")
        }
        if (password.isBlank()) {
            return AuthResult.Failure("Vui lòng nhập mật khẩu.")
        }

        return sessionMutex.withLock {
            safely {
                val body = JSONObject()
                    .put("email", normalizedEmail)
                    .put("password", password)

                val response = httpClient.execute(
                    SupabaseHttpRequest(
                        method = "POST",
                        path = PASSWORD_TOKEN_PATH,
                        body = body.toString(),
                    ),
                )

                if (!response.isSuccessful) {
                    return@safely response.toAuthFailure()
                }

                val session = JSONObject(response.body).toAuthSessionOrNull()
                    ?: return@safely AuthResult.Failure(
                        "Supabase đăng nhập thành công nhưng không trả về phiên hợp lệ.",
                        statusCode = response.statusCode,
                    )

                persistSession(session)
                AuthResult.Success(session)
            }
        }
    }

    override suspend fun refreshSession(): AuthResult<AuthSession> =
        sessionMutex.withLock {
            safely {
                val saved = withContext(ioDispatcher) { sessionStore.read() }
                val current = saved ?: mutableSession.value
                    ?: return@safely AuthResult.Failure("Không tìm thấy phiên đăng nhập cần làm mới.")

                mutableSession.value = current
                refreshSessionLocked(current)
            }
        }

    /**
     * Must only be called while [sessionMutex] is held.
     */
    private suspend fun refreshSessionLocked(
        current: AuthSession,
    ): AuthResult<AuthSession> = safely {
        val body = JSONObject()
            .put("refresh_token", current.refreshToken)

        val response = httpClient.execute(
            SupabaseHttpRequest(
                method = "POST",
                path = REFRESH_TOKEN_PATH,
                body = body.toString(),
            ),
        )

        if (!response.isSuccessful) {
            val failure = response.toAuthFailure()
            if (response.statusCode == INVALID_SESSION_STATUS ||
                response.statusCode == UNAUTHORIZED_STATUS
            ) {
                clearLocalSession()
            }
            return@safely failure
        }

        val refreshed = JSONObject(response.body).toAuthSessionOrNull()
            ?: return@safely AuthResult.Failure(
                "Supabase không trả về phiên hợp lệ sau khi làm mới.",
                statusCode = response.statusCode,
            )

        persistSession(refreshed)
        AuthResult.Success(refreshed)
    }

    override suspend fun signOut(): AuthResult<Unit> = sessionMutex.withLock {
        val current = try {
            withContext(ioDispatcher) { sessionStore.read() } ?: mutableSession.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableSession.value
        }

        var remoteResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var localClearFailed = false

        try {
            if (current != null) {
                remoteResult = safely {
                    val response = httpClient.execute(
                        SupabaseHttpRequest(
                            method = "POST",
                            // Revoke only this device's session; do not sign out other devices.
                            path = SIGN_OUT_LOCAL_PATH,
                            accessToken = current.accessToken,
                        ),
                    )

                    if (response.isSuccessful) {
                        AuthResult.Success(Unit)
                    } else {
                        response.toAuthFailure()
                    }
                }
            }
        } finally {
            // Always remove the local session, even when the device is offline.
            mutableSession.value = null
            try {
                withContext(NonCancellable + ioDispatcher) {
                    sessionStore.clear()
                }
            } catch (_: Exception) {
                localClearFailed = true
            }
        }

        if (localClearFailed) {
            AuthResult.Failure(
                "Không thể xóa phiên đăng nhập đã lưu trên thiết bị.",
                retryable = false,
            )
        } else {
            remoteResult
        }
    }

    private suspend fun persistSession(session: AuthSession) {
        withContext(ioDispatcher) {
            sessionStore.write(session)
        }
        mutableSession.value = session
    }

    private suspend fun clearLocalSession() {
        mutableSession.value = null
        withContext(ioDispatcher) {
            sessionStore.clear()
        }
    }

    private suspend fun <T> safely(
        block: suspend () -> AuthResult<T>,
    ): AuthResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        AuthResult.Failure(
            message = "Kết nối Supabase quá thời gian chờ. Anh kiểm tra mạng rồi thử lại nhé.",
            retryable = true,
        )
    } catch (_: IOException) {
        AuthResult.Failure(
            message = "Không thể kết nối Supabase. Kiểm tra kết nối mạng rồi thử lại.",
            retryable = true,
        )
    } catch (_: Exception) {
        AuthResult.Failure(
            message = "Không thể hoàn tất thao tác xác thực. Vui lòng thử lại.",
            retryable = false,
        )
    }

    private fun SupabaseHttpResponse.toAuthFailure(): AuthResult.Failure {
        val serverMessage = try {
            val json = JSONObject(body)
            sequenceOf("msg", "message", "error_description", "error")
                .mapNotNull { key ->
                    json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }

        val normalized = serverMessage.orEmpty()
        val lower = normalized.lowercase()
        val userMessage = when {
            lower.contains("invalid login credentials") ->
                "Email hoặc mật khẩu chưa chính xác."
            lower.contains("email not confirmed") ->
                "Email này chưa được xác nhận. Hãy kiểm tra hộp thư của anh."
            lower.contains("user already registered") ->
                "Email này đã được đăng ký."
            lower.contains("unable to validate email") ||
                lower.contains("invalid email") ->
                "Địa chỉ email chưa hợp lệ."
            lower.contains("password should be at least") ||
                lower.contains("password is too short") ->
                "Mật khẩu chưa đạt độ dài tối thiểu của hệ thống."
            lower.contains("too many requests") ||
                lower.contains("rate limit") ->
                "Anh thao tác hơi nhanh. Vui lòng đợi một chút rồi thử lại."
            else -> normalized.take(MAX_ERROR_MESSAGE_LENGTH)
                .ifBlank { "Yêu cầu xác thực thất bại (HTTP $statusCode)." }
        }

        return AuthResult.Failure(
            message = userMessage,
            statusCode = statusCode,
            retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
        )
    }

    private fun JSONObject.toAuthSessionOrNull(): AuthSession? {
        val accessToken = optString("access_token")
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val refreshToken = optString("refresh_token")
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val userJson = optJSONObject("user") ?: return null
        val user = userJson.toAuthUserOrNull() ?: return null

        val explicitExpiry = optLong("expires_at", 0L)
        val expiresIn = optLong("expires_in", DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS)
            .coerceAtLeast(1L)
        val expiresAt = if (explicitExpiry > 0L) {
            explicitExpiry
        } else {
            nowEpochSeconds() + expiresIn
        }

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = optString("token_type", "bearer").ifBlank { "bearer" },
            expiresAtEpochSeconds = expiresAt,
            user = user,
        )
    }

    private fun JSONObject.toAuthUserOrNull(): AuthUser? {
        val id = optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val email = if (has("email") && !isNull("email")) {
            optString("email").takeIf { it.isNotBlank() && it != "null" }
        } else {
            null
        }
        return AuthUser(id = id, email = email)
    }

    private companion object {
        const val SIGN_UP_PATH = "/auth/v1/signup"
        const val PASSWORD_TOKEN_PATH = "/auth/v1/token?grant_type=password"
        const val REFRESH_TOKEN_PATH = "/auth/v1/token?grant_type=refresh_token"
        const val SIGN_OUT_LOCAL_PATH = "/auth/v1/logout?scope=local"

        const val REFRESH_EARLY_SECONDS = 60L
        const val DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS = 3_600L
        const val INVALID_SESSION_STATUS = 400
        const val UNAUTHORIZED_STATUS = 401
        const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}
