package com.atpro.data

import java.util.Calendar
import java.util.TimeZone

/** Một cột của biểu đồ theo ngày. [coins] = tổng xu trong ngày, [jobs] = số job. */
data class DayPoint(val label: String, val coins: Double, val jobs: Int, val likeCoins: Double, val followCoins: Double)

/**
 * Số liệu kiếm tiền đã tổng hợp cho khoảng thời gian đang chọn (v1.10.0).
 * Hàm thuần — không phụ thuộc Android → kiểm thử được bằng JVM thường.
 */
data class EarningsSummary(
    val totalCoins:   Double,
    val totalJobs:    Int,
    val todayCoins:   Double,
    val todayJobs:    Int,
    /** Tổng xu của kỳ liền trước có cùng độ dài (để tính % tăng/giảm); 0 nếu chọn "Tất cả". */
    val prevCoins:    Double,
    val avgPerJob:    Double,
    val days:         List<DayPoint>,
    /** Xu cộng dồn theo từng ngày trong [days]. */
    val cumulative:   List<Float>,
    val likeCoins:    Double,
    val likeJobs:     Int,
    val followCoins:  Double,
    val followJobs:   Int,
    /** 24 phần tử: xu theo giờ trong ngày (0–23). */
    val hourCoins:    List<Double>,
    /** Tối đa 5 tài khoản, giảm dần theo xu. */
    val topAccounts:  List<Pair<String, Double>>,
    val bestDay:      DayPoint?,
) {
    val isEmpty: Boolean get() = totalJobs == 0
    /** % thay đổi so với kỳ trước; null nếu không có kỳ trước để so sánh. */
    val changePercent: Double? get() = if (prevCoins > 0.0) (totalCoins - prevCoins) / prevCoins * 100.0 else null

    companion object {
        val EMPTY = EarningsSummary(0.0, 0, 0.0, 0, 0.0, 0.0, emptyList(), emptyList(), 0.0, 0, 0.0, 0, List(24) { 0.0 }, emptyList(), null)
    }
}

/**
 * Tổng hợp [all] theo [rangeDays] ngày gần nhất (null = tất cả; biểu đồ theo ngày giới hạn 60 ngày gần nhất).
 * Ngày tính theo [tz] (mặc định múi giờ máy). `nowMs` truyền vào để test.
 */
fun summarizeEarnings(
    all:       List<EarningEntry>,
    nowMs:     Long,
    rangeDays: Int?,
    tz:        TimeZone = TimeZone.getDefault(),
): EarningsSummary {
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
    val firstMs = all.minOfOrNull { it.atMs }
    val spanDays = (rangeDays
        ?: if (firstMs == null) 1 else Math.round((todayStart - startOfDay(firstMs)) / dayMs).toInt() + 1
        ).coerceIn(1, 60)
    val windowStart = addDays(todayStart, -(spanDays - 1))

    val inRange = if (rangeDays == null) all else all.filter { it.atMs >= windowStart }
    val prevStart = addDays(windowStart, -spanDays)
    val prevCoins = if (rangeDays == null) 0.0
        else all.filter { it.atMs in prevStart until windowStart }.sumOf { it.reward }

    // Cột theo ngày (chỉ tính các entry nằm trong cửa sổ biểu đồ)
    val coins  = DoubleArray(spanDays); val jobs = IntArray(spanDays)
    val likeC  = DoubleArray(spanDays); val followC = DoubleArray(spanDays)
    val hours  = DoubleArray(24)
    var likeCoins = 0.0; var likeJobs = 0; var followCoins = 0.0; var followJobs = 0
    val byAccount = HashMap<String, Double>()
    var todayCoins = 0.0; var todayJobs = 0

    for (e in inRange) {
        val idx = Math.round((startOfDay(e.atMs) - windowStart) / dayMs).toInt()
        if (idx in 0 until spanDays) {
            coins[idx] += e.reward; jobs[idx]++
            if (e.kind == "LIKE") likeC[idx] += e.reward else followC[idx] += e.reward
        }
        if (e.kind == "LIKE") { likeCoins += e.reward; likeJobs++ } else { followCoins += e.reward; followJobs++ }
        cal.timeInMillis = e.atMs
        hours[cal.get(Calendar.HOUR_OF_DAY)] += e.reward
        byAccount[e.account] = (byAccount[e.account] ?: 0.0) + e.reward
        if (e.atMs >= todayStart) { todayCoins += e.reward; todayJobs++ }
    }

    val days = (0 until spanDays).map { i ->
        cal.timeInMillis = addDays(windowStart, i)
        val label = "%02d/%02d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
        DayPoint(label, coins[i], jobs[i], likeC[i], followC[i])
    }
    var run = 0.0
    val cumulative = days.map { run += it.coins; run.toFloat() }
    val totalCoins = inRange.sumOf { it.reward }
    val totalJobs  = inRange.size

    return EarningsSummary(
        totalCoins  = totalCoins,
        totalJobs   = totalJobs,
        todayCoins  = todayCoins,
        todayJobs   = todayJobs,
        prevCoins   = prevCoins,
        avgPerJob   = if (totalJobs > 0) totalCoins / totalJobs else 0.0,
        days        = days,
        cumulative  = cumulative,
        likeCoins   = likeCoins,  likeJobs   = likeJobs,
        followCoins = followCoins, followJobs = followJobs,
        hourCoins   = hours.toList(),
        topAccounts = byAccount.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        bestDay     = days.filter { it.jobs > 0 }.maxByOrNull { it.coins },
    )
}
