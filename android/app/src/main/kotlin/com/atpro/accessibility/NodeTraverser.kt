package com.atpro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NodeTraverser.kt — v1.0
 * Thay thế utils/xml_parser.py
 *
 * Dùng AccessibilityNodeInfo thay vì XML string dump.
 * Tất cả phương thức là static (companion object) — dùng như XMLParser.xyz()
 */
object NodeTraverser {

    // ── Data classes ─────────────────────────────────────────────────

    data class NodeResult(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val text: String?,
        val resourceId: String?,
        val className: String?,
        val isClickable: Boolean,
        val isEnabled: Boolean,
    ) {
        val centerX: Int get() = (bounds.left + bounds.right) / 2
        val centerY: Int get() = (bounds.top + bounds.bottom) / 2
    }

    // ── Core Finders ─────────────────────────────────────────────────

    /**
     * Tìm node theo text (exact hoặc contains)
     * Tương đương: XMLParser.find_account_by_name()
     */
    fun findByText(
        root: AccessibilityNodeInfo?,
        text: String,
        exact: Boolean = false,
        ignoreCase: Boolean = true,
    ): NodeResult? {
        root ?: return null
        val query = if (ignoreCase) text.lowercase() else text

        return traverseAll(root).firstOrNull { node ->
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: return@firstOrNull false
            val t = if (ignoreCase) nodeText.lowercase() else nodeText
            if (exact) t == query else t.contains(query)
        }?.toResult()
    }

    /**
     * Tìm node theo resource-id
     * Tương đương: XMLParser dùng r'resource-id="[^"]*profile[^"]*"'
     */
    fun findByResourceId(
        root: AccessibilityNodeInfo?,
        idPart: String,
        ignoreCase: Boolean = true,
    ): NodeResult? {
        root ?: return null
        val query = if (ignoreCase) idPart.lowercase() else idPart

        return traverseAll(root).firstOrNull { node ->
            val rid = node.viewIdResourceName ?: return@firstOrNull false
            val r = if (ignoreCase) rid.lowercase() else rid
            r.contains(query)
        }?.toResult()
    }

    /**
     * Tìm tất cả node theo class (vd: EditText, TextView, Button)
     */
    fun findAllByClass(
        root: AccessibilityNodeInfo?,
        className: String,
    ): List<NodeResult> {
        root ?: return emptyList()
        return traverseAll(root)
            .filter { it.className?.toString()?.endsWith(className) == true }
            .map { it.toResult() }.toList()
    }

    /**
     * Tìm node clickable gần nhất theo vùng Y (dùng cho nav bar, account list)
     */
    fun findClickableInYRange(
        root: AccessibilityNodeInfo?,
        yMin: Int,
        yMax: Int,
    ): List<NodeResult> {
        root ?: return emptyList()
        return traverseAll(root)
            .filter { node ->
                if (!node.isClickable) return@filter false
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.centerY() in yMin..yMax
            }
            .map { it.toResult() }.toList()
    }

    // ── TikTok-Specific Detectors ─────────────────────────────────

    /**
     * Detect nav bar (For You / Following / Profile tabs)
     * Tương đương: XMLParser.has_nav_bar()
     *
     * [FIX v1.0.4] Hạ ngưỡng từ >= 2 xuống >= 1 để tránh false-negative trên
     * TikTok 2026 — một số bản chỉ expose tab đang active vào AccessibilityNodeInfo
     * (tab không active render qua icon/drawable không có text thuần).
     * Bổ sung text variants (theo dõi, khám phá, hộp thư, live) và thêm
     * resource-id patterns của TikTok/Trill 2025-2026.
     */
    fun hasNavBar(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val NAV_TEXTS = setOf(
            // EN labels
            "for you", "following", "profile", "inbox", "discover", "live", "friends",
            // VN labels — TikTok Global 2024
            "dành cho bạn", "đang follow", "đang theo dõi", "theo dõi",
            "hồ sơ", "me", "hộp thư", "khám phá", "bạn bè",
            // VN labels — TikTok 2025-2026 (previously missing → isLost() false positive)
            "trang chủ",    // Home tab
            "đề xuất",      // For You / Recommendations tab
            "cộng đồng",    // Community / Friends tab
            "đã follow",    // Following tab
        )
        val NAV_IDS = listOf(
            "tab_bar", "nav_bar", "bottom_nav", "navigation",
            "tab_home", "tab_profile", "tab_me", "tab_inbox",
            "main_tab", "bottom_tab", "tab_item",
            "tab_for_you", "tab_following", "tab_live",
        )

        // Check resource-id — bất kỳ ID tab nào cũng đủ
        NAV_IDS.forEach { id ->
            if (findByResourceId(root, id) != null) return true
        }

        // [FIX v1.0.4] Ngưỡng >= 1 thay vì >= 2.
        // TikTok 2026 đôi khi chỉ expose tab đang active trong cây accessibility,
        // các tab còn lại dùng drawable/icon không có text → found chỉ = 1.
        val found = traverseAll(root).count { node ->
            val t = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.lowercase() ?: return@count false
            NAV_TEXTS.any { it in t }
        }
        return found >= 1
    }

    /**
     * Detect popup thật (account switch / generic)
     * Tương đương: XMLParser.is_real_popup() + XMLParser.verify_popup_open()
     */
    fun detectPopup(root: AccessibilityNodeInfo?): PopupInfo {
        root ?: return PopupInfo(false, PopupType.NONE, null)

        // Account switch popup: có @username nodes
        val usernameNodes = traverseAll(root).filter { node ->
            val t = node.text?.toString() ?: return@filter false
            t.startsWith("@") && t.length > 2
        }
        if (usernameNodes.size >= 2) {
            return PopupInfo(true, PopupType.ACCOUNT_SWITCH, null)
        }

        // 1234 popup: 4 EditText
        val editTexts = findAllByClass(root, "EditText")
        if (editTexts.size == 4) {
            return PopupInfo(true, PopupType.VERIFY_1234, editTexts.first())
        }

        // Follow friends popup
        val FRIEND_KEYWORDS = listOf(
            "follow your friends", "theo dõi bạn bè",
            "people you may know", "người bạn có thể biết",
            "find friends", "tìm bạn bè",
        )
        val xmlText = getAllText(root).lowercase()
        if (FRIEND_KEYWORDS.any { it in xmlText }) {
            val dismissBtn = findDismissButton(root)
            return PopupInfo(true, PopupType.FOLLOW_FRIENDS, dismissBtn)
        }

        // Generic popup: tìm nút đóng (X / Cancel / Not now)
        val closeBtn = findCloseButton(root)
        if (closeBtn != null) {
            return PopupInfo(true, PopupType.GENERIC, closeBtn)
        }

        return PopupInfo(false, PopupType.NONE, null)
    }

    /**
     * Tìm nút Profile trong nav bar
     * Tương đương: XMLParser.find_profile_tab()
     *
     * Hỗ trợ TikTok Trill (com.ss.android.ugc.trill) render tab bằng resource-id
     * và contentDescription thay vì text thuần.
     */
    fun findProfileTab(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null

        val PROFILE_IDS = listOf(
            "profile", "me", "personal",
            // Trill-specific resource-id patterns
            "tab_profile", "profile_tab", "tab_me",
            "main_tab_profile", "bottom_tab_profile",
        )
        val PROFILE_TEXTS = listOf("hồ sơ", "profile", "me")
        val PROFILE_CD_KEYWORDS = listOf("profile tab", "hồ sơ, tab", "me tab")

        // 1. Tìm theo resource-id
        PROFILE_IDS.forEach { id ->
            findByResourceId(root, id)?.let { return it }
        }

        // 2. Tìm theo text
        PROFILE_TEXTS.forEach { text ->
            findByText(root, text, exact = false)?.let { return it }
        }

        // 3. Fallback: tìm theo contentDescription (Trill)
        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
            PROFILE_CD_KEYWORDS.any { cd.contains(it) }
        }?.toResult()
    }

    /**
     * Tìm nút Home/Feed tab trong nav bar.
     * Dùng để điều hướng VỀ feed sau khi đã click sang Profile tab,
     * thay cho pressBack() (pressBack trên tab chính TikTok sẽ thoát app).
     */
    fun findHomeTab(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null

        val HOME_IDS = listOf(
            "home", "tab_home", "home_tab",
            "main_tab_home", "tab_for_you", "tab_feed",
            "bottom_tab_home",
        )
        val HOME_TEXTS   = listOf("for you", "dành cho bạn", "home", "trang chủ")
        val HOME_CD_KEYS = listOf("home tab", "for you tab", "trang chủ, tab")

        // 1. resource-id
        HOME_IDS.forEach { id ->
            findByResourceId(root, id)?.let { return it }
        }
        // 2. text
        HOME_TEXTS.forEach { text ->
            findByText(root, text, exact = false)?.let { return it }
        }
        // 3. contentDescription (Trill)
        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
            HOME_CD_KEYS.any { cd.contains(it) }
        }?.toResult()
    }

    /**
     * Kiểm tra đang ở màn hình Feed (Trang chủ) — v1.0.8 3 chiến lược xếp tầng.
     *
     * Chiến lược 1 — Broad isSelected scan (chính xác nhất):
     *   Quét TOÀN BỘ node trong cây. Bất kỳ node nào có text/contentDescription khớp
     *   từ khóa feed VÀ isSelected == true → đang ở feed tab.
     *   Rộng hơn cách cũ (chỉ tìm homeTab theo ID) — bắt được mọi biến thể tab name.
     *
     * Chiến lược 2 — Nav keyword count (dự phòng khi isSelected không khả dụng):
     *   Đếm số từ khóa nav bar phân biệt xuất hiện trên màn hình (>= 2).
     *   Tránh false positive từ popup chứa 1 từ trùng.
     *
     * Chiến lược 3 — Video interaction fallback (khi nav bar chưa render):
     *   Nếu layout tương tác video (like_layout, comment_layout, share_layout) hiển thị
     *   → chắc chắn đang ở feed, kể cả khi nav bar chưa kịp render xong sau animation.
     *
     * Dùng thay cho isLost() (= !hasNavBar) trong farmOneAccount() và waitFeedLoad().
     */
    fun isOnFeedTab(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        val allNodes = traverseAll(root)

        // ── Chiến lược 1: Broad isSelected scan ───────────────────────
        // Quét toàn bộ — không giới hạn chỉ homeTab — bắt được mọi biến thể UI/locale.
        val FEED_TAB_KEYWORDS = listOf(
            // EN
            "home", "for you", "following", "friends",
            // VN — TikTok Global 2024-2026
            "trang chủ", "dành cho bạn", "đề xuất", "đã follow", "bạn bè", "cộng đồng",
        )
        val hasSelectedFeedTab = allNodes.any { node ->
            if (!node.isSelected) return@any false
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.lowercase() ?: return@any false
            FEED_TAB_KEYWORDS.any { label.contains(it) }
        }
        if (hasSelectedFeedTab) return true

        // ── Chiến lược 2: Nav keyword count >= 2 ─────────────────────
        // Không dùng isSelected — bắt trường hợp tree không expose trạng thái selected.
        val NAV_KEYWORDS = setOf(
            "trang chủ", "đề xuất", "cộng đồng", "đã follow",
            "bạn bè", "hộp thư", "hồ sơ",
            "for you", "following", "inbox", "profile", "friends",
        )
        val allText = allNodes.mapNotNull {
            (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase()
        }
        val matched = NAV_KEYWORDS.count { kw -> allText.any { it.contains(kw) } }
        if (matched >= 2) return true

        // ── Chiến lược 3: Video interaction layout fallback ───────────
        // Nav bar đôi khi chưa render ngay sau swipe animation, nhưng nếu
        // layout Like/Comment/Share đã xuất hiện → chắc chắn đang ở feed.
        // Dùng layout container ID (thực tế TikTok APK) thay vì icon button ID.
        val VIDEO_LAYOUT_IDS = listOf(
            // TikTok APK layout containers — phổ biến nhất
            "like_layout", "comment_layout", "share_layout",
            // Fallback icon-level IDs (Trill / bản quốc tế cũ)
            "btn_like", "iv_like", "like_button",
            "btn_comment", "iv_comment", "comment_btn",
        )
        if (VIDEO_LAYOUT_IDS.any { findByResourceId(root, it) != null }) return true

        val VIDEO_TEXTS = listOf("thích", "bình luận", "chia sẻ", "like", "comment", "share")
        val videoTextCount = VIDEO_TEXTS.count { kw -> allText.any { it.contains(kw) } }
        if (videoTextCount >= 2) return true

        return false
    }

    /**
     * Lấy username hiện tại từ profile page
     * Tương đương: XMLParser.get_current_account_id()
     */
    fun getCurrentAccountId(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        val USERNAME_IDS = listOf("user_id", "username", "unique_id", "profile_id")
        USERNAME_IDS.forEach { id ->
            findByResourceId(root, id)?.let { node ->
                val t = node.text?.toString() ?: return@let
                if (t.startsWith("@")) return t.trimStart('@')
            }
        }
        // Fallback: tìm text bắt đầu bằng @
        return traverseAll(root)
            .firstOrNull { it.text?.toString()?.startsWith("@") == true }
            ?.text?.toString()?.trimStart('@')
    }

    /**
     * Parse danh sách accounts từ popup switch — v1.0.8 tăng độ bao phủ.
     *
     * Pass 1 — "@username" (TikTok Global):
     *   Ưu tiên text/contentDescription bắt đầu bằng "@".
     *   Chuẩn hóa: trim + strip spaces (bắt trường hợp TikTok render "@  user  name").
     *
     * Pass 2 — username thuần (TikTok Trill / bản không hiển thị "@"):
     *   Regex `^[a-zA-Z0-9_.]{3,24}$` trên cả text lẫn contentDescription.
     *   Loại bỏ UI label phổ biến và từ khóa sai như "tiktok".
     *
     * Kết quả luôn distinct, thứ tự giữ nguyên theo thứ tự xuất hiện trên màn hình.
     */
    fun parseAccountList(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()

        val UI_LABELS = setOf(
            "thêm tài khoản", "chuyển đổi tài khoản", "đăng nhập",
            "add account", "switch account", "log in", "login",
        )
        // Từ khóa ngắn dễ bị nhầm là username
        val FALSE_POSITIVES = setOf("tiktok", "app", "ok", "yes", "no")

        val accounts = mutableListOf<String>()
        val seen     = mutableSetOf<String>()

        val allRawTexts = traverseAll(root).mapNotNull { node ->
            node.text?.toString() ?: node.contentDescription?.toString()
        }

        // ── Pass 1: "@username" — chuẩn hóa bằng trim + strip spaces ───
        val withAt = allRawTexts.mapNotNull { raw ->
            val clean = raw.trim().replace(" ", "")
            if (clean.startsWith("@") && clean.length > 2) clean.removePrefix("@") else null
        }
        if (withAt.isNotEmpty()) {
            withAt.forEach { u -> if (seen.add(u.lowercase())) accounts.add(u) }
            return accounts
        }

        // ── Pass 2: Fallback — username không có "@" (TikTok Trill v.v.) ──
        val usernameRegex = Regex("^[a-zA-Z0-9_.]{3,24}$")
        allRawTexts.forEach { raw ->
            val clean = raw.trim().replace(" ", "")
            if (usernameRegex.matches(clean)
                && clean.lowercase() !in UI_LABELS
                && clean.lowercase() !in FALSE_POSITIVES
            ) {
                if (seen.add(clean.lowercase())) accounts.add(clean)
            }
        }
        return accounts
    }

    /**
     * Detect Live stream
     * Tương đương: TikTokAutomation.detect_live()
     */
    fun detectLive(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val LIVE_IDS = listOf("liveroom", "live_room", "live_gift", "live_rank", "live_chat")
        LIVE_IDS.forEach { id ->
            if (findByResourceId(root, id) != null) return true
        }
        val text = getAllText(root).lowercase()
        val LIVE_SIGNALS = listOf("liveroom", "đang trực tiếp", "đang phát trực tiếp", ":id/live")
        return LIVE_SIGNALS.count { it in text } >= 2
    }

    /**
     * Detect checkpoint / bị khóa
     * Tương đương: XMLParser.detect_checkpoint()
     */
    fun detectCheckpoint(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val CHECKPOINT_KEYWORDS = listOf(
            "suspicious activity", "hoạt động bất thường",
            "verify your account", "xác minh tài khoản",
            "unusual activity", "account suspended",
            "tài khoản bị khóa",
        )
        val text = getAllText(root).lowercase()
        return CHECKPOINT_KEYWORDS.any { it in text }
    }

    /**
     * Tự động quét và lấy TikTok ID khi đang ở màn hình Profile (Trang cá nhân).
     * Không cần biết trước tên nick, tự động bóc tách từ UI.
     *
     * v1.0.8 3 tầng ưu tiên — tránh bốc nhầm nút UI hoặc số liệu thống kê:
     *
     * Pass 1 — Dấu @ (chuẩn xác nhất):
     *   TikTok thường render ID dạng "@username". Nếu có node nào bắt đầu bằng "@",
     *   bóc ngay — không cần fallback.
     *
     * Pass 2 — Resource-ID username:
     *   Tìm node có resource-id chứa "username / unique_id / profile_id".
     *   Đây là node TikTok đặt tên rõ ràng → tin cậy cao, dùng trước Regex.
     *
     * Pass 3 — Regex scan với bộ lọc 4 tầng:
     *   (a) Blacklist UI button: Follow, Message, Share, Like, Edit... (tên nút viết liền)
     *   (b) Stat regex: loại chuỗi dạng số thống kê (150K, 10.5M, 2400, 1.2B)
     *   (c) Bắt buộc có ít nhất 1 chữ cái (username không thể là số thuần)
     *   (d) Blacklist từ hệ thống TikTok (tiktok, live, app...)
     */
    fun getTikTokIdFromProfile(profileRoot: AccessibilityNodeInfo?): String? {
        profileRoot ?: return null

        // ── Pass 1: Ưu tiên node có dấu @ ────────────────────────────────
        val withAt = traverseAll(profileRoot).mapNotNull { node ->
            val raw = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            val clean = raw.trim().replace(" ", "")
            if (clean.startsWith("@") && clean.length > 2) clean.removePrefix("@") else null
        }
        if (withAt.isNotEmpty()) return withAt.first()

        // ── Pass 2: Tìm theo resource-id liên quan username ──────────────
        val USERNAME_RESOURCE_IDS = listOf(
            "user_id", "username", "unique_id", "profile_id", "tiktok_id",
        )
        USERNAME_RESOURCE_IDS.forEach { id ->
            findByResourceId(profileRoot, id)?.let { node ->
                val t = node.text?.toString()?.trim()?.replace(" ", "") ?: return@let
                val clean = t.removePrefix("@")
                if (clean.matches(Regex("^[a-zA-Z0-9_.]{3,24}$"))) return clean
            }
        }

        // ── Pass 3: Regex scan với bộ lọc đa tầng ────────────────────────

        // (a) Blacklist các label nút UI và chữ hệ thống phổ biến trên màn hình Profile.
        //     Bao gồm cả dạng ghép sau khi strip space ("Edit profile" → "Editprofile").
        val UI_BLACKLIST = setOf(
            // Nút hành động
            "follow", "following", "followers", "unfollow",
            "message", "share", "like", "likes",
            "edit", "editprofile",              // "Edit profile" → strip space
            "report", "block", "mute",
            // Label thống kê & tiêu đề khu vực
            "videos", "video", "posts", "post",
            "profile", "bio", "link", "links",
            // Hệ thống TikTok
            "tiktok", "live", "app",
        )

        // (b) Stat regex: số thuần hoặc số + đơn vị K/M/B (150K, 10.5M, 2400, 1.2B).
        //     Dấu chấm "." nằm trong charset của username regex nên PHẢI lọc riêng.
        val STAT_REGEX = Regex("^[0-9]+([.,][0-9]+)?[KMBkmb]?$")

        val usernameRegex = Regex("^[a-zA-Z0-9_.]{3,24}$")

        return traverseAll(profileRoot)
            .mapNotNull { node ->
                val raw = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                raw.trim().replace(" ", "").takeIf { it.isNotBlank() }
            }
            .firstOrNull { text ->
                usernameRegex.matches(text)               // (0) đúng định dạng username
                    && text.lowercase() !in UI_BLACKLIST  // (a) không phải nút UI
                    && !STAT_REGEX.matches(text)           // (b) không phải số thống kê
                    && text.any { it.isLetter() }          // (c) phải có ít nhất 1 chữ cái
            }
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * v1.0.8 Debug helper: dump text/contentDescription của tất cả nodes hiển thị.
     * Dùng trong AutomationEngine để log khi isLostWithRetry() phát hiện lạc,
     * giúp phân tích xem nav bar đang hiển thị keyword nào bị NodeTraverser bỏ sót.
     */
    fun dumpScreenTexts(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        return traverseAll(root)
            .mapNotNull { node ->
                (node.text?.toString() ?: node.contentDescription?.toString())
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= 60 }
            }
            .distinct()
    }

    private fun traverseAll(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue  = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun AccessibilityNodeInfo.toResult(): NodeResult {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return NodeResult(
            node        = this,
            bounds      = bounds,
            text        = text?.toString(),
            resourceId  = viewIdResourceName,
            className   = className?.toString(),
            isClickable = isClickable,
            isEnabled   = isEnabled,
        )
    }

    private fun getAllText(root: AccessibilityNodeInfo): String =
        traverseAll(root)
            .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .joinToString(" ")

    private fun findCloseButton(root: AccessibilityNodeInfo): NodeResult? {
        val CLOSE_TEXTS = listOf("x", "close", "đóng", "not now", "để sau", "cancel", "hủy", "skip", "bỏ qua")
        return traverseAll(root).firstOrNull { node ->
            val t = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()
                ?: return@firstOrNull false
            CLOSE_TEXTS.any { t == it || t.contains(it) }
        }?.toResult()
    }

    private fun findDismissButton(root: AccessibilityNodeInfo): NodeResult? =
        findCloseButton(root)

    // ── Data types ────────────────────────────────────────────────

    enum class PopupType { NONE, ACCOUNT_SWITCH, VERIFY_1234, FOLLOW_FRIENDS, GENERIC }

    data class PopupInfo(
        val detected: Boolean,
        val type: PopupType,
        val dismissButton: NodeResult?,
    )
}
