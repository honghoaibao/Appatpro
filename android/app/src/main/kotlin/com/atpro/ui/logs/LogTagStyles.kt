package com.atpro.ui.logs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * v1.2.3 — Phân loại + style cho từng "tag" log (ACC:, LIKE:, ERR:, WARN:...).
 *
 * Trước v1.2.3: [com.atpro.db.entity.FarmLogEntity.level] LUÔN là "INFO" (engine
 * không bao giờ truyền level khác) → filter chip Cảnh báo/Lỗi/Thành công ở
 * LogsScreen không hoạt động, và mọi dòng log trông giống nhau (monospace thô).
 *
 * v1.2.3: suy ra "category" (INFO/WARNING/ERROR/SUCCESS) + màu/icon TỪ TIỀN TỐ
 * của message (vd "ERR:", "WARN:", "OK:"...) — không cần đổi schema DB.
 * Dùng chung bởi LogsViewModel (filter) và LogsScreen (hiển thị).
 */

// ── Màu sắc dùng chung ───────────────────────────────────────────────────────
private val Green        = Color(0xFF10B981)
private val Amber        = Color(0xFFF59E0B)
private val RedBad       = Color(0xFFEF4444)
private val Blue         = Color(0xFF60A5FA)
private val Cyan         = Color(0xFF69C9D0)
private val Purple       = Color(0xFF6C63FF)
private val Pink         = Color(0xFFEC4899)
private val Violet       = Color(0xFF8B5CF6)
private val FacebookBlue = Color(0xFF1877F2)
private val Slate        = Color(0xFF9CA3AF)

/** Danh mục cấp cao — dùng để lọc (FilterChip) và làm màu accent-bar mặc định. */
const val LOG_LEVEL_INFO    = "INFO"
const val LOG_LEVEL_WARNING = "WARNING"
const val LOG_LEVEL_ERROR   = "ERROR"
const val LOG_LEVEL_SUCCESS = "SUCCESS"

data class LogTagStyle(
    val color:    Color,
    val icon:     ImageVector,
    val category: String,
)

private val DEFAULT_STYLE = LogTagStyle(Blue, Icons.Rounded.Info, LOG_LEVEL_INFO)
private val PHASE_STYLE   = LogTagStyle(Purple, Icons.Rounded.PlayArrow, LOG_LEVEL_INFO)

/**
 * Bảng style theo tag — tag là phần TRƯỚC dấu ":" đầu tiên của message
 * (vd "ERR: Lỗi vòng farm..." → tag = "ERR").
 */
private val TAG_STYLES: Map<String, LogTagStyle> = mapOf(
    // Lỗi
    "ERR"          to LogTagStyle(RedBad,       Icons.Rounded.ErrorOutline,    LOG_LEVEL_ERROR),
    "DEL"          to LogTagStyle(RedBad,       Icons.Rounded.DeleteOutline,   LOG_LEVEL_WARNING),

    // Cảnh báo / khôi phục
    "WARN"         to LogTagStyle(Amber,        Icons.Rounded.WarningAmber,    LOG_LEVEL_WARNING),
    "LIMIT"        to LogTagStyle(Amber,        Icons.Rounded.Timer,           LOG_LEVEL_WARNING),
    "WDG"          to LogTagStyle(Amber,        Icons.Rounded.WarningAmber,    LOG_LEVEL_WARNING),
    "WDG-RECOVER"  to LogTagStyle(Amber,        Icons.Rounded.Healing,         LOG_LEVEL_WARNING),
    "RECOVER"      to LogTagStyle(Amber,        Icons.Rounded.Healing,         LOG_LEVEL_WARNING),
    "LIVE-FEED"    to LogTagStyle(Amber,        Icons.Rounded.LiveTv,          LOG_LEVEL_WARNING),
    "WELLBEING"    to LogTagStyle(Amber,        Icons.Rounded.SelfImprovement, LOG_LEVEL_WARNING),
    "LIKE SKIP"    to LogTagStyle(Amber,        Icons.Rounded.ThumbDown,       LOG_LEVEL_WARNING),
    "SKIP"         to LogTagStyle(Amber,        Icons.Rounded.SkipNext,        LOG_LEVEL_WARNING),

    // Thành công
    "OK"           to LogTagStyle(Green,        Icons.Rounded.CheckCircle,     LOG_LEVEL_SUCCESS),
    "DONE"         to LogTagStyle(Green,        Icons.Rounded.CheckCircle,     LOG_LEVEL_SUCCESS),
    "SAVE"         to LogTagStyle(Green,        Icons.Rounded.Save,            LOG_LEVEL_SUCCESS),

    // Hành động — Info nhưng có màu riêng cho dễ phân biệt nhanh
    "ACC"          to LogTagStyle(Cyan,         Icons.Rounded.AccountCircle,   LOG_LEVEL_INFO),
    "SWITCH"       to LogTagStyle(Purple,       Icons.Rounded.SwapHoriz,       LOG_LEVEL_INFO),
    "SCAN"         to LogTagStyle(Cyan,         Icons.Rounded.Search,          LOG_LEVEL_INFO),
    "LIST"         to LogTagStyle(Cyan,         Icons.Rounded.FormatListBulleted, LOG_LEVEL_INFO),
    "RELOAD"       to LogTagStyle(Purple,       Icons.Rounded.Refresh,         LOG_LEVEL_INFO),
    "DEV"          to LogTagStyle(Slate,        Icons.Rounded.PhoneAndroid,    LOG_LEVEL_INFO),
    "CFG"          to LogTagStyle(Slate,        Icons.Rounded.Settings,        LOG_LEVEL_INFO),

    "WATCH"        to LogTagStyle(Blue,         Icons.Rounded.PlayCircle,      LOG_LEVEL_INFO),
    "FOLLOW"       to LogTagStyle(Green,        Icons.Rounded.PersonAdd,       LOG_LEVEL_INFO),
    "CMT"          to LogTagStyle(Blue,         Icons.Rounded.ChatBubble,      LOG_LEVEL_INFO),
    "REST"         to LogTagStyle(Amber,        Icons.Rounded.Bedtime,         LOG_LEVEL_INFO),
    "INBOX"        to LogTagStyle(Blue,         Icons.Rounded.MarkEmailUnread, LOG_LEVEL_INFO),
    "SHOP"         to LogTagStyle(Blue,         Icons.Rounded.Storefront,      LOG_LEVEL_INFO),
    "SEARCH"       to LogTagStyle(Blue,         Icons.Rounded.Search,          LOG_LEVEL_INFO),

    // Task mode (Golike)
    "TASK"         to LogTagStyle(Violet,       Icons.Rounded.Assignment,      LOG_LEVEL_INFO),
    "TASK-FOLLOW"  to LogTagStyle(Violet,       Icons.Rounded.PersonAdd,       LOG_LEVEL_INFO),
    "TASK-LIKE"    to LogTagStyle(Violet,       Icons.Rounded.Favorite,        LOG_LEVEL_INFO),

    // Demo nuôi Facebook [v1.2.3]
    "FB"           to LogTagStyle(FacebookBlue, Icons.Rounded.ThumbUp,         LOG_LEVEL_INFO),
)

/** Kết quả parse 1 dòng log: tag (nếu có) + nội dung + style hiển thị. */
data class ParsedLog(
    val tag:   String?,
    val body:  String,
    val style: LogTagStyle,
)

/**
 * Tách "TAG: nội dung" → ParsedLog(tag="TAG", body="nội dung", style=...).
 * Hỗ trợ tag có dấu gạch ngang hoặc 1 từ phụ (vd "WDG-RECOVER", "LIKE SKIP",
 * "TASK-FOLLOW"). Message không khớp pattern (vd ">> [Phase 1] ...") → tag=null.
 */
fun parseLogMessage(message: String): ParsedLog {
    // ">> ..." — log mốc giai đoạn (phase marker)
    if (message.startsWith(">>")) {
        return ParsedLog(tag = null, body = message.removePrefix(">>").trim(), style = PHASE_STYLE)
    }

    val match = TAG_REGEX.find(message)
    if (match != null) {
        val tag  = match.groupValues[1].trim()
        val body = message.substring(match.range.last + 1).trim()
        val style = TAG_STYLES[tag.uppercase()] ?: DEFAULT_STYLE
        return ParsedLog(tag = tag, body = body.ifEmpty { message }, style = style)
    }

    return ParsedLog(tag = null, body = message, style = DEFAULT_STYLE)
}

/** Regex: 1–2 từ IN HOA (chữ/số/gạch ngang/gạch dưới) theo sau bởi ":". */
private val TAG_REGEX = Regex("^([A-Z][A-Z0-9_-]*(?: [A-Z][A-Z0-9_-]*)?):")

/**
 * Suy ra "level hiệu lực" của 1 dòng log từ nội dung — dùng cho filter chip.
 * Thay thế hoàn toàn [com.atpro.db.entity.FarmLogEntity.level] (luôn = "INFO").
 */
fun deriveLogLevel(message: String): String = parseLogMessage(message).style.category
