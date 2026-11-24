package com.atpro.data

/**
 * Nền tảng hỗ trợ giao diện "nuôi bằng cookie" (DEMO — v1.12.5).
 *
 * [idKey] = khoá chứa ID tài khoản; [requiredKeys] = các khoá cookie cần có để coi là "đủ" (kiểm tra cục bộ, không gọi mạng).
 */
enum class CookiePlatform(val displayName: String, val idKey: String, val requiredKeys: List<String>) {
    FACEBOOK("Facebook", "c_user", listOf("c_user", "xs")),
    INSTAGRAM("Instagram", "ds_user_id", listOf("sessionid", "ds_user_id")),
}

/**
 * Kết quả đọc một chuỗi cookie. [present]/[missing] theo thứ tự [CookiePlatform.requiredKeys].
 * Chỉ giữ TÊN khoá và ID tài khoản (không phải bí mật) — giá trị các khoá khác (xs, sessionid…) bị bỏ ngay sau khi đọc.
 */
data class CookieCheck(
    val platform:  CookiePlatform,
    val accountId: String?,
    val present:   List<String>,
    val missing:   List<String>,
) {
    /** Có ID tài khoản và đủ mọi khoá bắt buộc. */
    val isComplete: Boolean get() = accountId != null && missing.isEmpty()
}

/**
 * CookieDemo — đọc cookie cho giao diện demo. Hàm thuần (không phụ thuộc Android) → kiểm thử bằng JVM.
 *
 * Nhận ba dạng thường gặp khi sao chép cookie:
 *  • chuỗi header: `c_user=1000; xs=abc; fr=...` (cũng nhận xuống dòng thay cho `;`);
 *  • JSON xuất từ tiện ích trình duyệt: `[{"name":"c_user","value":"1000", ...}, ...]`;
 *  • Netscape (cookies.txt): 7 cột cách nhau bằng tab, tên ở cột 6, giá trị ở cột 7.
 */
object CookieDemo {

    /** Số cookie tối đa trong danh sách demo. */
    const val MAX_ITEMS = 20
    private const val MAX_INPUT = 20_000

    private val JSON_OBJ  = Regex("""\{[^{}]*\}""")
    private val JSON_NAME = Regex(""""name"\s*:\s*"([^"]*)"""")
    private val JSON_VAL  = Regex(""""value"\s*:\s*"([^"]*)"""")

    /** null = không đọc được cặp `tên=giá trị` nào (không phải cookie). */
    fun parse(raw: String, platform: CookiePlatform): CookieCheck? {
        val pairs = parsePairs(raw) ?: return null
        val present = platform.requiredKeys.filter { !pairs[it].isNullOrBlank() }
        val missing = platform.requiredKeys.filter { it !in present }
        return CookieCheck(platform, accountId(pairs, platform), present, missing)
    }

    /**
     * v1.12.7: tách riêng bước đọc để [CookieVaultCodec] dùng lại. Trả về cặp `tên(chữ thường) → giá trị`
     * theo thứ tự xuất hiện, hoặc null nếu không đọc được cặp nào. GIÁ TRỊ là bí mật đăng nhập — người gọi không được
     * log và chỉ được giữ trong bộ nhớ hoặc kho mã hoá.
     */
    fun parsePairs(raw: String): Map<String, String>? {
        val text = raw.trim().take(MAX_INPUT)
        if (text.isEmpty()) return null

        val pairs = LinkedHashMap<String, String>()
        if (text.startsWith("[") || text.startsWith("{")) {
            for (obj in JSON_OBJ.findAll(text)) {
                val name  = JSON_NAME.find(obj.value)?.groupValues?.get(1)?.trim().orEmpty()
                val value = JSON_VAL.find(obj.value)?.groupValues?.get(1)?.trim().orEmpty()
                if (name.isNotEmpty()) pairs[name.lowercase()] = value
            }
        } else {
            for (line in text.split('\n', '\r')) {
                val cols = line.split('\t')
                if (cols.size >= 7 && !line.trimStart().startsWith("#")) {         // Netscape
                    val name = cols[5].trim()
                    if (name.isNotEmpty()) pairs[name.lowercase()] = cols[6].trim()
                    continue
                }
                for (token in line.split(';')) {                                    // header / từng dòng name=value
                    val eq = token.indexOf('=')
                    if (eq <= 0) continue
                    val name = token.substring(0, eq).trim()
                    if (name.isNotEmpty() && name.none { it.isWhitespace() }) pairs[name.lowercase()] = token.substring(eq + 1).trim()
                }
            }
        }
        return pairs.ifEmpty { null }
    }

    /** ID tài khoản: khoá ID nếu có; Instagram còn suy ra được từ tiền tố của `sessionid` ("123%3Aabc…"). */
    internal fun accountId(pairs: Map<String, String>, platform: CookiePlatform): String? {
        pairs[platform.idKey]?.takeIf { it.isNotBlank() }?.let { return it.filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.ifEmpty { null } }
        if (platform == CookiePlatform.INSTAGRAM) {
            val session = pairs["sessionid"].orEmpty()
            val prefix = session.substringBefore("%3A").substringBefore(':')
            if (prefix.isNotEmpty() && prefix.all { it.isDigit() }) return prefix
        }
        return null
    }
}
