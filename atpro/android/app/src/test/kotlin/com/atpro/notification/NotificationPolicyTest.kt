package com.atpro.notification

import org.junit.Assert.*
import org.junit.Test

/** NotificationPolicyTest — v1.12.0 */
class NotificationPolicyTest {

    private val on = NotificationPrefs()

    // ── Giờ yên tĩnh ──────────────────────────────────────────
    @Test fun `gio yen tinh qua nua dem`() {
        val p = on.copy(quietEnabled = true, quietStartHour = 22, quietEndHour = 7)
        assertTrue(NotificationPolicy.isQuiet(p, 22))
        assertTrue(NotificationPolicy.isQuiet(p, 23))
        assertTrue(NotificationPolicy.isQuiet(p, 0))
        assertTrue(NotificationPolicy.isQuiet(p, 6))
        assertFalse(NotificationPolicy.isQuiet(p, 7))
        assertFalse(NotificationPolicy.isQuiet(p, 12))
        assertFalse(NotificationPolicy.isQuiet(p, 21))
    }

    @Test fun `gio yen tinh trong ngay`() {
        val p = on.copy(quietEnabled = true, quietStartHour = 13, quietEndHour = 15)
        assertTrue(NotificationPolicy.isQuiet(p, 13))
        assertTrue(NotificationPolicy.isQuiet(p, 14))
        assertFalse(NotificationPolicy.isQuiet(p, 15))
        assertFalse(NotificationPolicy.isQuiet(p, 12))
    }

    @Test fun `tat hoac bat dau bang ket thuc thi khong bao gio yen tinh`() {
        assertFalse(NotificationPolicy.isQuiet(on.copy(quietEnabled = false, quietStartHour = 0, quietEndHour = 23), 3))
        assertFalse(NotificationPolicy.isQuiet(on.copy(quietEnabled = true, quietStartHour = 5, quietEndHour = 5), 5))
    }

    // ── Quyết định ────────────────────────────────────────────
    @Test fun `tat tong thi bo het`() {
        val p = on.copy(enabled = false)
        NotifCategory.entries.forEach { assertEquals(NotifDecision.DROP, NotificationPolicy.decide(p, it, 12)) }
    }

    @Test fun `tat tung nhom`() {
        val p = on.copy(accounts = false, tasks = false)
        assertEquals(NotifDecision.DROP, NotificationPolicy.decide(p, NotifCategory.ACCOUNT, 12))
        assertEquals(NotifDecision.DROP, NotificationPolicy.decide(p, NotifCategory.TASK, 12))
        assertEquals(NotifDecision.POST, NotificationPolicy.decide(p, NotifCategory.ALERT, 12))
        assertEquals(NotifDecision.POST, NotificationPolicy.decide(p, NotifCategory.SESSION, 12))
    }

    @Test fun `gio yen tinh van hien nhung im lang`() {
        val p = on.copy(quietEnabled = true, quietStartHour = 22, quietEndHour = 7)
        assertEquals(NotifDecision.POST_SILENT, NotificationPolicy.decide(p, NotifCategory.ALERT, 2))
        assertEquals(NotifDecision.POST, NotificationPolicy.decide(p, NotifCategory.ALERT, 10))
    }

    // ── Chống lặp ─────────────────────────────────────────────
    @Test fun `throttle chan lap trong khoang va cho lai sau do`() {
        val t = NotificationThrottle(10 * 60_000L)
        assertTrue(t.allow("checkpoint:a", 1_000))
        assertFalse(t.allow("checkpoint:a", 1_000 + 5 * 60_000L))
        assertTrue(t.allow("checkpoint:b", 2_000))                  // khoá khác không bị chặn
        assertTrue(t.allow("checkpoint:a", 1_000 + 10 * 60_000L))   // đủ 10 phút
    }

    @Test fun `throttle chiu duoc dong ho nhay lui`() {
        val t = NotificationThrottle(60_000L)
        assertTrue(t.allow("k", 100_000))
        assertTrue(t.allow("k", 50_000))                            // giờ máy chỉnh lùi → cho đăng, không kẹt
    }

    // ── Câu chữ ───────────────────────────────────────────────
    @Test fun `dinh dang xu`() {
        assertEquals("26", NotificationText.coin(26.0))
        assertEquals("2,5", NotificationText.coin(2.5))
        assertEquals("0,05", NotificationText.coin(0.05))
        assertEquals("1.250", NotificationText.coin(1250.0))
        assertEquals("1.250,75", NotificationText.coin(1250.75))
        assertEquals("0", NotificationText.coin(0.0))
    }

    @Test fun `thoi luong`() {
        assertEquals("dưới 1 phút", NotificationText.duration(0))
        assertEquals("5 phút", NotificationText.duration(5))
        assertEquals("2 giờ", NotificationText.duration(120))
        assertEquals("1 giờ 15 phút", NotificationText.duration(75))
    }

    @Test fun `danh sach tai khoan`() {
        assertEquals("@a, @b", NotificationText.accountList(listOf("a", "@b")))
        assertEquals("@a, @b, @c, @d +2", NotificationText.accountList(listOf("a", "b", "c", "d", "e", "f")))
    }

    @Test fun `noi dung tong ket nhiem vu`() {
        val t = NotificationText.taskCompleted(3, 12, 5, 7, 31.5, 48, "1.250 coin")
        assertEquals("Nhiệm vụ xong — 12 job · +31,5 xu", t.title)
        assertTrue(t.body.contains("3 tài khoản"))
        assertTrue(t.body.contains("48 phút"))
        assertTrue(t.body.contains("Số dư: 1.250 coin"))
        val none = NotificationText.taskCompleted(2, 0, 0, 0, 0.0, 3, null)
        assertEquals("Nhiệm vụ xong — Không có job nào được làm", none.title)
        assertFalse(none.body.contains("Số dư"))
    }

    @Test fun `noi dung tung tai khoan`() {
        assertEquals("Xong @abc", NotificationText.taskAccountDone("abc", 5, 2, 3, 12.0).title)
        assertEquals("5 job (tim 2 · follow 3) · +12 xu", NotificationText.taskAccountDone("@abc", 5, 2, 3, 12.0).body)
        assertEquals("Không có job nào được làm", NotificationText.taskAccountDone("abc", 0, 0, 0, 0.0).body)
    }
}
