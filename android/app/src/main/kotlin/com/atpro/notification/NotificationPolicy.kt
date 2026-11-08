package com.atpro.notification

import java.util.Locale

/**
 * v1.12.0 — Lõi thuần Kotlin của hệ thống thông báo: loại thông báo, tuỳ chọn người dùng, quyết định có đăng/đăng im lặng,
 * chống lặp, và câu chữ. Không phụ thuộc Android → test bằng JUnit (`NotificationPolicyTest`).
 * Phần gọi Android (kênh, PendingIntent, NotificationCompat) nằm ở [AtProNotificationManager].
 */

/** Nhóm thông báo — mỗi nhóm một công tắc riêng + một kênh riêng ở Android. */
enum class NotifCategory {
    /** Checkpoint, ban, phiên Follow4Follow hết hạn, đạt giới hạn, lỗi dừng phiên. */
    ALERT,
    /** Bắt đầu / kết thúc cả phiên nuôi. */
    SESSION,
    /** Từng tài khoản xong. */
    ACCOUNT,
    /** Nhiệm vụ kiếm tiền: bắt đầu, từng acc, tổng kết + xu. */
    TASK,
}

data class NotificationPrefs(
    /** Công tắc tổng (khoá cũ `enable_system_notifications`). */
    val enabled: Boolean = true,
    val alerts: Boolean = true,
    val sessions: Boolean = true,
    val accounts: Boolean = true,
    val tasks: Boolean = true,
    /** Giờ yên tĩnh: trong khoảng này thông báo vẫn hiện nhưng KHÔNG phát âm/rung. */
    val quietEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
) {
    fun allows(category: NotifCategory): Boolean = enabled && when (category) {
        NotifCategory.ALERT   -> alerts
        NotifCategory.SESSION -> sessions
        NotifCategory.ACCOUNT -> accounts
        NotifCategory.TASK    -> tasks
    }
}

enum class NotifDecision { DROP, POST, POST_SILENT }

object NotificationPolicy {

    /** `hour` 0–23. Khoảng qua nửa đêm (22→7) và khoảng trong ngày (13→15) đều đúng; start == end → không bao giờ yên tĩnh. */
    fun isQuiet(prefs: NotificationPrefs, hour: Int): Boolean {
        if (!prefs.quietEnabled) return false
        val s = prefs.quietStartHour.coerceIn(0, 23)
        val e = prefs.quietEndHour.coerceIn(0, 23)
        return when {
            s == e -> false
            s < e  -> hour in s until e
            else   -> hour >= s || hour < e
        }
    }

    fun decide(prefs: NotificationPrefs, category: NotifCategory, hour: Int): NotifDecision = when {
        !prefs.allows(category) -> NotifDecision.DROP
        isQuiet(prefs, hour)    -> NotifDecision.POST_SILENT
        else                    -> NotifDecision.POST
    }
}

/** Chống lặp: cùng một khoá chỉ được đăng lại sau [minIntervalMs] (vd cảnh báo checkpoint của cùng acc). */
class NotificationThrottle(private val minIntervalMs: Long) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev < minIntervalMs && nowMs >= prev) return false
        last[key] = nowMs
        if (last.size > 256) last.entries.removeAll { nowMs - it.value >= minIntervalMs }   // không phình bộ nhớ
        return true
    }

    @Synchronized fun reset() = last.clear()
}

data class NotifText(val title: String, val body: String)

/** Câu chữ thông báo — ngắn, tiếng Việt, không thuật ngữ. */
object NotificationText {

    /** 26.0 → "26"; 2.5 → "2,5"; 1250.75 → "1.250,75" (dấu chấm hàng nghìn, phẩy thập phân kiểu Việt). */
    fun coin(v: Double): String {
        val rounded = Math.round(v * 100) / 100.0
        val intPart = rounded.toLong()
        val frac = Math.round((rounded - intPart) * 100).toInt()
        val head = String.format(Locale.US, "%,d", intPart).replace(',', '.')
        return when {
            frac == 0 -> head
            frac % 10 == 0 -> "$head,${frac / 10}"
            else -> "$head,${String.format(Locale.US, "%02d", frac)}"
        }
    }

    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "dưới 1 phút"
        minutes < 60 -> "$minutes phút"
        minutes % 60 == 0 -> "${minutes / 60} giờ"
        else -> "${minutes / 60} giờ ${minutes % 60} phút"
    }

    /** "@a, @b, @c +2" — tối đa [max] tên. */
    fun accountList(names: List<String>, max: Int = 4): String {
        val shown = names.take(max).joinToString(", ") { "@" + it.removePrefix("@") }
        val more = names.size - max
        return if (more > 0) "$shown +$more" else shown
    }

    fun farmStarted(count: Int) = NotifText("Bắt đầu nuôi", "$count tài khoản")

    fun farmCompleted(count: Int, likes: Int, follows: Int, videos: Int, minutes: Int) = NotifText(
        "Nuôi xong $count tài khoản",
        "Tim $likes · Follow $follows · Xem $videos video · ${duration(minutes)}",
    )

    fun sessionDone(account: String, likes: Int, follows: Int, videos: Int) = NotifText(
        "Xong @${account.removePrefix("@")}",
        "Tim $likes · Follow $follows · Xem $videos video",
    )

    fun taskStarted(names: List<String>) = NotifText(
        "Bắt đầu nhiệm vụ",
        "${names.size} tài khoản: ${accountList(names)}",
    )

    fun taskAccountDone(account: String, jobs: Int, likes: Int, follows: Int, reward: Double) = NotifText(
        "Xong @${account.removePrefix("@")}",
        if (jobs == 0) "Không có job nào được làm"
        else "$jobs job (tim $likes · follow $follows) · +${coin(reward)} xu",
    )

    fun taskCompleted(accounts: Int, jobs: Int, likes: Int, follows: Int, reward: Double, minutes: Int, balance: String?): NotifText {
        val head = if (jobs == 0) "Không có job nào được làm" else "$jobs job · +${coin(reward)} xu"
        val parts = ArrayList<String>()
        parts.add("$accounts tài khoản · tim $likes · follow $follows · ${duration(minutes)}")
        if (!balance.isNullOrBlank()) parts.add("Số dư: $balance")
        return NotifText("Nhiệm vụ xong — $head", parts.joinToString("\n"))
    }

    fun checkpoint(account: String) = NotifText("@${account.removePrefix("@")} bị checkpoint", "TikTok yêu cầu xác minh tài khoản này")

    fun banned(account: String) = NotifText("@${account.removePrefix("@")} bị khoá", "TikTok đã khoá hoặc cấm tài khoản này")

    fun error(error: String, account: String?) = NotifText(
        if (account != null) "Lỗi ở @${account.removePrefix("@")}" else "Lỗi",
        error,
    )

    fun f4fSessionExpired() = NotifText(
        "Follow4Follow cần đăng nhập lại",
        "Phiên đăng nhập đã hết hạn nên nhiệm vụ đã dừng. Mở ứng dụng → Dịch vụ để đăng nhập lại.",
    )

    fun dailyLimit(account: String, detail: String) = NotifText(
        "@${account.removePrefix("@")} đã hết lượt hôm nay",
        detail.ifBlank { "Đã đạt giới hạn job trong ngày" },
    )

    fun taskAborted(reason: String) = NotifText("Nhiệm vụ đã dừng", reason)
}
