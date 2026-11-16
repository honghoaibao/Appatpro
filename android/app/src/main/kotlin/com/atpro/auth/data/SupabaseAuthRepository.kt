package com.atpro.auth.data

import com.atpro.auth.domain.AuthRepository
import com.atpro.auth.domain.AuthResult
import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthSignUpResult
import com.atpro.auth.domain.AuthUser
import com.atpro.network.supabase.SupabaseHttpClient
import com.atpro.network.supabase.SupabaseHttpRequest
import com.atpro.network.supabase.SupabaseHttpResponse
import com.atpro.sync.SyncSessionGuard
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
 * A single mutex serializes login, signup, refresh, restore, and the local part of logout.
 * Only encrypted sessions are persisted; passwords are used for the request and
 * discarded immediately afterwards.
 */
class SupabaseAuthRepository(
    private val httpClient: SupabaseHttpClient = SupabaseHttpClient(),
    private val sessionStore: AuthSessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val syncSessionGuard: SyncSessionGuard = SyncSessionGuard(),
) : AuthRepository {

    private val sessionMutex = Mutex()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)

    override val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

    override suspend fun restoreSession(): AuthSession? = sessionMutex.withLock {
        val saved = try {
            // Startup restoration is local-only: it must remain fast and work offline.
            withContext(ioDispatcher) { sessionStore.read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            syncSessionGuard.invalidate()
            mutableSession.value = null
            return@withLock null
        }

        if (saved == null) {
            syncSessionGuard.invalidate()
        } else {
            syncSessionGuard.activate(saved.user.id)
        }
        mutableSession.value = saved
        saved
    }

    override suspend fun signUp(
        email: String,
        password: String,
    ): AuthResult<AuthSignUpResult> {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains('@')) {
            return AuthResult.Failure("Nhập email hợp lệ.")
        }
        if (password.isBlank()) {
            return AuthResult.Failure("Nhập mật khẩu.")
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
                val userPayload = sessionPayload.optJSONObject("user")
                    ?: payload.optJSONObject("user")
                    ?: payload
                val responseUser = userPayload.toAuthUserOrNull()
                val hasSessionTokens = sessionPayload.optString("access_token").isNotBlank() &&
                    sessionPayload.optString("refresh_token").isNotBlank()

                val createdSession = if (hasSessionTokens) {
                    when (val parsed = sessionPayload.toAuthSessionResult(responseUser)) {
                        is AuthResult.Success -> parsed.value
                        is AuthResult.Failure -> return@safely parsed
                    }
                } else {
                    null
                }
                val user = createdSession?.user ?: responseUser
                    ?: return@safely AuthResult.Failure(
                        "Máy chủ không trả về thông tin tài khoản.",
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
            return AuthResult.Failure("Nhập email hợp lệ.")
        }
        if (password.isBlank()) {
            return AuthResult.Failure("Nhập mật khẩu.")
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

                val session = when (val parsed = JSONObject(response.body).toAuthSessionResult()) {
                    is AuthResult.Success -> parsed.value
                    is AuthResult.Failure -> return@safely parsed
                }

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
                    ?: return@safely AuthResult.Failure("Không có phiên đăng nhập để làm mới.")

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
            if (response.isInvalidRefreshSession()) {
                clearLocalSession()
            }
            return@safely failure
        }

        val refreshed = when (
            val parsed = JSONObject(response.body).toAuthSessionResult(fallbackUser = current.user)
        ) {
            is AuthResult.Success -> parsed.value
            is AuthResult.Failure -> return@safely parsed
        }

        persistRefreshedSession(refreshed)
        AuthResult.Success(refreshed)
    }

    override suspend fun signOut(): AuthResult<Unit> {
        var localClearFailed = false

        // Invalidate immediately; repeat under sessionMutex to cover an in-flight login/refresh.
        syncSessionGuard.invalidate()

        // Serialize only the local mutation. Once the encrypted session is removed,
        // release the mutex before best-effort remote revocation so a new login is
        // not blocked by a slow/offline server response.
        val current = sessionMutex.withLock {
            syncSessionGuard.invalidate()
            val saved = try {
                withContext(ioDispatcher) { sessionStore.read() } ?: mutableSession.value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSession.value
            }

            try {
                withContext(NonCancellable + ioDispatcher) {
                    sessionStore.clear()
                }
            } catch (_: Exception) {
                localClearFailed = true
            }

            if (!localClearFailed) {
                // Emit signed-out only after persistent storage has actually been cleared.
                mutableSession.value = null
            } else {
                // A failed clear retains the live session under a new generation only.
                saved?.user?.id?.let { syncSessionGuard.activate(it) }
            }
            saved
        }

        if (localClearFailed) {
            return AuthResult.Failure(
                "Không xóa được phiên đăng nhập trên máy.",
                retryable = false,
            )
        }

        // Network revocation is best-effort and outside the mutex. Local logout
        // has already completed and a subsequent sign-in can proceed immediately.
        if (current == null) return AuthResult.Success(Unit)

        return safely {
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

    /**
     * A successful refresh consumes the previous refresh token. Persist the rotated pair
     * non-cancellably; if storage fails, discard the stale local pair instead of retrying
     * a token that the server may already have invalidated.
     */
    private suspend fun persistRefreshedSession(session: AuthSession) {
        var persisted = false
        try {
            withContext(NonCancellable + ioDispatcher) {
                sessionStore.write(session)
                syncSessionGuard.activate(session.user.id)
                mutableSession.value = session
                persisted = true
            }
        } catch (cancelled: CancellationException) {
            // withContext can report cancellation while switching back after its
            // NonCancellable block has already stored the rotated pair.
            if (!persisted) clearAfterFailedRefresh()
            throw cancelled
        } catch (error: Exception) {
            clearAfterFailedRefresh()
            throw error
        }
    }

    private suspend fun clearAfterFailedRefresh() {
        try {
            withContext(NonCancellable + ioDispatcher) {
                sessionStore.clear()
            }
        } catch (_: Exception) {
            // The in-memory token is still discarded; persistent storage failure is surfaced
            // by the refresh result and must not leave this process using a consumed token.
        }
        syncSessionGuard.invalidate()
        mutableSession.value = null
    }

    private suspend fun persistSession(session: AuthSession) {
        withContext(ioDispatcher) {
            sessionStore.write(session)
        }
        syncSessionGuard.activate(session.user.id)
        mutableSession.value = session
    }

    private suspend fun clearLocalSession() {
        // Keep the in-memory state consistent if persistent clearing fails.
        withContext(ioDispatcher) {
            sessionStore.clear()
        }
        syncSessionGuard.invalidate()
        mutableSession.value = null
    }

    private suspend fun <T> safely(
        block: suspend () -> AuthResult<T>,
    ): AuthResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SocketTimeoutException) {
        AuthResult.Failure(
            message = "Kết nối quá thời gian chờ. Kiểm tra mạng.",
            retryable = true,
        )
    } catch (_: IOException) {
        AuthResult.Failure(
            message = "Không kết nối được. Kiểm tra mạng.",
            retryable = true,
        )
    } catch (_: Exception) {
        AuthResult.Failure(
            message = "Đăng nhập không thành công. Thử lại.",
            retryable = false,
        )
    }

    /** Only definitive refresh-session rejection clears the durable local session. */
    private fun SupabaseHttpResponse.isInvalidRefreshSession(): Boolean {
        if (statusCode !in 400..422) return false

        return try {
            val json = JSONObject(body)
            val code = sequenceOf("code", "error_code")
                .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .firstOrNull()
                ?.lowercase()
            val message = sequenceOf("msg", "message", "error_description", "error")
                .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .firstOrNull()
                ?.lowercase()
                .orEmpty()

            (code != null && code in INVALID_REFRESH_ERROR_CODES) ||
                message.contains("invalid refresh token") ||
                message.contains("refresh token not found") ||
                message.contains("refresh token already used")
        } catch (_: Exception) {
            false
        }
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
                "Email chưa xác nhận. Xem hộp thư."
            lower.contains("user already registered") ->
                "Email này đã được đăng ký."
            lower.contains("unable to validate email") ||
                lower.contains("invalid email") ->
                "Địa chỉ email chưa hợp lệ."
            lower.contains("password should be at least") ||
                lower.contains("password is too short") ->
                "Mật khẩu quá ngắn."
            lower.contains("too many requests") ||
                lower.contains("rate limit") ->
                "Thao tác quá nhanh. Đợi một lát rồi thử lại."
            else -> normalized.take(MAX_ERROR_MESSAGE_LENGTH)
                .ifBlank { "Đăng nhập thất bại (HTTP $statusCode)." }
        }

        return AuthResult.Failure(
            message = userMessage,
            statusCode = statusCode,
            retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
        )
    }

    /**
     * The Auth token endpoint documents token fields separately from user data.
     * Fetch the authenticated user when the response does not include one. Refresh
     * can reuse the already-known user because a refresh does not change identity.
     */
    private suspend fun JSONObject.toAuthSessionResult(
        fallbackUser: AuthUser? = null,
    ): AuthResult<AuthSession> {
        val accessToken = optString("access_token")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: return AuthResult.Failure("Supabase không trả về access token hợp lệ.")
        val refreshToken = optString("refresh_token")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: return AuthResult.Failure("Máy chủ không trả về phiên đăng nhập.")

        var user = optJSONObject("user")?.toAuthUserOrNull() ?: fallbackUser
        if (user == null) {
            val userResponse = httpClient.execute(
                SupabaseHttpRequest(
                    method = "GET",
                    path = CURRENT_USER_PATH,
                    accessToken = accessToken,
                ),
            )
            if (!userResponse.isSuccessful) {
                return userResponse.toAuthFailure()
            }
            user = JSONObject(userResponse.body).toAuthUserOrNull()
        }

        val authenticatedUser = user
            ?: return AuthResult.Failure("Không xác minh được tài khoản.")

        val explicitExpiry = optLong("expires_at", 0L).takeIf { it > 0L }
        val expiresIn = optLong("expires_in", 0L)
            .takeIf { it > 0L }
            ?: DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS
        val now = nowEpochSeconds().coerceAtLeast(0L)
        val expiresAt = explicitExpiry ?: if (now > Long.MAX_VALUE - expiresIn) {
            Long.MAX_VALUE
        } else {
            now + expiresIn
        }

        return AuthResult.Success(
            AuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenType = optString("token_type")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: "bearer",
                expiresAtEpochSeconds = expiresAt,
                user = authenticatedUser,
            ),
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
        const val CURRENT_USER_PATH = "/auth/v1/user"
        const val PASSWORD_TOKEN_PATH = "/auth/v1/token?grant_type=password"
        const val REFRESH_TOKEN_PATH = "/auth/v1/token?grant_type=refresh_token"
        const val SIGN_OUT_LOCAL_PATH = "/auth/v1/logout?scope=local"

        const val DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS = 3_600L
        val INVALID_REFRESH_ERROR_CODES = setOf(
            "refresh_token_not_found",
            "refresh_token_already_used",
            "session_not_found",
            "session_expired",
        )
        const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}
