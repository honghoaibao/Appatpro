package com.atpro.data

import org.junit.Assert.*
import org.junit.Test

/** CookieVaultCodecTest — v1.12.7: kiểm tra/chuẩn hoá/mã hoá JSON của kho cookie (thuần JVM, không Keystore). */
class CookieVaultCodecTest {

    private val fb = CookiePlatform.FACEBOOK
    private val ig = CookiePlatform.INSTAGRAM

    private fun ok(r: CookiePrepareResult) = (r as CookiePrepareResult.Ok).entry

    // ── prepare ───────────────────────────────────────────────

    @Test fun `facebook du khoa duoc chuan hoa thanh mot header`() {
        val e = ok(CookieVaultCodec.prepare("C_User=1000; XS=abc%3Adef; fr=zzz", fb, 42L))
        assertEquals("FACEBOOK", e.platform); assertEquals("1000", e.accountId); assertEquals(42L, e.addedAt)
        assertEquals("c_user=1000; xs=abc%3Adef; fr=zzz", e.cookie)          // tên chữ thường, giữ nguyên giá trị + thứ tự
    }

    @Test fun `giu lai cac cookie phu vi phien that can nhieu hon khoa bat buoc`() {
        val e = ok(CookieVaultCodec.prepare("c_user=1;xs=2;datr=3;sb=4", fb, 0))
        assertTrue(e.cookie.contains("datr=3") && e.cookie.contains("sb=4"))
    }

    @Test fun `thieu xs thi khong luu`() {
        val r = CookieVaultCodec.prepare("c_user=1000; fr=1", fb, 0) as CookiePrepareResult.Incomplete
        assertEquals(listOf("xs"), r.missing); assertTrue(r.hasAccountId)
    }

    @Test fun `instagram can ca sessionid va ds_user_id`() {
        assertEquals("1234", ok(CookieVaultCodec.prepare("sessionid=1234%3Aabcd%3A5; ds_user_id=1234", ig, 0)).accountId)
        val r = CookieVaultCodec.prepare("sessionid=1234%3Aabcd%3A5", ig, 0) as CookiePrepareResult.Incomplete
        assertEquals(listOf("ds_user_id"), r.missing)
    }

    @Test fun `json tu tien ich trinh duyet cung duoc`() {
        val raw = """[{"name":"c_user","value":"77"},{"name":"xs","value":"tok"}]"""
        assertEquals("77", ok(CookieVaultCodec.prepare(raw, fb, 0)).accountId)
    }

    @Test fun `khong phai cookie va rong la Invalid`() {
        assertEquals(CookiePrepareResult.Invalid, CookieVaultCodec.prepare("hello world", fb, 0))
        assertEquals(CookiePrepareResult.Invalid, CookieVaultCodec.prepare("   ", fb, 0))
    }

    @Test fun `qua nhieu cap hoac qua dai bi tu choi`() {
        val many = (1..CookieVaultCodec.MAX_PAIRS + 1).joinToString("; ") { "k$it=v" } + "; c_user=1; xs=2"
        assertEquals(CookiePrepareResult.Invalid, CookieVaultCodec.prepare(many, fb, 0))
        val huge = "c_user=1; xs=" + "a".repeat(CookieVaultCodec.MAX_COOKIE_CHARS)
        assertEquals(CookiePrepareResult.Invalid, CookieVaultCodec.prepare(huge, fb, 0))
    }

    // ── upsert / remove / summaries ───────────────────────────

    private fun entry(id: String, p: CookiePlatform = fb, at: Long = 0, cookie: String = "c=$id") =
        StoredCookie(p.name, id, cookie, at)

    @Test fun `them moi roi thay the khi cung nen tang va id`() {
        val a = CookieVaultCodec.upsert(emptyList(), entry("1"))!!
        val b = CookieVaultCodec.upsert(a, entry("1", cookie = "moi"))!!
        assertEquals(1, b.size); assertEquals("moi", b[0].cookie)
    }

    @Test fun `cung id khac nen tang la hai muc rieng`() {
        val a = CookieVaultCodec.upsert(emptyList(), entry("1", fb))!!
        assertEquals(2, CookieVaultCodec.upsert(a, entry("1", ig))!!.size)
    }

    @Test fun `day thi tu choi them moi nhung van cho thay the`() {
        val full = (1..CookieVaultCodec.MAX_ENTRIES).map { entry("$it") }
        assertNull(CookieVaultCodec.upsert(full, entry("moi")))
        assertNotNull(CookieVaultCodec.upsert(full, entry("3", cookie = "x")))
    }

    @Test fun `xoa dung muc`() {
        val list = listOf(entry("1"), entry("2"), entry("1", ig))
        val r = CookieVaultCodec.remove(list, fb, "1")
        assertEquals(listOf("2" to "FACEBOOK", "1" to "INSTAGRAM"), r.map { it.accountId to it.platform })
    }

    @Test fun `summaries khong co gia tri cookie va loc theo nen tang theo thu tu them`() {
        val list = listOf(entry("b", at = 2), entry("a", at = 1), entry("z", ig, 0))
        val s = CookieVaultCodec.summaries(list, fb)
        assertEquals(listOf("a", "b"), s.map { it.accountId })
        assertFalse(s.toString().contains("cookie"))                          // CookieSummary không có trường cookie
    }

    // ── encode / decode ───────────────────────────────────────

    @Test fun `ma hoa roi giai ma tra lai y nguyen`() {
        val list = listOf(entry("1", cookie = "c_user=1; xs=a%3Ab", at = 5), entry("9", ig, 6, "sessionid=9%3Ax"))
        assertEquals(list, CookieVaultCodec.decode(CookieVaultCodec.encode(list)))
    }

    @Test fun `du lieu hong tra ve rong khong nem loi`() {
        assertEquals(emptyList<StoredCookie>(), CookieVaultCodec.decode("khong phai json".toByteArray()))
        assertEquals(emptyList<StoredCookie>(), CookieVaultCodec.decode(ByteArray(0)))
    }

    @Test fun `muc la hoac rong bi bo khi giai ma`() {
        val json = """[{"platform":"MYSPACE","accountId":"1","cookie":"a=b","addedAt":1},
            |{"platform":"FACEBOOK","accountId":"","cookie":"a=b","addedAt":1},
            |{"platform":"FACEBOOK","accountId":"7","cookie":"a=b","addedAt":1,"truongMoi":true}]""".trimMargin()
        val r = CookieVaultCodec.decode(json.toByteArray())
        assertEquals(listOf("7"), r.map { it.accountId })                    // nền tảng lạ + id rỗng bị bỏ; trường lạ bị bỏ qua
    }

    @Test fun `toString khong lo cookie`() {
        val s = entry("1", cookie = "xs=BI_MAT").toString()
        assertFalse(s.contains("BI_MAT")); assertTrue(s.contains("<ẩn>"))
    }
}
