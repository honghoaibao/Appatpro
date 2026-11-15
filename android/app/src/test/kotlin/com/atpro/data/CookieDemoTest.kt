package com.atpro.data

import org.junit.Assert.*
import org.junit.Test

/** CookieDemoTest — v1.12.5: đọc cookie cho giao diện "nuôi bằng cookie" (demo). Thuần Kotlin. */
class CookieDemoTest {

    private fun fb(raw: String) = CookieDemo.parse(raw, CookiePlatform.FACEBOOK)
    private fun ig(raw: String) = CookieDemo.parse(raw, CookiePlatform.INSTAGRAM)

    @Test fun `facebook header du khoa`() {
        val c = fb("datr=abc; c_user=100012345678901; xs=12%3Aabc%3A2; fr=0zzz")!!
        assertEquals("100012345678901", c.accountId)
        assertEquals(listOf("c_user", "xs"), c.present)
        assertTrue(c.missing.isEmpty())
        assertTrue(c.isComplete)
    }

    @Test fun `facebook thieu xs`() {
        val c = fb("c_user=100012345678901; datr=abc")!!
        assertEquals(listOf("xs"), c.missing)
        assertFalse(c.isComplete)
        assertEquals("100012345678901", c.accountId)
    }

    @Test fun `gia tri rong tinh la thieu`() {
        val c = fb("c_user=100012345678901; xs=")!!
        assertEquals(listOf("xs"), c.missing)
    }

    @Test fun `ten khoa khong phan biet hoa thuong va xuong dong thay cho cham phay`() {
        val c = fb("C_USER=100012345678901\nXS=abc")!!
        assertTrue(c.isComplete)
    }

    @Test fun `json xuat tu tien ich trinh duyet`() {
        val json = """[{"domain":".facebook.com","name":"c_user","value":"100012345678901","path":"/"},
            {"value":"abc%3A1","name":"xs","domain":".facebook.com"}]"""
        val c = fb(json)!!
        assertTrue(c.isComplete)
        assertEquals("100012345678901", c.accountId)
    }

    @Test fun `netscape cookies txt`() {
        val txt = ".facebook.com\tTRUE\t/\tTRUE\t1893456000\tc_user\t100012345678901\n" +
                  ".facebook.com\tTRUE\t/\tTRUE\t1893456000\txs\tabc"
        assertTrue(fb(txt)!!.isComplete)
    }

    @Test fun `dong chu thich netscape bi bo qua`() {
        val txt = "# Netscape HTTP Cookie File\n.facebook.com\tTRUE\t/\tTRUE\t1893456000\tc_user\t100012345678901"
        val c = fb(txt)!!
        assertEquals("100012345678901", c.accountId)
        assertEquals(listOf("xs"), c.missing)
    }

    @Test fun `instagram du khoa va id`() {
        val c = ig("sessionid=1234567%3Aabcdef%3A27; ds_user_id=1234567; csrftoken=zzz")!!
        assertTrue(c.isComplete)
        assertEquals("1234567", c.accountId)
    }

    @Test fun `instagram suy ra id tu tien to sessionid khi thieu ds_user_id`() {
        val c = ig("sessionid=1234567%3Aabcdef%3A27")!!
        assertEquals("1234567", c.accountId)
        assertEquals(listOf("ds_user_id"), c.missing)
        assertFalse(c.isComplete)       // còn thiếu khoá bắt buộc
    }

    @Test fun `khong phai cookie tra ve null`() {
        assertNull(fb(""))
        assertNull(fb("   "))
        assertNull(fb("xin chao day khong phai cookie"))
        assertNull(fb("=abc; ;"))
        assertNull(fb("[]"))
    }

    @Test fun `khong co id thi khong phai hoan chinh`() {
        val c = fb("datr=abc; fr=1")!!
        assertNull(c.accountId)
        assertEquals(listOf("c_user", "xs"), c.missing)
        assertFalse(c.isComplete)
    }

    @Test fun `gia tri chua dau bang van giu nguyen khoa`() {
        val c = fb("c_user=100012345678901; xs=a=b=c")!!
        assertTrue(c.isComplete)
    }

    @Test fun `chuoi qua dai bi cat khong lam hong`() {
        val long = "c_user=100012345678901; xs=abc; pad=" + "x".repeat(50_000)
        assertTrue(fb(long)!!.isComplete)
    }
}
