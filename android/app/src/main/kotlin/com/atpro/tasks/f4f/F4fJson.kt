package com.atpro.tasks.f4f

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Bộ đọc JSON "khoan dung" cho response Follow4Follow: server trả số lúc dạng số, lúc dạng chuỗi
 * (vd `coin_received` là chuỗi, `id` là số) nên mọi getter đọc qua nội dung chữ rồi mới ép kiểu.
 * Field thiếu / sai kiểu → `null`, không ném exception.
 */
internal fun parseJson(text: String): JsonElement? =
    try { Json.parseToJsonElement(text) } catch (_: Exception) { null }

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

/** `JsonNull` → null (giống cách `contentOrNull` xử lý). */
internal fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asLong(): Long? {
    val s = asStr()?.trim() ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

internal fun JsonElement?.asInt(): Int? = asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonElement?.asDouble(): Double? = asStr()?.trim()?.toDoubleOrNull()

internal fun JsonElement?.asBool(): Boolean? = asStr()?.trim()?.lowercase()?.toBooleanStrictOrNull()

/** `JsonNull` coi như không có. */
internal fun JsonElement?.nonNull(): JsonElement? = if (this == null || this is JsonNull) null else this

/**
 * Chuỗi số coin/tiền do server trả → số nguyên (làm tròn); null nếu không đọc được.
 * Hiểu: "12345", "12,345", "12.345", "1.234,5", "1,234.5", "12 xu", "1.2K", "3M".
 * (Trước v1.10.0 bảng xếp hạng đọc coin bằng `toLongOrNull()/toDoubleOrNull()` nên "12,345" → 0 và "12.345" → 12.)
 */
internal fun parseCoinText(raw: String?): Long? {
    val src = raw?.trim()?.lowercase() ?: return null
    if (src.isEmpty()) return null
    src.toLongOrNull()?.let { return it }
    var mult = 1.0
    var body = src.replace(Regex("[^0-9.,km]"), "")
    if (body.endsWith("k")) { mult = 1e3; body = body.dropLast(1) }
    else if (body.endsWith("m")) { mult = 1e6; body = body.dropLast(1) }
    body = body.filter { it.isDigit() || it == '.' || it == ',' }
    if (body.none { it.isDigit() }) return null
    val dots = body.count { it == '.' }
    val commas = body.count { it == ',' }
    val lastDot = body.lastIndexOf('.')
    val lastComma = body.lastIndexOf(',')
    val normalized = when {
        dots > 0 && commas > 0 ->
            if (lastDot > lastComma) body.replace(",", "") else body.replace(".", "").replace(',', '.')
        dots > 1   -> body.replace(".", "")
        commas > 1 -> body.replace(",", "")
        dots == 1 && body.length - lastDot - 1 == 3 && mult == 1.0     -> body.replace(".", "")
        commas == 1 && body.length - lastComma - 1 == 3 && mult == 1.0 -> body.replace(",", "")
        commas == 1 -> body.replace(',', '.')
        else -> body
    }
    val v = normalized.toDoubleOrNull() ?: return null
    return Math.round(v * mult)
}

/** Số coin từ JSON: số hoặc chuỗi định dạng (xem [parseCoinText]). */
internal fun JsonElement?.asCoins(): Long? = parseCoinText(asStr())
