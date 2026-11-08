package com.atpro.tasks.f4f

import com.atpro.tasks.TaskResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class F4fAuthManagerTest {

    private class MemoryStorage(
        override var token: String? = null,
        override var username: String? = null,
        override var balance: String? = null,
        override var balancePending: String? = null,
    ) : F4fSessionStorage {
        override fun clear() { token = null; username = null; balance = null; balancePending = null }
    }

    private val loginOk = envelope(
        """{"user":{"id":1,"username":"hoaibao","balance":"1250 coin","balance_pending":"30 coin","access_token":"T-user"},
            "access_token":"T-abc","type_token":"Bearer"}""",
        message = "Đăng nhập tài khoản thành công.",
    )

    private fun manager(storage: MemoryStorage, transport: FakeF4fTransport) =
        F4fAuthManager(storage, F4fApiClient(transport, tokenProvider = { storage.token }, nowSecs = { 1_793_087_078L }))

    @Test
    fun loginStoresTokenAndProfileButNeverThePassword() = runTest {
        val storage = MemoryStorage()
        val t = FakeF4fTransport { FakeReply(200, loginOk) }
        val m = manager(storage, t)
        assertTrue(m.login("  hoaibao ", "s3cret") is TaskResult.Success)

        assertEquals("T-abc", storage.token)                 // ưu tiên data.access_token
        assertEquals("hoaibao", storage.username)
        assertEquals("1250 coin", storage.balance)
        val st = m.state.value as F4fAuthState.LoggedIn
        assertEquals("hoaibao", st.username); assertEquals("30 coin", st.balancePending)

        val req = t.seen.single()
        assertEquals("/user/auth/login", req.path)
        assertEquals(setOf("username", "password"), req.body!!.keys)   // KHÔNG có recaptcha
        assertEquals("hoaibao", req.body!!["username"].asStr())         // đã trim
        assertFalse(storage.toString().contains("s3cret"))
    }

    @Test
    fun loginSendsNoBearerEvenWithStaleToken() = runTest {
        val storage = MemoryStorage(token = "OLD-EXPIRED")
        val t = FakeF4fTransport { FakeReply(200, loginOk) }
        manager(storage, t).login("hoaibao", "pw")
        assertNull(t.seen.single().headers["Authorization"])
    }

    @Test
    fun loginFallsBackToUserAccessToken() = runTest {
        val storage = MemoryStorage()
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"user":{"username":"u","access_token":"T-user"}}""")) }
        assertTrue(manager(storage, t).login("u", "p") is TaskResult.Success)
        assertEquals("T-user", storage.token)
    }

    @Test
    fun wrongCredentialsShowServerMessageAndStayLoggedOut() = runTest {
        val storage = MemoryStorage()
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 400, message = "Tên đăng nhập hoặc mật khẩu không đúng")) }
        val m = manager(storage, t)
        val r = m.login("u", "bad") as TaskResult.Error
        assertEquals("Tên đăng nhập hoặc mật khẩu không đúng", r.message)
        assertNull(storage.token)
        assertTrue(m.state.value is F4fAuthState.LoggedOut)
    }

    @Test
    fun emptyInputIsRejectedWithoutNetwork() = runTest {
        val t = FakeF4fTransport { FakeReply(200, loginOk) }
        val m = manager(MemoryStorage(), t)
        assertTrue(m.login("", "x") is TaskResult.Error)
        assertTrue(m.login("x", "") is TaskResult.Error)
        assertTrue(t.seen.isEmpty())
    }

    @Test
    fun successWithoutTokenIsAnError() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"user":{"username":"u"}}""")) }
        assertTrue(manager(MemoryStorage(), t).login("u", "p") is TaskResult.Error)
    }

    @Test
    fun restoresLoggedInStateFromStorage() {
        val storage = MemoryStorage(token = "T", username = "hoaibao", balance = "10 coin")
        val m = manager(storage, FakeF4fTransport { null })
        assertTrue(m.isLoggedIn)
        assertEquals("10 coin", (m.state.value as F4fAuthState.LoggedIn).balance)
    }

    @Test
    fun logoutClearsLocalSessionEvenIfServerCallFails() = runTest {
        val storage = MemoryStorage(token = "T", username = "u")
        val m = manager(storage, FakeF4fTransport { null })
        m.logout()
        assertNull(storage.token)
        assertTrue(m.state.value is F4fAuthState.LoggedOut)
    }

    @Test
    fun refreshWith401LogsOut() = runTest {
        val storage = MemoryStorage(token = "T", username = "u")
        val t = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Chức năng này cần đăng nhập")) }
        val m = manager(storage, t)
        val r = m.refresh() as TaskResult.Error
        assertTrue(r.isAuthError)
        assertNull(storage.token)
        assertTrue(m.state.value is F4fAuthState.LoggedOut)
    }

    @Test
    fun refreshUpdatesBalance() = runTest {
        val storage = MemoryStorage(token = "T", username = "u")
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"username":"u","balance":"999 coin","balance_pending":"0 coin"}""")) }
        val m = manager(storage, t)
        assertTrue(m.refresh() is TaskResult.Success)
        assertEquals("999 coin", (m.state.value as F4fAuthState.LoggedIn).balance)
        assertEquals("/user/auth/me", t.seen.single().path)
    }

    @Test
    fun balanceUpdatesFromProviderAreFormatted() {
        val storage = MemoryStorage(token = "T", username = "u", balance = "0 coin")
        val m = manager(storage, FakeF4fTransport { null })
        m.updateBalance("1250", "230")
        val st = m.state.value as F4fAuthState.LoggedIn
        assertEquals("1250 coin", st.balance); assertEquals("230 coin", st.balancePending)
        assertEquals("1250 coin", storage.balance)
        m.updateBalance(null, null)                           // thiếu số liệu → giữ giá trị cũ
        assertEquals("1250 coin", (m.state.value as F4fAuthState.LoggedIn).balance)
    }

    @Test
    fun balanceUpdateIgnoredWhenLoggedOut() {
        val m = manager(MemoryStorage(), FakeF4fTransport { null })
        m.updateBalance("5", "5")
        assertTrue(m.state.value is F4fAuthState.LoggedOut)
    }
}
