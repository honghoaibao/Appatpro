package com.atpro.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Loại nội dung/màn hình mà [ContentScanner] kết luận được từ chữ hiển thị trên màn hình TikTok.
 */
enum class ScanCategory { POPUP, SPONSORED, APP_INSTALL, LIVE, CLEAN }

/** Kết quả quét: loại, chữ khớp đầu tiên (để ghi log / chọn nút), điểm. */
data class ScanVerdict(val category: ScanCategory, val matchedText: String, val score: Int)

/**
 * Một điểm chữ lấy từ cây giao diện. [text] đã chuẩn hoá bằng [ContentScanner.normalize].
 * [clickable] = node (hoặc tổ tiên gần) bấm được; [visible] = đang hiện trên màn hình.
 */
data class ScanProbe(val text: String, val clickable: Boolean, val visible: Boolean)

/**
 * Bảng từ khoá. Hai kiểu khớp:
 *  • "chứa"      — nhãn tài trợ (có thể gộp dòng, vd "Dola · Được tài trợ");
 *  • "nguyên chữ" — từ dễ trùng (live, nhận, nút đóng): text node PHẢI BẰNG đúng chuỗi.
 */
data class ScanKeywords(
    /** Nút đóng "mềm" — chỉ dùng cho hộp thoại/popup, không bao giờ là nút của màn hình thường. */
    val softDismiss: List<String>,
    /** Nút đóng "cứng" — cũng là nhãn của nút X đóng bảng chọn (vd bảng chuyển đổi tài khoản). */
    val hardDismiss: List<String>,
    val sponsored: List<String>,
    val weakMusicMarkers: List<String>,
    val installButtons: List<String>,
    val liveStrong: List<String>,
    val liveWeak: List<String>,
) {
    /** Toàn bộ nút đóng được phép bấm (whitelist). Nút xác nhận (OK/Đồng ý/Cho phép) KHÔNG BAO GIỜ nằm ở đây. */
    val dismissButtons: List<String> get() = softDismiss + hardDismiss

    companion object {
        val DEFAULT = ScanKeywords(
            softDismiss = listOf(
                "để sau", "để sau nhé", "not now", "maybe later", "later", "no thanks", "no, thanks",
                "không, cảm ơn", "không cảm ơn", "không phải bây giờ", "lúc khác", "bỏ qua", "skip",
            ),
            hardDismiss = listOf(
                "đóng", "close", "hủy", "huỷ", "hủy bỏ", "huỷ bỏ", "cancel", "dismiss",
            ),
            sponsored        = listOf("được tài trợ", "sponsored", "promoted"),
            weakMusicMarkers = listOf("nhạc quảng cáo"),
            installButtons   = listOf("nhận", "cài đặt", "tải xuống", "tải app", "install", "get app"),
            liveStrong       = listOf("đang live", "nhấn để xem live", "tap to watch live", "tap to join live"),
            liveWeak         = listOf("live", "trực tiếp", "live now"),
        )
    }
}

/**
 * ContentScanner — quét MỘT LẦN cây giao diện TikTok và phân loại: popup / quảng cáo / quảng cáo tải app / live / sạch.
 *
 * v1.12.5 — theo "Hướng dẫn xây tool bỏ qua quảng cáo & livestream" (mục 5–6.5). Nhận diện chỉ bằng chữ
 * (`text`, nếu rỗng thì `contentDescription`), KHÔNG dùng viewId (TikTok làm rối ID, đổi theo phiên bản).
 *
 * Luật chấm điểm (theo thứ tự ưu tiên của kết luận):
 *  - R0  node bấm được, chữ ≤ 20 ký tự, NGUYÊN CHỮ thuộc whitelist nút đóng  → POPUP (kết luận ngay)
 *  - R1  chữ chứa nhãn tài trợ mạnh                                            → sponsored += 2
 *  - R2  chữ chứa tên nhạc quảng cáo                                           → sponsored += 1
 *  - R3  chữ NGUYÊN CHỮ thuộc LIVE mạnh                                        → live += 2
 *  - R4  chữ NGUYÊN CHỮ thuộc LIVE phụ                                         → live += 1
 *  - R5  node bấm được, chữ ≤ 18 ký tự, NGUYÊN CHỮ thuộc nút cài app           → installSignal
 *  Quyết định: POPUP › (sponsored ≥ 2 và installSignal → APP_INSTALL) › sponsored ≥ 2 → SPONSORED › live ≥ 2 → LIVE › CLEAN.
 *
 * Khác bản mẫu trong tài liệu (chỉ để giảm nhận nhầm trên caption dài):
 *  • R1/R2 chỉ xét text ngắn (≤ [MAX_LABEL_LEN] ký tự) — nhãn tài trợ luôn ngắn, caption dài chứa "promoted" thì không tính;
 *  • "bấm được" tính cả khi tổ tiên gần (≤ [CLICKABLE_ANCESTOR_DEPTH] cấp) bấm được — nút Tux của TikTok hay là
 *    TextView không tự bấm được, cha mới bấm được.
 *
 * Phần phân loại ([classify]) là hàm thuần Kotlin — chạy được trên JVM (xem ContentScannerTest).
 */
object ContentScanner {

    const val MAX_NODES = 800
    const val MAX_DEPTH = 60
    /** Nhãn tài trợ / tên nhạc quảng cáo dài hơn mức này là caption, không tính điểm. */
    const val MAX_LABEL_LEN = 60
    const val CLICKABLE_ANCESTOR_DEPTH = 3
    private const val DISMISS_MAX_LEN = 20
    private const val INSTALL_MAX_LEN = 18

    private val WS = Regex("\\s+")

    fun normalize(s: String): String = s.trim().replace(WS, " ").lowercase()

    // ── Trích xuất (cần AccessibilityNodeInfo thật) ─────────────────────────

    fun extractProbes(root: AccessibilityNodeInfo?): List<ScanProbe> {
        if (root == null) return emptyList()
        val out = ArrayList<ScanProbe>(256)
        dfs(root, out, depth = 0, clickableLeft = if (root.isClickable) CLICKABLE_ANCESTOR_DEPTH else 0)
        return out
    }

    private fun dfs(node: AccessibilityNodeInfo, out: MutableList<ScanProbe>, depth: Int, clickableLeft: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_NODES) return
        val raw    = node.text?.toString().orEmpty()
        val chosen = raw.ifEmpty { node.contentDescription?.toString().orEmpty() }
        if (chosen.isNotBlank()) {
            out.add(ScanProbe(normalize(chosen), node.isClickable || clickableLeft > 0, node.isVisibleToUser))
        }
        val childLeft = if (node.isClickable) CLICKABLE_ANCESTOR_DEPTH else (clickableLeft - 1).coerceAtLeast(0)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            dfs(c, out, depth + 1, childLeft)
        }
    }

    /** Vân tay nội dung: nối [limit] chữ hiển thị đầu tiên — để biết "đã sang nội dung mới" hay chưa. */
    fun fingerprint(probes: List<ScanProbe>, limit: Int = 15): String =
        probes.asSequence().filter { it.visible }.map { it.text }.take(limit).joinToString("|")

    // ── Phân loại (thuần Kotlin) ─────────────────────────────────────────────

    /**
     * @param detectPopup  false = bỏ qua R0 (dùng khi chỉ cần biết nội dung feed là quảng cáo/live).
     * @param softDismissOnly true = chỉ chấp nhận nút đóng "mềm" (Để sau/Not now/Bỏ qua…) — dùng khi trên màn hình
     *   có bảng chuyển đổi tài khoản (bảng này có nút X "Đóng" mà tool cần giữ mở).
     */
    fun classify(
        probes: List<ScanProbe>,
        cfg: ScanKeywords = ScanKeywords.DEFAULT,
        detectPopup: Boolean = true,
        softDismissOnly: Boolean = false,
    ): ScanVerdict {
        var sponsoredScore = 0
        var liveScore = 0
        var installSignal = false
        var best = ""
        val dismiss = if (softDismissOnly) cfg.softDismiss else cfg.dismissButtons

        for (p in probes) {
            if (!p.visible) continue

            // R0 — nút đóng popup: ưu tiên cao nhất (popup đang che feed).
            if (detectPopup && p.clickable && p.text.length <= DISMISS_MAX_LEN && dismiss.any { p.text == it }) {
                return ScanVerdict(ScanCategory.POPUP, p.text, 3)
            }
            val label = p.text.length <= MAX_LABEL_LEN
            // R1 — nhãn tài trợ (chứa): bắt cả dạng gộp dòng "Tên · Được tài trợ"
            if (label && cfg.sponsored.any { p.text.contains(it) }) {
                sponsoredScore += 2
                if (best.isEmpty()) best = p.text
                continue
            }
            // R2 — nhạc nền mang tên quảng cáo: chỉ là tín hiệu phụ
            if (label && cfg.weakMusicMarkers.any { p.text.contains(it) }) {
                sponsoredScore += 1
                if (best.isEmpty()) best = p.text
                continue
            }
            // R3/R4 — LIVE: chỉ khớp NGUYÊN CHỮ để không dính caption chứa "live"
            when {
                cfg.liveStrong.any { p.text == it } -> { liveScore += 2; if (best.isEmpty()) best = p.text }
                cfg.liveWeak.any   { p.text == it } -> { liveScore += 1; if (best.isEmpty()) best = p.text }
            }
            // R5 — nút cài app: phải bấm được, chữ ngắn, khớp nguyên chữ
            if (p.clickable && p.text.length <= INSTALL_MAX_LEN && cfg.installButtons.any { p.text == it }) {
                installSignal = true
                if (best.isEmpty()) best = p.text
            }
        }

        return when {
            sponsoredScore >= 2 && installSignal -> ScanVerdict(ScanCategory.APP_INSTALL, best, sponsoredScore)
            sponsoredScore >= 2                  -> ScanVerdict(ScanCategory.SPONSORED, best, sponsoredScore)
            liveScore >= 2                       -> ScanVerdict(ScanCategory.LIVE, best, liveScore)
            else                                 -> ScanVerdict(ScanCategory.CLEAN, "", 0)
        }
    }

    /** Quét + phân loại một lần từ cây node. */
    fun scan(
        root: AccessibilityNodeInfo?,
        cfg: ScanKeywords = ScanKeywords.DEFAULT,
        detectPopup: Boolean = true,
        softDismissOnly: Boolean = false,
    ): ScanVerdict = classify(extractProbes(root), cfg, detectPopup, softDismissOnly)

    /** Chỉ hỏi "nội dung feed hiện tại là quảng cáo (thường / tải app) không?" — bỏ R0. */
    fun isAdContent(root: AccessibilityNodeInfo?): Boolean =
        scan(root, detectPopup = false).category.let { it == ScanCategory.SPONSORED || it == ScanCategory.APP_INSTALL }

    /** Chỉ hỏi "nội dung feed hiện tại là LIVE không?" — bỏ R0. */
    fun isLiveContent(root: AccessibilityNodeInfo?): Boolean =
        scan(root, detectPopup = false).category == ScanCategory.LIVE

    /**
     * Màn hình đang hiện bảng chuyển đổi tài khoản (tool mở có chủ đích)? Bảng này có nút X "Đóng" mà
     * KHÔNG được bấm — khi đúng, chỉ cho phép nút đóng "mềm" (popup nằm đè lên bảng vẫn đóng được).
     */
    fun isAccountSwitchScreen(probes: List<ScanProbe>): Boolean =
        probes.any { it.visible && (it.text == "chuyển đổi tài khoản" || it.text == "switch account" ||
            it.text == "thêm tài khoản" || it.text == "add account") }

    // ── Hành động: bấm nút đóng popup (whitelist) ────────────────────────────

    /**
     * Tìm và bấm nút đóng popup theo whitelist. Node chữ thường không tự bấm được — nút bấm thật thường là
     * cha/tổ tiên của node chữ nên đi lên tối đa 4 cấp. Trả về chữ của nút đã bấm (null = không bấm).
     * KHÔNG BAO GIỜ truyền "OK"/"Đồng ý"/"Cho phép" vào [labels].
     */
    fun clickDismissButton(root: AccessibilityNodeInfo?, labels: List<String>): String? {
        if (root == null) return null
        val budget = intArrayOf(MAX_NODES)
        return clickDismissRec(root, labels, 0, budget)
    }

    private fun clickDismissRec(node: AccessibilityNodeInfo, labels: List<String>, depth: Int, budget: IntArray): String? {
        if (depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--
        val t = (node.text ?: node.contentDescription)?.toString().orEmpty()
        if (t.isNotBlank() && t.length <= DISMISS_MAX_LEN && node.isVisibleToUser) {
            val norm = normalize(t)
            if (labels.any { norm == it }) {
                val target = if (node.isClickable) node else clickableAncestor(node)
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return norm
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            clickDismissRec(c, labels, depth + 1, budget)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }
}
