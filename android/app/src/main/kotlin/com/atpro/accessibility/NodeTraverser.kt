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
     * `FIX v1.0.4` Hạ ngưỡng từ >= 2 xuống >= 1 để tránh false-negative trên
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
        // [FIX v1.0.9] Thêm context check — phân biệt switch popup thật vs feed có @username.
        // Feed (video description, duet tag...) cũng có >= 2 "@username" → false positive.
        // Chỉ xác nhận là switch popup khi CÓ switch container RÕ RÀNG và KHÔNG đang ở feed.
        val usernameNodes = traverseAll(root).filter { node ->
            val t = node.text?.toString() ?: return@filter false
            t.startsWith("@") && t.length > 2
        }
        if (usernameNodes.size >= 2) {
            val hasSwitchContainer =
                findByResourceId(root, "switch_account") != null
                    || findByResourceId(root, "account_list") != null
                    || findByResourceId(root, "user_list") != null
                    || findByText(root, "chuyển đổi tài khoản", ignoreCase = true) != null
                    || findByText(root, "switch account", ignoreCase = true) != null

            val onFeed = isOnFeedTab(root)  // đang ở feed → KHÔNG phải switch popup

            if (hasSwitchContainer && !onFeed) {
                return PopupInfo(true, PopupType.ACCOUNT_SWITCH, null)
            }
        }

        // 4 EditText — có thể là VERIFY_1234 (xác minh tài khoản) hoặc DAILY_LIMIT (giới hạn thời gian).
        // [v1.1.8] Kiểm tra daily limit signals TRƯỚC khi kết luận là VERIFY_1234.
        // Lý do: cả hai loại đều có 4 EditText → phân biệt bằng text context trên màn hình.
        val editTexts = findAllByClass(root, "EditText")
        if (editTexts.size == 4) {
            val screenText = getAllText(root).lowercase()
            return if (DAILY_LIMIT_SIGNALS.any { it in screenText }) {
                PopupInfo(true, PopupType.DAILY_LIMIT, editTexts.first())
            } else {
                PopupInfo(true, PopupType.VERIFY_1234, editTexts.first())
            }
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

        // [v1.1.8] Follow suggestion popup — TikTok gợi ý follow một account trong feed.
        // Nhận ra bằng nút "Không quan tâm" / "Not interested" (luôn xuất hiện cặp với "Follow").
        // Phát hiện trước GENERIC để có action chính xác (click dismiss thay vì generic back).
        val khongQuanTamNode = findByText(root, "không quan tâm", ignoreCase = true)
            ?: findByText(root, "not interested", ignoreCase = true)
        if (khongQuanTamNode != null) {
            return PopupInfo(true, PopupType.FOLLOW_SUGGEST, khongQuanTamNode)
        }

        // Generic popup: tìm nút đóng (X / Cancel / Not now)
        // [FIX-GENERIC-FEED] Nút X/close trên feed (gợi ý follow, notification overlay...)
        // bị nhận nhầm là generic popup → click sai → continue loop → overlay đứng yên.
        // Guard giống ACCOUNT_SWITCH (v1.0.9): chỉ báo GENERIC khi KHÔNG ở feed.
        val closeBtn = findCloseButton(root)
        if (closeBtn != null && !isOnFeedTab(root)) {
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
     * Tìm tab Hộp thư trong nav bar — dùng cho doViewInbox() v1.1.9+.
     */
    fun findInboxTab(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val IDS   = listOf("inbox", "tab_inbox", "inbox_tab", "main_tab_inbox",
                           "bottom_tab_inbox", "notification", "tab_notification")
        val TEXTS = listOf("hộp thư", "inbox", "messages", "notifications")
        val CDS   = listOf("hộp thư, tab", "inbox tab", "messages tab", "notification tab")

        IDS.forEach   { findByResourceId(root, it)?.let { r -> return r } }
        TEXTS.forEach { findByText(root, it, exact = false)?.let { r -> return r } }
        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
            CDS.any { cd.contains(it) }
        }?.toResult()
    }

    /**
     * Tìm tab Cửa hàng trong nav bar — dùng cho doViewShop() v1.1.9+.
     */
    fun findShopTab(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val IDS   = listOf("shop", "tab_shop", "shop_tab", "main_tab_shop",
                           "bottom_tab_shop", "store", "tab_store")
        val TEXTS = listOf("cửa hàng", "shop", "store")
        val CDS   = listOf("cửa hàng, tab", "shop tab", "store tab")

        IDS.forEach   { findByResourceId(root, it)?.let { r -> return r } }
        TEXTS.forEach { findByText(root, it, exact = false)?.let { r -> return r } }
        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
            CDS.any { cd.contains(it) }
        }?.toResult()
    }

    /**
     * Kiểm tra đang ở màn hình Feed (Trang chủ) — v1.0.8 3 chiến lược xếp tầng.
     *
     * v1.2.1: Thêm Tier 0 live-room exclusion:
     * Live room TikTok có thể chứa keyword feed ("bạn bè", "đề xuất" từ live
     * recommendation strip) → các Tier 1/2 bên dưới trả true nhầm.
     * Kiểm tra nhanh một số resource-ID đặc trưng của live room để loại trừ sớm.
     *
     * Chiến lược 1 — Broad isSelected scan (chính xác nhất):
     *   Quét TOÀN BỘ node trong cây. Bất kỳ node nào có text/contentDescription khớp
     *   từ khóa feed VÀ isSelected == true → đang ở feed tab.
     *
     * Chiến lược 2 — Nav keyword count (dự phòng khi isSelected không khả dụng):
     *   Đếm số từ khóa nav bar phân biệt xuất hiện trên màn hình (>= 2).
     *
     * Chiến lược 3 — Video interaction layout fallback (khi nav bar chưa render):
     *   Nếu layout Like/Comment/Share hiển thị → đang ở feed.
     */
    fun isOnFeedTab(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // ── Tier 0a: Live-in-feed card ────────────────────────────────────
        // Thẻ Live preview TOÀN MÀN HÌNH trong feed.
        // Phải kiểm tra TRƯỚC Tier 0b vì feed card có thể có view ID chứa "live".
        // CHỈ dùng cụm từ xuất hiện trên nút lớn của thẻ full-screen live card.
        // KHÔNG dùng "tap to watch" vì cũng xuất hiện trên LIVE badge nhỏ của video thường.
        val allNodesEarly = traverseAll(root)
        val allTextEarly  = allNodesEarly.mapNotNull {
            (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase()
        }
        val LIVE_CARD_TEXTS = listOf(
            "nhấn để xem live",    // VI — chỉ xuất hiện trên thẻ full-screen
            "tap to watch live",   // EN — full phrase, không bị nhầm với badge
            "tap to join live",    // EN — alternative phrasing
        )
        // Thêm guard: thẻ live toàn màn hình KHÔNG có nút like/share/comment
        val hasInteractionButtons = allNodesEarly.any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            id.contains("like_btn") || id.contains("comment_btn") ||
            id.contains("share_btn") || id.contains("digg_btn") ||
            id.contains("like_count") || id.contains("comment_count")
        }
        val hasLiveCardText = LIVE_CARD_TEXTS.any { kw -> allTextEarly.any { it.contains(kw) } }
        if (hasLiveCardText && !hasInteractionButtons) return true

        // ── Tier 0b: Live-room exclusion ──────────────────────────────────
        // Kiểm tra nhanh resource-ID đặc trưng của live room.
        // Live room hay có text "bạn bè"/"đề xuất" từ recommendation strip
        // → Tier 1/2 dễ false-positive nếu không loại trừ trước.
        // Dùng subset nhỏ của GIFT_IDS/LIVE_IDS — đủ phân biệt, không IPC tốn kém.
        val LIVE_EXCL_IDS = listOf(
            "liveroom", "live_room", "gift_entrance", "gift_btn", "send_gift_btn",
            "live_chat", "live_anchor", "live_host", "live_viewer_count",
            "audience_tab", "pk_battle",
        )
        if (LIVE_EXCL_IDS.any { findByResourceId(root, it) != null }) return false

        // Bổ sung: quét viewIdResourceName cho live fragment
        val hasLiveFragment = traverseAll(root).any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: return@any false
            id.contains("liveroom") || id.contains("/gift_") || id.contains("live_room")
        }
        if (hasLiveFragment) return false

        val allNodes = traverseAll(root)

        // ── Chiến lược 1: Broad isSelected scan ───────────────────────
        val FEED_TAB_KEYWORDS = listOf(
            "home", "for you", "following", "friends",
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
        val VIDEO_LAYOUT_IDS = listOf(
            "like_layout", "comment_layout", "share_layout",
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
     * Phát hiện thẻ Live TOÀN MÀN HÌNH trong feed (preview trước khi vào live room).
     *
     * Khác với LIVE badge nhỏ trên video thường:
     *   - Thẻ full-screen: "Nhấn để xem LIVE" lớn + KHÔNG có nút like/share/comment
     *   - LIVE badge: text nhỏ bên cạnh video thường CÓ nút like/share/comment
     *
     * Guard kép = tránh false positive trên video thường có LIVE badge.
     */
    fun isLiveCardInFeed(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val all   = traverseAll(root)
        val texts = all.mapNotNull {
            (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase()
        }
        val LIVE_CARD_TEXTS = listOf(
            "nhấn để xem live",
            "tap to watch live",
            "tap to join live",
        )
        val hasLiveText = LIVE_CARD_TEXTS.any { kw -> texts.any { it.contains(kw) } }
        if (!hasLiveText) return false

        // Guard: thẻ live toàn màn hình KHÔNG có nút like/share/comment
        // (các nút này chỉ xuất hiện trên video thường)
        val hasInteractionButtons = all.any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            id.contains("like_btn") || id.contains("comment_btn") ||
            id.contains("share_btn") || id.contains("digg_btn") ||
            id.contains("like_count") || id.contains("comment_count")
        }
        return !hasInteractionButtons
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
     *   Regex `^`a-zA-Z0-9_.`{3,24}$` trên cả text lẫn contentDescription.
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
     * Detect Live stream — v1.1.4 cải tiến 4 tầng.
     * Tương đương: TikTokAutomation.detect_live()
     *
     * Tier 1 — Resource-ID partial match (nhanh, chính xác, offline):
     *   v1.1.4: Dùng contains() thay vì exact match — bắt được ID dạng
     *   "com.zhiliaoapp.musically:id/live_room_container" (full resource-id).
     *   Mở rộng thêm: live_stream, live_badge, broadcast.
     *
     * Tier 1b — Node resource-id full-path scan:
     *   Quét toàn bộ node, kiểm tra viewIdResourceName chứa fragment "live".
     *   Bắt được các biến thể TikTok APK mà exact-list bỏ sót.
     *
     * Tier 2 — Strict text signals (single-signal đủ để kết luận):
     *   "đang trực tiếp" / "đang phát trực tiếp" là chữ TikTok hiển thị DUY NHẤT
     *   trong live room → một tín hiệu đã đủ kết luận.
     *
     * Tier 3 — Soft signal count (phải >= 2 để tránh false positive):
     *   "tặng quà", "người xem", "bình luận trực tiếp" có thể xuất hiện lẻ trong
     *   caption video thường → chỉ kết luận khi có >= 2 soft signals cùng lúc.
     */
    /**
     * Phát hiện đang ở trong phòng live TikTok — v1.2.1 rewrite.
     *
     * Các tầng theo thứ tự độ tin cậy giảm dần:
     *
     * **Tier 0 — Gift-panel + viewer-count combo** (chỉ tồn tại trong live room):
     *   Nút gửi quà và badge số người xem xuất hiện DUY NHẤT ở live room.
     *   Bất kỳ resource-ID nào trong GIFT_IDS → kết luận live ngay.
     *
     * **Tier 1 — Resource-ID fragments** (nhanh, chính xác):
     *   Quét `viewIdResourceName` của toàn bộ node tree.
     *   Cập nhật thêm ID từ TikTok APK 2025-2026 so với v1.1.x.
     *
     * **Tier 2 — Strict text signals** (một tín hiệu đủ kết luận):
     *   Các cụm từ CHỈ xuất hiện trong live room TikTok — không bao giờ trong feed.
     *
     * **Tier 3 — Số người xem với pattern số** (tránh false-positive từ "viewers" trong caption):
     *   Yêu cầu "số + người xem" hoặc "số + viewer(s)" — không match chỉ từ "viewer" đơn lẻ.
     *
     * **Tier 4 — Soft combo** (>= 2 tín hiệu mềm, mỗi tín hiệu đơn có thể sai):
     *   Giảm từ SOFT_SIGNALS cũ, loại bỏ "viewers" và "live now" (dễ false-positive).
     *
     * FALSE-POSITIVE known risks (đã xử lý):
     *   - Tab "LIVE" trong horizontal scroll của feed → không có gift IDs → không trigger Tier 0/1
     *   - Caption video chứa từ "live" → Tier 2 strict không match; Tier 3 cần số đi kèm
     *   - "người xem" trong stats video thường → không có gift IDs để pair
     */
    fun detectLive(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // ── Tier 0: Gift-panel IDs — CHỈ tồn tại trong live room ────────
        // Nút/panel quà tặng + danh sách người xem là tín hiệu mạnh nhất,
        // không bao giờ xuất hiện trong feed video thông thường.
        val GIFT_IDS = listOf(
            // Gift buttons / panels
            "gift_entrance", "gift_btn", "send_gift_btn", "gift_panel",
            "gift_list", "gift_item", "gift_container", "coin_panel",
            "gift_tab", "gift_icon", "btn_gift",
            // Audience / viewers
            "audience_list", "audience_tab", "live_audience",
            "viewer_list", "viewer_count", "live_viewer_count",
            // PK battle UI
            "pk_battle", "pk_score", "pk_vs",
            // Live-specific action bar
            "live_action_bar", "live_tool_bar",
        )
        if (GIFT_IDS.any { findByResourceId(root, it) != null }) return true

        // ── Tier 1a: Resource-ID exact fragments ─────────────────────────
        val LIVE_ID_FRAGMENTS = listOf(
            "liveroom", "live_room", "live_gift", "live_rank", "live_chat",
            "live_duration", "live_viewer", "live_viewers", "live_anchor",
            "live_host", "live_close", "live_exit", "live_countdown",
            "live_button", "btn_exit_live", "exit_live",
            "live_stream", "live_badge", "live_comment", "broadcast",
            "live_panel", "live_info", "live_title",
            // v1.2.1 — IDs từ TikTok APK 2025 build
            "live_screen", "live_container", "live_layer",
            "live_floating", "live_status_bar", "live_top_bar",
            "live_bottom_bar", "live_control", "live_interact",
            "live_caption", "live_pinned", "live_notice",
        )
        if (LIVE_ID_FRAGMENTS.any { findByResourceId(root, it) != null }) return true

        // ── Tier 1b: Full-path resource-id scan ──────────────────────────
        val hasLiveId = traverseAll(root).any { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: return@any false
            resId.contains("/live") || resId.contains("liveroom") ||
                resId.contains("broadcast") || resId.contains("/gift_")
        }
        if (hasLiveId) return true

        val allNodes = traverseAll(root)
        val text     = allNodes
            .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .joinToString(" ")
            .lowercase()

        // ── Tier 2: Strict text signals — 1 tín hiệu đủ kết luận ────────
        // Các cụm này KHÔNG xuất hiện trong feed video thông thường.
        val STRICT_SIGNALS = listOf(
            // TikTok VN
            "đang trực tiếp",        // badge live VN
            "đang phát trực tiếp",   // toast khi vào live
            "đang live",             // variant ngắn
            "phòng live",            // "Phòng Live của @..."
            "đang phát sóng",        // host sees this
            "kết thúc phát sóng",    // end-broadcast button (host)
            "thoát khỏi phòng live", // viewer exit prompt
            "tặng quà cho người dùng", // gift action text
            // TikTok internal
            "liveroom",
            ":id/live",
            // TikTok EN
            "you are now live",
            "end your live",
            "going live",            // "You are going live" — toast
        )
        if (STRICT_SIGNALS.any { it in text }) return true

        // ── Tier 3: Số người xem với pattern số — tránh false-positive ───
        // "1.2K người xem" / "234 viewers" là chỉ số riêng của live room.
        // Không dùng "viewers" đơn lẻ — có thể xuất hiện trong caption video.
        val viewerPattern = Regex("""[\d,.]+\s*[kKmM]?\s*(người xem|viewer|viewers)""")
        if (viewerPattern.containsMatchIn(text)) return true

        // ── Tier 4: Soft combo >= 2 — thu hẹp so với v1.1.x ─────────────
        // Đã LOẠI BỎ khỏi SOFT_SIGNALS: "viewers", "live now" (quá dễ false-positive)
        // Chỉ giữ tín hiệu ĐẶC THÙ cho live interaction/gifting.
        val SOFT_SIGNALS = listOf(
            "tặng quà",              // gift button text
            "gửi quà",               // alternative gift text
            "bình luận trực tiếp",   // live chat label
            "tặng tim",              // heart gift VN
            "nhận quà",              // receive gift
            "kết thúc live",         // end live (shorter variant)
            "thoát live",            // exit live viewer
            "chia sẻ live",          // share live button
        )
        return SOFT_SIGNALS.count { it in text } >= 2
    }

    /**
     * Detect quảng cáo trong feed TikTok — v1.1.4.
     * Tương đương: XMLParser.detect_ad() (Python backend).\
     *
     * Tier 1 — Resource-ID: nhanh, chính xác.
     *   TikTok gắn ID rõ ràng cho các element quảng cáo (skip button, badge, CTA).
     *   v1.1.4: Thêm partial-scan toàn bộ node tree qua viewIdResourceName.
     *
     * Tier 2 — Exact text node: "Quảng cáo" / "Sponsored" là badge dán DUY NHẤT
     *   trong feed — dùng exact match để tránh bắt nhầm caption có chứa từ này.
     *
     * Tier 3 — Soft CTA signals (>= 2): "Tìm hiểu thêm", "Cài đặt ngay",
     *   "Mua ngay" là các nút CTA phổ biến trong quảng cáo TikTok VN.
     *   Một tín hiệu đơn lẻ có thể xuất hiện trong video thường → cần >= 2.
     */
    fun detectAd(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // Tier 1: Resource-ID exact list
        val AD_IDS = listOf(
            // Skip / close button
            "ad_skip", "skip_ad", "btn_skip_ad", "ad_skip_btn", "adSkipButton",
            // Ad badge / label
            "ad_badge", "ad_label", "ad_indicator", "ad_tag",
            // Ad action / CTA
            "ad_action_button", "ad_cta_btn", "ad_cta",
            // Ad container / view
            "feed_ad", "ad_view", "ad_image", "ad_container",
            // Ad countdown timer
            "ad_countdown", "ad_timer",
        )
        if (AD_IDS.any { findByResourceId(root, it) != null }) return true

        // Tier 1b: Full-path resource-id scan cho AD
        val hasAdId = traverseAll(root).any { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: return@any false
            resId.contains("/ad_") || resId.contains("_ad/") || resId.contains("feed_ad")
        }
        if (hasAdId) return true

        // Tier 2: Exact text badge — tránh false positive từ caption/hashtag
        val AD_EXACT = listOf(
            "quảng cáo",            // TikTok VN badge
            "sponsored",            // TikTok EN badge
            "tài trợ",              // "Được tài trợ" variant
            "bỏ qua quảng cáo",     // skip ad button text
            "skip ad",
        )
        if (AD_EXACT.any { findByText(root, it, exact = true) != null }) return true

        // Tier 3: Soft CTA signals — >= 2 để tránh false positive
        // Các nút này xuất hiện ĐỘC QUYỀN trong quảng cáo nhưng
        // từng nút riêng lẻ có thể bắt nhầm video thông thường.
        val text = getAllText(root).lowercase()
        val AD_SOFT_SIGNALS = listOf(
            "tìm hiểu thêm",        // "Learn more" CTA — rất phổ biến trong ads VN
            "cài đặt ngay",         // App install ad
            "mua ngay",             // Shopping ad
            "đặt hàng ngay",        // Order now
            "đăng ký ngay",         // Sign up ad
            "xem thêm",             // See more — thường đi kèm badge quảng cáo
            "shop now",
            "download now",
            "install now",
            "learn more",
        )
        return AD_SOFT_SIGNALS.count { it in text } >= 2
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
     * Detect màn hình Digital Wellbeing / Nghỉ ngơi của TikTok — v1.1.4.
     *
     * TikTok hiển thị màn hình này sau khi user xem video quá lâu liên tục.
     * Màn hình toàn phần che phủ feed, hiển thị bài tập thở hoặc lời nhắc
     * nghỉ ngơi, kèm nút "Quay lại ngay bây giờ" để tiếp tục xem.
     *
     * Ví dụ thực tế đã gặp (screenshot 2026-05-30):
     *   - Header: "Hít vào" (breathing exercise)
     *   - Button: "Quay lại ngay bây giờ"
     *   - Footer: "Khám phá các công cụ chăm sóc sức khỏe"
     *
     * Detection 2 tầng:
     *   Tier 1: Tìm trực tiếp nút "Quay lại ngay bây giờ" (chính xác nhất).
     *   Tier 2: Quét text wellbeing đặc trưng để bắt các biến thể màn hình khác.
     */
    // ── Daily Screen Time Limit screen (v1.1.8) ───────────────────────────────

    /**
     * Tín hiệu phân biệt màn hình "Giới hạn thời gian sử dụng hằng ngày".
     *
     * Màn hình này KHÁC với VERIFY_1234 (xác minh tài khoản) dù đều có 4 EditText.
     * Phân biệt: cả hai đều hiện 4 ô nhập nhưng context text hoàn toàn khác.
     *
     * Screenshot 2026-05-31:
     *   Title: "Thời gian sử dụng mà..." (Screen Time Limit)
     *   Body: "...hoặc nhập mật mã 1234 để quay lại TikTok"
     *   Button: "Quay lại TikTok"
     */
    private val DAILY_LIMIT_SIGNALS = listOf(
        "bạn đã sẵn sàng đóng tiktok",          // tiêu đề đặc trưng nhất
        "giới hạn thời gian sử dụng hằng ngày",  // body text
        "nhập mật mã",                            // body: "nhập mật mã 1234 để quay lại"
        "quay lại tiktok",                        // button text
        "ready to close tiktok",                  // EN variant
        "daily time limit",                       // EN variant
        "enter your passcode",                    // EN passcode prompt
    )

    /**
     * Phát hiện màn hình giới hạn thời gian sử dụng hằng ngày của TikTok.
     *
     * TikTok hiển thị màn hình này khi user đã dùng app quá giới hạn đã đặt.
     * Yêu cầu nhập mật mã (mặc định: 1234) để tiếp tục xem.
     *
     * v1.1.8 Tier 1: Tìm nút "Quay lại TikTok" — tín hiệu mạnh nhất.
     * v1.1.8 Tier 2: Quét DAILY_LIMIT_SIGNALS trong toàn bộ text màn hình.
     */
    fun detectDailyLimitScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        if (findReturnToTikTokButton(root) != null) return true
        val text = getAllText(root).lowercase()
        return DAILY_LIMIT_SIGNALS.any { it in text }
    }

    /**
     * Tìm nút "Quay lại TikTok" trên màn hình giới hạn thời gian sử dụng.
     *
     * Thứ tự ưu tiên: text chính xác trước, fallback sau.
     * Không thêm "quay lại" generic — quá phổ biến, gây false positive.
     */
    fun findReturnToTikTokButton(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val RETURN_TEXTS = listOf(
            "quay lại tiktok",   // VN — text chính xác trên screenshot
            "return to tiktok",  // EN variant
        )
        for (text in RETURN_TEXTS) {
            val node = findByText(root, text, ignoreCase = true)
            if (node != null) return node
        }
        return null
    }

    fun detectWellbeingScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        // Tier 1: Tìm nút "Quay lại" đặc trưng — tín hiệu mạnh nhất
        if (findReturnFromWellbeingButton(root) != null) return true

        // Tier 2: Text signals đặc trưng của wellbeing screen
        val text = getAllText(root).lowercase()
        val WELLBEING_SIGNALS = listOf(
            "khám phá các công cụ chăm sóc sức khỏe",  // footer cố định (mọi biến thể)
            "sức khỏe kỹ thuật số",                     // "Digital Wellbeing" VN
            "digital wellbeing",                         // EN variant
            "screen time",                               // EN screen time
            "thời gian sử dụng màn hình",               // VN screen time
            "take a break",                              // EN break screen
            // [v1.1.9+] Swipe-to-return / stretch screen (screenshot 2026-06-04):
            "hãy kéo giãn cơ thể",                     // header đầy đủ — stretch screen
            "bạn đã đạt đến giới hạn hằng ngày",       // subtitle cố định
            "hãy kéo",                                  // header rút gọn (biến thể cũ hơn)
            "tạm thời quay lại",                        // button text
        )
        return WELLBEING_SIGNALS.any { it in text }
    }

    /**
     * Tìm nút "Quay lại ngay bây giờ" trên màn hình Wellbeing TikTok.
     *
     * Tìm theo text theo thứ tự ưu tiên — text chính xác trước, fallback sau.
     * Trả về null nếu không tìm thấy nút nào.
     */
    fun findReturnFromWellbeingButton(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val RETURN_TEXTS = listOf(
            "quay lại ngay bây giờ",   // Wellbeing/nghỉ ngơi (screenshot 2026-05-30)
            "tạm thời quay lại",       // Stretch/swipe screen VN (screenshot 2026-06-04)
            "tam thời quay lại",       // Biến thể hiển thị không dấu (font rendering)
            "temporarily go back",     // EN: Stretch/swipe screen
            "return now",               // EN variant
            "tiếp tục xem",            // "Continue watching" VN
            "continue watching",        // EN variant
            "xem tiếp",                // short VN variant
        )
        for (text in RETURN_TEXTS) {
            val node = findByText(root, text, ignoreCase = true)
            if (node != null) return node
        }
        return null
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


    // ── Tim theo nội dung [v1.2.0] ───────────────────────────────────────

    /**
     * Trích xuất text caption của video hiện tại trên feed.
     *
     * Pass 1: resource-id chứa "caption" / "desc" / "description".
     * Pass 2: node text dài nhất không phải số thống kê (≥15 ký tự).
     */
    fun extractVideoCaption(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val CAPTION_IDS = listOf(
            "caption", "desc", "description", "video_desc",
            "text_content", "video_caption", "caption_text",
        )
        for (id in CAPTION_IDS) {
            val node = findByResourceId(root, id) ?: continue
            val text = node.text?.toString()?.trim() ?: continue
            if (text.length >= 5) return text
        }
        // Fallback: node text dài nhất, loại số thuần và UI labels ngắn
        val STAT_RE = Regex("^[0-9.,KMBkmb%\\s]+$")
        return traverseAll(root)
            .mapNotNull { it.text?.toString()?.trim()?.takeIf { t -> t.length >= 15 && !STAT_RE.matches(t) } }
            .maxByOrNull { it.length }
            ?: ""
    }

    /**
     * Trích xuất danh sách hashtag từ video hiện tại.
     *
     * Pass 1: Regex #word trong caption text.
     * Pass 2: Quét node bắt đầu bằng "#" (TikTok render hashtag thành node riêng).
     */
    fun extractVideoHashtags(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        val HASHTAG_RE = Regex("#([\\w\u00C0-\u024F\u1E00-\u1EFF]+)")
        val caption = extractVideoCaption(root)
        val fromCaption = HASHTAG_RE.findAll(caption).map { it.groupValues[1].lowercase() }.toList()
        val fromNodes = traverseAll(root).mapNotNull { node ->
            val t = (node.text?.toString() ?: node.contentDescription?.toString()) ?: return@mapNotNull null
            if (t.startsWith("#") && t.length > 2) t.removePrefix("#").lowercase() else null
        }
        return (fromCaption + fromNodes).distinct()
    }

    // ── Tìm kiếm theo từ khoá [v1.2.0] ──────────────────────────────────

    /**
     * Tìm nút/icon tìm kiếm (kính lúp) trên feed TikTok.
     * TikTok render search icon ở nhiều vị trí tuỳ version — thử resource-id trước, text sau.
     */
    fun findSearchTab(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val SEARCH_IDS = listOf(
            "search", "search_tab", "search_icon", "search_button",
            "navigation_search", "nav_search", "search_entry",
            "icon_search", "action_search", "feed_search",
        )
        for (id in SEARCH_IDS) {
            findByResourceId(root, id)?.let { return it }
        }
        return findByText(root, "tìm kiếm", ignoreCase = true)
            ?: findByText(root, "search", ignoreCase = true)
    }

    /**
     * Tìm ô nhập liệu trên màn hình search.
     * Thử resource-id trước, fallback EditText đầu tiên trên màn hình.
     */
    fun findSearchInputField(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        val INPUT_IDS = listOf(
            "search_input", "search_box", "search_field",
            "search_edittext", "input_search", "search_bar_input", "search_query",
        )
        for (id in INPUT_IDS) {
            findByResourceId(root, id)?.let { return it }
        }
        return findAllByClass(root, "EditText").firstOrNull()
    }

    /**
     * Phát hiện đang ở màn hình kết quả search.
     * Dùng sau pressBack() để kiểm tra đã về feed chưa.
     */
    fun isOnSearchScreen(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        if (isOnFeedTab(root)) return false
        val text = getAllText(root).lowercase()
        return "tìm kiếm" in text || "search" in text
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * v1.0.8 Debug helper: dump text/contentDescription của tất cả nodes hiển thị.
     * Dùng trong AutomationEngine để log khi isLostWithRetry() phát hiện lạc,
     * giúp phân tích xem nav bar đang hiển thị keyword nào bị NodeTraverser bỏ sót.
     */
    /**
     * Detect video có overlay "Xem Nhật ký" của TikTok — v1.1.9.
     *
     * TikTok hiển thị nút "Xem Nhật ký" (và dòng phụ "Nhật ký khác trong 'Hộp thư'")
     * chồng lên video — che phủ giao diện bình thường, làm nhiễu farm flow.
     * Khi phát hiện → swipe qua.
     *
     * Tier 1: Exact text match "xem nhật ký" — rất đặc trưng, không xuất hiện trong
     *   caption video thường. Một tín hiệu đủ kết luận.
     * Tier 2: Soft double-signal — "nhật ký" + "hộp thư" cùng lúc trên màn hình.
     */
    fun detectDiary(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val text = getAllText(root).lowercase()
        if ("xem nhật ký" in text) return true
        return "nhật ký" in text && "hộp thư" in text
    }

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

    // ── Profile page helpers [v1.2.1] ──────────────────────────────────────

    /**
     * v1.2.1: Tìm nút Follow trên trang hồ sơ người dùng.
     *
     * Khác với doFollowWithRoot() trong AutomationEngine (tìm trên feed):
     * Trang hồ sơ có nút Follow nổi bật hơn, thường có resource-id chuyên biệt.
     * Trả null nếu không tìm thấy (đã follow / profile bị khoá / không có nút).
     */
    fun findFollowButtonOnProfile(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null

        // Resource-id ưu tiên cao nhất
        val PROFILE_FOLLOW_IDS = listOf(
            "btn_follow", "follow_btn", "follow_button",
            "user_follow_btn", "profile_follow", "btn_user_follow",
            "follow_action_btn",
        )
        PROFILE_FOLLOW_IDS.forEach { id ->
            findByResourceId(root, id)?.let { return it }
        }

        // Tìm node clickable có text khớp từ khoá follow (chưa follow)
        val FOLLOW_LABELS = listOf("follow", "theo dõi", "đăng ký")
        val ALREADY_FOLLOWING_LABELS = listOf(
            "following", "đang theo dõi", "đã theo dõi", "friends", "bạn bè",
        )
        return traverseAll(root).firstOrNull { node ->
            if (!node.isClickable) return@firstOrNull false
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.lowercase() ?: return@firstOrNull false
            // Khớp follow nhưng KHÔNG phải "following"
            val isFollow = FOLLOW_LABELS.any { label == it || (label.contains(it) && label.length < 30) }
            val isAlready = ALREADY_FOLLOWING_LABELS.any { label.contains(it) }
            isFollow && !isAlready
        }?.toResult()
    }

    /**
     * v1.2.1: Kiểm tra trang hồ sơ có bị khoá / hạn chế không.
     *
     * Phát hiện:
     *   - Tài khoản bị khoá / đình chỉ
     *   - Trang chế độ riêng tư (private account — không follow được)
     *   - Trang không tồn tại / không khả dụng
     */
    fun detectBlockedProfile(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val allText = traverseAll(root)
            .mapNotNull { (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase() }

        val BLOCKED_KEYWORDS = listOf(
            // Khoá / đình chỉ
            "account suspended", "tài khoản bị khóa", "tài khoản bị đình chỉ",
            "this account has been suspended",
            // Không khả dụng
            "account unavailable", "tài khoản không khả dụng",
            "this account is unavailable",
            // Riêng tư
            "this account is private", "tài khoản này ở chế độ riêng tư",
            "đây là tài khoản riêng tư",
            // Không thể xem
            "không thể xem hồ sơ này", "bị hạn chế",
        )
        return BLOCKED_KEYWORDS.any { kw -> allText.any { it.contains(kw) } }
    }

    /**
     * v1.2.1: Kiểm tra nút Follow đã thành công chưa sau khi click.
     *
     * Trả true nếu nút đã chuyển sang "Following" / "Đang theo dõi".
     * Dùng để xác nhận follow thành công trước khi báo cáo lên Golike.
     */
    fun isFollowConfirmed(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val CONFIRMED_LABELS = listOf(
            "following", "đang theo dõi", "đã theo dõi", "friends", "bạn bè",
        )
        return traverseAll(root).any { node ->
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.lowercase() ?: return@any false
            CONFIRMED_LABELS.any { label.contains(it) }
        }
    }

    fun traverseAll(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
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

    enum class PopupType { NONE, ACCOUNT_SWITCH, VERIFY_1234, DAILY_LIMIT, FOLLOW_FRIENDS, FOLLOW_SUGGEST, GENERIC, SAVE_LOGIN, PERMISSION_REQUEST }

    data class PopupInfo(
        val detected: Boolean,
        val type: PopupType,
        val dismissButton: NodeResult?,
    )
}
