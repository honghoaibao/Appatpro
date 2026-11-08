package com.atpro.tasks.f4f

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class F4fApiClientTest {

    private fun client(
        transport: F4fTransport,
        token: String? = "tok-123",
        now: Long = 1_793_087_078L,
    ) = F4fApiClient(transport, tokenProvider = { token }, nowSecs = { now })

    @Test
    fun attachesAllRequiredHeaders() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("{}")) }
        client(t).get("/user/balance")
        val h = t.seen.single().headers
        assertEquals("Bearer tok-123", h["Authorization"])
        assertEquals("1793087078", h["x-ts"])
        assertEquals(F4fSigner.sign("GET", "/user/balance", 1793087078L).sign, h["x-sign"])
        assertEquals("https://follow4follow.net", h["Origin"])
        assertEquals("application/json", h["Accept"])
        assertTrue(h["User-Agent"]!!.contains("Chrome/"))
    }

    @Test
    fun postSendsJsonContentType_getDoesNot() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("{}")) }
        val c = client(t)
        c.get("/user/balance")
        c.post("/user/auth/logout")
        assertNull(t.seen[0].headers["Content-Type"])
        assertEquals("application/json", t.seen[1].headers["Content-Type"])
    }

    @Test
    fun noBearerWhenNotAuthenticatedOrNoToken() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("{}")) }
        client(t).post("/user/auth/login", authenticated = false)
        client(t, token = null).get("/user/balance")
        assertNull(t.seen[0].headers["Authorization"])
        assertNull(t.seen[1].headers["Authorization"])
    }

    @Test
    fun httpOkButStatusCode400IsAnError() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 400, message = "Tài khoản @x chưa làm job này")) }
        val r = client(t).post("/user/earn-coin/complete-job")
        assertTrue(r.isError)
        assertEquals(400, r.apiCode)
        assertEquals("Tài khoản @x chưa làm job này", r.describe())
    }

    @Test
    fun successFlagOverridesErrorCode() = runTest {
        val t = FakeF4fTransport { FakeReply(200, """{"status_code":400,"success":true,"message":"ok"}""") }
        assertFalse(client(t).post("/user/earn-coin/skip-job").isError)
    }

    @Test
    fun validationErrorsAreFlattenedWhenMessageEmpty() = runTest {
        val t = FakeF4fTransport {
            FakeReply(422, envelope(code = 422, errors = """{"username":["Tên đăng nhập không đúng"]}"""))
        }
        val r = client(t).post("/user/auth/login", authenticated = false)
        assertTrue(r.isError)
        assertEquals("username: Tên đăng nhập không đúng", r.describe())
    }

    @Test
    fun nonJsonBodyIsMalformed() = runTest {
        val t = FakeF4fTransport { FakeReply(502, "<html>Bad Gateway</html>") }
        val r = client(t).get("/user/balance")
        assertTrue(r.malformed)
        assertTrue(r.isError)
    }

    @Test
    fun networkFailureNeverThrows() = runTest {
        val t = FakeF4fTransport { null }
        val r = client(t).get("/user/balance")
        assertTrue(r.networkError)
        assertTrue(r.describe().startsWith("Lỗi mạng"))
    }

    @Test
    fun detects401AndRateLimit() = runTest {
        val t1 = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Chức năng này cần đăng nhập")) }
        assertTrue(client(t1).get("/user/balance").isUnauthorized)

        val t2 = FakeF4fTransport {
            FakeReply(429, envelope("""{"retry_after":7}""", code = 429, message = "Bạn thao tác hơi nhanh"))
        }
        val r = client(t2).post("/user/earn-coin/complete-job")
        assertTrue(r.isRateLimited)
        assertEquals(7, r.retryAfterSecs)
    }

    @Test
    fun clockSkewIsLearnedFromDateHeaderAndRetriedOnce() = runTest {
        val serverNow = 1_793_087_078L
        val deviceNow = serverNow - 600            // máy chậm 10 phút
        var calls = 0
        val t = FakeF4fTransport { req ->
            calls++
            val ts = req.headers["x-ts"]!!.toLong()
            if (kotlin.math.abs(ts - serverNow) > 60) FakeReply(403, envelope(code = 403, message = "Chữ ký không hợp lệ"), serverNow)
            else FakeReply(200, envelope("""{"ok":true}"""), serverNow)
        }
        val c = client(t, now = deviceNow)
        val r = c.get("/user/balance")
        assertEquals(2, calls)
        assertFalse(r.isError)
        // Request sau đó dùng luôn độ lệch đã học → thành công ngay, không cần thử lại.
        assertFalse(c.get("/user/balance").isError)
        assertEquals(3, calls)
    }

    @Test
    fun queryStringIsEncoded() {
        assertEquals("", UrlConnectionTransport.queryString(emptyMap()))
        assertEquals(
            "?platform=tiktok&type=follow&uid=a+b%2Bc",
            UrlConnectionTransport.queryString(linkedMapOf("platform" to "tiktok", "type" to "follow", "uid" to "a b+c")),
        )
    }
}
