package com.atpro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsRollupPolicyTest {
    private val me = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val day = EarningsRollupPolicy.MS_PER_DAY
    private val today = 1_000L                       // epochDay của "bây giờ"
    private val now = today * day + 3_600_000L

    private var seq = 0
    private fun entry(daysAgo: Long, reward: Double = 1.0, kind: String = "LIKE", account: String = "acc", origin: String? = me): EarningEntry {
        seq++
        return EarningEntry(
            atMs = (today - daysAgo) * day + 1_000L,
            kind = kind, reward = reward, account = account,
            id = "00000000-0000-4000-8000-%012d".format(seq), origin = origin,
        )
    }

    @Test fun `epochDay dung cho moc am va ranh gioi ngay`() {
        assertEquals(0L, EarningsRollupPolicy.epochDay(0L))
        assertEquals(0L, EarningsRollupPolicy.epochDay(day - 1))
        assertEquals(1L, EarningsRollupPolicy.epochDay(day))
        assertEquals(-1L, EarningsRollupPolicy.epochDay(-1L))
    }

    @Test fun `chi nen ngay cu hon 60 ngay cua chinh may nay`() {
        val old1 = entry(61, 2.0); val old2 = entry(61, 3.0)
        val old3 = entry(90, 5.0, kind = "FOLLOW")
        val recent = entry(10)
        val foreign = entry(100, origin = other)
        val legacy = entry(100, origin = null)

        val r = EarningsRollupPolicy.compact(listOf(old1, old2, old3, recent, foreign, legacy), emptyList(), me, now)

        assertTrue(r.changed)
        assertEquals(setOf(recent.id, foreign.id, legacy.id), r.keptEntries.map { it.id }.toSet())
        assertEquals(2, r.rollups.size)
        val like = r.rollups.single { it.kind == "LIKE" }
        assertEquals(5.0, like.coins, 0.0); assertEquals(2L, like.jobs); assertEquals(today - 61, like.epochDay)
        assertEquals(me, like.origin)
        assertEquals(EarningsRollupPolicy.rollupId(me, today - 61, "LIKE", "acc"), like.id)
    }

    @Test fun `khong nen ngay con trong cua so 60 ngay khi chua qua nguong`() {
        val r = EarningsRollupPolicy.compact(listOf(entry(60), entry(30), entry(2)), emptyList(), me, now)
        assertFalse(r.changed)
        assertEquals(3, r.keptEntries.size)
    }

    @Test fun `nen idempotent - chay lai khong doi gi`() {
        val first = EarningsRollupPolicy.compact(listOf(entry(70), entry(5)), emptyList(), me, now)
        val second = EarningsRollupPolicy.compact(first.keptEntries, first.rollups, me, now)
        assertFalse(second.changed)
        assertEquals(first.rollups, second.rollups)
        assertEquals(first.keptEntries, second.keptEntries)
    }

    @Test fun `qua nguong thi nen them ngay cu nhat nhung khong dung 2 ngay gan nhat`() {
        val many = ArrayList<EarningEntry>()
        repeat(21_000) { many += entry(daysAgo = 3 + (it % 50).toLong()) }   // 3..52 ngày trước
        many += entry(1); many += entry(0)
        val r = EarningsRollupPolicy.compact(many, emptyList(), me, now)

        assertTrue(r.changed)
        assertTrue(r.keptEntries.size <= EarningsRollupPolicy.COMPACT_TARGET_RAW + 2000) // dừng khi về mức đích (ngày cuối có thể vượt chút)
        assertTrue(r.keptEntries.size < many.size)
        // hôm nay và hôm qua không bao giờ bị nén
        assertTrue(r.keptEntries.any { EarningsRollupPolicy.epochDay(it.atMs) == today })
        assertTrue(r.keptEntries.any { EarningsRollupPolicy.epochDay(it.atMs) == today - 1 })
        // tổng xu được bảo toàn
        val before = many.sumOf { it.reward }
        val after = r.keptEntries.sumOf { it.reward } + r.rollups.sumOf { it.coins }
        assertEquals(before, after, 1e-6)
    }

    @Test fun `ngay da co dong tong thi dong tho cung ngay bi loai - khong dem doi`() {
        val e1 = entry(80, 4.0)
        val rollup = EarningRollup(
            EarningsRollupPolicy.rollupId(me, today - 80, "LIKE", "acc"), me, today - 80, "LIKE", "acc", 4.0, 1L,
        )
        // dòng thô của cùng (máy, ngày) quay lại từ đám mây cũ
        val dropped = EarningsRollupPolicy.dropCovered(listOf(e1, entry(80, origin = other), entry(80, origin = null)), listOf(rollup))
        assertEquals(2, dropped.size)
        assertFalse(dropped.any { it.id == e1.id })

        val r = EarningsRollupPolicy.compact(listOf(e1), listOf(rollup), me, now)
        assertTrue(r.changed)                  // phải ghi lại: dòng thô bị loại
        assertTrue(r.keptEntries.isEmpty())
        assertEquals(listOf(rollup), r.rollups)
    }

    @Test fun `mergeRollups chon ban lon hon va khong phu thuoc thu tu`() {
        val id = EarningsRollupPolicy.rollupId(me, 5L, "LIKE", "acc")
        val a = EarningRollup(id, me, 5L, "LIKE", "acc", 10.0, 4L)
        val b = a.copy(coins = 12.0, jobs = 5L)
        val c = EarningRollup(EarningsRollupPolicy.rollupId(other, 5L, "LIKE", "acc"), other, 5L, "LIKE", "acc", 1.0, 1L)
        val ab = EarningsRollupPolicy.mergeRollups(listOf(a, c), listOf(b))
        val ba = EarningsRollupPolicy.mergeRollups(listOf(b), listOf(c, a))
        assertEquals(ab, ba)
        assertEquals(2, ab.size)
        assertEquals(5L, ab.single { it.origin == me }.jobs)
    }

    @Test fun `khong nen day khi dong tong dat tran cuc bo`() {
        val rollups = (0 until EarningsRollupPolicy.LOCAL_ROLLUP_CAP).map {
            EarningRollup(EarningsRollupPolicy.rollupId(me, -(it + 1).toLong(), "LIKE", "acc"), me, -(it + 1).toLong(), "LIKE", "acc", 1.0, 1L)
        }
        val e = entry(100)
        val r = EarningsRollupPolicy.compact(listOf(e), rollups, me, now)
        assertFalse(r.changed)
        assertEquals(listOf(e), r.keptEntries)
    }
}
