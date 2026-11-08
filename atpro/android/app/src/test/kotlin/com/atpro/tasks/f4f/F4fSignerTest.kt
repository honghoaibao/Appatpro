package com.atpro.tasks.f4f

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F4fSignerTest {

    /** Vector thật từ ảnh DevTools POST /user/auth/login (xem API_reference_v2.txt mục 2). */
    @Test
    fun matchesKnownDevToolsVector() {
        val s = F4fSigner.sign("POST", "/user/auth/login", 1793087078L)
        assertEquals(1793087078L, s.ts)
        assertEquals("589b0d565bd8ce3db463535bbac382f55096350eb8de0df605dbcb0e84b8028e", s.sign)
    }

    @Test
    fun queryStringIsNotPartOfSignature() {
        val a = F4fSigner.sign("GET", "/user/earn-coin?platform=tiktok&type=follow", 1700000000L)
        val b = F4fSigner.sign("GET", "/user/earn-coin", 1700000000L)
        assertEquals(b.sign, a.sign)
    }

    @Test
    fun methodIsUppercased() {
        val a = F4fSigner.sign("post", "/user/auth/login", 1700000000L)
        val b = F4fSigner.sign("POST", "/user/auth/login", 1700000000L)
        assertEquals(b.sign, a.sign)
    }

    @Test
    fun signatureIs64LowercaseHexChars() {
        val s = F4fSigner.sign("GET", "/user/balance", 1700000000L).sign
        assertEquals(64, s.length)
        assertTrue(s.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun differentTimestampGivesDifferentSignature() {
        val a = F4fSigner.sign("GET", "/user/balance", 1700000000L).sign
        val b = F4fSigner.sign("GET", "/user/balance", 1700000001L).sign
        assertTrue(a != b)
    }
}
