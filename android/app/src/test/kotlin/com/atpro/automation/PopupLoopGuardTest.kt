package com.atpro.automation

import com.atpro.automation.popup.PopupLoopGuard
import com.atpro.automation.popup.PopupLoopGuard.Verdict
import org.junit.Assert.*
import org.junit.Test

/** PopupLoopGuardTest — unit test cho [PopupLoopGuard] (v1.4.0). Thời gian được inject. */
class PopupLoopGuardTest {

    @Test
    fun `lan dau va lan hai xu ly binh thuong`() {
        val g = PopupLoopGuard()
        assertEquals(Verdict.PROCEED, g.record("generic", 0))
        assertEquals(Verdict.PROCEED, g.record("generic", 1_000))
    }

    @Test
    fun `lan thu ba chuyen sang bam Back`() {
        val g = PopupLoopGuard(backAfter = 3, giveUpAfter = 6)
        g.record("generic", 0); g.record("generic", 1_000)
        assertEquals(Verdict.TRY_BACK, g.record("generic", 2_000))
    }

    @Test
    fun `lan thu sau thi bo cuoc`() {
        val g = PopupLoopGuard(backAfter = 3, giveUpAfter = 6)
        var last = Verdict.PROCEED
        for (i in 0 until 6) last = g.record("generic", i * 1_000L)
        assertEquals(Verdict.GIVE_UP, last)
    }

    @Test
    fun `popup khac nhau khong cong don chung`() {
        val g = PopupLoopGuard(backAfter = 3, giveUpAfter = 6)
        g.record("a", 0); g.record("b", 100); g.record("c", 200); g.record("a", 300)
        assertEquals(Verdict.PROCEED, g.record("b", 400))
    }

    @Test
    fun `su kien ngoai cua so thoi gian bi bo qua`() {
        val g = PopupLoopGuard(windowMs = 10_000L, backAfter = 3, giveUpAfter = 6)
        g.record("generic", 0); g.record("generic", 1_000)
        // 60s sau: hai lần cũ đã hết hạn → tính như lần đầu
        assertEquals(Verdict.PROCEED, g.record("generic", 60_000))
    }

    @Test
    fun `reset xoa lich su`() {
        val g = PopupLoopGuard(backAfter = 2, giveUpAfter = 4)
        g.record("x", 0); g.reset()
        assertEquals(Verdict.PROCEED, g.record("x", 10))
    }
}
