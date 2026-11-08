package com.atpro.data

import org.junit.Assert.*
import org.junit.Test

/** LogPolishTest — v1.11.0: nhật ký dễ đọc, ẩn dòng gỡ lỗi. Các dòng mẫu lấy từ ảnh chụp màn Nhật ký thật. */
class LogPolishTest {

    private fun p(raw: String) = LogPolish.polish(raw)

    @Test fun `cac dong trong anh chup man Nhat ky`() {
        assertEquals("Không thấy @hoaibao.2009 trong danh sách tài khoản", p("ERR: Không tìm thấy @hoaibao.2009 trong switch popup").body)
        assertEquals("Chuyển từ @hoaibao.209.13 sang @hoaibao.2009", p("SWITCH: Cần switch sang @hoaibao.2009 (hiện tại: @hoaibao.209.13)").body)
        assertEquals("Tìm thấy 1 tài khoản trên máy", p("LIST: Discover 1 acc từ switch popup").body)
        assertEquals("Đang dùng @hoaibao.209.13", p("OK: Đã xác nhận tài khoản hiện tại là: @hoaibao.209.13").body)
        assertEquals("Không chuyển được sang tài khoản đầu tiên", p("ERR: Task — Không position được acc đầu").body)
    }

    @Test fun `dong ky thuat bi an`() {
        assertTrue(p("BTN: Đã tìm thấy nút chuyển đổi tài khoản").detail)
        assertTrue(p("CFG: Mở Settings để đọc danh sách acc...").detail)
        assertTrue(p("DEV: Acc đang active: @abc").detail)
        assertTrue(p("FEED: Xem video #3 — 5s (còn 40s)").detail)
        assertTrue(p("TASK: Đã về feed (Back ×2)").detail)
        assertTrue(p("FEED: Video 120 tim (cần ≥ 1K) — không tim").detail)
    }

    @Test fun `dong quan trong khong bi an`() {
        assertFalse(p("ERR: Không tìm thấy @a trong switch popup").detail)
        assertFalse(p("WARN: Lỡ chạm Đăng xuất — đã huỷ, đang thử lại").detail)
        assertFalse(p("OK: Đã xác nhận tài khoản hiện tại là: @a").detail)
        assertFalse(p("TASK: Job #5 hoàn thành ✓ (+0.5)").detail)
        assertFalse(p("WARN: Không đọc được số tim của 3 video liên tiếp — kiểm tra lại ngưỡng số tim tối thiểu").detail)
    }

    @Test fun `nhan tieng Viet va muc do`() {
        assertEquals("Lỗi", p("ERR: x").label);        assertEquals(LogLevel.ERROR, p("ERR: x").level)
        assertEquals("Chuyển acc", p("SWITCH: x").label)
        assertEquals("Nhiệm vụ", p("TASK: x").label)
        assertEquals(LogLevel.WARNING, p("WARN: x").level)
        assertEquals(LogLevel.SUCCESS, p("OK: x").level)
        assertEquals(LogLevel.SUCCESS, p("TASK: Job #5 hoàn thành ✓ (+0.5)").level)   // theo nội dung
        assertEquals(LogLevel.WARNING, p("TASK-FOLLOW: Không tìm thấy nút Follow").level)
    }

    @Test fun `dong job va ket qua tai khoan`() {
        assertEquals("Nhận job #12 (follow)", p("TASK: Job #12 FOLLOW link=https://www.tiktok.com/@a").body)
        assertEquals("Nhận job #12 (tim)", p("TASK: Job #12 LIKE link=https://www.tiktok.com/@a").body)
        assertEquals("Job #12 xong ✓ +0.5 xu", p("TASK: Job #12 hoàn thành ✓ (+0.5)").body)
        assertEquals("@abc xong: 5 job (tim 2, follow 3) · +2.5 xu", p("TASK: @abc xong — jobs=5 likes=2 follows=3 videos=40 thưởng=+2.5").body)
    }

    @Test fun `tag long nhau va ten ham bi go bo`() {
        val x = p("ERR: FIX: Không tìm thấy nút switch cho 'Bảo' — bỏ qua")
        assertEquals("Lỗi", x.label)
        assertFalse(x.body.contains("FIX:"))
        val y = p("WARN: Lạc 3 lần liên tiếp → recoverToFeed()")
        assertFalse(y.body.contains("recoverToFeed"))
        assertEquals("Bị lạc khỏi feed 3 lần — đang quay lại", y.body)
    }

    @Test fun `ten tai khoan chua so phien ban khong bi hong`() {
        assertTrue(p("OK: Đã xác nhận tài khoản hiện tại là: @abc.v1.2").body.contains("@abc.v1.2"))
    }

    @Test fun `moc giai doan va dong khong tag`() {
        assertEquals("Khởi động TikTok", p(">> [Phase 1] Khởi động TikTok").body)
        assertEquals("", p("").body)
        assertEquals("Chờ feed 1/6", p("Chờ feed 1/6...").body)
    }

    @Test fun `do dai bi cat`() {
        assertTrue(p("WARN: " + "x".repeat(400)).body.length <= 110)
    }

    @Test fun `ket qua duoc nho lai`() {
        val a = p("OK: abc"); val b = p("OK: abc")
        assertSame(a, b)
    }

    @Test fun `dong v1_12_0 chon tai khoan va buoc doi acc`() {
        assertTrue(p("F4F: S1 active-acc: OK").detail)
        assertTrue(p("F4F: Lấy job: đang active hoaibao.2009 · 3 job").detail)
        assertEquals("Job #7 đã hết hạn — sang job kế", p("TASK: Job #7 đã hết hạn/bị xoá trên máy chủ — bỏ qua, sang job kế").body)
        assertEquals("Phiên Follow4Follow đã hết hạn — cần đăng nhập lại", p("ERR: Task — phiên Follow4Follow đã hết hạn, cần đăng nhập lại").body)
        assertEquals("2 tài khoản đã chọn chưa có trên máy: @a, @b", p("WARN: Task — 2 acc đã chọn nhưng chưa có trên máy: @a, @b").body)
        assertEquals("Bỏ qua 1 tài khoản không được chọn: @c", p("TASK: Bỏ qua 1 acc không được chọn: @c").body)
        assertFalse(p("WARN: Task — Không có acc nào được chọn khớp với máy và Follow4Follow").detail)
    }
}
