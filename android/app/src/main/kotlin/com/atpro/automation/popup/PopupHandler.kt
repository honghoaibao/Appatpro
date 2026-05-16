package com.atpro.automation.popup

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.accessibility.NodeTraverser
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.bridge.FlutterBridge
import kotlinx.coroutines.*

/**
 * PopupHandler — 2-tier (AI/Gemini hoàn toàn bị loại bỏ per Fix 8)
 *
 * Tier 1: NodeTraverser (resource-id + node tree — nhanh, offline)
 * Tier 2: Keyword scan (text-based fallback cho popup chưa biết)
 *
 * Fix 6+8: Không còn "Gemini Vision" hay bất kỳ AI call nào.
 * Text-only detection đã là tất cả những gì có thể làm không cần screenshot.
 * Popup chỉ có icon thì dùng Tier 1 (resource-id) hoặc pressBack fallback.
 */
class PopupHandler(private val service: TikTokAccessibilityService) {

    companion object { const val TAG = "PopupHandler" }

    // ── Entry point ───────────────────────────────────────────

    suspend fun handleIfPresent(): PopupResult {
        val root = service.getRootNode() ?: return PopupResult.NONE

        // Tier 1: NodeTraverser (resource-id primary, text secondary)
        val detected = NodeTraverser.detectPopup(root)
        if (detected.detected) {
            val result = handleKnownPopup(detected, root)
            if (result.handled) return result
        }

        // Tier 2: Keyword scan
        val tier2 = scanKeywords(root)
        if (tier2 != null) {
            log("🔍 Tier 2: ${tier2.description}")
            return handleByKeyword(tier2, root)
        }

        return PopupResult.NONE
    }

    // ── Tier 1 ────────────────────────────────────────────────

    private suspend fun handleKnownPopup(
        popup: NodeTraverser.PopupInfo,
        root: AccessibilityNodeInfo,
    ): PopupResult = when (popup.type) {

        NodeTraverser.PopupType.VERIFY_1234 -> handle1234(root)

        NodeTraverser.PopupType.FOLLOW_FRIENDS -> {
            log("👥 Follow friends popup")
            popup.dismissButton?.let { service.clickNode(it.node) } ?: run {
                dismissByText(root) ?: service.pressBack()
            }
            delay(800)
            PopupResult(true, "follow_friends")
        }

        NodeTraverser.PopupType.GENERIC -> {
            log("🔔 Generic popup")
            popup.dismissButton?.let { service.clickNode(it.node) } ?: service.pressBack()
            delay(700)
            PopupResult(true, "generic")
        }

        NodeTraverser.PopupType.ACCOUNT_SWITCH ->
            PopupResult(true, "account_switch") // bình thường, không đóng

        else -> PopupResult.NONE
    }

    private suspend fun handle1234(root: AccessibilityNodeInfo): PopupResult {
        log("🔐 1234 popup")
        val editTexts = NodeTraverser.findAllByClass(root, "EditText")
        if (editTexts.size < 4) return PopupResult.NONE

        listOf("1", "2", "3", "4").forEachIndexed { i, digit ->
            service.typeText(editTexts[i].node, digit)
            delay(200)
        }
        delay(800)

        // Tìm nút xác nhận
        val confirmRoot = service.getRootNode()
        val confirm = NodeTraverser.findByText(confirmRoot, "confirm")
            ?: NodeTraverser.findByText(confirmRoot, "xác nhận")
            ?: NodeTraverser.findByText(confirmRoot, "done")
            ?: NodeTraverser.findByText(confirmRoot, "tiếp tục")

        confirm?.let { service.clickNode(it.node) } ?: service.pressBack()
        delay(1200)
        return PopupResult(true, "verify_1234")
    }

    // ── Tier 2: Keyword scan ──────────────────────────────────

    private data class KeywordMatch(val description: String, val action: String)

    private fun scanKeywords(root: AccessibilityNodeInfo): KeywordMatch? {
        val text = getAllText(root).lowercase()
        // Fix 5: keywords phủ nhiều ngôn ngữ hơn, tránh false positive
        val patterns = listOf(
            // Xác minh số điện thoại / email
            "verify your phone"       to "dismiss",
            "verify your email"       to "dismiss",
            "xác minh số điện thoại"  to "dismiss",
            "enter your birthday"     to "dismiss",
            "nhập ngày sinh"          to "dismiss",
            // Thông báo
            "turn on notifications"   to "decline",
            "allow notifications"     to "decline",
            "bật thông báo"           to "decline",
            // Cập nhật
            "update available"        to "later",
            "update to continue"      to "later",
            "cập nhật"                to "later",
            // Đánh giá
            "rate us"                 to "dismiss",
            "rate this app"           to "dismiss",
            // Cảnh báo nội dung
            "sensitive content"       to "confirm",
            "nội dung nhạy cảm"       to "confirm",
            // GDPR / Privacy
            "accept cookies"          to "confirm",
            "privacy policy"          to "dismiss",
        )
        return patterns.firstOrNull { (kw, _) -> kw in text }
            ?.let { (kw, action) -> KeywordMatch(kw, action) }
    }

    private suspend fun handleByKeyword(match: KeywordMatch, root: AccessibilityNodeInfo): PopupResult {
        val dismissTexts = when (match.action) {
            "decline" -> listOf("no thanks", "không", "decline", "later", "để sau", "skip", "bỏ qua")
            "later"   -> listOf("later", "để sau", "remind me later", "không phải bây giờ", "skip")
            "confirm" -> listOf("continue", "tiếp tục", "ok", "i understand", "đồng ý")
            else      -> listOf("skip", "close", "đóng", "không", "cancel", "x", "dismiss")
        }
        for (kw in dismissTexts) {
            val btn = NodeTraverser.findByText(root, kw, ignoreCase = true)
            if (btn != null && btn.isClickable) {
                service.clickNode(btn.node); delay(800)
                return PopupResult(true, "keyword_${match.action}")
            }
        }
        service.pressBack(); delay(700)
        return PopupResult(true, "keyword_back")
    }

    // ── Helpers ───────────────────────────────────────────────

    private suspend fun dismissByText(root: AccessibilityNodeInfo?): Boolean? {
        root ?: return null
        val CLOSE = listOf("skip", "bỏ qua", "not now", "để sau", "close", "đóng", "x", "cancel")
        for (text in CLOSE) {
            val btn = NodeTraverser.findByText(root, text, ignoreCase = true)
            if (btn != null && btn.isClickable) {
                service.clickNode(btn.node); return true
            }
        }
        return null
    }

    private fun getAllText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
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
        FlutterBridge.sendEvent("log", mapOf("message" to msg, "level" to "INFO"))
    }

    data class PopupResult(
        val handled:     Boolean,
        val type:        String = "none",
    ) {
        companion object { val NONE = PopupResult(false, "none") }
    }
}
