package com.atpro.auth.data

import com.atpro.auth.domain.AuthResult
import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthUser
import com.atpro.network.supabase.SupabaseClientConfig
import com.atpro.network.supabase.SupabaseHttpClient
import com.atpro.network.supabase.SupabaseHttpRequest
import com.atpro.network.supabase.SupabaseHttpResponse
import com.atpro.network.supabase.SupabaseHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupabaseAuthRepositoryTest {

    private class QueueTransport(
        responses: List<SupabaseHttpResponse>,
    ) : SupabaseHttpTransport {
        private val queuedResponses = responses.toMutableList()
        private var responseIndex = 0
        val requests = mutableListOf<SupabaseHttpRequest>()

        override suspend fun execute(
            config: SupabaseClientConfig,
            request: SupabaseHttpRequest,
        ): SupabaseHttpResponse {
            requests += request
            check(responseIndex < queuedResponses.size) {
                "Unexpected Supabase HTTP request: ${request.method} ${request.path}"
            }
            return queuedResponses[responseIndex++]
        }
    }

    private class MemorySessionStore(
        var value: AuthSession? = null,
    ) : AuthSessionStore {
        var readCount = 0
        var writeCount = 0
        var clearCount = 0
        var failWrite = false
        var failClear = false

        override fun read(): AuthSession? {
            readCount++
            return value
        }

        override fun write(session: AuthSession) {
            if (failWrite) error("write failed")
            writeCount++
            value = session
        }

        override fun clear() {
            if (failClear) error("clear failed")
            clearCount++
            value = null
        }
    }

    private fun repository(
        transport: QueueTransport,
        store: MemorySessionStore = MemorySessionStore(),
        now: Long = 100_000L,
    ): SupabaseAuthRepository = SupabaseAuthRepository(
        httpClient = SupabaseHttpClient(
            config = SupabaseClientConfig(
                projectUrl = "https://unit-test.supabase.co",
                publishableKey = "sb_publishable_unit_test_key",
            ),
            transport = transport,
        ),
        sessionStore = store,
        ioDispatcher = Dispatchers.Unconfined,
        nowEpochSeconds = { now },
    )

    private fun response(status: Int, body: String = "") =
        SupabaseHttpResponse(status, body)

    private fun tokenBody(
        access: String = "access-1",
        refresh: String = "refresh-1",
        extra: String = "\"expires_in\":1800,\"token_type\":\"bearer\",",
        includeUser: Boolean = true,
    ): String {
        val user = if (includeUser) ",\"user\":{\"id\":\"$USER_ID\",\"email\":\"tester@example.com\"}" else ""
        return """{"access_token":"$access","refresh_token":"$refresh",$extra"scope":"email"$user}"""
    }

    private fun signedInSession(
        access: String = "access-old",
        refresh: String = "refresh-old",
    ) = AuthSession(
        accessToken = access,
        refreshToken = refresh,
        tokenType = "bearer",
        expiresAtEpochSeconds = 200_000L,
        user = AuthUser(USER_ID, "tester@example.com"),
    )

    @Test
    fun signupWithoutSessionRequiresEmailConfirmationAndDoesNotPersistCredentials() = runBlocking {
        val transport = QueueTransport(
            listOf(response(200, """{"id":"$USER_ID","email":"tester@example.com"}""")),
        )
        val store = MemorySessionStore()
        val result = repository(transport, store).signUp(
            email = "  tester@example.com  ",
            password = "test-password",
        )

        assertTrue(result is AuthResult.Success)
        val signup = (result as AuthResult.Success).value
        assertEquals(USER_ID, signup.user.id)
        assertTrue(signup.emailConfirmationRequired)
        assertNull(store.value)
        assertEquals(0, store.writeCount)
        assertEquals("/auth/v1/signup", transport.requests.single().path)
        val body = JSONObject(transport.requests.single().body!!)
        assertEquals("tester@example.com", body.getString("email"))
        assertEquals("test-password", body.getString("password"))
    }

    @Test
    fun signInFetchesCurrentUserWhenTokenResponseOmitsUser() = runBlocking {
        val transport = QueueTransport(
            listOf(
                response(200, tokenBody(includeUser = false)),
                response(200, """{"id":"$USER_ID","email":"tester@example.com"}"""),
            ),
        )
        val store = MemorySessionStore()
        val repo = repository(transport, store, now = 50_000L)

        val result = repo.signIn("tester@example.com", "test-password")

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).value
        assertEquals(USER_ID, session.user.id)
        assertEquals(51_800L, session.expiresAtEpochSeconds)
        assertEquals("access-1", store.value?.accessToken)
        assertEquals(1, store.writeCount)
        assertEquals("/auth/v1/token?grant_type=password", transport.requests[0].path)
        assertEquals("/auth/v1/user", transport.requests[1].path)
        assertEquals("access-1", transport.requests[1].accessToken)
    }

    @Test
    fun refreshUsesKnownUserAndPersistsRotatedRefreshToken() = runBlocking {
        val transport = QueueTransport(
            listOf(response(200, tokenBody(
                access = "access-new",
                refresh = "refresh-new",
                extra = "",
                includeUser = false,
            ))),
        )
        val store = MemorySessionStore(signedInSession())
        val repo = repository(transport, store, now = 70_000L)

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Success)
        val refreshed = (result as AuthResult.Success).value
        assertEquals("access-new", refreshed.accessToken)
        assertEquals("refresh-new", refreshed.refreshToken)
        assertEquals(USER_ID, refreshed.user.id)
        assertEquals(73_600L, refreshed.expiresAtEpochSeconds)
        assertEquals(1, transport.requests.size)
        assertEquals("/auth/v1/token?grant_type=refresh_token", transport.requests.single().path)
        assertEquals(
            "refresh-old",
            JSONObject(transport.requests.single().body!!).getString("refresh_token"),
        )
        assertEquals("refresh-new", store.value?.refreshToken)
    }

    @Test
    fun malformedOrMissingExpiresInUsesOneHourDefault() = runBlocking {
        val transport = QueueTransport(
            listOf(response(200, tokenBody(
                extra = "\"expires_in\":\"not-a-number\",\"token_type\":\"null\",",
            ))),
        )
        val result = repository(transport, now = 80_000L)
            .signIn("tester@example.com", "test-password")

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).value
        assertEquals(83_600L, session.expiresAtEpochSeconds)
        assertEquals("bearer", session.tokenType)
    }

    @Test
    fun definitiveRefreshTokenRejectionClearsOnlyTheLocalSession() = runBlocking {
        val transport = QueueTransport(
            listOf(response(
                400,
                """{"code":"refresh_token_not_found","message":"Session containing the refresh token not found"}""",
            )),
        )
        val store = MemorySessionStore(signedInSession())
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Failure)
        assertNull(store.value)
        assertNull(repo.session.value)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun unrelatedBadRequestDoesNotDestroySavedRefreshSession() = runBlocking {
        val transport = QueueTransport(
            listOf(response(400, """{"code":"invalid_credentials","message":"Invalid credentials"}""")),
        )
        val original = signedInSession()
        val store = MemorySessionStore(original)
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Failure)
        assertEquals(original, store.value)
        assertEquals(original, repo.session.value)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun unauthorizedApiKeyFailureDoesNotClearTheUserRefreshSession() = runBlocking {
        val transport = QueueTransport(
            listOf(response(401, """{"code":"invalid_api_key","message":"Invalid API key"}""")),
        )
        val original = signedInSession()
        val store = MemorySessionStore(original)
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Failure)
        assertEquals(original, store.value)
        assertEquals(original, repo.session.value)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun transientRefreshFailureKeepsEncryptedSessionForLaterRetry() = runBlocking {
        val transport = QueueTransport(
            listOf(response(503, """{"message":"service unavailable"}""")),
        )
        val original = signedInSession()
        val store = MemorySessionStore(original)
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Failure)
        assertTrue((result as AuthResult.Failure).retryable)
        assertEquals(original, store.value)
        assertEquals(original, repo.session.value)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun refreshPersistenceFailureDiscardsTheConsumedOldRefreshToken() = runBlocking {
        val transport = QueueTransport(
            listOf(response(200, tokenBody(access = "access-rotated", refresh = "refresh-rotated"))),
        )
        val store = MemorySessionStore(signedInSession()).apply { failWrite = true }
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.refreshSession()

        assertTrue(result is AuthResult.Failure)
        assertNull(store.value)
        assertNull(repo.session.value)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun localRestoreDoesNotCallNetwork() = runBlocking {
        val transport = QueueTransport(emptyList())
        val saved = signedInSession()
        val repo = repository(transport, MemorySessionStore(saved))

        assertEquals(saved, repo.restoreSession())
        assertEquals(saved, repo.session.value)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun signOutClearsLocalStateAndUsesOnlyLocalServerScope() = runBlocking {
        val transport = QueueTransport(
            listOf(response(503, """{"message":"service unavailable"}""")),
        )
        val store = MemorySessionStore(signedInSession())
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.signOut()

        assertTrue(result is AuthResult.Failure)
        assertNull(store.value)
        assertNull(repo.session.value)
        assertEquals(1, store.clearCount)
        assertEquals("/auth/v1/logout?scope=local", transport.requests.single().path)
        assertEquals("access-old", transport.requests.single().accessToken)
    }

    @Test
    fun localClearFailureDoesNotEmitSignedOutStateOrCallServer() = runBlocking {
        val transport = QueueTransport(emptyList())
        val original = signedInSession()
        val store = MemorySessionStore(original).apply { failClear = true }
        val repo = repository(transport, store)
        repo.restoreSession()

        val result = repo.signOut()

        assertTrue(result is AuthResult.Failure)
        assertEquals(original, repo.session.value)
        assertEquals(original, store.value)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun concurrentRefreshCallsAreSerializedAndUseTheRotatedToken() = runBlocking {
        val transport = QueueTransport(
            listOf(
                response(200, tokenBody(access = "access-2", refresh = "refresh-2", extra = "", includeUser = false)),
                response(200, tokenBody(access = "access-3", refresh = "refresh-3", extra = "", includeUser = false)),
            ),
        )
        val store = MemorySessionStore(signedInSession())
        val repo = repository(transport, store)
        repo.restoreSession()

        val results = listOf(
            async(Dispatchers.Unconfined) { repo.refreshSession() },
            async(Dispatchers.Unconfined) { repo.refreshSession() },
        ).awaitAll()

        assertTrue(results.all { it is AuthResult.Success })
        val submittedRefreshTokens = transport.requests.map {
            JSONObject(it.body!!).getString("refresh_token")
        }
        assertEquals(listOf("refresh-old", "refresh-2"), submittedRefreshTokens)
        assertEquals("refresh-3", store.value?.refreshToken)
    }

    private companion object {
        const val USER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
