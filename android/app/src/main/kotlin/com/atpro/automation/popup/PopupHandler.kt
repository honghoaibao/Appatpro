package com.atpro.automation.popup
import com.atpro.automation.IFarmHost

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.accessibility.NodeTraverser
import com.atpro.network.LanWebSocketServer
import kotlinx.coroutines.*

/**
 * PopupHandler — phát hiện và xử lý popup TikTok trong suốt quá trình farm.
 *
 * Kiến trúc 2 tầng (AI/Gemini đã bị loại bỏ hoàn toàn per Fix 8):
 *
 *   Tier 1 — NodeTraverser (resource-id + node tree)
 *     Nhanh, offline, không cần screenshot.
 *     Phát hiện popup đã biết qua resource-id pattern.
 *
 *   Tier 2 — Keyword scan (text fallback)
 *     Dự phòng cho popup chưa biết hoặc TikTok version mới.
 *     Quét text/contentDescription toàn bộ cây node.
 *
 * Fallback cuối: pressBack nếu cả 2 tier đều fail.
 *
 * Không dùng screenshot, không cần internet — hoàn toàn local.
 */
class PopupHandler(private val service: IFarmHost) {

    companion object { const val TAG = "PopupHandler" }

    // ── Entry point ───────────────────────────────────────────

    suspend fun handleIfPresent(): PopupResult {
        val root = service.getRootNode() ?: return PopupResult.NONE

        // Tier 1: NodeTraverser — resource-id primary, text secondary.
        // Nhanh hơn Tier 2 vì không cần quét toàn bộ text.
        val detected = NodeTraverser.detectPopup(root)
        if (detected.detected) {
            val result = handleKnownPopup(detected, root)
            if (result.handled) return result
        }

        // Tier 2: Keyword scan — fallback khi Tier 1 không nhận ra popup.
        // Chạy chậm hơn, nhưng bắt được popup TikTok version mới.
        val tier2 = scanKeywords(root)
        if (tier2 != null) {
            log("SCAN: Tier 2: ${tier2.description}")
            return handleByKeyword(tier2, root)
        }

        return PopupResult.NONE
    }

    // ── Tier 1 ────────────────────────────────────────────────

    private suspend fun handleKnownPopup(
        popup: NodeTraverser.PopupInfo,
        root:  AccessibilityNodeInfo,
    ): PopupResult = when (popup.type) {

        // Popup xác minh "1-2-3-4": TikTok yêu cầu người dùng nhập
        // 4 chữ số vào 4 EditText riêng biệt. Xuất hiện khi tài khoản
        // bị nghi ngờ hoạt động bất thường (login mới, IP lạ...).
        NodeTraverser.PopupType.VERIFY_1234 -> handle1234(root)

        // Popup "Theo dõi bạn bè": TikTok gợi ý follow contacts từ
        // danh bạ điện thoại. Thường xuất hiện sau lần login đầu tiên
        // hoặc sau khi switch account. Cần đóng để tiếp tục farm.
        NodeTraverser.PopupType.FOLLOW_FRIENDS -> {
            log("ACCS: Follow friends popup")
            popup.dismissButton?.let { service.clickNode(it.node) } ?: run {
                // Nút dismiss không có resource-id → thử tìm bằng text
                dismissByText(root) ?: service.pressBack()
            }
            delay(800)
            PopupResult(true, "follow_friends")
        }

        // Popup generic: không xác định được loại cụ thể nhưng
        // NodeTraverser phát hiện có nút dismiss. Áp dụng cho các
        // popup mới (thông báo, survey, v.v.) chưa được phân loại.
        NodeTraverser.PopupType.GENERIC -> {
            log("NOTIFY: Generic popup")
            popup.dismissButton?.let { service.clickNode(it.node) } ?: service.pressBack()
            delay(700)
            PopupResult(true, "generic")
        }

        // Popup "Chuyển đổi tài khoản": không phải popup cần đóng —
        // đây là màn hình chọn account trong flow switchToAccountViaSettings.
        // Trả về handled=true để ngăn Tier 2 xử lý nhầm.
        NodeTraverser.PopupType.ACCOUNT_SWITCH ->
            PopupResult(true, "account_switch")

        // Loại không xử lý được ở Tier 1 → để Tier 2 thử.
        else -> PopupResult.NONE
    }

    /**
     * Xử lý popup xác minh "1-2-3-4".
     *
     * TikTok hiển thị 4 EditText inline. Flow:
     * 1. Tìm đúng 4 EditText trong cây node
     * 2. Gõ lần lượt "1", "2", "3", "4" với delay giữa các ký tự
     *    để tránh TikTok reject input quá nhanh
     * 3. Tìm nút confirm (đa ngôn ngữ: EN + VI)
     * 4. Click confirm hoặc pressBack nếu không tìm thấy
     */
    private suspend fun handle1234(root: AccessibilityNodeInfo): PopupResult {
        log("AUTH: 1234 popup")
        val editTexts = NodeTraverser.findAllByClass(root, "EditText")

        // Cần đúng 4 field — nếu ít hơn thì đây không phải popup 1234 đúng
        if (editTexts.size < 4) return PopupResult.NONE

        listOf("1", "2", "3", "4").forEachIndexed { i, digit ->
            service.typeText(editTexts[i].node, digit)
            delay(200) // 200ms giữa các chữ số — dưới ngưỡng "typing too fast"
        }
        delay(800) // Đợi TikTok validate input trước khi tìm confirm button

        // Confirm button text thay đổi theo ngôn ngữ device và TikTok version
        val confirmRoot = service.getRootNode()
        val confirm = NodeTraverser.findByText(confirmRoot, "confirm")
            ?: NodeTraverser.findByText(confirmRoot, "xác nhận")
            ?: NodeTraverser.findByText(confirmRoot, "done")
            ?: NodeTraverser.findByText(confirmRoot, "tiếp tục")

        confirm?.let { service.clickNode(it.node) } ?: service.pressBack()
        delay(1200) // Đợi TikTok xử lý và dismiss popup
        return PopupResult(true, "verify_1234")
    }

    // ── Tier 2: Keyword scan ──────────────────────────────────

    private data class KeywordMatch(val description: String, val action: String)

    /**
     * Quét toàn bộ text của cây node để phát hiện loại popup.
     *
     * Keywords được nhóm theo action cần thực hiện:
     *   "dismiss" — skip/close popup không cần tương tác
     *   "decline" — từ chối yêu cầu (notifications, permissions...)
     *   "later"   — hoãn (update, review...)
     *   "confirm" — chấp nhận để tiếp tục (sensitive content, cookies...)
     *
     * Mỗi keyword phủ nhiều ngôn ngữ (EN + VI) để hoạt động với
     * device language settings khác nhau.
     *
     * Đây là danh sách sống — cần cập nhật khi TikTok thay đổi wording.
     * Xem TD-003 trong technical_debt.md.
     */
    private fun scanKeywords(root: AccessibilityNodeInfo): KeywordMatch? {
        val text = getAllText(root).lowercase()

        val patterns = listOf(
            // Xác minh danh tính: TikTok yêu cầu confirm SĐT/email.
            // Action "dismiss" = tìm nút skip/close, không nhập thông tin.
            "verify your phone"       to "dismiss",
            "verify your email"       to "dismiss",
            "xác minh số điện thoại"  to "dismiss",

            // Nhập ngày sinh: yêu cầu khi TikTok nghi account dưới 13 tuổi.
            "enter your birthday"     to "dismiss",
            "nhập ngày sinh"          to "dismiss",

            // Bật thông báo: TikTok push notification permission request.
            // Action "decline" = tìm nút "No thanks" / "Không".
            "turn on notifications"   to "decline",
            "allow notifications"     to "decline",
            "bật thông báo"           to "decline",

            // Cập nhật app: TikTok yêu cầu update lên version mới.
            // Action "later" = tìm "Remind me later" / "Không phải bây giờ".
            "update available"        to "later",
            "update to continue"      to "later",
            "cập nhật"                to "later",

            // Đánh giá app: TikTok yêu cầu rate trên CH Play.
            "rate us"                 to "dismiss",
            "rate this app"           to "dismiss",

            // Cảnh báo nội dung nhạy cảm: TikTok hiển thị warning trước video
            // có nội dung 18+, bạo lực, v.v. Action "confirm" = nhấn Continue.
            "sensitive content"       to "confirm",
            "nội dung nhạy cảm"       to "confirm",

            // GDPR / Cookie consent: hiển thị trên một số vùng/IP.
            // "accept cookies" → confirm để tiếp tục.
            // "privacy policy" → dismiss (chỉ thông báo, có nút OK/đóng).
            "accept cookies"          to "confirm",
            "privacy policy"          to "dismiss",
        )

        return patterns.firstOrNull { (kw, _) -> kw in text }
            ?.let { (kw, action) -> KeywordMatch(kw, action) }
    }

    /**
     * Thực hiện hành động dựa trên [KeywordMatch.action].
     *
     * Với mỗi action, có danh sách text ưu tiên để tìm nút bấm.
     * Thứ tự trong danh sách = thứ tự ưu tiên click.
     * Nếu không tìm được nút nào → pressBack làm last resort.
     */
    private suspend fun handleByKeyword(match: KeywordMatch, root: AccessibilityNodeInfo): PopupResult {
        val dismissTexts = when (match.action) {
            // Từ chối: ưu tiên "No thanks" / "Không" trước "Later"
            "decline" -> listOf("no thanks", "không", "decline", "later", "để sau", "skip", "bỏ qua")
            // Hoãn: ưu tiên "later" variants trước "skip"
            "later"   -> listOf("later", "để sau", "remind me later", "không phải bây giờ", "skip")
            // Chấp nhận: ưu tiên "continue" / "tiếp tục"
            "confirm" -> listOf("continue", "tiếp tục", "ok", "i understand", "đồng ý")
            // dismiss (default): tìm bất kỳ nút đóng nào
            else      -> listOf("skip", "close", "đóng", "không", "cancel", "x", "dismiss")
        }

        for (kw in dismissTexts) {
            val btn = NodeTraverser.findByText(root, kw, ignoreCase = true)
            if (btn != null && btn.isClickable) {
                service.clickNode(btn.node)
                delay(800)
                return PopupResult(true, "keyword_${match.action}")
            }
        }

        // Không tìm được nút phù hợp → pressBack để thoát popup
        service.pressBack()
        delay(700)
        return PopupResult(true, "keyword_back")
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Tìm và click nút đóng bằng text thông dụng.
     * Trả về true nếu đã click, null nếu không tìm thấy nút nào.
     */
    private suspend fun dismissByText(root: AccessibilityNodeInfo?): Boolean? {
        root ?: return null
        val CLOSE = listOf("skip", "bỏ qua", "not now", "để sau", "close", "đóng", "x", "cancel")
        for (text in CLOSE) {
            val btn = NodeTraverser.findByText(root, text, ignoreCase = true)
            if (btn != null && btn.isClickable) {
                service.clickNode(btn.node)
                return true
            }
        }
        return null
    }

    /**
     * Thu thập toàn bộ text và contentDescription trong cây node.
     * BFS để tránh StackOverflow với cây node sâu của TikTok.
     */
    private fun getAllText(root: AccessibilityNodeInfo): String {
        val sb    = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return sb.toString()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
    }

    // ── Result ────────────────────────────────────────────────

    data class PopupResult(
        val handled: Boolean,
        val type:    String = "none",
    ) {
        companion object { val NONE = PopupResult(false, "none") }
    }
}
