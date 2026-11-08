package com.atpro.tasks.f4f

/**
 * Gợi ý xử lý cho các message nghiệp vụ Follow4Follow hay gặp (đã field-test). Khớp theo chuỗi con
 * (không phân biệt hoa/thường) với message server trả về; hiển thị kèm trong log để biết nên làm gì.
 */
internal object F4fHints {

    private val HINTS: List<Pair<String, String>> = listOf(
        "chưa làm job này"        to "chưa follow/like thật trên TikTok",
        "chưa thực hiện follow"   to "chưa follow thật trên TikTok",
        "chưa thực hiện like"     to "chưa like thật trên TikTok",
        "đã được thực hiện"       to "job đã làm trước đó",
        "hết thời gian"           to "job đã hết hạn",
        "hết quota"               to "đã đạt giới hạn job hôm nay",
        "không tìm thấy dữ liệu"  to "job đã bị xoá/hết",
        "có lỗi hệ thống"         to "lỗi phía server, không phải do app",
        "vui lòng thử lại"        to "server tạm lỗi",
    )

    fun hintFor(message: String): String? {
        val m = message.lowercase()
        return HINTS.firstOrNull { (pattern, _) -> pattern in m }?.second
    }

    fun withHint(message: String): String {
        val hint = hintFor(message) ?: return message
        return "$message ($hint)"
    }

    /** v1.12.0: "Không tìm thấy dữ liệu" khi complete/skip = job đã bị xoá/hết → bỏ job, không thử lại. */
    fun isJobGoneMessage(message: String): Boolean = "không tìm thấy dữ liệu" in message.lowercase()

    /** Server báo hết lượt trong ngày → ngừng lấy job cho tài khoản này. */
    fun isQuotaMessage(message: String): Boolean = "hết quota" in message.lowercase()
}
