package com.atpro.automation

import kotlin.random.Random

/**
 * v1.4.0 — Thuật toán hành vi nuôi acc (pure Kotlin, không phụ thuộc Android/coroutine
 * để test trực tiếp bằng JUnit — xem `FarmBehaviorTest.kt`).
 *
 * Vấn đề của bản cũ (`Random.nextFloat() < rate` độc lập từng video):
 *   1. Phương sai lớn: phiên 5 phút ≈ 30 video, likeRate 30% → số like dao động
 *      ±2,5 quanh 9 → tỉ lệ thực tế lệch xa cấu hình, có thể "cụm" nhiều lần liên tiếp.
 *   2. Quyết định like không liên quan gì đến thời gian xem video.
 *   3. Live/quảng cáo vẫn "ăn" một lần tung xúc xắc rồi mới bị loại → lệch tỉ lệ.
 *
 * Bản này giữ nguyên ý nghĩa các thông số cấu hình (likeRate, followRate, …) nhưng
 * làm cho tỉ lệ THỰC TẾ bám sát cấu hình hơn (xem [RateGate]) và gắn quyết định
 * với mức quan tâm theo thời gian xem ([WatchPlan]).
 */
internal object FarmBehavior {

    /**
     * Hệ số nhịp theo tiến độ phiên (0.0 = vừa bắt đầu, 1.0 = sắp hết):
     * 0.9 → 1.1, trung bình đúng 1.0 → tổng thời gian xem trung bình không đổi so
     * với cấu hình, chỉ là đầu phiên lướt nhanh hơn một chút, cuối phiên chậm hơn.
     */
    fun paceFactor(progress: Double): Double = 0.9 + 0.2 * progress.coerceIn(0.0, 1.0)

    /** Kết quả lập kế hoạch xem 1 video. */
    data class WatchPlan(
        /** Thời gian xem (ms) — đã áp [paceFactor], không nhỏ hơn mức tối thiểu cấu hình. */
        val ms: Long,
        /** Mức quan tâm 0.0–1.0: vị trí của lần bốc ngẫu nhiên trong khoảng [min, max]. */
        val interest: Double,
    )

    /**
     * Bốc thời gian xem trong [minSec, maxSec] (đều, như cấu hình) + mức quan tâm.
     * `interest` tính từ lần bốc GỐC (trước khi nhân nhịp) để giữ phân bố đều 0–1.
     */
    fun watchPlan(
        minSec:   Double,
        maxSec:   Double,
        progress: Double,
        rng:      Random = Random.Default,
    ): WatchPlan {
        val minMs = (minSec * 1_000).toLong().coerceAtLeast(500L)
        val maxMs = (maxSec * 1_000).toLong()
        if (maxMs <= minMs) {
            return WatchPlan((minMs * paceFactor(progress)).toLong().coerceAtLeast(minMs), 0.5)
        }
        val raw      = rng.nextLong(minMs, maxMs)
        val interest = (raw - minMs).toDouble() / (maxMs - minMs).toDouble()
        val ms       = (raw * paceFactor(progress)).toLong().coerceAtLeast(minMs)
        return WatchPlan(ms, interest.coerceIn(0.0, 1.0))
    }

    /**
     * Trọng số hành động (like/follow) theo mức quan tâm: 0.6 → 1.4.
     * `interest` phân bố đều 0–1 → trung bình đúng 1.0 → không làm lệch tỉ lệ cấu hình.
     */
    fun interestWeight(interest: Double): Double = 0.6 + 0.8 * interest.coerceIn(0.0, 1.0)
}

/**
 * Bộ điều tiết tỉ lệ hành động — thay cho `Random.nextFloat() < rate`.
 *
 * Xác suất mỗi lần = `rate × weight + correction × (rate − tỉ lệ thực tế trong cửa sổ gần nhất)`.
 * Số hạng thứ hai kéo tỉ lệ thực tế về đúng mức cấu hình (như bộ điều khiển P), nên:
 *   • tỉ lệ dài hạn bám sát `rate`;
 *   • phương sai trong phiên ngắn thấp hơn rõ rệt so với tung xúc xắc độc lập;
 *   • có trần chuỗi liên tiếp ([maxStreak]) cho rate < 0.6 → không "tim liền 6 video".
 *
 * Mỗi phiên/acc tạo 1 instance riêng; mỗi loại hành động (like/follow/…) 1 instance.
 * `rate <= 0` → luôn false, `rate >= 1` → luôn true (giữ nguyên ngữ nghĩa cũ).
 */
internal class RateGate(
    private val window:     Int    = 24,
    private val correction: Double = 0.6,
    private val maxStreak:  Int    = 4,
    private val rng:        Random = Random.Default,
) {
    private val history = ArrayDeque<Boolean>()
    private var streak  = 0

    /** Tỉ lệ thực tế trong cửa sổ hiện tại (null nếu chưa đủ mẫu). */
    val observedRate: Double?
        get() = if (history.size >= MIN_SAMPLES) history.count { it }.toDouble() / history.size else null

    fun decide(rate: Float, weight: Double = 1.0): Boolean {
        val base = rate.toDouble().coerceIn(0.0, 1.0)
        val result = when {
            base <= 0.0 -> false
            base >= 1.0 -> true
            else -> {
                val observed = observedRate ?: base
                val p = (base * weight + correction * (base - observed)).coerceIn(0.0, MAX_P)
                val capped = base < STREAK_CAP_BELOW && streak >= maxStreak
                !capped && rng.nextDouble() < p
            }
        }
        history.addLast(result)
        if (history.size > window) history.removeFirst()
        streak = if (result) streak + 1 else 0
        return result
    }

    private companion object {
        const val MIN_SAMPLES       = 6
        const val MAX_P             = 0.95
        const val STREAK_CAP_BELOW  = 0.6
    }
}
