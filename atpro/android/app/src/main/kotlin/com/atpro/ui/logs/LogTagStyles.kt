package com.atpro.ui.logs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Assignment
import com.atpro.ui.theme.AtColors
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import com.atpro.data.LogLevel
import com.atpro.data.LogPolish
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
// v1.7.0: bảng TAG_STYLES dựng 1 lần lúc nạp class nên KHÔNG được giữ [Color] cứng (sẽ không đổi theo
// chế độ Sáng/Tối). Mỗi style giữ một [LogTone] và màu thật được tính khi vẽ.
enum class LogTone {
    Green, Amber, RedBad, Blue, Cyan, Purple, Pink, Slate, FacebookBlue;

    val color: Color
        get() = when (this) {
            Green        -> AtColors.Success
            Amber        -> AtColors.Warning
            RedBad       -> AtColors.Danger
            Blue         -> AtColors.Sky
            Cyan         -> AtColors.Info
            Purple       -> AtColors.Accent
            Pink         -> AtColors.Rose
            Slate        -> AtColors.TextSec
            FacebookBlue -> Color(0xFF1877F2)
        }
}

private val Green        = LogTone.Green
private val Amber        = LogTone.Amber
private val RedBad       = LogTone.RedBad
private val Blue         = LogTone.Blue
private val Cyan         = LogTone.Cyan
private val Purple       = LogTone.Purple
private val Pink         = LogTone.Pink
private val Violet       = LogTone.Purple
private val FacebookBlue = LogTone.FacebookBlue
private val Slate        = LogTone.Slate
/** Danh mục cấp cao — dùng để lọc (FilterChip) và làm màu accent-bar mặc định. */
const val LOG_LEVEL_INFO    = "INFO"
const val LOG_LEVEL_WARNING = "WARNING"
const val LOG_LEVEL_ERROR   = "ERROR"
const val LOG_LEVEL_SUCCESS = "SUCCESS"

data class LogTagStyle(
    val tone:     LogTone,
    val icon:     ImageVector,
    val category: String,
) {
    /** Màu hiện hành theo chế độ Sáng/Tối (tính mỗi lần đọc). */
    val color: Color get() = tone.color
}

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
    "LIST"         to LogTagStyle(Cyan,         Icons.AutoMirrored.Rounded.FormatListBulleted, LOG_LEVEL_INFO),
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

    // Task mode (nhiệm vụ kiếm tiền)
    "TASK"         to LogTagStyle(Violet,       Icons.AutoMirrored.Rounded.Assignment,      LOG_LEVEL_INFO),
    "TASK-FOLLOW"  to LogTagStyle(Violet,       Icons.Rounded.PersonAdd,       LOG_LEVEL_INFO),
    "TASK-LIKE"    to LogTagStyle(Violet,       Icons.Rounded.Favorite,        LOG_LEVEL_INFO),

    // Demo nuôi Facebook [v1.2.3]
    "FB"           to LogTagStyle(FacebookBlue, Icons.Rounded.ThumbUp,         LOG_LEVEL_INFO),
    // v1.3.0 — Reels Facebook
    "FB REELS"     to LogTagStyle(FacebookBlue, Icons.Rounded.SmartDisplay,    LOG_LEVEL_INFO),

    // Demo nuôi X / Instagram / Threads / Snapchat [v1.2.4, tag riêng từ v1.3.0]
    "X"            to LogTagStyle(Slate,        Icons.Rounded.Bolt,           LOG_LEVEL_INFO),
    "IG"           to LogTagStyle(Pink,         Icons.Rounded.PhotoCamera,    LOG_LEVEL_INFO),
    "THREADS"      to LogTagStyle(Slate,        Icons.Rounded.AlternateEmail, LOG_LEVEL_INFO),
    "SNAPCHAT"     to LogTagStyle(Amber,        Icons.Rounded.Bolt,           LOG_LEVEL_INFO),
)

/** Kết quả parse 1 dòng log: nhãn (nếu có) + nội dung + style hiển thị + cờ "chỉ để gỡ lỗi". */
data class ParsedLog(
    val tag:    String?,
    val body:   String,
    val style:  LogTagStyle,
    val detail: Boolean = false,
)

/**
 * v1.11.0: "TAG: nội dung" → nhãn tiếng Việt + câu đã gọt qua [LogPolish] (xem file đó: ẩn dòng chỉ để gỡ lỗi,
 * viết lại câu quá thuật ngữ). Icon/màu lấy theo tag gốc, màu theo MỨC ĐỘ hiệu lực (vd job xong → xanh lá).
 * Dòng không có tag (vd "Chờ feed 1/6...") → không hiện nhãn; mốc giai đoạn ">> …" dùng icon Play.
 */
fun parseLogMessage(message: String): ParsedLog {
    val p = LogPolish.polish(message)
    if (message.startsWith(">>")) {
        return ParsedLog(tag = null, body = p.body, style = PHASE_STYLE, detail = p.detail)
    }
    val base = TAG_STYLES[p.rawTag.uppercase()] ?: DEFAULT_STYLE
    val category = when (p.level) {
        LogLevel.ERROR   -> LOG_LEVEL_ERROR
        LogLevel.WARNING -> LOG_LEVEL_WARNING
        LogLevel.SUCCESS -> LOG_LEVEL_SUCCESS
        LogLevel.INFO    -> LOG_LEVEL_INFO
    }
    val tone = when {
        category == base.category      -> base.tone
        p.level == LogLevel.SUCCESS    -> Green
        p.level == LogLevel.WARNING    -> Amber
        p.level == LogLevel.ERROR      -> RedBad
        else                           -> base.tone
    }
    val style = if (category == base.category && tone == base.tone) base else base.copy(tone = tone, category = category)
    return ParsedLog(
        tag    = if (p.rawTag.isEmpty()) null else p.label,
        body   = p.body.ifEmpty { message },
        style  = style,
        detail = p.detail,
    )
}

/**
 * Suy ra "level hiệu lực" của 1 dòng log từ nội dung — dùng cho filter chip.
 * Thay thế hoàn toàn [com.atpro.db.entity.FarmLogEntity.level] (luôn = "INFO").
 */
fun deriveLogLevel(message: String): String = parseLogMessage(message).style.category
