package com.atpro.accessibility

import java.text.Normalizer

/**
 * v1.11.0 — Đọc dữ liệu hiển thị của một video TikTok: số tim, tác giả, chú thích, hashtag.
 *
 * Thuần Kotlin (không phụ thuộc Android) để test trực tiếp bằng JUnit — xem `VideoTextTest.kt`.
 * `NodeTraverser.readVideoInfo()` chỉ gom text + toạ độ từ cây node rồi gọi [VideoScanner.scan].
 *
 * Nguồn tham chiếu: tài liệu "Hướng dẫn quét dữ liệu TikTok bằng Accessibility Service" (11/2026).
 */

// ─────────────────────────────────────────────────────────────────────────────
//  Số đếm kiểu TikTok
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Chuyển số đếm hiển thị trên TikTok thành số nguyên: "449.6K" → 449600, "2,621" → 2621,
 * "2.5Tr" → 2500000, "449,6N" → 449600, "3 Tỷ" → 3000000000. Chuỗi không phải số đếm → `null`.
 */
object CountParser {
    private val RX = Regex("^(\\d[\\d.,]*)\\s*(K|k|M|m|Tr|TR|tr|N|n|Tỷ|tỷ|Bn|bn|B|b)?$")

    fun parse(raw: String): Long? {
        val m = RX.find(raw.trim()) ?: return null
        var num = m.groupValues[1]
        val suffix = m.groupValues[2].lowercase()

        num = when {
            // "1,234.5" → phẩy là phân cách hàng nghìn kiểu Anh
            num.contains(',') && num.contains('.') -> num.replace(",", "")
            // "449,6" → phẩy là dấu thập phân kiểu Việt
            num.contains(',') -> num.replace(',', '.')
            else -> num
        }
        // Không hậu tố: "2.621" / "2,621" là phân cách hàng nghìn
        if (suffix.isEmpty()) num = num.replace(".", "")

        val value = num.toDoubleOrNull() ?: return null
        val factor = when (suffix) {
            ""            -> 1.0
            "k", "n"      -> 1_000.0
            "m", "tr"     -> 1_000_000.0
            "tỷ", "bn", "b" -> 1_000_000_000.0
            else          -> return null
        }
        return Math.round(value * factor)
    }

    /** Dạng ngắn để hiển thị: 0 → "Tắt" do nơi gọi xử lý; 1500 → "1,5K"; 2_000_000 → "2Tr". */
    fun format(n: Long): String = when {
        n >= 1_000_000_000L -> trimZero(n / 1_000_000_000.0) + "Tỷ"
        n >= 1_000_000L     -> trimZero(n / 1_000_000.0) + "Tr"
        n >= 1_000L         -> trimZero(n / 1_000.0) + "K"
        else                -> n.toString()
    }

    private fun trimZero(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Quét một video từ danh sách text + toạ độ
// ─────────────────────────────────────────────────────────────────────────────

/** Một text trên màn hình kèm khung toạ độ (px). */
data class TextBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
}

/** Dữ liệu đọc được của video đang hiện. Trường không đọc được → `null` / rỗng. */
data class VideoInfo(
    val username: String = "",
    val caption:  String = "",
    val hashtags: List<String> = emptyList(),
    val likes:    Long? = null,
    val comments: Long? = null,
    val saves:    Long? = null,
    val shares:   Long? = null,
)

object VideoScanner {

    /** Cột nút bên phải (tim, bình luận, lưu, chia sẻ) nằm ở 30% ngoài cùng bên phải màn hình. */
    private const val RAIL_LEFT_FRACTION  = 0.70f
    /** Số đếm nằm trong dải dọc này của màn hình (bỏ thanh trạng thái và thanh điều hướng dưới). */
    private const val RAIL_TOP_FRACTION   = 0.20f
    private const val RAIL_BOTTOM_FRACTION = 0.90f
    /** Chú thích nằm ở nửa dưới, bên trái. */
    private const val CAPTION_TOP_FRACTION = 0.55f
    private const val CAPTION_BOTTOM_FRACTION = 0.93f
    private const val CAPTION_MIN_LEN = 10

    private val HASHTAG_RX = Regex("#[\\p{L}\\p{N}_]+")
    private val SOUND_HINTS = listOf("original sound", "âm thanh gốc", "nhạc nền", "♫", "♪")
    /** "Thích video, 449,6K lượt thích" / "Like video. 449.6K likes" — số nằm trong mô tả nút tim. */
    private val COUNT_IN_TEXT = Regex("(\\d[\\d.,]*\\s?(?:K|k|M|m|Tr|tr|N|n|Tỷ|tỷ|B|b)?)(?=\\s|$|[^\\p{L}\\p{N}])")

    fun scan(boxes: List<TextBox>, screenW: Int, screenH: Int, likeButtonDescriptions: List<String> = emptyList()): VideoInfo {
        if (boxes.isEmpty() || screenW <= 0 || screenH <= 0) return VideoInfo()

        // ── Số đếm ở cột phải, xếp từ trên xuống: tim, bình luận, lưu, chia sẻ ──
        val railLeft = screenW * RAIL_LEFT_FRACTION
        val rail = boxes
            .asSequence()
            .filter { it.left >= railLeft && it.width <= screenW * 0.25f }
            .filter { it.top >= screenH * RAIL_TOP_FRACTION && it.bottom <= screenH * RAIL_BOTTOM_FRACTION }
            .mapNotNull { b -> CountParser.parse(b.text)?.let { b to it } }
            .sortedBy { it.first.top }
            .map { it.second }
            .toList()

        // Số tim: ưu tiên mô tả của CHÍNH nút tim ("Thích video. 449,6K lượt thích") vì không thể nhầm sang
        // bình luận/lưu khi node số tim thiếu trong cây; không có mô tả → phần tử trên cùng của cột phải.
        val likes = likeButtonDescriptions.firstNotNullOfOrNull { likesFromDescription(it) } ?: rail.getOrNull(0)

        // ── Tác giả: "@ten" không chứa dấu cách, ở nửa dưới bên trái, lấy cái cao nhất ──
        val lower = boxes.filter { it.top >= screenH * CAPTION_TOP_FRACTION - screenH * 0.10f && it.left < railLeft }
        val username = lower
            .filter { it.text.trim().startsWith("@") && it.text.trim().length in 2..40 && !it.text.trim().contains(' ') }
            .minByOrNull { it.top }?.text?.trim() ?: ""

        // ── Chú thích: text dài nhất ở nửa dưới bên trái, không phải số đếm / tên / nhạc nền ──
        val captionBoxes = boxes.filter {
            it.top >= screenH * CAPTION_TOP_FRACTION &&
                it.bottom <= screenH * CAPTION_BOTTOM_FRACTION &&
                it.left < railLeft
        }
        val caption = captionBoxes
            .map { it.text.trim() }
            .filter { t ->
                t.length > CAPTION_MIN_LEN &&
                    !t.startsWith("@") &&
                    CountParser.parse(t) == null &&
                    SOUND_HINTS.none { h -> t.lowercase().contains(h) }
            }
            .maxByOrNull { it.length } ?: ""

        // ── Hashtag: trong chú thích + mọi text rời ở vùng chú thích (TikTok đôi khi tách thành node riêng) ──
        val tags = LinkedHashSet<String>()
        HASHTAG_RX.findAll(caption).forEach { tags.add(ContentMatcher.normalizeTag(it.value)) }
        captionBoxes.forEach { b ->
            if (b.text.contains('#')) HASHTAG_RX.findAll(b.text).forEach { tags.add(ContentMatcher.normalizeTag(it.value)) }
        }
        tags.remove("")

        return VideoInfo(
            username = username,
            caption  = caption,
            hashtags = tags.toList(),
            likes    = likes,
            comments = rail.getOrNull(1),
            saves    = rail.getOrNull(2),
            shares   = rail.getOrNull(3),
        )
    }

    /** Lấy số tim từ mô tả nút tim; mô tả không chứa con số → `null`. */
    fun likesFromDescription(desc: String): Long? =
        COUNT_IN_TEXT.findAll(desc).firstNotNullOfOrNull { CountParser.parse(it.groupValues[1]) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Khớp từ khoá / hashtag
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Khớp từ khoá do người dùng nhập với hashtag + chú thích của video.
 *
 * Khác bản cũ (`kw in hashtags` so khớp nguyên văn, `kw in caption` là "chứa chuỗi"):
 *   • Không phân biệt hoa/thường và DẤU tiếng Việt: "xu hướng" khớp "#xuhuong", "Đẹp" khớp "dep".
 *   • Từ khoá có/không có "#" đều được; từ khoá nhiều từ ("xu huong") khớp hashtag viết liền.
 *   • Hashtag: khớp đúng, hoặc hashtag BẮT ĐẦU bằng từ khoá khi từ khoá ≥ 4 ký tự
 *     ("xuhuong" khớp "#xuhuongtiktok"; "vn" không khớp "#vnexpress").
 *   • Chú thích: khớp theo RANH GIỚI TỪ ("hot" không khớp "photo"); từ khoá ≥ 4 ký tự cho phép
 *     kéo dài đuôi ("unbox" khớp "unboxing").
 *   • Tách từ khoá bằng dấu phẩy, chấm phẩy hoặc xuống dòng.
 */
class ContentMatcher private constructor(
    private val tagKeywords: List<String>,      // đã chuẩn hoá, bỏ khoảng trắng (so với hashtag)
    private val captionRegexes: List<Regex>,    // dùng cho chú thích đã chuẩn hoá
) {
    val isEmpty: Boolean get() = tagKeywords.isEmpty()

    fun matchesHashtags(hashtags: List<String>): Boolean {
        if (tagKeywords.isEmpty() || hashtags.isEmpty()) return false
        val folded = hashtags.map { fold(it.removePrefix("#")).replace(" ", "") }
        return tagKeywords.any { k ->
            folded.any { h -> h == k || (k.length >= MIN_PREFIX_LEN && h.startsWith(k)) }
        }
    }

    fun matchesCaption(caption: String): Boolean {
        if (captionRegexes.isEmpty() || caption.isBlank()) return false
        val text = fold(caption)
        return captionRegexes.any { it.containsMatchIn(text) }
    }

    companion object {
        private const val MIN_PREFIX_LEN = 4
        val EMPTY = ContentMatcher(emptyList(), emptyList())

        /** `raw`: "xuhuong, #fyp; viral" hoặc danh sách đã tách. */
        fun of(keywords: List<String>): ContentMatcher {
            val cleaned = keywords.flatMap { splitKeywords(it) }
                .map { fold(it.trim().removePrefix("#")) }
                .filter { it.isNotEmpty() }
                .distinct()
            if (cleaned.isEmpty()) return EMPTY
            val tags = cleaned.map { it.replace(" ", "") }.filter { it.isNotEmpty() }
            val regexes = cleaned.map { k ->
                val tail = if (k.length >= MIN_PREFIX_LEN) "" else "(?![\\p{L}\\p{N}])"
                Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(k) + tail)
            }
            return ContentMatcher(tags, regexes)
        }

        fun parse(raw: String): ContentMatcher = of(listOf(raw))

        private fun splitKeywords(s: String): List<String> = s.split(',', ';', '\n')

        /** "#FYP" → "fyp"; chuẩn hoá hashtag đọc được từ video (giữ dấu để hiển thị, chỉ hạ chữ thường). */
        fun normalizeTag(tag: String): String = tag.trim().removePrefix("#").lowercase()

        /** Hạ chữ thường, bỏ dấu tiếng Việt (đ → d), gộp khoảng trắng. */
        fun fold(s: String): String {
            val lowered = s.lowercase().replace('đ', 'd')
            val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            return decomposed.filter { it.category != CharCategory.NON_SPACING_MARK }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Quyết định tim theo số tim + nội dung
// ─────────────────────────────────────────────────────────────────────────────

/** Kết quả kiểm tra điều kiện số tim tối thiểu. */
enum class LikeCountGate { PASS, TOO_FEW, UNKNOWN }

/**
 * `minLikes <= 0` → tắt, luôn PASS. Còn lại: số tim của video >= `minLikes` → PASS;
 * nhỏ hơn → TOO_FEW; không đọc được số tim → UNKNOWN (nơi gọi bỏ qua tim, không đoán).
 */
fun likeCountGate(minLikes: Int, videoLikes: Long?): LikeCountGate = when {
    minLikes <= 0          -> LikeCountGate.PASS
    videoLikes == null     -> LikeCountGate.UNKNOWN
    videoLikes >= minLikes -> LikeCountGate.PASS
    else                   -> LikeCountGate.TOO_FEW
}
