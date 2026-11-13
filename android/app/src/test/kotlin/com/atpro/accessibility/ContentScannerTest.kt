package com.atpro.accessibility

import org.junit.Assert.*
import org.junit.Test

/**
 * ContentScannerTest — v1.12.5: luật quét popup / quảng cáo / live ([ContentScanner.classify]).
 * Hàm phân loại là thuần Kotlin nên chạy trên JVM, không cần cây node thật.
 */
class ContentScannerTest {

    private fun probe(text: String, clickable: Boolean = false, visible: Boolean = true) =
        ScanProbe(ContentScanner.normalize(text), clickable, visible)

    private fun classify(vararg p: ScanProbe, detectPopup: Boolean = true, softOnly: Boolean = false) =
        ContentScanner.classify(p.toList(), detectPopup = detectPopup, softDismissOnly = softOnly)

    // ── R0: popup ────────────────────────────────────────────

    @Test fun `nut De sau bam duoc la popup`() {
        val v = classify(probe("Liên kết email"), probe("Để sau", clickable = true), probe("OK", clickable = true))
        assertEquals(ScanCategory.POPUP, v.category)
        assertEquals("để sau", v.matchedText)
    }

    @Test fun `chi co nut OK thi khong phai popup - khong bao gio coi nut xac nhan la dong`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("OK", clickable = true), probe("Đồng ý", clickable = true), probe("Cho phép", clickable = true)).category)
    }

    @Test fun `chu dong khong bam duoc thi bo qua`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Đóng", clickable = false)).category)
    }

    @Test fun `chu dong nam trong caption dai khong phai popup`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Đóng cửa hàng lúc 9 giờ tối nay", clickable = true)).category)
    }

    @Test fun `node an khong tinh`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Để sau", clickable = true, visible = false)).category)
    }

    @Test fun `nhieu kieu viet nut dong deu nhan ra`() {
        for (t in listOf("Not now", "Bỏ qua", "Hủy", "Huỷ", "Close", "Maybe later", "  ĐÓNG  ")) {
            assertEquals(t, ScanCategory.POPUP, classify(probe(t, clickable = true)).category)
        }
    }

    // ── Bảng chuyển đổi tài khoản: giữ nút X "Đóng", vẫn đóng popup nằm đè lên ──

    @Test fun `bang chuyen doi tai khoan duoc nhan ra`() {
        assertTrue(ContentScanner.isAccountSwitchScreen(listOf(probe("Chuyển đổi tài khoản"), probe("@abc"))))
        assertTrue(ContentScanner.isAccountSwitchScreen(listOf(probe("Thêm tài khoản", clickable = true))))
        assertFalse(ContentScanner.isAccountSwitchScreen(listOf(probe("Cài đặt và quyền riêng tư"), probe("Đăng xuất"))))
    }

    @Test fun `tren bang chuyen doi tai khoan nut Dong cung khong bi bam`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Đóng", clickable = true), softOnly = true).category)
        assertEquals(ScanCategory.CLEAN, classify(probe("Hủy", clickable = true), softOnly = true).category)
    }

    @Test fun `tren bang chuyen doi tai khoan popup nam de len van dong duoc bang nut mem`() {
        val v = classify(probe("Đóng", clickable = true), probe("Để sau", clickable = true), softOnly = true)
        assertEquals(ScanCategory.POPUP, v.category)
        assertEquals("để sau", v.matchedText)
    }

    // ── R1/R2: quảng cáo ─────────────────────────────────────

    @Test fun `nhan Duoc tai tro gop dong la quang cao`() {
        assertEquals(ScanCategory.SPONSORED, classify(probe("Dola · Được tài trợ")).category)
        assertEquals(ScanCategory.SPONSORED, classify(probe("Sponsored")).category)
    }

    @Test fun `quang cao co nut cai app la APP_INSTALL`() {
        val v = classify(probe("Được tài trợ"), probe("Nhận", clickable = true))
        assertEquals(ScanCategory.APP_INSTALL, v.category)
    }

    @Test fun `nut Nhan mot minh khong phai quang cao`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Nhận", clickable = true)).category)
    }

    @Test fun `ten nhac quang cao la tin hieu phu - mot minh chua du`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Nhạc Quảng cáo - Dola")).category)
        assertEquals(ScanCategory.SPONSORED, classify(probe("Nhạc Quảng cáo - Dola"), probe("Được tài trợ")).category)
    }

    @Test fun `caption dai co chu promoted khong tinh la quang cao`() {
        val caption = "Hôm nay mình được promoted lên trưởng nhóm, cảm ơn mọi người đã luôn ủng hộ mình trong suốt thời gian qua"
        assertTrue(caption.length > ContentScanner.MAX_LABEL_LEN)
        assertEquals(ScanCategory.CLEAN, classify(probe(caption)).category)
    }

    // ── R3/R4: live ──────────────────────────────────────────

    @Test fun `huy hieu Dang LIVE la live`() {
        assertEquals(ScanCategory.LIVE, classify(probe("Đang LIVE")).category)
        assertEquals(ScanCategory.LIVE, classify(probe("Nhấn để xem LIVE")).category)
    }

    @Test fun `chu live roi rac hoac nam trong caption khong du`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("LIVE")).category)
        assertEquals(ScanCategory.CLEAN, classify(probe("Tối nay mình live bán hàng nhé cả nhà")).category)
    }

    @Test fun `hai tin hieu live phu cong lai la live`() {
        assertEquals(ScanCategory.LIVE, classify(probe("LIVE"), probe("Trực tiếp")).category)
    }

    // ── Chế độ chỉ-nội-dung & thứ tự ưu tiên ─────────────────

    @Test fun `bo qua popup khi chi hoi noi dung feed`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Đóng", clickable = true), detectPopup = false).category)
        assertEquals(ScanCategory.SPONSORED, classify(probe("Đóng", clickable = true), probe("Được tài trợ"), detectPopup = false).category)
    }

    @Test fun `popup uu tien hon quang cao va live`() {
        assertEquals(ScanCategory.POPUP, classify(probe("Được tài trợ"), probe("Đang LIVE"), probe("Để sau", clickable = true)).category)
    }

    @Test fun `quang cao uu tien hon live`() {
        assertEquals(ScanCategory.SPONSORED, classify(probe("Đang LIVE"), probe("Được tài trợ")).category)
    }

    @Test fun `man hinh sach`() {
        assertEquals(ScanCategory.CLEAN, classify(probe("Trang chủ", clickable = true), probe("Hộp thư"), probe("@abc"), probe("Thích")).category)
        assertEquals(ScanCategory.CLEAN, ContentScanner.classify(emptyList()).category)
    }

    // ── Tiện ích ─────────────────────────────────────────────

    @Test fun `normalize gop khoang trang va ha chu`() {
        assertEquals("để sau nhé", ContentScanner.normalize("  Để   SAU\n nhé "))
    }

    @Test fun `van tay chi lay chu dang hien`() {
        val fp = ContentScanner.fingerprint(listOf(probe("a"), probe("b", visible = false), probe("c")), limit = 5)
        assertEquals("a|c", fp)
    }
}
