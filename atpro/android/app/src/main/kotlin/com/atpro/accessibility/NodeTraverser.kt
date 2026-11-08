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
        // v1.3.0 — Facebook/X/Instagram/Threads/Snapchat báo trạng thái "đã thích"
        // qua isChecked (toggle button) hoặc isSelected, không chỉ qua text/content-desc.
        // Dùng để tránh bấm trúng nút Unlike (bỏ thích) khi bài đã được thích từ trước.
        val isChecked: Boolean = false,
        val isSelected: Boolean = false,
    ) {
        val centerX: Int get() = (bounds.left + bounds.right) / 2
        val centerY: Int get() = (bounds.top + bounds.bottom) / 2
        /** Lấy text hiển thị — fallback sang contentDescription nếu text null. */
        val displayText: String? get() = text ?: node.contentDescription?.toString()
    }

    /**
     * v1.2.5 — Entry trong switch popup kèm node gốc để click.
     *
     * @param displayText     Text TikTok hiển thị (có thể là username hợp lệ,
     *                        ID thuần số, hoặc display name như "Bảo Hoài1502").
     * @param node            Node accessibility để click (trigger switch).
     * @param isNeedsNormalize  true = entry này KHÔNG phải username hợp lệ
     *                        → cần click để lấy username thực sau khi TikTok switch.
     */
    data class AccountEntry(
        val displayText:       String,
        val node:              AccessibilityNodeInfo,
        val isNeedsNormalize:  Boolean,
    )

    /**
     * v1.2.5 — Kiểm tra chuỗi có phải TikTok unique_username hợp lệ không.
     *
     * Hợp lệ   : chỉ gồm ASCII [a-zA-Z0-9_.], dài ≥3, có ít nhất 1 chữ cái,
     *            KHÔNG phải toàn chữ số (numeric ID bị lỗi hiển thị).
     * Không hợp lệ:
     *   - Toàn số          → "6784523189328"  (TikTok internal ID bị lộ)
     *   - Chứa ký tự Unicode → "Bảo Hoài1502" (display name hiện thay username)
     *   - Chứa dấu cách    → "Ảo vãi"
     *   - Quá ngắn (< 3)
     */
    fun isValidTikTokUsername(s: String): Boolean {
        if (s.length < 3) return false
        if (s.all { it.isDigit() }) return false            // pure numeric ID
        if (s.any { it == ' ' || it.code > 127 }) return false  // spaces / Unicode (display name)
        return s.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    /**
     * v1.2.6 — Nhãn UI hệ thống / nút bấm KHÔNG BAO GIỜ được coi là display name.
     *
     * BUG-NORMALIZE-001 (v1.2.5): Pass 3 (Unicode display name) từng bắt nhầm
     * content-description hệ thống như "Trang tính dưới cùng" (Android tự sinh
     * cho BottomSheetDialog) và nhãn nút "Đóng" (X close icon) — vì cả 2 đều
     * chứa dấu tiếng Việt và không nằm trong danh sách loại trừ cũ. Hệ quả:
     * app bấm nhầm vào nút Đóng / khung chứa, văng ra khỏi luồng switch acc.
     * Fix: mở rộng denylist phủ toàn bộ nhãn nút/hệ thống phổ biến (VN + EN).
     */
    private val GENERIC_UI_DENYLIST = setOf(
        "thêm tài khoản", "chuyển đổi tài khoản", "đăng nhập", "đăng xuất",
        "add account", "switch account", "log in", "login", "log out", "logout",
        "manage accounts", "quản lý tài khoản",
        "đóng", "close", "x", "huỷ", "hủy", "cancel", "bỏ qua", "skip",
        "lưu", "save", "xác nhận", "confirm", "đồng ý", "agree", "ok",
        "quay lại", "back", "tiếp theo", "next", "xong", "done",
        "có", "không", "yes", "no", "từ chối", "decline", "reject",
        "để sau", "not now", "more", "thêm", "ít hơn", "less",
        "trang tính dưới cùng", "bottom sheet", "lớp nền", "scrim",
        "cài đặt", "settings", "tài khoản", "account",
        "tiktok", "app",
    )
    private val BADGE_REGEX = Regex("""^\d+\+?$""")          // "9+", "12" – notification badge

    /**
     * v1.2.6 — Kiểm tra nhanh: chuỗi này có khớp một nhãn UI hệ thống/nút bấm
     * đã biết không (không phân biệt hoa thường, so khớp chính xác sau trim).
     * Dùng làm lớp bảo vệ thứ 2 ở AutomationEngine trước khi click 1 entry
     * được cho là "display name cần chuẩn hoá".
     */
    fun isLikelySystemLabel(s: String): Boolean =
        s.trim().lowercase() in GENERIC_UI_DENYLIST

    /**
     * v1.2.5 — Phân tích danh sách tài khoản trong switch popup, kèm node gốc.
     *
     * Khác với [parseAccountList], method này:
     * - Giữ lại node của TỪNG entry (để AutomationEngine click normalize).
     * - Phân loại entry: username hợp lệ vs. cần chuẩn hoá (ID số / display name).
     * - **Pass 3** bắt thêm display name Unicode (VD: "Bảo Hoài1502", "Ảo vãi")
     *   mà Pass 2 (regex ASCII) bỏ sót — đây là lỗi hiển thị phổ biến.
     *
     * Cách dùng:
     * ```kotlin
     * val entries = NodeTraverser.parseAccountListWithNodes(root)
     * val valid   = entries.filter { !it.isNeedsNormalize }
     * val invalid = entries.filter {  it.isNeedsNormalize }
     * ```
     */
    fun parseAccountListWithNodes(
        root: AccessibilityNodeInfo?,
        screenWidth:  Int = Int.MAX_VALUE,
        screenHeight: Int = Int.MAX_VALUE,
    ): List<AccountEntry> {
        root ?: return emptyList()

        val entries = mutableListOf<AccountEntry>()
        val seen    = mutableSetOf<String>()

        val allNodes = traverseAll(root).map { it.toResult() }
        // Bắt "@hoaibao.209.12" → "hoaibao.209.12" (có/không hợp lệ đều capture)
        val withAtNodes = allNodes.mapNotNull { nr ->
            val raw   = nr.displayText ?: return@mapNotNull null
            val clean = raw.trim().replace(" ", "")
            if (clean.startsWith("@") && clean.length > 2)
                Pair(clean.removePrefix("@"), nr.node)
            else null
        }
        if (withAtNodes.isNotEmpty()) {
            withAtNodes.forEach { (u, n) ->
                if (seen.add(u.lowercase()))
                    entries.add(AccountEntry(u, n, isNeedsNormalize = !isValidTikTokUsername(u)))
            }
            return entries   // Pass 1 thành công — bỏ qua Pass 2/3
        }

        // ── Pass 2: ASCII username (TikTok Trill / biến thể) ────────────────
        val usernameRegex = Regex("^[a-zA-Z0-9_.]{3,24}$")
        allNodes.forEach { nr ->
            val raw   = nr.displayText ?: return@forEach
            val clean = raw.trim().replace(" ", "")
            if (usernameRegex.matches(clean)
                && clean.lowercase() !in GENERIC_UI_DENYLIST
                && !BADGE_REGEX.matches(clean)
            ) {
                if (seen.add(clean.lowercase()))
                    entries.add(AccountEntry(clean, nr.node, isNeedsNormalize = !isValidTikTokUsername(clean)))
            }
        }

        // ── Pass 3: Display name Unicode (VD: "Bảo Hoài1502", "Ảo vãi") ────
        // TikTok đôi khi hiển thị nickname thay @unique_username → Pass 2 bỏ sót.
        // Dấu hiệu: chứa ký tự Unicode (ã, ả, ô...) hoặc dấu cách.
        //
        // BUG-NORMALIZE-001 fix: ngoài denylist mở rộng ở trên, áp thêm 2 bộ lọc
        // hình học để chặn các node KHÔNG PHẢI hàng tài khoản trong danh sách:
        //   (a) "container-sized" — node cao gần hết bottom sheet/màn hình
        //       (VD content-description "Trang tính dưới cùng" phủ cả khung).
        //   (b) "icon-sized" — node nhỏ & gần vuông như nút X đóng (24-48dp),
        //       trong khi text 1 dòng (nickname) luôn rộng hơn cao rõ rệt.
        allNodes.forEach { nr ->
            val raw   = nr.displayText ?: return@forEach
            val clean = raw.trim()
            if (clean.length < 2 || clean.length > 60) return@forEach
            if (clean.lowercase() in GENERIC_UI_DENYLIST) return@forEach
            if (BADGE_REGEX.matches(clean)) return@forEach
            if (seen.contains(clean.lowercase())) return@forEach

            val w = nr.bounds.width()
            val h = nr.bounds.height()
            if (w <= 0 || h <= 0) return@forEach

            // (a) Container/dialog-sized — bottom sheet, overlay nền, v.v.
            if (screenHeight != Int.MAX_VALUE && h >= screenHeight * 0.4) return@forEach

            // (b) Icon-sized — nút vuông nhỏ (close/back/...) không phải text hàng acc.
            val aspectRatio = w.toFloat() / h.toFloat()
            val isSmallSquareIcon = aspectRatio < 1.8f &&
                (screenWidth == Int.MAX_VALUE || w <= screenWidth * 0.18)
            if (isSmallSquareIcon) return@forEach

            // Phải có ký tự Unicode hoặc dấu cách (đây là display name, không phải username)
            val hasUnicode = clean.any { it.code > 127 }
            val hasSpace   = clean.contains(' ')
            if (hasUnicode || hasSpace) {
                if (seen.add(clean.lowercase()))
                    entries.add(AccountEntry(clean, nr.node, isNeedsNormalize = true))
            }
        }

        return entries
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
        // v1.11.0: tìm kiểu "chứa" (vd "x", "no", "ok") KHÔNG ĐƯỢC chọn nút Đăng xuất — "Đăng xuất" chứa chữ "x",
        // từng bị coi là nút đóng hộp thoại và bị bấm nhầm khi chuyển tài khoản.
        val queryIsLogout = isLogoutLabel(query)

        return traverseAll(root).firstOrNull { node ->
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: return@firstOrNull false
            val t = if (ignoreCase) nodeText.lowercase() else nodeText
            if (exact) t == query
            else t.contains(query) && (queryIsLogout || !isLogoutLabel(t))
        }?.toResult()
    }

    // ── Đăng xuất: không bao giờ được bấm nhầm [v1.11.0] ─────────────────────

    private val LOGOUT_LABELS = listOf("đăng xuất", "log out", "logout", "sign out", "đăng xuất khỏi")

    /** Chuỗi (text/mô tả) có phải nhãn "Đăng xuất" không (chứa, không phân biệt hoa thường). */
    fun isLogoutLabel(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim().lowercase()
        return t.isNotEmpty() && LOGOUT_LABELS.any { it in t }
    }

    /**
     * v1.2.9: Tìm node theo contentDescription (phân biệt với text).
     * Facebook dùng contentDescription cho các nút reaction (Like, Love...).
     */
    fun findByContentDesc(
        root: AccessibilityNodeInfo?,
        desc: String,
        ignoreCase: Boolean = true,
    ): NodeResult? {
        root ?: return null
        val query = if (ignoreCase) desc.lowercase() else desc

        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString() ?: return@firstOrNull false
            val d = if (ignoreCase) cd.lowercase() else cd
            d.contains(query) && (isLogoutLabel(query) || !isLogoutLabel(d))
        }?.toResult()
    }

    /**
     * v1.3.0 — Tìm node theo contentDescription khớp CHÍNH XÁC (sau trim/lowercase),
     * không dùng contains(). Dùng cho nút Like/Comment của Facebook/X/Instagram/
     * Threads/Snapchat — nếu dùng contains("like") sẽ bắt nhầm "Unlike"/"Liked"
     * (đều chứa "like" như substring), khiến tool bấm bỏ-thích thay vì thích.
     * Ưu tiên gọi hàm này TRƯỚC findByContentDesc() (contains) khi cần phân biệt
     * trạng thái đã-thích / chưa-thích.
     */
    fun findByContentDescExact(
        root: AccessibilityNodeInfo?,
        vararg descs: String,
        ignoreCase: Boolean = true,
    ): NodeResult? {
        root ?: return null
        val queries = if (ignoreCase) descs.map { it.lowercase() } else descs.toList()

        return traverseAll(root).firstOrNull { node ->
            val cd = node.contentDescription?.toString()?.trim() ?: return@firstOrNull false
            val d = if (ignoreCase) cd.lowercase() else cd
            queries.any { d == it }
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

        // [v1.3.1] "Tài khoản của bạn cần được cập nhật" — dialog TikTok yêu cầu
        // liên kết SĐT/email TRƯỚC KHI chuyển acc, đè lên ngay trên danh sách
        // switch-account. Phải check TRƯỚC nhánh ACCOUNT_SWITCH bên dưới: cây
        // node lúc này vẫn còn đủ >=2 "@username" + "chuyển đổi tài khoản" (vì
        // danh sách switch vẫn nằm phía sau dialog), nên nếu không ưu tiên check
        // ở đây trước, nó bị nhận nhầm thành ACCOUNT_SWITCH bình thường (handled
        // = true, không làm gì) → không bấm "Để sau" → tool tưởng đã chuyển acc
        // xong nhưng dialog vẫn còn chặn, dẫn tới verify sai / dừng tool.
        val hasUpdatePrompt =
            findByText(root, "cần được cập nhật", ignoreCase = true) != null
                || findByText(root, "liên kết số điện thoại", ignoreCase = true) != null
                || findByText(root, "account needs to be updated", ignoreCase = true) != null
        if (hasUpdatePrompt) {
            return PopupInfo(true, PopupType.ACCOUNT_UPDATE_PROMPT, null)
        }

        // Account switch popup: có @username nodes
        // [FIX v1.0.9] Thêm context check — phân biệt switch popup thật vs feed có @username.
        // Feed (video description, duet tag...) cũng có >= 2 "@username" → false positive.
        // Chỉ xác nhận là switch popup khi CÓ switch container RÕ RÀNG và KHÔNG đang ở feed.
        val usernameNodes = traverseAll(root).filter { node ->
            val t = node.text?.toString() ?: return@filter false
            t.startsWith("@") && t.length > 2
        }
        // v1.11.0: >= 1 (trước là >= 2). Máy chỉ đọc được 1 acc trong danh sách (acc còn lại hiện bằng tên hiển thị)
        // bị coi là "popup chung" rồi bấm nhầm nút đóng/Đăng xuất. Điều kiện hasSwitchContainer + !onFeed vẫn bảo vệ.
        if (usernameNodes.isNotEmpty()) {
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
     *
     * v1.3.2: BUG: `PROFILE_IDS`/`PROFILE_TEXTS` chứa các chuỗi ngắn dùng
     * `contains()` (vd "me") — dễ trúng nhầm node KHÔNG nằm trong nav bar, vì
     * substring "me" cũng nằm trong "ho**me**"/"trang chủ (**home**)" hay
     * resource-id "tab_**home**". Khi node đó (Trang chủ/Đề xuất/"Xem thêm"
     * caption...) xuất hiện SỚM HƠN nút Profile thật trong thứ tự duyệt cây,
     * tool bấm nhầm nó thay vì mở Hồ sơ → không đọc được @username.
     *
     * Fix: nếu biết `screenHeight`, giới hạn ứng viên vào dải Y ở khoảng
     * dưới cùng màn hình (nơi nav bar luôn nằm) TRƯỚC khi so khớp id/text —
     * loại các node cùng khớp chuỗi nhưng nằm sai vị trí (giữa/trên màn hình).
     * Nếu không tìm thấy trong dải này (hoặc không truyền screenHeight), rơi
     * về hành vi cũ — quét toàn cây — để không phá các case đang hoạt động
     * đúng trên thiết bị có nav bar cao hơn ước tính.
     */
    fun findProfileTab(root: AccessibilityNodeInfo?, screenHeight: Int = Int.MAX_VALUE): NodeResult? {
        root ?: return null

        val PROFILE_IDS = listOf(
            "profile", "me", "personal",
            // Trill-specific resource-id patterns
            "tab_profile", "profile_tab", "tab_me",
            "main_tab_profile", "bottom_tab_profile",
        )
        val PROFILE_TEXTS = listOf("hồ sơ", "profile", "me")
        val PROFILE_CD_KEYWORDS = listOf("profile tab", "hồ sơ, tab", "me tab")

        fun matchWithin(candidates: List<NodeResult>): NodeResult? {
            PROFILE_IDS.forEach { id ->
                candidates.firstOrNull {
                    it.resourceId?.lowercase()?.contains(id) == true
                }?.let { return it }
            }
            PROFILE_TEXTS.forEach { text ->
                candidates.firstOrNull {
                    it.displayText?.lowercase()?.contains(text) == true
                }?.let { return it }
            }
            return candidates.firstOrNull { nr ->
                val cd = nr.node.contentDescription?.toString()?.lowercase() ?: return@firstOrNull false
                PROFILE_CD_KEYWORDS.any { cd.contains(it) }
            }
        }

        // 1. Ưu tiên quét trong dải Y ở đáy màn hình (vùng nav bar).
        if (screenHeight != Int.MAX_VALUE) {
            val yMin = (screenHeight * 0.82).toInt()
            val navBarCandidates = traverseAll(root).map { it.toResult() }
                .filter { it.bounds.centerY() in yMin..screenHeight }
            matchWithin(navBarCandidates)?.let { return it }
        }

        // 2. Fallback: quét toàn cây (hành vi cũ — giữ để không regress khi
        //    không có screenHeight hoặc nav bar nằm ngoài dải ước tính).
        return matchWithin(traverseAll(root).map { it.toResult() })
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

    private val BACK_LABELS = setOf("quay lại", "back", "navigate up", "go back", "trở lại")
    private val BACK_ID_TAILS = listOf("back_btn", "btn_back", "iv_back", "nav_back", "back_arrow", "back_icon", "back_button")

    /**
     * v1.10.0 — Có nút Quay lại (mũi tên) ở góc trên trái? Trang video/hồ sơ mở từ link luôn có; feed thật thì không.
     */
    fun hasBackButton(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val nodes = traverseAll(root)
        val rb = Rect(); root.getBoundsInScreen(rb)
        val w = if (rb.right > rb.left) rb.right - rb.left else 1080
        val h = if (rb.bottom > rb.top) rb.bottom - rb.top else 2400
        for (n in nodes) {
            val tail = n.viewIdResourceName?.lowercase()?.substringAfter(":id/")
            val byId = tail != null && (tail == "back" || BACK_ID_TAILS.any { tail.endsWith(it) })
            val byLabel = normalizeLabel(n.contentDescription).let { it.isNotEmpty() && it in BACK_LABELS } ||
                normalizeLabel(n.text).let { it.isNotEmpty() && it in BACK_LABELS }
            if (!byId && !byLabel) continue
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.right > b.left && b.bottom > b.top && b.left < w / 4 && b.top < h / 5) return true
        }
        return false
    }

    /** v1.10.0 — Loại trang hiện tại, theo thanh điều hướng dưới cùng. */
    enum class FeedKind {
        /** Tab Trang chủ của thanh điều hướng đang được chọn — feed chính. */
        REAL_FEED,
        /** Có nút Quay lại, hoặc tab khác đang được chọn, hoặc không phải feed — không phải feed chính. */
        OTHER_PAGE,
        /** Không thấy thanh điều hướng nào (trang video/hồ sơ mở từ link, HOẶC máy không lộ nhãn tab). */
        NO_NAV,
    }

    private val NAV_HOME_LABELS = setOf("trang chủ", "home")
    private val NAV_OTHER_LABELS = setOf(
        "bạn bè", "friends", "hộp thư", "inbox", "hồ sơ", "profile", "me", "cộng đồng", "khám phá", "discover",
    )

    /**
     * v1.10.0 — Phân loại trang bằng THANH ĐIỀU HƯỚNG DƯỚI CÙNG (nút/tab nằm trong 18% dưới của màn hình).
     *
     * [isOnFeedTab] một mình KHÔNG đủ để biết đã về feed: chiến lược dự phòng của nó coi cả trang video mở từ link
     * (có Thích/Bình luận) và trang hồ sơ (có nhãn thống kê "Đã follow") là feed → sau khi báo cáo job, engine không
     * Back và nuôi acc ngay trên trang job. Trang mở từ link không có thanh điều hướng dưới cùng.
     */
    fun classifyFeed(root: AccessibilityNodeInfo?): FeedKind {
        root ?: return FeedKind.NO_NAV
        if (hasBackButton(root)) return FeedKind.OTHER_PAGE
        val nodes = traverseAll(root)
        val rb = Rect(); root.getBoundsInScreen(rb)
        val h = if (rb.bottom > rb.top) rb.bottom - rb.top else 2400
        var homeSelected = false; var otherSelected = false; var navCount = 0
        for (n in nodes) {
            val label = normalizeLabel(n.contentDescription).ifEmpty { normalizeLabel(n.text) }
            val isHome = label in NAV_HOME_LABELS
            if (!isHome && label !in NAV_OTHER_LABELS) continue
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.bottom <= b.top || b.top < h * 82 / 100) continue   // chỉ tính tab nằm dưới đáy màn hình
            if (!n.isVisibleToUser) continue
            navCount++
            if (n.isSelected || n.isChecked) { if (isHome) homeSelected = true else otherSelected = true }
        }
        return when {
            navCount == 0     -> FeedKind.NO_NAV
            homeSelected      -> FeedKind.REAL_FEED
            otherSelected     -> FeedKind.OTHER_PAGE
            // Có thanh điều hướng nhưng không đọc được tab nào đang chọn → dựa vào chiến lược cũ.
            else              -> if (isOnFeedTab(root)) FeedKind.REAL_FEED else FeedKind.OTHER_PAGE
        }
    }

    /** true khi [classifyFeed] == REAL_FEED. */
    fun isRealFeed(root: AccessibilityNodeInfo?): Boolean = classifyFeed(root) == FeedKind.REAL_FEED

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

        // Tier 1: Resource-ID chính xác — chỉ xuất hiện trong ad container
        val AD_IDS = listOf(
            "ad_skip","skip_ad","btn_skip_ad","ad_skip_btn","adSkipButton",
            "ad_badge","ad_label","ad_indicator","ad_tag",
            "ad_action_button","ad_cta_btn","ad_cta",
            "feed_ad","ad_view","ad_image","ad_container",
            "ad_countdown","ad_timer",
        )
        if (AD_IDS.any { findByResourceId(root, it) != null }) return true

        val hasAdId = traverseAll(root).any { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: return@any false
            resId.contains("/ad_") || resId.contains("_ad/") || resId.contains("feed_ad")
        }
        if (hasAdId) return true

        // Tier 2: Badge quảng cáo — exact match để tránh false positive
        val AD_EXACT = listOf(
            "quảng cáo",
            "sponsored",
            "tài trợ",
            "bỏ qua quảng cáo",
            "skip ad",
        )
        if (AD_EXACT.any { findByText(root, it, exact = true) != null }) return true

        // Tier 3: Soft signals — cần >= 2 để kết luận ad
        val text = getAllText(root).lowercase()
        val AD_SOFT = listOf(
            "tìm hiểu thêm", "cài đặt ngay", "mua ngay", "đặt hàng ngay",
            "đăng ký ngay", "xem thêm", "shop now", "download now",
            "install now", "learn more",
        )
        return AD_SOFT.count { it in text } >= 2
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

    // v1.11.0: extractVideoCaption()/extractVideoHashtags() được thay bằng [readVideoInfo] (VideoScanner).

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

    // ── Profile page helpers [v1.2.1 → v1.9.0] ───────────────────────────────
    //
    // v1.9.0 — VIẾT LẠI bộ dò nút Follow (lỗi "không tìm thấy nút Follow" trên mọi loại hồ sơ:
    // người nổi tiếng, cửa hàng, người dùng thường).
    //
    // Nguyên nhân bản cũ:
    //   1. Chỉ nhận node `isClickable == true` có nhãn "follow". Trên TikTok mới, chữ "Follow"
    //      nằm ở TextView con KHÔNG clickable (cha mới clickable) hoặc chỉ có contentDescription
    //      → không bao giờ khớp.
    //   2. Khớp `contains("follow")` + độ dài < 30 → dính nhãn thống kê "Đã follow" / "Follower"
    //      và nút trạng thái "Đang follow" (không có trong danh sách "đã follow") → nhận nhầm.
    //   3. `isFollowConfirmed` dùng `contains("following")` → UI tiếng Anh luôn có nhãn thống kê
    //      "Following" nên luôn "xác nhận" dù chưa bấm được.
    //   4. Chỉ đọc cây một lần, không chờ hồ sơ tải xong.
    //
    // Cách mới: chuẩn hoá nhãn → khớp CHÍNH XÁC → chỉ xét node nằm trong HÀNG NÚT (neo theo nút
    // "Nhắn tin" / nhãn "Follower"), → tìm cha clickable để bấm. Có dự phòng theo hình học.

    /** v1.9.0 — Trạng thái nút theo dõi trên trang hồ sơ. */
    enum class FollowState { CAN_FOLLOW, FOLLOWING, REQUESTED, FRIENDS, NOT_FOUND }

    /**
     * v1.9.0 — Kết quả dò nút theo dõi.
     *
     * @param button node ĐỂ BẤM (chính nút hoặc cha clickable gần nhất); null nếu NOT_FOUND
     * @param label  nhãn đã chuẩn hoá (dùng để log)
     * @param how    cách tìm thấy: "id" | "nhãn:<vùng>" | "hình học" (dùng để log / chẩn đoán)
     */
    data class FollowProbe(
        val state:  FollowState,
        val button: NodeResult? = null,
        val label:  String      = "",
        val how:    String      = "",
    ) {
        /** Đã theo dõi / đã gửi yêu cầu / đã là bạn bè — không cần bấm nữa. */
        val isDone: Boolean
            get() = state == FollowState.FOLLOWING ||
                state == FollowState.REQUESTED ||
                state == FollowState.FRIENDS

        companion object {
            val NOT_FOUND = FollowProbe(FollowState.NOT_FOUND)
        }
    }

    /** v1.9.0 — Quét một lần: hồ sơ có bị khoá không + trạng thái nút theo dõi. */
    data class ProfileScan(val blocked: Boolean, val probe: FollowProbe)

    private val FOLLOW_ID_PARTS = listOf(
        "btn_follow", "follow_btn", "follow_button",
        "user_follow_btn", "profile_follow", "btn_user_follow",
        "follow_action_btn",
    )
    // "Follow", "Theo dõi", "Follow lại", "Follow back", "Follow @user" (không khớp "Following"/"Follower").
    private val FOLLOW_LABEL_RE = Regex("^(follow|theo dõi)( lại| back)?( @[\\w.]+)?$")
    // Nhãn nút đã theo dõi. "đã follow"/"following" cũng là nhãn THỐNG KÊ → chỉ tin khi nằm trong hàng nút.
    private val FOLLOWING_LABELS = setOf(
        "following", "followed", "đang follow", "đang theo dõi", "đã follow", "đã theo dõi",
    )
    private val REQUESTED_LABELS = setOf("requested", "đã yêu cầu", "đã gửi yêu cầu", "đang chờ duyệt")
    private val FRIENDS_LABELS   = setOf("friends", "bạn bè")
    private val MESSAGE_LABELS   = setOf("message", "nhắn tin", "gửi tin nhắn", "tin nhắn")
    private val OWN_PROFILE_LABELS = setOf(
        "edit profile", "sửa hồ sơ", "chỉnh sửa hồ sơ", "share profile", "chia sẻ hồ sơ",
    )
    private val FOLLOWERS_RE = Regex("(^|\\s)(followers?|người theo dõi|người follow)$")

    /** Chuẩn hoá nhãn: NFC, chữ thường, bỏ ký tự vô hình / NBSP, gộp khoảng trắng. */
    internal fun normalizeLabel(raw: CharSequence?): String {
        if (raw.isNullOrBlank()) return ""
        return java.text.Normalizer.normalize(raw.toString(), java.text.Normalizer.Form.NFC)
            .lowercase()
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val Rect.w: Int get() = right - left
    private val Rect.h: Int get() = bottom - top

    private class Cand(
        val node:      AccessibilityNodeInfo,
        val b:         Rect,
        val text:      String,
        val desc:      String,
        val clickable: Boolean,
        val visible:   Boolean,
    ) {
        val hasSize: Boolean get() = b.right > b.left && b.bottom > b.top
    }

    private val NUMERIC_RE = Regex("^[\\d.,]+\\s?[kmb]?$")

    /** Nhãn thống kê ("Đã follow", "Following"...) luôn có node anh em là con số ("35", "1.748", "2,5M"). */
    private fun looksLikeStatLabel(c: Cand): Boolean {
        val p = c.node.parent ?: return false
        for (i in 0 until p.childCount) {
            val sib = p.getChild(i) ?: continue
            if (NUMERIC_RE.matches(normalizeLabel(sib.text))) return true
        }
        return false
    }

    private class Region(val name: String, val top: Int, val bottom: Int, val strict: Boolean)

    private class Hit(val c: Cand, val state: FollowState, val label: String)

    /** Trả (trạng thái, mơ hồ?) nếu nhãn là nhãn nút theo dõi; null nếu không phải. */
    private fun classifyFollowLabel(label: String, clickable: Boolean): Pair<FollowState, Boolean>? {
        if (label.isEmpty() || label.length > 40) return null
        if (FOLLOW_LABEL_RE.matches(label)) return FollowState.CAN_FOLLOW to false
        // contentDescription dạng "Follow <tên>" — chỉ nhận khi bấm được, loại nhãn thống kê.
        if (clickable && (label.startsWith("follow ") || label.startsWith("theo dõi ")) &&
            !label.contains("follower") && !label.contains("following")) {
            return FollowState.CAN_FOLLOW to false
        }
        if (label in FOLLOWING_LABELS) return FollowState.FOLLOWING to true
        if (label in REQUESTED_LABELS) return FollowState.REQUESTED to false
        if (label in FRIENDS_LABELS)   return FollowState.FRIENDS to false
        return null
    }

    /** Node bấm được: chính nó, hoặc cha clickable gần nhất không quá to; nếu không → chính nó (chạm theo toạ độ). */
    private fun clickTargetOf(node: AccessibilityNodeInfo, b: Rect, screenW: Int, screenH: Int): NodeResult {
        if (node.isClickable) return node.toResult()
        var p: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (p != null && depth < 4) {
            if (p.isClickable) {
                val pb = Rect(); p.getBoundsInScreen(pb)
                val okW = pb.w in 1..(screenW * 3 / 4)
                val okH = pb.h in 1..maxOf(b.h * 3, screenH * 12 / 100)
                if (okW && okH) return p.toResult()
                break
            }
            p = p.parent
            depth++
        }
        return node.toResult()
    }

    private fun probeNodes(nodes: List<AccessibilityNodeInfo>): FollowProbe {
        if (nodes.isEmpty()) return FollowProbe.NOT_FOUND

        var maxBottom = 0; var maxRight = 0
        val cands = ArrayList<Cand>(nodes.size)
        val blankClickables = ArrayList<Cand>()   // nút bấm được nhưng không lộ nhãn nào (dùng cho chiến lược hình học)
        for (n in nodes) {
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.bottom > maxBottom) maxBottom = b.bottom
            if (b.right  > maxRight)  maxRight  = b.right
            val t = normalizeLabel(n.text)
            val d = normalizeLabel(n.contentDescription)
            if (t.isEmpty() && d.isEmpty() && n.viewIdResourceName == null) {
                if (n.isClickable) blankClickables += Cand(n, b, t, d, true, n.isVisibleToUser)
                continue
            }
            cands += Cand(n, b, t, d, n.isClickable, n.isVisibleToUser)
        }
        val rootB = Rect(); nodes[0].getBoundsInScreen(rootB)
        val screenH = if (rootB.h > 0) rootB.h else maxBottom.coerceAtLeast(1)
        val screenW = if (rootB.w > 0) rootB.w else maxRight.coerceAtLeast(1)

        // Ưu tiên node đang hiển thị; nếu không có node nào hiển thị (cờ isVisibleToUser không tin cậy) thì dùng tất cả.
        val pool = cands.filter { it.visible }.ifEmpty { cands }.filter { it.hasSize }
        fun labelsOf(c: Cand) = listOf(c.text, c.desc)

        // Hồ sơ của chính mình → không có nút theo dõi.
        if (pool.any { c -> c.b.top < screenH / 2 && labelsOf(c).any { it in OWN_PROFILE_LABELS } }) {
            return FollowProbe.NOT_FOUND
        }

        // ── Neo bố cục ──
        val anchorFollowers = pool
            .filter { c -> c.b.top < screenH * 55 / 100 && labelsOf(c).any { it.isNotEmpty() && FOLLOWERS_RE.containsMatchIn(it) } }
            .minByOrNull { it.b.top }
        val anchorMessage = pool
            .filter { c -> c.b.top < screenH * 60 / 100 && labelsOf(c).any { it in MESSAGE_LABELS } }
            .minByOrNull { it.b.top }

        // ── Chiến lược 1: resource-id đặc thù (khớp đuôi id, nửa trên màn hình) ──
        pool.filter { c ->
            val tail = c.node.viewIdResourceName?.lowercase()?.substringAfter(":id/") ?: return@filter false
            c.b.top < screenH / 2 && FOLLOW_ID_PARTS.any { tail.endsWith(it) }
        }.minWithOrNull(compareBy({ it.b.top }, { it.b.left }))?.let { c ->
            val lab = c.text.ifEmpty { c.desc }
            val cl  = classifyFollowLabel(lab, c.clickable)
            val state = when {
                cl != null     -> cl.first
                lab.isEmpty()  -> FollowState.CAN_FOLLOW
                else           -> null
            }
            if (state != null) {
                return FollowProbe(state, clickTargetOf(c.node, c.b, screenW, screenH), lab, "id")
            }
        }

        // ── Chiến lược 2: khớp nhãn theo vùng (hàng nút → dưới thống kê → nửa trên) ──
        val regions = ArrayList<Region>(3)
        anchorMessage?.let { m ->
            val h = m.b.h.coerceAtLeast(1)
            // Nới lên ~nửa chiều cao nút (nút Nhắn tin có thể là container lớn hoặc chữ nhỏ bên trong);
            // nhãn thống kê phía trên đã bị loại riêng bằng looksLikeStatLabel().
            regions += Region("hàng-nút", m.b.top - h / 2 - 20, m.b.bottom + h / 2, true)
        }
        anchorFollowers?.let { f ->
            regions += Region("dưới-thống-kê", f.b.bottom - 2, f.b.bottom + screenH * 22 / 100, true)
        }
        regions += Region("nửa-trên", screenH * 6 / 100, screenH * 45 / 100, false)

        for (r in regions) {
            val hits = ArrayList<Hit>()
            for (c in pool) {
                if (c.b.top < r.top || c.b.top > r.bottom) continue
                for (lab in labelsOf(c)) {
                    val (st, ambiguous) = classifyFollowLabel(lab, c.clickable) ?: continue
                    if (ambiguous && !r.strict) continue   // "Đã follow"/"Following" ngoài hàng nút có thể là nhãn thống kê
                    if (ambiguous && looksLikeStatLabel(c)) continue   // nhãn thống kê luôn đi kèm số ("35" + "Đã follow")
                    hits += Hit(c, st, lab); break
                }
            }
            val best = hits.minWithOrNull(compareBy({ it.c.b.top }, { it.c.b.left })) ?: continue
            return FollowProbe(
                best.state,
                clickTargetOf(best.c.node, best.c.b, screenW, screenH),
                best.label,
                "nhãn:${r.name}",
            )
        }

        // ── Chiến lược 3: hình học — nút bên TRÁI "Nhắn tin" trong cùng hàng, hoàn toàn không có nhãn ──
        anchorMessage?.let { m ->
            val h = m.b.h.coerceAtLeast(1)
            val rowTop = m.b.top - h / 2
            val rowBottom = m.b.bottom + h / 2
            val blankPool = blankClickables.filter { it.visible }.ifEmpty { blankClickables }.filter { it.hasSize }
            val left = blankPool
                .filter { c ->
                    c.b.top in rowTop..rowBottom && c.b.right <= m.b.left + 8 &&
                        c.b.w >= screenW * 15 / 100
                }
                .minByOrNull { it.b.left }
            if (left != null) {
                val anyLabel = traverseAll(left.node).any { n ->
                    normalizeLabel(n.text).isNotEmpty() || normalizeLabel(n.contentDescription).isNotEmpty()
                }
                if (!anyLabel) {
                    return FollowProbe(FollowState.CAN_FOLLOW, left.node.toResult(), "", "hình học")
                }
            }
        }

        return FollowProbe.NOT_FOUND
    }

    private fun detectBlocked(nodes: List<AccessibilityNodeInfo>): Boolean {
        val allText = nodes.mapNotNull {
            (it.text?.toString() ?: it.contentDescription?.toString())?.lowercase()
        }
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

    /** v1.9.0: quét hồ sơ MỘT lần (một lần duyệt cây) — khoá/riêng tư + trạng thái nút theo dõi. */
    fun scanProfile(root: AccessibilityNodeInfo?): ProfileScan {
        root ?: return ProfileScan(false, FollowProbe.NOT_FOUND)
        val nodes = traverseAll(root)
        return ProfileScan(detectBlocked(nodes), probeNodes(nodes))
    }

    /** v1.9.0: dò nút theo dõi (xem [FollowProbe]). */
    fun probeFollow(root: AccessibilityNodeInfo?): FollowProbe {
        root ?: return FollowProbe.NOT_FOUND
        return probeNodes(traverseAll(root))
    }

    /**
     * v1.2.1 → v1.9.0: nút Follow CHƯA bấm trên hồ sơ (null nếu đã theo dõi / không thấy).
     * Giữ chữ ký cũ cho nơi gọi cũ; luồng mới dùng [probeFollow] / [scanProfile].
     */
    fun findFollowButtonOnProfile(root: AccessibilityNodeInfo?): NodeResult? =
        probeFollow(root).takeIf { it.state == FollowState.CAN_FOLLOW }?.button

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
        return detectBlocked(traverseAll(root))
    }

    /**
     * v1.2.1 → v1.9.0: nút đã chuyển sang "Đang follow" / "Bạn bè" / "Đã yêu cầu" chưa.
     * Chỉ tính nhãn nằm TRONG HÀNG NÚT (không tính nhãn thống kê "Following").
     */
    fun isFollowConfirmed(root: AccessibilityNodeInfo?): Boolean =
        probeFollow(root).isDone

    /**
     * v1.9.0 — Chẩn đoán: liệt kê nhãn + toạ độ các node nửa trên màn hình ("nhãn@trái,trên", dấu * = clickable).
     * Dùng khi không tìm thấy nút Follow để gửi log lại phân tích — KHÔNG ảnh hưởng luồng chạy.
     */
    fun dumpProfileNodes(root: AccessibilityNodeInfo?, limit: Int = 30): List<String> {
        root ?: return emptyList()
        val nodes = traverseAll(root)
        val rootB = Rect(); root.getBoundsInScreen(rootB)
        val out = ArrayList<String>()
        out += "nodes=${nodes.size}"
        for (n in nodes) {
            if (out.size > limit) break
            val t = normalizeLabel(n.text).ifEmpty { normalizeLabel(n.contentDescription) }
            if (t.isEmpty()) continue
            val b = Rect(); n.getBoundsInScreen(b)
            if (rootB.h > 0 && b.top > rootB.h * 60 / 100) continue
            out += "${t.take(22)}@${b.left},${b.top}${if (n.isClickable) "*" else ""}"
        }
        return out
    }

    // ── Hàng "Chuyển đổi tài khoản" trong Cài đặt TikTok [v1.11.0] ──────────────

    private val SWITCH_LABELS = listOf(
        "chuyển đổi tài khoản", "chuyển tài khoản", "switch account", "switch accounts", "manage accounts",
    )

    /**
     * Hàng "Chuyển đổi tài khoản" tìm được: [label] là node chữ, [tapNode] là node nên BẤM (hàng bấm được
     * chứa chữ đó; null = hàng không bấm được → bấm theo toạ độ [label]).
     */
    data class SwitchRow(
        val label:   NodeResult,
        val tapNode: AccessibilityNodeInfo?,
    ) {
        val bounds: Rect get() = label.bounds
    }

    /**
     * Tìm hàng "Chuyển đổi tài khoản" một cách THẬN TRỌNG (thay cho `findByText(…, "chuyển đổi tài khoản")`):
     *  • chỉ nhận node có chữ gần như đúng nhãn (không nhận khung/nhóm có mô tả dài chứa nhãn — bấm vào tâm
     *    khung đó có thể rơi trúng hàng "Đăng xuất" bên dưới);
     *  • loại node cao quá [maxHeightFraction] chiều cao màn hình;
     *  • bấm node cha khi cha là hàng bấm được, KHÔNG chứa nhãn Đăng xuất và không quá cao.
     */
    fun findSwitchAccountRow(
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        maxHeightFraction: Float = 0.2f,
    ): SwitchRow? {
        root ?: return null
        val maxH = (screenHeight * maxHeightFraction).toInt().coerceAtLeast(1)
        val nodes = traverseAll(root)

        val best = nodes.asSequence()
            .mapNotNull { n ->
                val raw = n.text?.toString() ?: n.contentDescription?.toString() ?: return@mapNotNull null
                val t = raw.trim().lowercase()
                if (isLogoutLabel(t)) return@mapNotNull null
                val hit = SWITCH_LABELS.any { lbl -> t == lbl || (t.contains(lbl) && t.length <= lbl.length + 16) }
                if (!hit) return@mapNotNull null
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.isEmpty || r.height() > maxH) return@mapNotNull null
                n to r
            }
            .minByOrNull { (_, r) -> r.width().toLong() * r.height() }
            ?: return null

        val (node, rect) = best
        val label = node.toResult()

        var tap: AccessibilityNodeInfo? = if (node.isClickable) node else null
        if (tap == null) {
            var p = node.parent
            var depth = 0
            while (p != null && depth < 4) {
                val pr = Rect(); p.getBoundsInScreen(pr)
                if (pr.height() > maxH) break
                if (p.isClickable && pr.contains(rect.centerX(), rect.centerY()) && !subtreeHasLogout(p)) { tap = p; break }
                p = p.parent; depth++
            }
        }
        return SwitchRow(label, tap)
    }

    /** Cây con (tối đa 40 node) của [n] có chứa nhãn Đăng xuất không. */
    private fun subtreeHasLogout(n: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(n)
        var seen = 0
        while (q.isNotEmpty() && seen < 40) {
            val x = q.removeFirst(); seen++
            if (isLogoutLabel(x.text?.toString()) || isLogoutLabel(x.contentDescription?.toString())) return true
            for (i in 0 until x.childCount) x.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    /** Các node chữ "Đăng xuất" (nhãn ngắn — không tính khung/nhóm có mô tả dài). */
    private fun logoutNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        traverseAll(root).filter { n ->
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            t != null && t.length <= 24 && isLogoutLabel(t)
        }

    /** Điểm (x, y) có nằm trong khung của một nhãn Đăng xuất không — dùng để chặn cú chạm nhầm trước khi bấm. */
    fun pointHitsLogout(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        root ?: return false
        return logoutNodes(root).any { n ->
            val r = Rect(); n.getBoundsInScreen(r); r.contains(x, y)
        }
    }

    /** Đang hiện hộp xác nhận đăng xuất ("Bạn có muốn đăng xuất?" + Hủy). */
    fun detectLogoutPrompt(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val nodes = traverseAll(root)
        val texts = nodes.mapNotNull { (it.text?.toString() ?: it.contentDescription?.toString())?.trim()?.lowercase() }
        val hasLogout = texts.any { isLogoutLabel(it) }
        if (!hasLogout) return false
        val hasCancel = texts.any { it == "hủy" || it == "huỷ" || it == "cancel" }
        val hasQuestion = texts.any {
            it.contains("bạn có chắc") || it.contains("muốn đăng xuất") || it.contains("are you sure") || it.contains("log out of")
        }
        return hasCancel || hasQuestion
    }

    /** Nút "Hủy" của hộp xác nhận (khớp nguyên chữ). */
    fun findLogoutCancel(root: AccessibilityNodeInfo?): NodeResult? {
        root ?: return null
        return traverseAll(root).firstOrNull { n ->
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.lowercase()
            t == "hủy" || t == "huỷ" || t == "cancel"
        }?.toResult()
    }

    // ── Đọc dữ liệu video đang hiện [v1.11.0] ────────────────────────────────

    /**
     * Số tim, tác giả, chú thích, hashtag của video đang hiện trên feed (xem [VideoScanner]).
     * Gom text + khung toạ độ của mọi node rồi để bộ quét thuần Kotlin quyết định theo vị trí trên màn hình.
     */
    fun readVideoInfo(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): VideoInfo {
        root ?: return VideoInfo()
        val boxes = ArrayList<TextBox>(64)
        val likeDescs = ArrayList<String>(2)
        val r = Rect()
        for (n in traverseAll(root)) {
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            if (text.isNullOrEmpty() && desc.isNullOrEmpty()) continue
            n.getBoundsInScreen(r)
            if (!text.isNullOrEmpty()) boxes.add(TextBox(text, r.left, r.top, r.right, r.bottom))
            if (!desc.isNullOrEmpty() && desc.length <= 80) {
                val low = desc.lowercase()
                // nút tim: có "thích"/"like" kèm số, nằm ở cột phải
                if ((low.contains("thích") || low.contains("like")) && desc.any { it.isDigit() } && r.left > screenWidth * 0.6f)
                    likeDescs.add(desc)
            }
        }
        return VideoScanner.scan(boxes, screenWidth, screenHeight, likeDescs)
    }

    /** v1.10.0 — bản chụp duyệt cây gần nhất (xem [traverseAll]). */
    private class TreeSnap(val root: AccessibilityNodeInfo, val atNanos: Long, val nodes: List<AccessibilityNodeInfo>)
    @Volatile private var snap: TreeSnap? = null
    private const val SNAP_TTL_NANOS = 250_000_000L   // 250ms

    /**
     * Duyệt BFS toàn cây. v1.10.0: nhớ kết quả cho CÙNG một node gốc trong 250ms.
     *
     * Trước đây mỗi `findByResourceId()`/`hasNavBar()`/`isOnFeedTab()` tự duyệt lại toàn cây — một lần gọi
     * `isOnFeedTab()` duyệt hơn 20 lần (mỗi resource-id một lần), và từ v1.9.0 cây còn lớn hơn
     * (`flagIncludeNotImportantViews`) → tốn CPU/pin. Gốc mới (mỗi lần `getRootNode()`) luôn duyệt mới,
     * nên không bao giờ dùng lại dữ liệu của màn hình cũ.
     */
    fun traverseAll(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val now = System.nanoTime()
        val c = snap
        if (c != null && c.root === root && now - c.atNanos < SNAP_TTL_NANOS) return c.nodes

        val result = ArrayList<AccessibilityNodeInfo>(128)
        val queue  = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        snap = TreeSnap(root, now, result)
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
            isChecked   = isChecked,
            isSelected  = isSelected,
        )
    }

    private fun getAllText(root: AccessibilityNodeInfo): String =
        traverseAll(root)
            .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .joinToString(" ")

    private fun findCloseButton(root: AccessibilityNodeInfo): NodeResult? {
        val CLOSE_TEXTS = listOf("close", "đóng", "not now", "để sau", "cancel", "hủy", "skip", "bỏ qua")
        return traverseAll(root).firstOrNull { node ->
            val t = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()
                ?: return@firstOrNull false
            // v1.11.0: "x" (nút ✕) chỉ khớp NGUYÊN chữ — trước đây `contains("x")` trúng "Đăng xuất"/"Xem thêm"…;
            // nhãn Đăng xuất bị loại hẳn.
            if (isLogoutLabel(t)) return@firstOrNull false
            t == "x" || t == "✕" || CLOSE_TEXTS.any { t == it || t.contains(it) }
        }?.toResult()
    }

    private fun findDismissButton(root: AccessibilityNodeInfo): NodeResult? =
        findCloseButton(root)

    // ── Data types ────────────────────────────────────────────────

    enum class PopupType { NONE, ACCOUNT_SWITCH, ACCOUNT_UPDATE_PROMPT, VERIFY_1234, DAILY_LIMIT, FOLLOW_FRIENDS, FOLLOW_SUGGEST, GENERIC, SAVE_LOGIN, PERMISSION_REQUEST }

    data class PopupInfo(
        val detected: Boolean,
        val type: PopupType,
        val dismissButton: NodeResult?,
    )
}
