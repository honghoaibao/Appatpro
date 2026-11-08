package com.atpro.accessibility

import org.junit.Assert.*
import org.junit.Test

/**
 * VideoTextTest — v1.11.0. Pure Kotlin, chỉ cần JUnit.
 * Bảng dữ liệu thật lấy từ tài liệu "Quét dữ liệu TikTok" (Bảng 6, Hình 1/2).
 */
class VideoTextTest {

    // ── CountParser ───────────────────────────────────────────────────────

    @Test fun `so dem thuc te tu tai lieu`() {
        assertEquals(449_600L, CountParser.parse("449.6K"))
        assertEquals(2_621L, CountParser.parse("2,621"))
        assertEquals(25_200L, CountParser.parse("25.2K"))
        assertEquals(215_900L, CountParser.parse("215.9K"))
        assertEquals(2_500_000L, CountParser.parse("2.5Tr"))
        assertEquals(22_000L, CountParser.parse("22K"))
        assertEquals(180_700L, CountParser.parse("180.7K"))
        assertEquals(3_400_000L, CountParser.parse("3.4Tr"))
        assertEquals(449_600L, CountParser.parse("449,6N"))
        assertEquals(3_000_000_000L, CountParser.parse("3 Tỷ"))
    }

    @Test fun `hau to tieng Anh va so khong hau to`() {
        assertEquals(1_200_000L, CountParser.parse("1.2M"))
        assertEquals(1_200_000_000L, CountParser.parse("1.2B"))
        assertEquals(2_621L, CountParser.parse("2.621"))
        assertEquals(1_100L, CountParser.parse("1.1K"))   // 1.1 * 1000 không được rơi về 1099
        assertEquals(0L, CountParser.parse("0"))
    }

    @Test fun `chuoi khong phai so dem tra ve null`() {
        assertNull(CountParser.parse("12:48"))
        assertNull(CountParser.parse("62%"))
        assertNull(CountParser.parse("@DDuongw"))
        assertNull(CountParser.parse("#fyp"))
        assertNull(CountParser.parse(""))
    }

    @Test fun `dinh dang ngan`() {
        assertEquals("500", CountParser.format(500))
        assertEquals("1,5K", CountParser.format(1_500))
        assertEquals("10K", CountParser.format(10_000))
        assertEquals("1Tr", CountParser.format(1_000_000))
    }

    // ── VideoScanner ──────────────────────────────────────────────────────

    private val w = 1080
    private val h = 2400
    private val screen = listOf(
        TextBox("12:48", 40, 20, 160, 70),
        TextBox("449.6K", 930, 1180, 1040, 1230),
        TextBox("2,621", 940, 1420, 1040, 1470),
        TextBox("25.2K", 940, 1660, 1040, 1710),
        TextBox("215.9K", 930, 1900, 1040, 1950),
        TextBox("@DDuongw", 40, 1850, 330, 1900),
        TextBox("Một ngày bình thường #xuhuong #FYP #ănvặt", 40, 1920, 860, 2010),
        TextBox("♫ âm thanh gốc - DDuongw gốc", 40, 2060, 600, 2100),
        TextBox("Trang chủ", 30, 2300, 200, 2360),
    )

    @Test fun `quet video day du`() {
        val v = VideoScanner.scan(screen, w, h)
        assertEquals(449_600L, v.likes)
        assertEquals(2_621L, v.comments)
        assertEquals(25_200L, v.saves)
        assertEquals(215_900L, v.shares)
        assertEquals("@DDuongw", v.username)
        assertTrue(v.caption.startsWith("Một ngày bình thường"))
        assertEquals(listOf("xuhuong", "fyp", "ănvặt"), v.hashtags)
    }

    @Test fun `khong co du lieu tra ve rong`() {
        assertEquals(VideoInfo(), VideoScanner.scan(emptyList(), w, h))
    }

    @Test fun `so tim doc tu mo ta nut tim khi node so thieu`() {
        val noLike = screen.filter { it.text != "449.6K" }
        val v = VideoScanner.scan(noLike, w, h, listOf("Thích video, 449,6K lượt thích"))
        assertEquals(449_600L, v.likes)
    }

    @Test fun `khong co cot phai thi khong doan so tim`() {
        assertNull(VideoScanner.scan(screen.filter { it.left < 700 }, w, h).likes)
    }

    @Test fun `so tim trong mo ta`() {
        assertEquals(449_600L, VideoScanner.likesFromDescription("Thích video, 449,6K lượt thích"))
        assertEquals(2_500_000L, VideoScanner.likesFromDescription("Like video. 2.5M likes"))
        assertNull(VideoScanner.likesFromDescription("Thích video"))
    }

    // ── ContentMatcher ────────────────────────────────────────────────────

    @Test fun `hashtag khong phan biet dau va co hoac khong co dau thang`() {
        val m = ContentMatcher.parse("xu hướng, #FYP; viral")
        assertTrue(m.matchesHashtags(listOf("fyp")))
        assertTrue(m.matchesHashtags(listOf("xuhướng")))
        assertTrue(m.matchesHashtags(listOf("xuhuongtiktok")))
        assertTrue(m.matchesHashtags(listOf("viralvideo")))
        assertFalse(m.matchesHashtags(listOf("monngon", "review")))
        assertFalse(m.matchesHashtags(emptyList()))
    }

    @Test fun `tu khoa ngan chi khop dung`() {
        assertFalse(ContentMatcher.parse("vn").matchesHashtags(listOf("vnexpress")))
        assertTrue(ContentMatcher.parse("vn").matchesHashtags(listOf("vn")))
    }

    @Test fun `chu thich khop theo ranh gioi tu`() {
        assertTrue(ContentMatcher.parse("hot").matchesCaption("Món ăn này hot lắm"))
        assertFalse(ContentMatcher.parse("hot").matchesCaption("Nhiều photo đẹp"))
        assertTrue(ContentMatcher.parse("unbox").matchesCaption("Unboxing đồ mới"))
        assertTrue(ContentMatcher.parse("dep").matchesCaption("Hôm nay trời đẹp quá"))
        assertTrue(ContentMatcher.parse("review").matchesCaption("xem ngay #review sản phẩm"))
        assertTrue(ContentMatcher.parse("xu huong").matchesCaption("Đang là xu hướng mới"))
    }

    @Test fun `danh sach tu khoa rong`() {
        assertTrue(ContentMatcher.parse("  , ;").isEmpty)
        assertFalse(ContentMatcher.EMPTY.matchesCaption("abc def"))
    }

    @Test fun `fold bo dau va gop khoang trang`() {
        assertEquals("dep qua", ContentMatcher.fold("  Đẹp   QUÁ  "))
    }

    // ── likeCountGate ─────────────────────────────────────────────────────

    @Test fun `cong so tim`() {
        assertEquals(LikeCountGate.PASS, likeCountGate(0, null))
        assertEquals(LikeCountGate.UNKNOWN, likeCountGate(1_000, null))
        assertEquals(LikeCountGate.PASS, likeCountGate(1_000, 1_000))
        assertEquals(LikeCountGate.PASS, likeCountGate(1_000, 5_000))
        assertEquals(LikeCountGate.TOO_FEW, likeCountGate(1_000, 999))
    }
}
