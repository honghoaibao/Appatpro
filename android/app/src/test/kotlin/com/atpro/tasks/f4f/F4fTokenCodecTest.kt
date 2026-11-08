package com.atpro.tasks.f4f

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class F4fTokenCodecTest {

    @Test
    fun roundTrip() {
        val token = "123|AbCdEf0123456789xyz_-"
        assertEquals(token, F4fTokenCodec.decode(F4fTokenCodec.encode(token)))
    }

    @Test
    fun storedFormIsNotPlaintext() {
        val token = "secret-token-value"
        val stored = F4fTokenCodec.encode(token)
        assertFalse(stored.contains("secret"))
        assertFalse(stored.contains(token))
    }

    @Test
    fun nonAsciiRoundTrip() {
        assertEquals("tôi-đăng-nhập", F4fTokenCodec.decode(F4fTokenCodec.encode("tôi-đăng-nhập")))
    }

    @Test
    fun corruptOrEmptyInputGivesNull() {
        assertNull(F4fTokenCodec.decode(null))
        assertNull(F4fTokenCodec.decode(""))
        assertNull(F4fTokenCodec.decode("   "))
        assertNull(F4fTokenCodec.decode("abc,def"))
        assertNull(F4fTokenCodec.decode("plain-token-in-old-prefs"))
    }
}
