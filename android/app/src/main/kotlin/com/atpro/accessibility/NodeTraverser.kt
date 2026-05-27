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
     */
    fun hasNavBar(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val NAV_TEXTS = setOf(
            "for you", "dành cho bạn",
            "following", "đang follow",
            "profile", "hồ sơ", "me",
        )
        val NAV_IDS = listOf("tab_bar", "nav_bar", "bottom_nav", "navigation")

        // Check resource-id
        NAV_IDS.forEach { id ->
            if (findByResourceId(root, id) != null) return true
        }

        // Check text (cần ít nhất 2 nav items)
        val found = traverseAll(root).count { node ->
            val t = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.lowercase() ?: return@count false
            NAV_TEXTS.any { it in t }
        }
        return found >= 2
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
