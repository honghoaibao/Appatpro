package com.atpro.cloud.auth

/** UI-facing states kept independent from Dashboard and automation state. */
sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
    data object Loading : AuthState
    data class Error(val message: String) : AuthState
}
