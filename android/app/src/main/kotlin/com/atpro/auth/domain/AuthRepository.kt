package com.atpro.auth.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain boundary for the AT PRO account.
 *
 * The implementation uses Supabase Auth, while callers depend only on these
 * domain models and do not need to know about HTTP or Android storage details.
 */
interface AuthRepository {
    val session: StateFlow<AuthSession?>

    /** Restore the encrypted local session and refresh it when it is near expiry. */
    suspend fun restoreSession(): AuthSession?

    /** A successful signup may still require the user to confirm their email. */
    suspend fun signUp(email: String, password: String): AuthResult<AuthSignUpResult>

    suspend fun signIn(email: String, password: String): AuthResult<AuthSession>

    suspend fun refreshSession(): AuthResult<AuthSession>

    suspend fun signOut(): AuthResult<Unit>
}
