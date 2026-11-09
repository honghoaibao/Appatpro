package com.atpro.auth.data

import com.atpro.auth.domain.AuthSession

/** Local persistence boundary for Supabase sessions. Passwords must never be stored. */
interface AuthSessionStore {
    fun read(): AuthSession?

    fun write(session: AuthSession)

    fun clear()
}
