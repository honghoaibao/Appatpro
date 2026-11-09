package com.atpro.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseAuthJsonTest {

    @Test
    fun parsesPasswordGrantSession() {
        val payload = """
            {
              "access_token": "access-value",
              "refresh_token": "refresh-value",
              "expires_in": 3600,
              "user": {
                "id": "e52e3e0c-61a2-4a4e-8f0e-2c1053ba9d51",
                "email": "user@example.com"
              }
            }
        """.trimIndent()

        val session = SupabaseAuthJson.parseSession(payload, nowEpochSeconds = 1_000L)

        assertEquals("access-value", session?.accessToken)
        assertEquals("refresh-value", session?.refreshToken)
        assertEquals(4_600L, session?.expiresAtEpochSeconds)
        assertEquals("user@example.com", session?.user?.email)
    }

    @Test
    fun parsesSignupUserWithoutSession() {
        val payload = """
            {
              "id": "e52e3e0c-61a2-4a4e-8f0e-2c1053ba9d51",
              "email": "user@example.com"
            }
        """.trimIndent()

        val user = SupabaseAuthJson.parseUser(payload)

        assertEquals("e52e3e0c-61a2-4a4e-8f0e-2c1053ba9d51", user?.id)
        assertEquals("user@example.com", user?.email)
        assertNull(SupabaseAuthJson.parseSession(payload, nowEpochSeconds = 1_000L))
    }

    @Test
    fun rejectsIncompleteSession() {
        val payload = """{"access_token":"only-access-token","user":{"id":"user-id"}}"""
        assertNull(SupabaseAuthJson.parseSession(payload, nowEpochSeconds = 1_000L))
    }

    @Test
    fun mapsInvalidCredentialsToLocalizedMessage() {
        val message = SupabaseAuthJson.errorMessage(
            """{"code":400,"msg":"Invalid login credentials"}""",
            400,
        )
        assertEquals("Email hoặc mật khẩu không chính xác.", message)
    }
}
