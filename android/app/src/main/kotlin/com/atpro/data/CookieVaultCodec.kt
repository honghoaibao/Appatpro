package com.atpro.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Một cookie đăng nhập đã lưu. [cookie] là BÍ MẬT (chuỗi header `tên=giá trị; …`) — không được log, không được
 * đưa lên giao diện sau khi lưu, không được đưa vào sao lưu Supabase.
 */
@Serializable
data class StoredCookie(
    val platform:  String,      // CookiePlatform.name — lưu dạng chữ để thêm nền tảng mới không làm hỏng dữ liệu cũ
    val accountId: String,
    val cookie:    String,
    val addedAt:   Long,
) {
    override fun toString() = "StoredCookie(platform=$platform, accountId=$accountId, cookie=<ẩn>)"   // chống lộ qua log
}

/** Phần được phép hiện trên giao diện: KHÔNG có giá trị cookie. */
data class CookieSummary(val platform: CookiePlatform, val accountId: String, val addedAt: Long)

/** Kết quả chuẩn bị một cookie để lưu. */
sealed interface CookiePrepareResult {
    data class Ok(val entry: StoredCookie) : CookiePrepareResult
    /** Đọc được nhưng thiếu khoá bắt buộc hoặc không rõ ID tài khoản. */
    data class Incomplete(val missing: List<String>, val hasAccountId: Boolean) : CookiePrepareResult
    /** Không phải cookie (không đọc được cặp tên=giá trị nào) hoặc quá lớn. */
    data object Invalid : CookiePrepareResult
}

/**
 * CookieVaultCodec — phần THUẦN (không Android) của kho cookie: kiểm tra, chuẩn hoá và mã hoá JSON.
 * Chạy được trên JVM (xem CookieVaultCodecTest). Việc mã hoá AES-GCM bằng Android Keystore nằm ở [CookieVault].
 */
object CookieVaultCodec {

    /** Số cookie tối đa trong kho (cùng giới hạn với giao diện demo trước đó). */
    const val MAX_ENTRIES = CookieDemo.MAX_ITEMS
    /** Chuỗi cookie chuẩn hoá dài hơn mức này bị từ chối (cookie thật của FB/IG chỉ vài KB). */
    const val MAX_COOKIE_CHARS = 16_000
    /** Số cặp tên=giá trị tối đa của một cookie. */
    const val MAX_PAIRS = 80

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Đọc [raw] (header / JSON tiện ích trình duyệt / Netscape), kiểm tra đủ khoá bắt buộc + có ID tài khoản,
     * rồi chuẩn hoá thành một chuỗi header duy nhất. KHÔNG lưu cookie thiếu khoá — dùng không được.
     */
    fun prepare(raw: String, platform: CookiePlatform, nowMs: Long): CookiePrepareResult {
        val pairs = CookieDemo.parsePairs(raw) ?: return CookiePrepareResult.Invalid
        if (pairs.size > MAX_PAIRS) return CookiePrepareResult.Invalid

        val missing = platform.requiredKeys.filter { pairs[it].isNullOrBlank() }
        val accountId = CookieDemo.accountId(pairs, platform)
        if (missing.isNotEmpty() || accountId == null) {
            return CookiePrepareResult.Incomplete(missing, hasAccountId = accountId != null)
        }

        // Giá trị chứa ';' / xuống dòng sẽ làm hỏng header → từ chối thay vì lưu chuỗi sai.
        if (pairs.values.any { v -> v.any { it == ';' || it == '\n' || it == '\r' } }) return CookiePrepareResult.Invalid
        val header = pairs.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        if (header.length > MAX_COOKIE_CHARS) return CookiePrepareResult.Invalid

        return CookiePrepareResult.Ok(StoredCookie(platform.name, accountId, header, nowMs))
    }

    /**
     * Thêm [entry] vào [current]; cùng (nền tảng, ID) thì THAY (cookie hết hạn thì dán lại cookie mới).
     * Trả null nếu thêm mới vượt [MAX_ENTRIES].
     */
    fun upsert(current: List<StoredCookie>, entry: StoredCookie): List<StoredCookie>? {
        val index = current.indexOfFirst { it.platform == entry.platform && it.accountId == entry.accountId }
        return when {
            index >= 0 -> current.toMutableList().also { it[index] = entry }
            current.size >= MAX_ENTRIES -> null
            else -> current + entry
        }
    }

    fun remove(current: List<StoredCookie>, platform: CookiePlatform, accountId: String): List<StoredCookie> =
        current.filterNot { it.platform == platform.name && it.accountId == accountId }

    fun summaries(current: List<StoredCookie>, platform: CookiePlatform): List<CookieSummary> =
        current.asSequence()
            .filter { it.platform == platform.name }
            .map { CookieSummary(platform, it.accountId, it.addedAt) }
            .sortedBy { it.addedAt }
            .toList()

    fun encode(items: List<StoredCookie>): ByteArray =
        json.encodeToString(items).toByteArray(Charsets.UTF_8)

    /**
     * Giải mã; mục hỏng/không hợp lệ bị bỏ, dữ liệu hỏng hoàn toàn trả danh sách rỗng (không bao giờ ném —
     * kho hỏng không được chặn mở màn hình). Không bao giờ đưa nội dung vào thông báo lỗi.
     */
    fun decode(bytes: ByteArray): List<StoredCookie> = try {
        json.decodeFromString<List<StoredCookie>>(String(bytes, Charsets.UTF_8))
            .filter { it.accountId.isNotBlank() && it.cookie.isNotBlank() && it.platform in PLATFORM_NAMES }
            .take(MAX_ENTRIES)
    } catch (_: Exception) {
        emptyList()
    }

    private val PLATFORM_NAMES = CookiePlatform.values().map { it.name }.toSet()
}
