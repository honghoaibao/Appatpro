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
     * Kiểm tra đang ở màn hình Feed (Trang chủ) bằng 2 chiến lược:
     *
     * Chiến lược 1 — isSelected trên Home tab (chính xác nhất):
     *   Quét Bottom TabBar, tìm tab Home/Trang chủ, kiểm tra isSelected == true.
     *   Đây là dấu hiệu TikTok đang active trên Feed tab.
     *
     * Chiến lược 2 — keyword fallback (dự phòng khi isSelected không khả dụng):
     *   Tìm bất kỳ từ khóa feed nào trong cây accessibility:
     *   "cộng đồng", "đã follow", "đề xuất", "bạn bè", "trang chủ", "hộp thư", "hồ sơ"
     *   (ít nhất 2 từ → tránh false positive từ popup có 1 từ trùng).
     *
     * Trả về true nếu một trong hai chiến lược match.
     * Dùng thay cho isLost() (= !hasNavBar) trong farmOneAccount() và waitFeedLoad().
     */
    fun isOnFeedTab(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // ── Chiến lược 1: Home tab isSelected ─────────────────────────
        val HOME_TEXTS = listOf(
            "for you", "dành cho bạn", "home", "trang chủ", "đề xuất",
        )
        val HOME_IDS = listOf(
            "home", "tab_home", "home_tab", "main_tab_home",
            "tab_for_you", "tab_feed", "bottom_tab_home",
        )

        // Tìm Home tab node theo resource-id hoặc text
        val homeTabNode: AccessibilityNodeInfo? = run {
            // resource-id trước
            for (id in HOME_IDS) {
                val found = findByResourceId(root, id)
                if (found != null) return@run found.node
            }
            // text
            for (text in HOME_TEXTS) {
                val found = findByText(root, text, exact = false)
                if (found != null) return@run found.node
            }
            // contentDescription
            traverseAll(root).firstOrNull { node ->
                val cd = node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
                HOME_TEXTS.any { cd.contains(it) }
            }
        }

        if (homeTabNode != null && homeTabNode.isSelected) return true

        // ── Chiến lược 2: keyword presence (>= 2 từ khóa feed) ───────
        val FEED_KEYWORDS = setOf(
            "trang chủ", "đề xuất", "cộng đồng", "đã follow",
            "bạn bè", "hộp thư", "hồ sơ",
            "for you", "following", "inbox", "profile", "friends",
        )
        val allText = traverseAll(root)
            .mapNotNull { (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase() }

        val matched = FEED_KEYWORDS.count { kw -> allText.any { it.contains(kw) } }
        return matched >= 2
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
     * Parse danh sách accounts từ popup switch
     * Tương đương: XMLParser.parse_all_usernames()
     *
     * Strategy:
     *  1. Ưu tiên nodes có text bắt đầu "@" (TikTok Global) — trim "@" trước khi return.
     *  2. Nếu không có node nào như vậy (TikTok Trill không hiển thị "@"):
     *     fallback lấy text ngắn 3–30 ký tự, không space, chỉ [a-zA-Z0-9_.],
     *     loại bỏ UI label phổ biến.
     *  3. Kết quả luôn `.distinct()`.
     */
    fun parseAccountList(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()

        val UI_LABELS = setOf(
            "thêm tài khoản", "chuyển đổi tài khoản", "đăng nhập",
            "add account", "switch account", "log in", "login",
        )

        val allTexts = traverseAll(root).mapNotNull { it.text?.toString() }

        // Pass 1 — nodes có "@"
        val withAt = allTexts
            .filter { it.startsWith("@") && it.length > 2 }
            .map { it.trimStart('@') }

        if (withAt.isNotEmpty()) return withAt.distinct()

        // Pass 2 — fallback cho TikTok Trill (không có "@")
        val usernameRegex = Regex("^[a-zA-Z0-9_.]{3,30}$")
        return allTexts
            .filter { text ->
                usernameRegex.matches(text) &&
                    text.lowercase() !in UI_LABELS
            }
            .distinct()
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

    // ── Private Helpers ───────────────────────────────────────────

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
