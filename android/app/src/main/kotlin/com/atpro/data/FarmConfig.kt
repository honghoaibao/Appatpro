package com.atpro.data

/** v1.2.1 — Loại nhiệm vụ Golike được thực hiện trong task mode. */
enum class TaskJobType { LIKE, FOLLOW, BOTH }

/**
 * FarmConfig — cấu hình farm, load từ Room DB qua LocalRepository.loadFarmConfig()
 *
 * v2.0 — thêm:
 *   `[comment]` commentRate, commentTexts, delayAfterComment
 *   `[human]`   occasionalPauseChance
 *   `[watchdog]` watchdogTimeoutSecs
 *
 * v1.1.8 — thêm:
 *   `[like]` likeAdsEnabled — kiểm soát like quảng cáo độc lập với skipAds
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
     * Ví dụ: `["hay quá", "LIKE:LIKE:", "video đỉnh", " thích lắm"]`
     */
    val commentTexts: List<String> = emptyList(),

    /**
     * v1.2.9: Giới hạn số comment tối đa mỗi GIỜ (per account, tính theo phiên farm hiện tại).
     * 0 = không giới hạn. Khuyến nghị 20–30 theo tài liệu chống bị flag "spam behavior".
     */
    val maxCommentsPerHour: Int = 20,

    /**
     * v1.2.9: Xem bình luận (không gõ gì, chỉ mở panel comment và cuộn xem).
     * Khác với commentRate (gõ + gửi comment thật) — đây chỉ là hành vi xem thụ động.
     * Mặc định 8%, khoá cùng nhóm với các setting nhạy cảm khác (Experiment Mode).
     */
    val commentViewRate: Float = 0.08f,
    /** Số lần cuộn xem comment mỗi lần mở panel — random trong khoảng `[min, max]`. */
    val commentViewScrollMin: Int = 1,
    val commentViewScrollMax: Int = 5,

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

    /**
     * v1.2.6 — Bật tính năng chuẩn hoá display name / ID thuần số trong
     * switch popup thành @username hợp lệ. Tắt khi danh sách acc đã ổn định
     * (giảm thời gian chuẩn bị mỗi phiên).
     * Mặc định true — bật để đảm bảo acc list đầy đủ.
     */
    val normalizeEnabled: Boolean = true,

    /**
     * `v1.1.8` Cho phép like quảng cáo TikTok.
     * Chỉ có hiệu lực khi skipAds = false (tức quảng cáo không bị vuốt qua).
     * Mặc định false — không bao giờ like quảng cáo.
     */
    val likeAdsEnabled: Boolean = false,

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

    // ── Tab ghé thăm [v1.1.9+] ────────────────────────────────────────────

    /**
     * Xác suất ghé qua Hộp thư sau mỗi video (0.0 = tắt).
     * Engine click tab Hộp thư, xem `inboxViewDurationSecs` giây, rồi về feed.
     */
    val inboxViewRate: Float = 0.0f,

    /** Thời gian xem Hộp thư mỗi lần ghé (giây). */
    val inboxViewDurationSecs: Int = 15,

    /**
     * Xác suất ghé qua Cửa hàng sau mỗi video (0.0 = tắt).
     * Engine click tab Cửa hàng, cuộn `shopScrollCount` lần, rồi về feed.
     */
    val shopViewRate: Float = 0.0f,

    /** Số lần cuộn khi xem Cửa hàng. */
    val shopScrollCount: Int = 3,

    // ── Tim video theo nội dung [v1.2.0] ─────────────────────────────────

    /**
     * `v1.2.0` Bật like video khi caption chứa ít nhất 1 từ khoá trong `[captionKeywords]`.
     * Ưu tiên over likeRate — video khớp luôn được like (trừ live/ad).
     */
    val likeByCaption: Boolean = false,

    /**
     * `v1.2.0` Danh sách từ khoá tìm trong caption video (lowercase, chứa là match).
     * Ví dụ: `["review", "unboxing", "trending", "hot"]`
     */
    val captionKeywords: List<String> = emptyList(),

    /**
     * `v1.2.0` Bật like video khi video có ít nhất 1 hashtag khớp trong `[hashtagKeywords]`.
     * Ưu tiên over likeRate — video khớp luôn được like (trừ live/ad).
     */
    val likeByHashtag: Boolean = false,

    /**
     * `v1.2.0` Danh sách hashtag tìm trong video (không cần dấu #, lowercase).
     * Ví dụ: `["xuhuong", "trending", "viral", "fyp"]`
     */
    val hashtagKeywords: List<String> = emptyList(),

    // ── Tìm kiếm theo từ khoá [v1.2.0] ──────────────────────────────────

    /**
     * `v1.2.0` Bật tính năng tìm kiếm định kỳ trong quá trình farm.
     * Flow: click search → nhập keyword → click video → xem N video → back về feed.
     */
    val searchEnabled: Boolean = false,

    /**
     * `v1.2.0` Danh sách từ khoá tìm kiếm (chọn ngẫu nhiên mỗi phiên).
     */
    val searchKeywords: List<String> = emptyList(),

    /**
     * `v1.2.0` Số video xem trong mỗi phiên tìm kiếm trước khi về feed.
     */
    val searchVideosPerSession: Int = 3,

    /**
     * `v1.2.0` Xác suất thực hiện 1 phiên search sau mỗi video (0.0 = tắt).
     * Độc lập với inboxViewRate / shopViewRate.
     */
    val searchRate: Float = 0.05f,

    // ── Làm nhiệm vụ TikTok [v1.2.1] ────────────────────────────────────────

    /**
     * `v1.2.1` Loại nhiệm vụ được thực hiện trong task mode.
     * LIKE = chỉ tim, FOLLOW = chỉ follow, BOTH = cả hai (filter theo job.type).
     */
    val taskJobType: TaskJobType = TaskJobType.BOTH,

    /**
     * `v1.2.1` Số giây nuôi acc (scroll feed + tim) trước mỗi nhiệm vụ.
     * Thời gian farm đệm giúp tài khoản trông tự nhiên hơn giữa các job.
     */
    val taskFarmBeforeJobSecs: Int = 60,

    /**
     * `v1.2.1` Delay (giây) sau khi mở link nhiệm vụ trước khi thực hiện tương tác.
     * Cho phép video/profile tải đầy đủ trước khi tim/follow.
     */
    val taskJobDelaySecs: Int = 4,

    /**
     * `v1.2.1` Số job hoàn thành trên một acc trước khi chuyển sang acc tiếp theo.
     * Khi đạt đủ số này, chuyển acc kể cả còn thời gian.
     */
    val taskJobsPerAccount: Int = 5,

    /**
     * `v1.2.1` Số lần thất bại liên tiếp tối đa trước khi chuyển acc.
     * Reset về 0 mỗi khi có job thành công.
     */
    val taskMaxConsecFailures: Int = 3,

    /**
     * `v1.3.3` Nuôi acc NGẮN — số giây tối thiểu tool lướt feed bình thường
     * ngay SAU KHI hoàn thành 1 job (trước khi lấy job tiếp theo). Random đều
     * trong `[taskFarmAfterJobMinSecs, taskFarmAfterJobMaxSecs]` mỗi lần, giúp
     * hành vi trông tự nhiên hơn là báo cáo job xong rồi lấy job mới ngay lập tức.
     */
    val taskFarmAfterJobMinSecs: Int = 5,

    /** `v1.3.3` Nuôi acc NGẮN sau job — số giây tối đa. */
    val taskFarmAfterJobMaxSecs: Int = 20,

    /**
     * `v1.3.3` Nuôi acc LÂU định kỳ — cứ hoàn thành đủ số job này thì thay vì
     * nuôi ngắn (5-20s), tool nuôi acc lâu hơn hẳn (taskLongFarmDurationSecs)
     * trước khi lấy job tiếp theo. `<= 0` = tắt hẳn nuôi lâu, luôn dùng nuôi ngắn.
     */
    val taskLongFarmEveryNJobs: Int = 3,

    /** `v1.3.3` Thời lượng (giây) của phiên nuôi acc LÂU định kỳ ở trên. */
    val taskLongFarmDurationSecs: Int = 180,

    // ── Demo nuôi Facebook [v1.2.3] ─────────────────────────────────────────

    /**
     * `v1.2.3` Thời gian (giây) lướt feed Facebook trong mỗi phiên nuôi acc.
     * Flow: mở Facebook → lướt feed → ngẫu nhiên thích bài đăng → đóng app.
     */
    val facebookNurtureDurationSecs: Int = 180,

    /**
     * `v1.2.3` Xác suất thích (Like) 1 bài đăng sau mỗi lần lướt feed Facebook (0.0–1.0).
     * v1.2.9: Mặc định đổi 20% → 10% + chuyển thành setting ẩn (mở qua Experiment),
     * dùng chung cơ chế khoá với các setting nhạy cảm của TikTok.
     */
    val facebookLikeRate: Float = 0.10f,

    /**
     * v1.2.9: Khoảng thời gian "đọc bài" (giây) trước khi quyết định like/lướt tiếp —
     * thay cho delay cố định cũ, mô phỏng hành vi đọc tự nhiên hơn.
     */
    val facebookReadTimeMinSecs: Int = 8,
    val facebookReadTimeMaxSecs: Int = 25,

    /**
     * v1.3.0: Xác suất xem bình luận (thụ động, không gõ) sau khi đọc xong 1 bài đăng.
     * Cùng cơ chế với commentViewRate của TikTok nhưng riêng cho Facebook.
     */
    val facebookCommentViewRate: Float = 0.05f,

    /**
     * v1.3.0 — Tính năng xem Reels Facebook.
     * Sau mỗi lần lướt feed, có xác suất `facebookReelsViewRate` tool sẽ ghé qua
     * tab Reels, lướt trong khoảng `facebookReelsViewDurationMinSecs`..`facebookReelsViewDurationMaxSecs`
     * giây, thỉnh thoảng thích video bằng cách đúp (double-tap) vào màn hình, rồi quay lại feed.
     */
    val facebookReelsViewRate: Float = 0.05f,
    val facebookReelsViewDurationMinSecs: Int = 600,   // 10 phút
    val facebookReelsViewDurationMaxSecs: Int = 1200,  // 20 phút
    /** Xác suất đúp màn hình để thích mỗi Reel đang xem (0.0–1.0). */
    val facebookReelsLikeRate: Float = 0.20f,

    // ── Demo nuôi X (Twitter) [v1.2.4] ──────────────────────────────────────

    /** `v1.2.4` Thời gian (giây) lướt timeline X trong mỗi phiên nuôi acc. */
    val xNurtureDurationSecs: Int = 120,

    /** `v1.2.4` Xác suất like tweet khi lướt timeline X (0.0–1.0). */
    val xLikeRate: Float = 0.25f,

    /** `v1.2.4` Xác suất repost tweet khi lướt timeline X (0.0–1.0). */
    val xRetweetRate: Float = 0.05f,

    /** v1.3.0: Xác suất mở xem reply (thụ động) sau khi like/lướt qua 1 tweet. */
    val xReplyViewRate: Float = 0.05f,

    // ── Demo nuôi Instagram [v1.2.4] ────────────────────────────────────────

    /** `v1.2.4` Thời gian (giây) lướt Reels / feed Instagram trong mỗi phiên. */
    val instagramNurtureDurationSecs: Int = 180,

    /** `v1.2.4` Xác suất like bài đăng / reel trên Instagram (0.0–1.0). */
    val instagramLikeRate: Float = 0.30f,

    /** `v1.2.4` Xác suất follow tác giả reel trên Instagram (0.0–1.0). */
    val instagramFollowRate: Float = 0.08f,

    /** v1.3.0: Xác suất xem bình luận (thụ động) sau khi like/lướt qua 1 bài/reel. */
    val instagramCommentViewRate: Float = 0.05f,

    // ── Demo nuôi Threads [v1.2.4] ──────────────────────────────────────────

    /** `v1.2.4` Thời gian (giây) lướt feed Threads trong mỗi phiên nuôi acc. */
    val threadsNurtureDurationSecs: Int = 120,

    /** `v1.2.4` Xác suất like bài trên Threads (0.0–1.0). */
    val threadsLikeRate: Float = 0.20f,

    /** v1.3.0: Xác suất mở xem reply (thụ động) sau khi like/lướt qua 1 bài. */
    val threadsReplyViewRate: Float = 0.05f,

    // ── Demo nuôi Snapchat [v1.2.4] ─────────────────────────────────────────

    /** `v1.2.4` Thời gian (giây) xem Spotlight / Stories trên Snapchat. */
    val snapchatNurtureDurationSecs: Int = 90,

    /** `v1.2.4` Số giây xem mỗi Story / Spotlight trước khi swipe tiếp. */
    val snapchatStoryViewSecs: Int = 8,

    /**
     * v1.3.0: Xác suất thích (nút Like) mỗi Story/Spotlight đang xem trên Snapchat.
     * Snapchat trước đây chỉ xem (swipe qua), chưa có hành động thích.
     */
    val snapchatLikeRate: Float = 0.20f,

    // ── Thông báo Discord [v1.3.0] ──────────────────────────────────────────

    /**
     * v1.3.0: Discord Webhook URL — nếu để trống, không gửi thông báo gì.
     * Gửi khi: bắt đầu nuôi, hoàn thành/dừng phiên nuôi, lỗi nghiêm trọng.
     */
    val discordWebhookUrl: String = "",
)