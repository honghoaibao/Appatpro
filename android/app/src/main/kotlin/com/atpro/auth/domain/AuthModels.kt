package com.atpro.auth.domain

data class AuthUser(
    val id: String,
    val email: String?,
)

/** Result of registration; a session may be withheld until email confirmation. */
data class AuthSignUpResult(
    val user: AuthUser,
    val emailConfirmationRequired: Boolean,
)

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    /** Absolute Unix time in seconds, not a relative expires_in duration. */
    val expiresAtEpochSeconds: Long,
    val user: AuthUser,
)

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>

    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val retryable: Boolean = false,
    ) : AuthResult<Nothing>
}
