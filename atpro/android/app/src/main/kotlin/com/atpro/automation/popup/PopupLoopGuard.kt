package com.atpro.automation.popup

/**
 * v1.4.0 — Chống vòng lặp popup (pure Kotlin, test bằng JUnit — xem `PopupLoopGuardTest.kt`).
 *
 * Vấn đề: vòng lặp farm gọi `PopupHandler.handleIfPresent()` mỗi vòng; nếu 1 popup KHÔNG
 * đóng được (nút đổi text, nhấn không ăn…) thì handler báo `handled = true` mãi và farm
 * `continue` vô hạn — kẹt cho tới khi watchdog (≥120s) can thiệp.
 *
 * Guard đếm số lần CÙNG một popup (theo [key]) được xử lý trong cửa sổ [windowMs]:
 *   • < [backAfter]          → [Verdict.PROCEED]  : xử lý bình thường.
 *   • ≥ [backAfter]          → [Verdict.TRY_BACK] : đổi cách — bấm Back thay vì tìm nút.
 *   • ≥ [giveUpAfter]        → [Verdict.GIVE_UP]  : bỏ qua popup này, để luồng recover/lost-detection xử lý.
 */
internal class PopupLoopGuard(
    private val windowMs:     Long = 30_000L,
    private val backAfter:    Int  = 3,
    private val giveUpAfter:  Int  = 6,
) {
    enum class Verdict { PROCEED, TRY_BACK, GIVE_UP }

    private val events = ArrayDeque<Pair<String, Long>>()

    /** Ghi nhận 1 lần gặp popup [key] tại thời điểm [nowMs] và trả về cách xử lý. */
    fun record(key: String, nowMs: Long): Verdict {
        while (events.isNotEmpty() && nowMs - events.first().second > windowMs) events.removeFirst()
        events.addLast(key to nowMs)
        val n = events.count { it.first == key }
        return when {
            n >= giveUpAfter -> Verdict.GIVE_UP
            n >= backAfter   -> Verdict.TRY_BACK
            else             -> Verdict.PROCEED
        }
    }

    /** Xoá lịch sử (gọi khi đã về feed an toàn). */
    fun reset() = events.clear()
}
