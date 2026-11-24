package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncFailureSummaryTest — v1.12.7: chi tiết lỗi đồng bộ ngắn, đã làm sạch, không lộ token. */
class SyncFailureSummaryTest {

    @Test fun `loi may chu hien ma va http`() {
        assertEquals(
            "Mã lỗi: remote_snapshot_invalid (HTTP 400)",
            SyncFailureSummary.remote("remote_snapshot_invalid", 400),
        )
        assertEquals("Mã lỗi: snapshot_merge_invalid", SyncFailureSummary.remote("snapshot_merge_invalid", null))
    }

    @Test fun `ma loi dai van duoc giu nguyen`() {
        val code = "remote_revision_behind_local_checkpoint"
        assertTrue(SyncFailureSummary.remote(code, null).contains(code))
    }

    @Test fun `ngoai le hien ten lop va thong diep`() {
        val text = SyncFailureSummary.exception(IllegalStateException("Account changed locally"))
        assertEquals("IllegalStateException: Account changed locally", text)
    }

    @Test fun `ngoai le khong co thong diep chi hien ten lop`() {
        assertEquals("IllegalArgumentException", SyncFailureSummary.exception(IllegalArgumentException()))
    }

    @Test fun `che token va bearer`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEF123456"
        val text = SyncFailureSummary.exception(RuntimeException("401 Authorization: Bearer $jwt failed"))
        assertFalse(text.contains("eyJ"))
        assertFalse(text.contains(jwt))
        assertTrue(text.contains("***"))
    }

    @Test fun `cat do dai va bo ky tu dieu khien`() {
        val text = SyncFailureSummary.reason("a\nb\u0000" + "x ".repeat(500))
        assertTrue(text.length <= SyncFailureSummary.MAX_LENGTH)
        assertFalse(text.any(Char::isISOControl))
    }
}
