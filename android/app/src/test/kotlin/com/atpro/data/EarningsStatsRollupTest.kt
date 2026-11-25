package com.atpro.data

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class EarningsStatsRollupTest {
    private val me = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = EarningsRollupPolicy.MS_PER_DAY
    private val now = 2_000L * day + 12 * 3_600_000L

    private fun rollup(daysAgo: Long, kind: String, account: String, coins: Double, jobs: Long) =
        EarningRollup(
            EarningsRollupPolicy.rollupId(me, 2_000L - daysAgo, kind, account),
            me, 2_000L - daysAgo, kind, account, coins, jobs,
        )

    private fun entry(daysAgo: Long, reward: Double, kind: String = "LIKE", account: String = "a", id: String) =
        EarningEntry(now - daysAgo * day, kind, reward, account, id, origin = me)

    @Test fun `tat ca = tho + tong, khong dem doi dong tho da duoc phu`() {
        val raw = listOf(
            entry(1, 10.0, id = "00000000-0000-4000-8000-000000000001"),
            // dòng thô cũ đã được phủ bởi dòng tổng cùng ngày → phải bị bỏ
            entry(100, 99.0, id = "00000000-0000-4000-8000-000000000002"),
        )
        val rollups = listOf(
            rollup(100, "LIKE", "a", coins = 40.0, jobs = 4),
            rollup(120, "FOLLOW", "b", coins = 60.0, jobs = 3),
        )
        val s = summarizeEarnings(raw, now, rangeDays = null, tz = utc, rollups = rollups)

        assertEquals(110.0, s.totalCoins, 1e-9)
        assertEquals(1 + 4 + 3, s.totalJobs)
        assertEquals(50.0, s.likeCoins, 1e-9); assertEquals(5, s.likeJobs)
        assertEquals(60.0, s.followCoins, 1e-9); assertEquals(3, s.followJobs)
        assertEquals(listOf("b" to 60.0, "a" to 50.0), s.topAccounts)
    }

    @Test fun `khoang ngay ngan khong cong dong tong`() {
        val raw = listOf(entry(1, 10.0, id = "00000000-0000-4000-8000-000000000001"))
        val rollups = listOf(rollup(100, "LIKE", "a", coins = 40.0, jobs = 4))
        val s = summarizeEarnings(raw, now, rangeDays = 7, tz = utc, rollups = rollups)
        assertEquals(10.0, s.totalCoins, 1e-9)
        assertEquals(1, s.totalJobs)
    }

    @Test fun `khong truyen rollups thi giu nguyen hanh vi cu`() {
        val raw = listOf(entry(1, 10.0, id = "00000000-0000-4000-8000-000000000001"))
        val s = summarizeEarnings(raw, now, rangeDays = null, tz = utc)
        assertEquals(10.0, s.totalCoins, 1e-9)
    }
}
