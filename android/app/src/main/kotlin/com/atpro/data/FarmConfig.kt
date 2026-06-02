package com.atpro.data

/**
 * FarmConfig — cấu hình farm, load từ Room DB qua LocalRepository.loadFarmConfig()
 *
 * v2.0 — thêm:
 *   [comment] commentRate, commentTexts, delayAfterComment
 *   [human]   occasionalPauseChance
 *   [watchdog] watchdogTimeoutSecs
 *
 * Mọi field đều có default value an toàn — engine chạy ngay cả khi DB trống.
 */
data class FarmConfig(

    // ── Timing cơ bản ──────────────────────────────────────────────────────

    /** Số phút nuôi mỗi account. */
    val minutesPerAccount: Int = 5,

    /** Thời gian xem mỗi video (giây), phần nhỏ nhất. */
    val videoWatchTimeMin: Double = 3.0,

    /** Thời gian xem mỗi video (giây), phần lớn nhất. */
    val videoWatchTimeMax: Double = 8.0,

    // ── Hành động ─────────────────────────────────────────────────────────

    /** Xác suất like mỗi video (0.0–1.0). */
    val likeRate: Float = 0.30f,

    /** Xác suất follow tác giả mỗi video (0.0–1.0). */
    val followRate: Float = 0.15f,

    /**
     * `v2.0` Xác suất để lại comment mỗi video (0.0–1.0).
     * Mặc định 0 = tắt comment.
     */
    val commentRate: Float = 0.0f,

    /**
     * `v2.0` Danh sách text comment ngẫu nhiên.
     * Mỗi lần doComment() sẽ chọn ngẫu nhiên 1 text từ list này.
     * Ví dụ: ["hay quá", "LIKE:LIKE:", "video đỉnh", " thích lắm"]
     */
    val commentTexts: List<String> = emptyList(),

    /** Delay sau khi like (giây). */
    val delayAfterLike: Double = 0.5,

    /** Delay sau khi follow (giây). */
    val delayAfterFollow: Double = 1.0,

    /**
     * `v2.0` Delay sau khi comment (giây).
     * Dài hơn like/follow vì TikTok cần thời gian submit comment.
     */
    val delayAfterComment: Double = 1.5,

    /** Delay sau khi click acc trong switch popup (giây). */
    val delayAfterSwitchClick: Double = 3.0,

    // ── Hành vi ───────────────────────────────────────────────────────────

    /** Bỏ qua video live stream. */
    val skipLive: Boolean = true,

    /** Bỏ qua quảng cáo TikTok (tự động vuốt qua). */
    val skipAds: Boolean = true,

    /** Bật nghỉ giữa các acc. */
    val enableRestBetweenAccounts: Boolean = false,

    /** Số phút nghỉ giữa acc (chỉ dùng khi enableRestBetweenAccounts = true). */
    val restDurationMinutes: Int = 2,

    /** Xác nhận @username sau khi switch account. */
    val enableVerifyAccount: Boolean = true,

    /**
     * `v2.0` Xác suất dừng lại lâu (~3–9s) sau mỗi video.
     * Mô phỏng người dùng bị phân tâm — giảm pattern đều đặn.
     * Mặc định 6% (khoảng 1 lần mỗi 17 video).
     */
    val occasionalPauseChance: Float = 0.06f,

    // ── Recovery ──────────────────────────────────────────────────────────

    /** Số lần tối đa pressBack() khi bị lạc (Tier 1 recovery). */
    val maxBackAttempts: Int = 5,

    /**
     * `v2.0` Thời gian (giây) không có video mới trước khi Watchdog cảnh báo.
     * Mặc định 120s (2 phút) — đủ dài để không false-alarm khi xem video dài.
     */
    val watchdogTimeoutSecs: Int = 120,
)
