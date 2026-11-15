package com.atpro.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** FarmStatsTest — v1.12.5: tổng hợp thống kê nuôi acc (giờ địa phương, chỉ phiên đã đóng, so kỳ trước…). Thuần Kotlin. */
class FarmStatsTest {

    private val vn = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")          // UTC+7, không DST

    /** Mốc giờ địa phương VN → epoch ms. */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(vn).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    private val now = at(2026, 11, 15, 10)                              // 15/11/2026 10:00 (VN)

    private fun s(
        start: Long, acc: String = "a", likes: Int = 0, follows: Int = 0, comments: Int = 0,
        videos: Int = 10, dur: Int = 600, closed: Boolean = true,
    ) = FarmSessionRow(acc, start, if (closed) start + dur * 1000L else null, likes, follows, comments, videos, dur)

    private fun sum(rows: List<FarmSessionRow>, days: Int? = 7) = summarizeFarmStats(rows, now, days, vn)

    @Test fun `phien chua dong khong duoc tinh`() {
        val r = sum(listOf(s(at(2026, 11, 15, 8), likes = 5), s(at(2026, 11, 15, 9), likes = 99, closed = false)))
        assertEquals(1, r.sessions)
        assertEquals(5, r.likes)
        assertEquals(1, r.todaySessions)
    }

    @Test fun `ngay chia theo gio dia phuong chu khong phai UTC`() {
        // 01:30 sáng 15/11 giờ VN = 18:30 UTC ngày 14/11 → phải tính vào NGÀY 15 (hôm nay), không phải 14
        val r = sum(listOf(s(at(2026, 11, 15, 1, 30), likes = 7)))
        assertEquals(7, r.todayInteractions)
        assertEquals(1, r.todaySessions)
        assertEquals(0, r.days[5].interactions)         // 14/11
        assertEquals(7, r.days[6].interactions)         // 15/11 (cột cuối)
    }

    @Test fun `bieu do du moi ngay va nhan ngay dung`() {
        val r = sum(listOf(s(at(2026, 11, 12, 20), likes = 3)))
        assertEquals(7, r.days.size)
        assertEquals(listOf("09/11", "10/11", "11/11", "12/11", "13/11", "14/11", "15/11"), r.days.map { it.label })
        assertEquals(listOf(0, 0, 0, 3, 0, 0, 0), r.days.map { it.interactions })
        assertEquals(1, r.activeDays)
        assertEquals("12/11", r.bestDay?.label)
    }

    @Test fun `khoang 7 ngay tinh tu 0h cua ngay dau chu khong phai 7x24 gio lui`() {
        // 09/11 00:30 vẫn thuộc cửa sổ 7 ngày (09/11 → 15/11); 08/11 23:30 thì không
        val r = sum(listOf(s(at(2026, 11, 9, 0, 30), likes = 2), s(at(2026, 11, 8, 23, 30), likes = 50)))
        assertEquals(2, r.likes)
        assertEquals(1, r.sessions)
    }

    @Test fun `so voi ky truoc cung do dai`() {
        val rows = listOf(
            s(at(2026, 11, 14, 9), likes = 10, follows = 5, comments = 5),     // kỳ này: 20 tương tác
            s(at(2026, 11, 5, 9), likes = 10),                                  // kỳ trước (02–08/11): 10 tương tác
        )
        val r = sum(rows)
        assertEquals(20, r.interactions)
        assertEquals(10, r.prevInteractions)
        assertEquals(100.0, r.changePercent!!, 0.001)
    }

    @Test fun `khong co ky truoc thi khong co phan tram`() {
        assertNull(sum(listOf(s(at(2026, 11, 14, 9), likes = 4))).changePercent)
    }

    @Test fun `chon Tat ca thi khong so voi ky truoc va bao gom phien cu`() {
        val r = sum(listOf(s(at(2026, 10, 1, 9), likes = 8), s(at(2026, 11, 14, 9), likes = 2)), days = null)
        assertEquals(10, r.likes)
        assertEquals(2, r.sessions)
        assertEquals(0, r.prevInteractions)
        assertNull(r.changePercent)
        assertTrue(r.days.size in 1..60)
    }

    @Test fun `tuong tac theo gio bat dau phien`() {
        val r = sum(listOf(s(at(2026, 11, 14, 21, 15), likes = 6), s(at(2026, 11, 13, 21, 50), follows = 4), s(at(2026, 11, 14, 8), comments = 1)))
        assertEquals(10, r.hourInteractions[21])
        assertEquals(1, r.hourInteractions[8])
        assertEquals(24, r.hourInteractions.size)
    }

    @Test fun `xep hang tai khoan gop nhieu phien`() {
        val rows = listOf(
            s(at(2026, 11, 14, 9), acc = "x", likes = 5), s(at(2026, 11, 14, 11), acc = "x", likes = 5),
            s(at(2026, 11, 14, 13), acc = "y", likes = 30),
            s(at(2026, 11, 14, 15), acc = "z", likes = 1),
        )
        val r = sum(rows)
        assertEquals(listOf("y", "x", "z"), r.accounts.map { it.account })
        assertEquals(10, r.accounts[1].likes)
        assertEquals(2, r.accounts[1].sessions)
    }

    @Test fun `chi giu 5 tai khoan dau`() {
        val rows = (1..8).map { i -> s(at(2026, 11, 14, 9), acc = "u$i", likes = i) }
        assertEquals(5, sum(rows).accounts.size)
        assertEquals("u8", sum(rows).accounts.first().account)
    }

    @Test fun `ty le va trung binh`() {
        val r = sum(listOf(s(at(2026, 11, 14, 9), likes = 10, follows = 2, comments = 1, videos = 40, dur = 1200),
                           s(at(2026, 11, 14, 11), videos = 60, dur = 600)))
        assertEquals(100, r.videos)
        assertEquals(10f, r.likeRate, 0.001f)
        assertEquals(2f, r.followRate, 0.001f)
        assertEquals(1f, r.commentRate, 0.001f)
        assertEquals(50f, r.avgVideosPerSession, 0.001f)
        assertEquals(900L, r.avgDurationSecs)
        assertEquals(1800L, r.durationSecs)
    }

    @Test fun `thoi luong rong thi lay tu thoi diem bat dau va ket thuc`() {
        val start = at(2026, 11, 14, 9)
        val row = FarmSessionRow("a", start, start + 90_000L, 0, 0, 0, 5, durationSecs = 0)
        assertEquals(90L, sum(listOf(row)).durationSecs)
    }

    @Test fun `du lieu rong`() {
        val r = sum(emptyList())
        assertTrue(r.isEmpty)
        assertEquals(7, r.days.size)
        assertNull(r.bestDay)
        assertEquals(0, r.activeDays)
        assertEquals(0f, r.likeRate, 0.001f)
        assertEquals(0L, r.avgDurationSecs)
        assertTrue(FarmStatsSummary.EMPTY.isEmpty)
    }

    @Test fun `dinh dang thoi luong`() {
        assertEquals("—", formatDuration(0))
        assertEquals("45 giây", formatDuration(45))
        assertEquals("10p", formatDuration(600))
        assertEquals("1g 30p", formatDuration(5_400))
        assertEquals("2g", formatDuration(7_200))
    }
}
