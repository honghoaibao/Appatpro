package com.atpro.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Một phiên nuôi acc — đầu vào thuần Kotlin của [summarizeFarmStats] (không phụ thuộc Room → test được bằng JVM).
 * [endedAt] = null nghĩa là phiên chưa đóng (đang chạy hoặc bị ngắt giữa chừng): KHÔNG được tính vào thống kê.
 */
data class FarmSessionRow(
    val accountId:     String,
    val startedAt:     Long,
    val endedAt:       Long?,
    val likes:         Int,
    val follows:       Int,
    val comments:      Int,
    val videos:        Int,
    val durationSecs:  Int,
)

/** Một cột của biểu đồ theo ngày (ngày tính theo GIỜ ĐỊA PHƯƠNG, luôn đủ mọi ngày trong khoảng — ngày không chạy = 0). */
data class FarmDayPoint(val label: String, val likes: Int, val follows: Int, val comments: Int, val videos: Int, val sessions: Int) {
    val interactions: Int get() = likes + follows + comments
}

/** Số liệu một tài khoản trong khoảng đang chọn. */
data class FarmAccountStat(
    val account: String, val likes: Int, val follows: Int, val comments: Int,
    val videos: Int, val sessions: Int, val durationSecs: Long,
) {
    val interactions: Int get() = likes + follows + comments
}

/**
 * Thống kê nuôi acc đã tổng hợp cho khoảng thời gian đang chọn (v1.12.5). Hàm tổng hợp là [summarizeFarmStats].
 *
 * "Tương tác" = thích + follow + bình luận.
 */
data class FarmStatsSummary(
    val sessions:     Int,
    val videos:       Int,
    val likes:        Int,
    val follows:      Int,
    val comments:     Int,
    val durationSecs: Long,
    /** Tương tác của kỳ liền trước có cùng độ dài (để tính % tăng/giảm); 0 nếu chọn "Tất cả". */
    val prevInteractions: Int,
    val todayInteractions: Int,
    val todaySessions:     Int,
    val days:         List<FarmDayPoint>,
    /** 24 phần tử: tương tác theo giờ BẮT ĐẦU phiên (0–23, giờ địa phương). */
    val hourInteractions: List<Int>,
    /** Tối đa 5 tài khoản, giảm dần theo tương tác. */
    val accounts:     List<FarmAccountStat>,
    val bestDay:      FarmDayPoint?,
    /** Số ngày có chạy trong biểu đồ. */
    val activeDays:   Int,
) {
    val isEmpty: Boolean get() = sessions == 0
    val interactions: Int get() = likes + follows + comments

    /** % video có thả tim / follow / bình luận kèm theo (0–100). */
    val likeRate: Float    get() = if (videos > 0) likes    * 100f / videos else 0f
    val followRate: Float  get() = if (videos > 0) follows  * 100f / videos else 0f
    val commentRate: Float get() = if (videos > 0) comments * 100f / videos else 0f

    val avgVideosPerSession: Float get() = if (sessions > 0) videos.toFloat() / sessions else 0f
    val avgDurationSecs: Long      get() = if (sessions > 0) durationSecs / sessions else 0L

    /** % thay đổi tương tác so với kỳ trước; null nếu không có kỳ trước để so sánh. */
    val changePercent: Double? get() = if (prevInteractions > 0) (interactions - prevInteractions) * 100.0 / prevInteractions else null

    companion object {
        val EMPTY = FarmStatsSummary(0, 0, 0, 0, 0, 0L, 0, 0, 0, emptyList(), List(24) { 0 }, emptyList(), null, 0)
    }
}

/**
 * Tổng hợp [all] theo [rangeDays] ngày gần nhất (null = tất cả; biểu đồ theo ngày giới hạn 60 ngày gần nhất).
 *
 * Khác cách tính cũ (SQL `getTotals` / `getDailyStats`), vì các lỗi logic của bản cũ:
 *  • **Ngày theo giờ địa phương** ([tz]) — bản cũ chia ngày theo UTC nên phiên chạy 00:00–07:00 (giờ VN) bị tính sang ngày hôm trước;
 *  • **Chỉ tính phiên đã đóng** — phiên đang chạy / bị ngắt giữa chừng (0 video) làm số phiên phình lên và trung bình bị kéo thấp;
 *  • **Khoảng theo NGÀY LỊCH** (hôm nay + N−1 ngày trước, từ 00:00), không phải N×24 giờ lùi từ giờ hiện tại → cột ngày đầu không còn bị hụt;
 *  • **Biểu đồ đủ mọi ngày** (ngày không chạy = 0) thay vì chỉ các ngày có dữ liệu → khoảng cách giữa các cột đúng thời gian;
 *  • **So với kỳ trước** cùng độ dài, thêm bình luận / thời gian chạy / theo giờ / xếp hạng tài khoản đầy đủ.
 * Phiên được tính vào ngày BẮT ĐẦU. [nowMs] truyền vào để test.
 */
fun summarizeFarmStats(
    all:       List<FarmSessionRow>,
    nowMs:     Long,
    rangeDays: Int?,
    tz:        TimeZone = TimeZone.getDefault(),
): FarmStatsSummary {
    val closed = all.filter { it.endedAt != null }
    val cal = Calendar.getInstance(tz)
    fun startOfDay(ms: Long): Long {
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    fun addDays(ms: Long, n: Int): Long {
        cal.timeInMillis = ms; cal.add(Calendar.DAY_OF_YEAR, n); return cal.timeInMillis
    }
    val dayMs = 86_400_000.0

    val todayStart = startOfDay(nowMs)
    val firstMs = closed.minOfOrNull { it.startedAt }
    val spanDays = (rangeDays
        ?: if (firstMs == null) 1 else Math.round((todayStart - startOfDay(firstMs)) / dayMs).toInt() + 1
        ).coerceIn(1, 60)
    val windowStart = addDays(todayStart, -(spanDays - 1))

    val inRange = if (rangeDays == null) closed else closed.filter { it.startedAt >= windowStart }
    val prevStart = addDays(windowStart, -spanDays)
    val prevInteractions = if (rangeDays == null) 0
        else closed.filter { it.startedAt in prevStart until windowStart }.sumOf { it.likes + it.follows + it.comments }

    fun durationOf(r: FarmSessionRow): Int =
        if (r.durationSecs > 0) r.durationSecs else (((r.endedAt ?: r.startedAt) - r.startedAt) / 1000).toInt().coerceAtLeast(0)

    val likes = IntArray(spanDays); val follows = IntArray(spanDays); val comments = IntArray(spanDays)
    val videos = IntArray(spanDays); val sessions = IntArray(spanDays)
    val hours = IntArray(24)
    val byAccount = LinkedHashMap<String, FarmAccountStat>()
    var todayInteractions = 0; var todaySessions = 0

    for (r in inRange) {
        val idx = Math.round((startOfDay(r.startedAt) - windowStart) / dayMs).toInt()
        if (idx in 0 until spanDays) {
            likes[idx] += r.likes; follows[idx] += r.follows; comments[idx] += r.comments
            videos[idx] += r.videos; sessions[idx]++
        }
        val inter = r.likes + r.follows + r.comments
        cal.timeInMillis = r.startedAt
        hours[cal.get(Calendar.HOUR_OF_DAY)] += inter
        val prev = byAccount[r.accountId]
        byAccount[r.accountId] = FarmAccountStat(
            account = r.accountId,
            likes = (prev?.likes ?: 0) + r.likes, follows = (prev?.follows ?: 0) + r.follows,
            comments = (prev?.comments ?: 0) + r.comments, videos = (prev?.videos ?: 0) + r.videos,
            sessions = (prev?.sessions ?: 0) + 1, durationSecs = (prev?.durationSecs ?: 0L) + durationOf(r),
        )
        if (r.startedAt >= todayStart) { todayInteractions += inter; todaySessions++ }
    }

    val days = (0 until spanDays).map { i ->
        cal.timeInMillis = addDays(windowStart, i)
        val label = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
        FarmDayPoint(label, likes[i], follows[i], comments[i], videos[i], sessions[i])
    }

    return FarmStatsSummary(
        sessions          = inRange.size,
        videos            = inRange.sumOf { it.videos },
        likes             = inRange.sumOf { it.likes },
        follows           = inRange.sumOf { it.follows },
        comments          = inRange.sumOf { it.comments },
        durationSecs      = inRange.sumOf { durationOf(it).toLong() },
        prevInteractions  = prevInteractions,
        todayInteractions = todayInteractions,
        todaySessions     = todaySessions,
        days              = days,
        hourInteractions  = hours.toList(),
        accounts          = byAccount.values.sortedWith(compareByDescending<FarmAccountStat> { it.interactions }.thenByDescending { it.sessions }).take(5),
        bestDay           = days.filter { it.sessions > 0 }.maxByOrNull { it.interactions },
        activeDays        = days.count { it.sessions > 0 },
    )
}

/** 0 → "—"; 45 giây → "45 giây"; 5_400 → "1g 30p"; 600 → "10p". */
fun formatDuration(secs: Long): String {
    if (secs <= 0L) return "—"
    if (secs < 60L) return "$secs giây"
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}g ${m}p"
        h > 0          -> "${h}g"
        else           -> "${m}p"
    }
}
