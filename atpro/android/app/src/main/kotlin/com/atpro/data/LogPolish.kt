package com.atpro.data

/**
 * v1.11.0 — Làm sạch nhật ký để người dùng đọc được: nhãn tiếng Việt ngắn thay cho mã kỹ thuật
 * (`SWITCH`, `BTN`, `CFG`, `DEV`…), ẩn các dòng chỉ phục vụ gỡ lỗi, viết lại câu quá thuật ngữ.
 *
 * Chỉ ảnh hưởng PHẦN HIỂN THỊ (màn Nhật ký + popup nổi). Nhật ký gốc vẫn lưu nguyên văn trong Room
 * (nút "Chi tiết" ở màn Nhật ký hiện lại tất cả) nên không mất thông tin khi cần gỡ lỗi.
 *
 * Thuần Kotlin → test bằng JUnit (`LogPolishTest`).
 */
enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

data class PolishedLog(
    /** Tag gốc của engine (`ERR`, `SWITCH`…), rỗng nếu dòng không có tag. Dùng để chọn icon/màu. */
    val rawTag: String,
    /** Nhãn tiếng Việt ngắn hiển thị cho người dùng. */
    val label:  String,
    val body:   String,
    val level:  LogLevel,
    /** `true` = dòng chỉ để gỡ lỗi — ẩn ở popup và ở màn Nhật ký (trừ khi bật "Chi tiết"). */
    val detail: Boolean,
)

object LogPolish {

    private val TAG_REGEX = Regex("^([A-Z][A-Z0-9_-]*(?: [A-Z][A-Z0-9_-]*)?):\\s*")

    private class TagInfo(val label: String, val level: LogLevel = LogLevel.INFO, val detail: Boolean = false)

    private val TAGS: Map<String, TagInfo> = mapOf(
        "ERR"          to TagInfo("Lỗi", LogLevel.ERROR),
        "WARN"         to TagInfo("Chú ý", LogLevel.WARNING),
        "OK"           to TagInfo("Xong", LogLevel.SUCCESS),
        "DONE"         to TagInfo("Hoàn tất", LogLevel.SUCCESS),
        "SAVE"         to TagInfo("Lưu", LogLevel.SUCCESS, detail = true),
        "ACC"          to TagInfo("Tài khoản"),
        "ACCS"         to TagInfo("Tài khoản"),
        "SWITCH"       to TagInfo("Chuyển acc"),
        "RELOAD"       to TagInfo("Chuyển acc"),
        "SCAN"         to TagInfo("Quét"),
        "LIST"         to TagInfo("Danh sách"),
        "FIX"          to TagInfo("Chuẩn hoá"),
        "NORM"         to TagInfo("Chuẩn hoá"),
        "TASK"         to TagInfo("Nhiệm vụ"),
        "TASK-LIKE"    to TagInfo("Tim"),
        "TASK-FOLLOW"  to TagInfo("Follow"),
        "TIM"          to TagInfo("Tim"),
        "FOLLOW"       to TagInfo("Follow"),
        "CMT"          to TagInfo("Bình luận"),
        "WATCH"        to TagInfo("Xem", detail = true),
        "FEED"         to TagInfo("Xem", detail = true),
        "WDG"          to TagInfo("Tự sửa lỗi", LogLevel.WARNING),
        "WDG-RECOVER"  to TagInfo("Tự sửa lỗi", LogLevel.WARNING),
        "RECOVER"      to TagInfo("Phục hồi", LogLevel.WARNING),
        "POPUP"        to TagInfo("Hộp thoại", detail = true),
        "NOTIFY"       to TagInfo("Hộp thoại", detail = true),
        "AUTH"         to TagInfo("Xác minh"),
        "LIMIT"        to TagInfo("Giới hạn", LogLevel.WARNING),
        "WELLBEING"    to TagInfo("Nghỉ ngơi", LogLevel.WARNING),
        "SKIP"         to TagInfo("Bỏ qua", LogLevel.WARNING),
        "LIKE SKIP"    to TagInfo("Bỏ qua", LogLevel.WARNING),
        "CMT SKIP"     to TagInfo("Bỏ qua", LogLevel.WARNING),
        "LIVE-FEED"    to TagInfo("Bỏ qua", LogLevel.WARNING),
        "REST"         to TagInfo("Nghỉ"),
        "INBOX"        to TagInfo("Hộp thư"),
        "SHOP"         to TagInfo("Cửa hàng"),
        "SEARCH"       to TagInfo("Tìm kiếm"),
        "FB"           to TagInfo("Facebook"),
        "FB REELS"     to TagInfo("Reels"),
        "IG"           to TagInfo("Instagram"),
        "X"            to TagInfo("X"),
        "THREADS"      to TagInfo("Threads"),
        "SNAPCHAT"     to TagInfo("Snapchat"),
        // Chỉ phục vụ gỡ lỗi
        "F4F"          to TagInfo("Follow4Follow", detail = true),   // v1.12.0: từng bước đổi acc / lấy job của nhà cung cấp
        "DEV"          to TagInfo("Hệ thống", detail = true),
        "CFG"          to TagInfo("Hệ thống", detail = true),
        "BTN"          to TagInfo("Hệ thống", detail = true),
        "OPEN"         to TagInfo("Hệ thống", detail = true),
        "FOUND"        to TagInfo("Hệ thống", detail = true),
    )

    /** Một luật viết lại: khớp toàn bộ phần thân → câu mới. `detail`/`level` ghi đè nếu khác null. */
    private class Rule(
        val rx:     Regex,
        val detail: Boolean? = null,
        val level:  LogLevel? = null,
        val build:  (MatchResult) -> String,
    )

    private fun r(pattern: String, detail: Boolean? = null, level: LogLevel? = null, build: (MatchResult) -> String) =
        Rule(Regex(pattern, RegexOption.DOT_MATCHES_ALL), detail, level, build)

    private fun kind(raw: String) = if (raw.equals("LIKE", true)) "tim" else "follow"

    private val RULES: List<Rule> = listOf(
        // ── Chuyển / đọc tài khoản ───────────────────────────────────────────
        r("Không tìm thấy @(\\S+) trong switch popup") { "Không thấy @${it.groupValues[1]} trong danh sách tài khoản" },
        r("Cần switch sang @(\\S+) \\(hiện tại: @?([^)]*)\\)") {
            val cur = it.groupValues[2].trim()
            if (cur.isEmpty() || cur == "?") "Chuyển sang @${it.groupValues[1]}" else "Chuyển từ @$cur sang @${it.groupValues[1]}"
        },
        r("Chuyển acc → @(\\S+)") { "Đang chuyển sang @${it.groupValues[1]}" },
        r("Discover (\\d+) acc từ switch popup") { "Tìm thấy ${it.groupValues[1]} tài khoản trên máy" },
        r("Farm list \\((\\d+) acc\\):.*") { "Sẽ nuôi ${it.groupValues[1]} tài khoản" },
        r("Đã xác nhận tài khoản hiện tại là: @?(\\S+)") { "Đang dùng @${it.groupValues[1].trimStart('@')}" },
        r("Acc đang active: @(\\S+)", detail = true) { "Đang dùng @${it.groupValues[1]}" },
        r("@(\\S+) (?:đang active|thực ra đã là acc đang login).*") { "@${it.groupValues[1]} đang được dùng — không cần chuyển" },
        r("\\[(\\d+)/(\\d+)\\] @(\\S+)") { "Tài khoản ${it.groupValues[1]}/${it.groupValues[2]}: @${it.groupValues[3]}" },
        r("Mở Settings để đọc danh sách acc.*", detail = true) { it.value },
        r("Đã tìm thấy nút chuyển đổi tài khoản", detail = true) { it.value },
        r("Không tìm thấy nút chuyển đổi tài khoản") { "Không thấy nút Chuyển đổi tài khoản" },
        r("Không tìm thấy nút switch khi chuyển @(\\S+)") { "Không thấy nút Chuyển đổi tài khoản (@${it.groupValues[1]})" },
        r("Nút (?:chuyển đổi|switch)[^@]*biến mất.*") { "Nút Chuyển đổi tài khoản bị mất, đang thử lại" },
        r("Feed không load sau khi switch @(\\S+)") { "Chưa vào được feed sau khi chuyển sang @${it.groupValues[1]}" },
        r("Feed không load trong \\d+s") { "Feed chưa tải được" },
        r("Không thể position acc đầu: @(\\S+)") { "Không chuyển được sang @${it.groupValues[1]}" },
        r("Task — Không position được acc đầu") { "Không chuyển được sang tài khoản đầu tiên" },
        r("Task — Không mở được switch popup") { "Không mở được danh sách tài khoản" },
        r("Skip @(\\S+) — switch thất bại") { "Bỏ qua @${it.groupValues[1]} vì chuyển acc không được" },
        r("Task: Skip @(\\S+) — switch thất bại") { "Bỏ qua @${it.groupValues[1]} vì chuyển acc không được" },
        r("@(\\S+) đã nuôi trong phiên — skip") { "@${it.groupValues[1]} đã làm trong phiên này — bỏ qua" },
        r("detectCurrentAccount: không tìm thấy profile tab.*") { "Chưa thấy tab Hồ sơ để đọc tài khoản đang dùng" },
        r("detectCurrentAccount: không đọc được @username") { "Không đọc được tên tài khoản đang dùng" },
        r("verifyCurrentAccount: .*fallback save @(\\S+)") { "Chưa xác nhận được tài khoản, tạm tính là @${it.groupValues[1]}" },
        r("Account mismatch: expected=@(\\S+) actual=@(\\S+)") { "Sai tài khoản: cần @${it.groupValues[1]} nhưng đang ở @${it.groupValues[2]}" },
        r("Checkpoint phát hiện: @(\\S+)") { "@${it.groupValues[1]} bị TikTok yêu cầu xác minh" },
        r("Auto-saved: @(\\S+)", detail = true) { it.value },

        // ── Chuẩn hoá tên tài khoản ──────────────────────────────────────────
        r("Phát hiện (\\d+) entry không hợp lệ:.*") { "Có ${it.groupValues[1]} tài khoản cần đọc lại tên thật" },
        r("Acc đang login '.*' cần chuẩn hoá.*") { "Đang đọc tên thật của tài khoản đang dùng" },
        r("'.*' là acc đang login \\(vị trí 0\\).*", detail = true) { it.value },
        r("\\[(\\d+)/(\\d+)\\] Chuẩn hoá \\(.*\\): '(.*)'") { "Đọc tên thật ${it.groupValues[1]}/${it.groupValues[2]}: '${it.groupValues[3]}'" },
        r("\\[(\\d+)/(\\d+)\\] Hoàn tất.*", detail = true) { it.value },
        r("Click node '.*' để switch\\.\\.\\.", detail = true) { it.value },
        r("✓ '.*' \\(acc đang login\\) → '@?(\\S+)'") { "Đã nhận ra tài khoản @${it.groupValues[1].trimStart('@')}" },
        r("✓ '(.*)' → '@?(\\S+)'") { "'${it.groupValues[1]}' là @${it.groupValues[2].trimStart('@')}" },
        r("Chuẩn hoá xong — (\\d+) username.*") { "Đã đọc xong tên thật của ${it.groupValues[1]} tài khoản" },
        r("Chuẩn hoá xong — không có username mới") { "Không có tài khoản nào cần đọc lại tên" },
        r("(?:Fresh node )?'(.*)' (?:nghi là nhãn hệ thống|\\(đã chuẩn hoá lần trước\\?\\)).*") { "Bỏ qua '${it.groupValues[1]}' (không phải tên tài khoản)" },
        r("Bỏ qua '(.*)' — nghi là nhãn hệ thống") { "Bỏ qua '${it.groupValues[1]}' (không phải tên tài khoản)" },
        r("Không đọc được @username (?:của acc đang login|sau switch).*") { "Không đọc được tên tài khoản" },
        r("Không mở lại được popup sau chuẩn hoá acc đầu") { "Không mở lại được danh sách tài khoản" },
        r("Feed chưa (?:hiện sau switch|sẵn sàng trước khi xác nhận).*") { "Feed chưa hiện, đang thử lại" },
        r("Feed không load sau fallback — bỏ qua") { "Feed không tải được — bỏ qua tài khoản này" },
        r("parseAccountList rỗng.*") { "Chưa đọc được danh sách tài khoản" },
        r("ALL_LOCAL: discoveredList rỗng, fallback DB") { "Dùng danh sách tài khoản đã lưu" },

        // ── Nhiệm vụ kiếm tiền ───────────────────────────────────────────────
        r("(\\d+) acc sẽ làm nhiệm vụ: \\[(.*)\\]") { "Làm nhiệm vụ với ${it.groupValues[1]} tài khoản: ${it.groupValues[2]}" },
        r("\\[(\\d+)/(\\d+)\\] @(\\S+)") { "Tài khoản ${it.groupValues[1]}/${it.groupValues[2]}: @${it.groupValues[3]}" },
        r("Job #(\\d+) (LIKE|FOLLOW) link=.*") { "Nhận job #${it.groupValues[1]} (${kind(it.groupValues[2])})" },
        r("Job #(\\d+) hoàn thành ✓ \\(\\+([\\d.,]+)\\)") { "Job #${it.groupValues[1]} xong ✓ +${it.groupValues[2]} xu" },
        r("Job #(\\d+) không thực hiện được trên máy — gọi skip") { "Job #${it.groupValues[1]} không làm được — bỏ qua" },
        r("Job #(\\d+) loại .* không khớp cấu hình — skip") { "Job #${it.groupValues[1]} không đúng loại đã chọn — bỏ qua" },
        r("Đã skip job #(\\d+)(?: sau lỗi báo cáo)?") { "Đã bỏ qua job #${it.groupValues[1]}" },
        r("@(\\S+) xong — jobs=(\\d+) likes=(\\d+) follows=(\\d+) videos=(\\d+) thưởng=\\+?([\\d.,]+)") {
            "@${it.groupValues[1]} xong: ${it.groupValues[2]} job (tim ${it.groupValues[3]}, follow ${it.groupValues[4]}) · +${it.groupValues[6]} xu"
        },
        r("Hoàn thành tất cả (\\d+) tài khoản — đóng TikTok") { "Xong ${it.groupValues[1]} tài khoản" },
        r("Farm hoàn thành tất cả (\\d+) tài khoản — đóng TikTok") { "Đã nuôi xong ${it.groupValues[1]} tài khoản" },
        r("Lấy job thất bại: (.*)") { "Chưa lấy được job: ${it.groupValues[1]}" },
        r("Không có job khả dụng cho @(\\S+) lúc này(.*) — dừng làm việc") { "Hết job cho @${it.groupValues[1]}${it.groupValues[2]}" },
        r("Server yêu cầu chờ (\\d+)s") { "Máy chủ yêu cầu chờ ${it.groupValues[1]} giây" },
        r("Chờ cooldown (\\d+)s") { "Chờ ${it.groupValues[1]} giây theo yêu cầu máy chủ" },
        r("Báo job #(\\d+) bị giới hạn tốc độ — chờ (\\d+)s rồi báo lại") { "Báo job #${it.groupValues[1]} bị giới hạn tốc độ, chờ ${it.groupValues[2]} giây" },
        r("Lỗi báo cáo job #(\\d+): (.*)") { "Báo job #${it.groupValues[1]} thất bại: ${it.groupValues[2]}" },
        r("Lỗi skip job #(\\d+): (.*)") { "Không bỏ qua được job #${it.groupValues[1]}: ${it.groupValues[2]}" },
        r("Thông tin xác thực (.*) không hợp lệ — dừng task") { "Cần đăng nhập lại ${it.groupValues[1]} — đã dừng" },
        r("Mốc (\\d+) job — nuôi acc LÂU (\\d+)s") { "Đủ ${it.groupValues[1]} job — nuôi acc lâu ${it.groupValues[2]} giây" },
        r("Nuôi acc (\\d+)s sau job #\\d+") { "Nuôi acc ${it.groupValues[1]} giây" },
        r("Đã về feed.*", detail = true) { it.value },
        r("Double-tap x2.*") { "Đã tim video" },
        r("Đã follow rồi — tính là thành công") { "Đã follow từ trước — tính là xong" },
        r("Tim video") { "Đã tim video" },
        r("Task — (.*) chưa có acc TikTok nào được liên kết") { "${it.groupValues[1]} chưa liên kết tài khoản TikTok nào" },
        r("Task — Không có acc nào trùng với (.*)") { "Không có tài khoản trên máy trùng với ${it.groupValues[1]}" },
        r("Task — Không có tài khoản nào trên thiết bị") { "Máy chưa có tài khoản TikTok nào" },
        r("Task — chưa cấu hình nơi làm nhiệm vụ.*") { "Chưa chọn nơi làm nhiệm vụ" },
        r("Job #(\\d+) đã hết hạn/bị xoá trên máy chủ.*") { "Job #${it.groupValues[1]} đã hết hạn — sang job kế" },
        r("Task — phiên (.*) đã hết hạn, cần đăng nhập lại") { "Phiên ${it.groupValues[1]} đã hết hạn — cần đăng nhập lại" },
        r("Task — Không có acc nào được chọn khớp.*") { "Không có tài khoản nào đã chọn khớp với máy và nhà cung cấp" },
        r("Task — (\\d+) acc đã chọn nhưng chưa có trên máy: (.*)") { "${it.groupValues[1]} tài khoản đã chọn chưa có trên máy: ${it.groupValues[2]}" },
        r("Task — (\\d+) acc đã chọn nhưng chưa liên kết (.*): (.*)") { "${it.groupValues[1]} tài khoản đã chọn chưa liên kết ${it.groupValues[2]}: ${it.groupValues[3]}" },
        r("Bỏ qua (\\d+) acc chưa liên kết (.*): (.*)") { "Bỏ qua ${it.groupValues[1]} tài khoản chưa liên kết ${it.groupValues[2]}: ${it.groupValues[3]}" },
        r("Bỏ qua (\\d+) acc không được chọn: (.*)") { "Bỏ qua ${it.groupValues[1]} tài khoản không được chọn: ${it.groupValues[2]}" },
        r("Task — TikTok không mở được: (.*)") { "Không mở được TikTok" },
        r("Task — không lấy được acc từ (.*): (.*)") { "Không lấy được tài khoản từ ${it.groupValues[1]}: ${it.groupValues[2]}" },

        // ── Xem video / tim theo số tim / nội dung ───────────────────────────
        r("Xem video #(\\d+) — .*", detail = true) { it.value },
        r("Rời feed khi xem.*") { "Rời feed khi đang xem — đang quay lại" },
        r("Không like live") { "Bỏ qua tim vì đang là livestream" },
        r("Không like quảng cáo.*") { "Bỏ qua tim vì là quảng cáo" },

        // ── Tự phục hồi ──────────────────────────────────────────────────────
        r("Không có video mới trong (\\d+)s.*") { "Không có video mới ${it.groupValues[1]} giây — đang tự sửa" },
        r("Thực hiện smart recovery.*") { "Đang tự sửa vì lâu không có video mới" },
        r("Back ×\\d+ để thoát màn hình chặn") { "Đang thoát màn hình đang chặn" },
        r("Về feed sau .*✓") { "Đã về feed ✓" },
        r("Đã xử lý popup chặn") { "Đã đóng hộp thoại đang chặn" },
        r("Vẫn chưa về feed — kill \\+ relaunch TikTok") { "Mở lại TikTok để về feed" },
        r("Không còn ở TikTok.*relaunch.*") { "Đã rời TikTok — đang mở lại" },
        r("recoverToFeed: tier 3 — kill \\+ relaunch TikTok") { "Mở lại TikTok để về feed" },
        r("Lạc (\\d+) lần liên tiếp.*") { "Bị lạc khỏi feed ${it.groupValues[1]} lần — đang quay lại" },
        r("Rời feed sau like.*") { "Lỡ rời feed sau khi tim — đang quay lại" },
        r("Skip liên tiếp (\\d+)× \\((.*)\\).*") { "Bỏ qua ${it.groupValues[1]} video liên tiếp (${it.groupValues[2]}) — vuốt tiếp" },
        r("Back (\\d+) lần vẫn chưa về feed.*") { "Back ${it.groupValues[1]} lần vẫn chưa về feed — đang phục hồi" },
        r("Feed chưa hiện sau pressBack×2.*") { "Feed chưa hiện — đang phục hồi" },
        r("Wellbeing/swipe screen.*") { "Đóng màn hình nhắc nghỉ ngơi" },
        r("Daily (?:screen time )?limit.*") { "Giới hạn thời gian dùng TikTok trong ngày — đang xử lý" },
        r("Lost #(\\d+).*", detail = true) { it.value },
        r("Farm kết thúc sớm: (.*)") { "Dừng sớm: ${it.groupValues[1]}" },
        r("farmOneAccount\\(@(\\S+)\\) lỗi:.*") { "Lỗi ở @${it.groupValues[1]} — chuyển sang tài khoản kế" },
        r("farmOneAccount: recoverToFeed\\(\\) thất bại — skip @(\\S+)") { "Không về được feed — bỏ qua @${it.groupValues[1]}" },
        r("(?:recoverToFeed|recoverFromStuck)\\(\\) thất bại — kết thúc sớm @(\\S+)") { "Không về được feed — dừng @${it.groupValues[1]}" },
        r("farmOneAccount: feed chưa load.*") { "Feed chưa tải — đang phục hồi" },
        r("Không mở được TikTok sau 3 lần thử") { "Không mở được TikTok sau 3 lần thử" },
    )

    // Tên hàm / thuật ngữ lọt vào câu chữ → cách nói thường
    private val JARGON: List<Pair<Regex, String>> = listOf(
        Regex("\\brecoverToFeed\\(\\)|\\brecoverToFeed\\b") to "quay lại feed",
        Regex("\\bforceSwipeNext\\b") to "vuốt tiếp",
        Regex("\\bswitch popup\\b", RegexOption.IGNORE_CASE) to "danh sách tài khoản",
        Regex("\\bpressBack(?:×\\d+)?\\b") to "Back",
        Regex("\\bkill \\+ relaunch\\b") to "mở lại",
        Regex("\\bsmart recovery\\b", RegexOption.IGNORE_CASE) to "tự sửa",
        Regex("\\bpopup\\b", RegexOption.IGNORE_CASE) to "hộp thoại",
        Regex("\\bSettings\\b") to "Cài đặt",
        Regex("\\b\\w+Exception\\b\\s*—\\s*") to "",
        Regex("\\s*(?<![\\w.@])\\[?v\\d+\\.\\d+(?:\\.\\d+)?\\]?\\s*") to " ",
        Regex("\\bBUG-[A-Z]+-\\d+\\b:?\\s*") to "",
        Regex("\\b[a-z]\\w*\\(\\)") to "",   // tên hàm còn sót: foo()
    )

    private const val MAX_BODY = 110

    /** Nhớ kết quả (màn Nhật ký dựng lại 500 dòng mỗi khi có log mới → hầu hết dòng đã gặp). */
    private val memo = object : LinkedHashMap<String, PolishedLog>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PolishedLog>?): Boolean = size > 1_500
    }

    /** Dòng gốc → dạng hiển thị. Không bao giờ ném lỗi; dòng lạ được giữ nguyên (chỉ gọt chữ). */
    fun polish(raw: String): PolishedLog {
        synchronized(memo) { memo[raw]?.let { return it } }
        val result = compute(raw)
        synchronized(memo) { memo[raw] = result }
        return result
    }

    private fun compute(raw: String): PolishedLog {
        var msg = raw.trim()

        // Mốc giai đoạn ">> [Phase 1] Khởi động TikTok"
        if (msg.startsWith(">>")) {
            val phase = msg.removePrefix(">>").trim().replace(Regex("^\\[Phase \\d+]\\s*"), "")
            return PolishedLog("", "Bắt đầu", tidy(phase), LogLevel.INFO, detail = false)
        }
        if (msg.startsWith("✓")) {
            return PolishedLog("OK", "Xong", tidy(msg.removePrefix("✓")), LogLevel.SUCCESS, detail = false)
        }

        var rawTag = ""
        TAG_REGEX.find(msg)?.let { m ->
            rawTag = m.groupValues[1]
            msg = msg.substring(m.range.last + 1)
        }
        // "ERR: FIX: …" — tag lồng nhau: giữ tag ngoài (mức độ), bỏ tag trong
        TAG_REGEX.find(msg)?.let { m ->
            if (m.groupValues[1] in TAGS) msg = msg.substring(m.range.last + 1)
        }

        val info = TAGS[rawTag]
        var level  = info?.level ?: LogLevel.INFO
        var detail = info?.detail ?: false
        var body   = msg

        val rule = RULES.firstOrNull { it.rx.matches(msg) }
        if (rule != null) {
            rule.rx.matchEntire(msg)?.let { m -> body = rule.build(m) }
            rule.detail?.let { detail = it }
            rule.level?.let { level = it }
        }

        // Màu theo nội dung cho nhóm nhiệm vụ: thành công / thất bại
        if (rawTag in TONE_TAGS) {
            val low = body.lowercase()
            if (BAD_WORDS.any { it in low }) level = LogLevel.WARNING
            else if (GOOD_WORDS.any { it in low }) level = LogLevel.SUCCESS
        }

        val label = info?.label ?: if (rawTag.isEmpty()) "Thông tin" else rawTag.lowercase().replaceFirstChar { it.uppercase() }
        return PolishedLog(rawTag, label, tidy(body), level, detail)
    }

    /** Dòng này chỉ để gỡ lỗi? (dùng để lọc nhanh, không cần dựng toàn bộ kết quả) */
    fun isDetail(raw: String): Boolean = polish(raw).detail

    private val TONE_TAGS = setOf("TASK", "TASK-FOLLOW", "TASK-LIKE", "FOLLOW", "TIM")
    private val BAD_WORDS  = listOf("thất bại", "không thấy", "không tìm thấy", "lỗi", "không làm được", "không follow", "chưa xác nhận", "khoá", "riêng tư")
    private val GOOD_WORDS = listOf("✓", "thành công", "xong")

    private fun tidy(s: String): String {
        var t = s
        for ((rx, to) in JARGON) t = rx.replace(t, to)
        t = t.replace(Regex("\\s{2,}"), " ").trim().trimEnd('.', '…', ' ')
        if (t.length > MAX_BODY) t = t.take(MAX_BODY - 1).trimEnd() + "…"
        return t.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
    }
}
