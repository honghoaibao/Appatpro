package com.atpro.auth.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain boundary for the AT PRO account.
 *
 * The implementation is added in the next auth round. This interface deliberately
 * keeps Supabase and Android storage details out of UI and application code.
 */
interface AuthRepository {
    val session: StateFlow<AuthSession?>

    /** Load a previously saved session. Returns null when no valid session exists. */
    suspend fun restoreSession(): AuthSession?

    suspend fun signUp(email: String, password: String): AuthResult<AuthUser>

    suspend fun signIn(email: String, password: String): AuthResult<AuthSession>

    suspend fun refreshSession(): AuthResult<AuthSession>

    suspend fun signOut(): AuthResult<Unit>
}
