package com.atpro.sync

/**
 * v1.12.7 — Tóm tắt NGẮN nguyên nhân một lần đồng bộ lỗi để hiện ở màn Cài đặt (chỉ hiển thị trên máy, không gửi đi đâu).
 *
 * Trước đây chỉ xung đột gộp mới có chi tiết; lỗi máy chủ / lỗi cục bộ chỉ hiện "Đồng bộ lỗi" nên không ai biết vì sao.
 * Thuần Kotlin (test bằng JUnit). Luôn cắt độ dài và che chuỗi trông như token — thông báo lỗi của thư viện mạng
 * đôi khi chứa URL/Authorization.
 */
object SyncFailureSummary {

    const val MAX_LENGTH = BackupMergeConflictSummary.MAX_SUMMARY_LENGTH

    private val jwt = Regex("""eyJ[A-Za-z0-9_\-]{8,}(\.[A-Za-z0-9_\-]+){0,2}""")
    private val bearer = Regex("""(?i)bearer\s+\S+""")
    private val longSecret = Regex("""[A-Za-z0-9_\-]{64,}""")

    /** Lỗi trả về từ máy chủ / bước CAS: mã máy đọc được (vd `remote_snapshot_invalid`) + mã HTTP nếu có. */
    fun remote(code: String, httpStatus: Int?): String {
        val http = if (httpStatus != null) " (HTTP $httpStatus)" else ""
        return clean("Mã lỗi: $code$http")
    }

    /** Lý do một bước bị từ chối (CAS / xác nhận). */
    fun reason(reason: String): String = clean("Bị từ chối: $reason")

    /** Ngoại lệ cục bộ: chỉ tên lớp + thông điệp đã làm sạch, không bao giờ đưa stack trace. */
    fun exception(error: Throwable): String {
        val name = error.javaClass.simpleName.ifBlank { "Exception" }
        val message = error.message?.takeIf { it.isNotBlank() }
        return clean(if (message != null) "$name: $message" else name)
    }

    private fun clean(raw: String): String =
        raw.filterNot(Char::isISOControl)
            .replace(jwt, "***")
            .replace(bearer, "Bearer ***")
            .replace(longSecret, "***")
            .trim()
            .take(MAX_LENGTH)
}
