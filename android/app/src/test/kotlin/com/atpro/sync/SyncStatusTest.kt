package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncStatusTest — v1.12.6: ánh xạ mã worker, câu chữ trạng thái, chính sách thông báo đồng bộ. */
class SyncStatusTest {

    private val min = 60_000L

    // ── Mã worker → kết quả ───────────────────────────────────
    @Test fun `ma worker anh xa dung`() {
        assertEquals(SyncOutcome.COMMITTED, SyncOutcome.fromStatusCode(SyncStatusCodes.SYNC_COMMITTED))
        assertEquals(SyncOutcome.UP_TO_DATE, SyncOutcome.fromStatusCode(SyncStatusCodes.NO_PENDING_DATA))
        assertEquals(SyncOutcome.SIGNED_OUT, SyncOutcome.fromStatusCode(SyncStatusCodes.SIGNED_OUT))
        assertEquals(SyncOutcome.NOT_LINKED, SyncOutcome.fromStatusCode(SyncStatusCodes.OWNER_NOT_BOUND))
        assertEquals(SyncOutcome.OWNER_CHANGED, SyncOutcome.fromStatusCode(SyncStatusCodes.AUTH_OWNER_CHANGED))
        assertEquals(SyncOutcome.AUTH_EXPIRED, SyncOutcome.fromStatusCode(SyncStatusCodes.AUTH_REFRESH_FAILED))
        assertEquals(SyncOutcome.MERGE_CONFLICT, SyncOutcome.fromStatusCode(SyncStatusCodes.MERGE_CONFLICT))
        listOf(
            SyncStatusCodes.ACKNOWLEDGEMENT_REJECTED,
            SyncStatusCodes.REMOTE_REJECTED,
            SyncStatusCodes.CAS_REJECTED,
        ).forEach { assertEquals(SyncOutcome.REJECTED, SyncOutcome.fromStatusCode(it)) }
        listOf(
            SyncStatusCodes.APPLICATION_UNAVAILABLE,
            SyncStatusCodes.OUTBOX_HEAD_UNAVAILABLE,
            SyncStatusCodes.INVALID_OUTBOX_EVENT,
            SyncStatusCodes.OVERSIZED_EVENT,
            SyncStatusCodes.LOCAL_SYNC_FAILED,
        ).forEach { assertEquals(SyncOutcome.FAILED, SyncOutcome.fromStatusCode(it)) }
    }

    @Test fun `ma la hoac thieu thi coi la loi`() {
        assertEquals(SyncOutcome.FAILED, SyncOutcome.fromStatusCode(null))
        assertEquals(SyncOutcome.FAILED, SyncOutcome.fromStatusCode("khong_biet"))
    }

    @Test fun `thanh cong va can xu ly khong giao nhau`() {
        SyncOutcome.entries.forEach { assertFalse("$it", it.isSuccess && it.needsAttention) }
        assertTrue(SyncOutcome.COMMITTED.isSuccess)
        assertTrue(SyncOutcome.UP_TO_DATE.isSuccess)
        assertTrue(SyncOutcome.AUTH_EXPIRED.needsAttention)
        assertFalse(SyncOutcome.SIGNED_OUT.needsAttention)
        assertFalse(SyncOutcome.NEVER.needsAttention)
    }

    // ── Cờ đang chạy ──────────────────────────────────────────
    @Test fun `co dang chay het han khi tien trinh chet`() {
        val s = SyncStatusSnapshot(runningSince = 1_000L)
        assertTrue(s.isRunning(1_000L + min))
        assertFalse(s.isRunning(1_000L + SyncStatusSnapshot.RUNNING_STALE_MS))
        assertFalse(SyncStatusSnapshot().isRunning(5_000L))
        // đồng hồ nhảy lùi: không coi là đang chạy
        assertFalse(SyncStatusSnapshot(runningSince = 10_000L).isRunning(5_000L))
    }

    // ── Thời gian tương đối ───────────────────────────────────
    @Test fun `thoi gian tuong doi`() {
        assertEquals("vừa xong", SyncStatusText.relative(100_000L, 100_000L))
        assertEquals("vừa xong", SyncStatusText.relative(100_000L, 60_000L))
        assertEquals("1 phút trước", SyncStatusText.relative(100_000L, 40_000L))
        assertEquals("5 phút trước", SyncStatusText.relative(5 * min, 0L))
        assertEquals("2 giờ trước", SyncStatusText.relative(2 * 60 * min, 0L))
        assertEquals("3 ngày trước", SyncStatusText.relative(3 * 24 * 60 * min, 0L))
        // mốc ở tương lai (đồng hồ lệch) không âm
        assertEquals("vừa xong", SyncStatusText.relative(0L, 99_999L))
    }

    // ── Câu chữ ───────────────────────────────────────────────
    @Test fun `tieu de theo trang thai`() {
        val now = 10 * min
        assertEquals("Chưa đồng bộ", SyncStatusText.headline(SyncStatusSnapshot(), now))
        assertEquals("Đã đồng bộ", SyncStatusText.headline(SyncStatusSnapshot(outcome = SyncOutcome.COMMITTED), now))
        assertEquals("Phiên đăng nhập hết hạn", SyncStatusText.headline(SyncStatusSnapshot(outcome = SyncOutcome.AUTH_EXPIRED), now))
        assertEquals("Chờ thử lại", SyncStatusText.headline(SyncStatusSnapshot(outcome = SyncOutcome.COMMITTED, retrying = true), now))
        assertEquals("Đang đồng bộ", SyncStatusText.headline(SyncStatusSnapshot(outcome = SyncOutcome.AUTH_EXPIRED, runningSince = now - min), now))
    }

    @Test fun `mo ta ghep lan cuoi va so thay doi cho`() {
        val now = 100 * min
        val s = SyncStatusSnapshot(outcome = SyncOutcome.COMMITTED, lastSuccessAt = now - 5 * min, pending = 3)
        assertEquals("Lần cuối 5 phút trước · 3 thay đổi chờ gửi", SyncStatusText.detail(s, now))
    }

    @Test fun `mo ta khi chua tung dong bo`() {
        assertEquals("Chưa có lần nào", SyncStatusText.detail(SyncStatusSnapshot(), 0L))
    }

    @Test fun `mo ta su co co huong xu ly ngan`() {
        val now = 100 * min
        val s = SyncStatusSnapshot(outcome = SyncOutcome.AUTH_EXPIRED, lastSuccessAt = now - 60 * min)
        assertEquals("Đăng xuất rồi đăng nhập lại · Lần cuối 1 giờ trước", SyncStatusText.detail(s, now))
    }

    @Test fun `mo ta khi cho thu lai va khi dang chay`() {
        val now = 100 * min
        assertEquals(
            "Mạng hoặc máy chủ chưa sẵn sàng · 2 thay đổi chờ gửi",
            SyncStatusText.detail(SyncStatusSnapshot(outcome = SyncOutcome.NEVER, retrying = true, pending = 2), now),
        )
        assertEquals(
            "Đang gửi và nhận dữ liệu",
            SyncStatusText.detail(SyncStatusSnapshot(runningSince = now - min), now),
        )
    }

    @Test fun `noi dung thong bao khong co huong dan dai dong`() {
        SyncOutcome.entries.forEach { outcome ->
            listOf(true, false).forEach { manual ->
                val t = SyncStatusText.notification(outcome, manual)
                assertTrue("$outcome tiêu đề", t.title.isNotBlank() && t.title.length <= 40)
                assertTrue("$outcome nội dung", t.body.isNotBlank() && t.body.length <= 100)
            }
        }
    }

    // ── Chính sách thông báo ──────────────────────────────────
    @Test fun `chay nen chi bao khi vao trang thai can xu ly`() {
        assertTrue(SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, SyncOutcome.AUTH_EXPIRED, manual = false))
        assertTrue(SyncNotifyPolicy.shouldNotify(SyncOutcome.NEVER, SyncOutcome.NOT_LINKED, manual = false))
        // cùng sự cố lặp lại: không báo lại
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.AUTH_EXPIRED, SyncOutcome.AUTH_EXPIRED, manual = false))
        // đổi sang sự cố khác: báo
        assertTrue(SyncNotifyPolicy.shouldNotify(SyncOutcome.REJECTED, SyncOutcome.MERGE_CONFLICT, manual = false))
    }

    @Test fun `chay nen thanh cong binh thuong im lang`() {
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, SyncOutcome.COMMITTED, manual = false))
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.NEVER, SyncOutcome.COMMITTED, manual = false))
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, SyncOutcome.UP_TO_DATE, manual = false))
    }

    @Test fun `het su co thi bao mot lan`() {
        assertTrue(SyncNotifyPolicy.shouldNotify(SyncOutcome.AUTH_EXPIRED, SyncOutcome.COMMITTED, manual = false))
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.AUTH_EXPIRED, SyncOutcome.UP_TO_DATE, manual = false))
    }

    @Test fun `chua dang nhap va chua tung chay khong bao khi chay nen`() {
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, SyncOutcome.SIGNED_OUT, manual = false))
        assertFalse(SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, SyncOutcome.NEVER, manual = false))
    }

    @Test fun `bam dong bo ngay luon co phan hoi`() {
        SyncOutcome.entries.forEach { assertTrue("$it", SyncNotifyPolicy.shouldNotify(SyncOutcome.COMMITTED, it, manual = true)) }
    }

    @Test fun `bam tay thanh cong noi dung rieng`() {
        assertEquals("Đã đồng bộ", SyncStatusText.notification(SyncOutcome.COMMITTED, manual = true).title)
        assertEquals("Đồng bộ đã ổn định", SyncStatusText.notification(SyncOutcome.COMMITTED, manual = false).title)
    }
}
