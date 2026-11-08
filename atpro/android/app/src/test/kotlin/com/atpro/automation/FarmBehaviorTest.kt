package com.atpro.automation

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * FarmBehaviorTest — unit test cho [FarmBehavior] và [RateGate] (v1.4.0).
 * RNG có seed cố định → kết quả lặp lại được; không cần MockK/Robolectric.
 */
class FarmBehaviorTest {

    // ── RateGate ─────────────────────────────────────────────────────────

    @Test
    fun `rate 0 luon false va rate 1 luon true`() {
        val gate = RateGate(rng = Random(1))
        repeat(200) { assertFalse(gate.decide(0f)) }
        repeat(200) { assertTrue(gate.decide(1f)) }
    }

    @Test
    fun `ti le dai han bam sat cau hinh`() {
        for (rate in listOf(0.1f, 0.3f, 0.5f)) {
            val gate = RateGate(rng = Random(42))
            val n = 20_000
            val hits = (1..n).count { gate.decide(rate) }
            val realized = hits.toDouble() / n
            assertTrue("rate=$rate realized=$realized", abs(realized - rate) < 0.03)
        }
    }

    @Test
    fun `khong co chuoi like lien tiep vuot tran khi rate duoi 0,6`() {
        val gate = RateGate(maxStreak = 4, rng = Random(7))
        var streak = 0
        var maxSeen = 0
        repeat(20_000) {
            if (gate.decide(0.5f)) { streak++; maxSeen = maxOf(maxSeen, streak) } else streak = 0
        }
        assertTrue("maxSeen=$maxSeen", maxSeen <= 4)
    }

    @Test
    fun `phuong sai phien ngan thap hon tung xuc xac doc lap`() {
        val rate = 0.3f
        val sessions = 2_000
        val videos = 30
        val rng = Random(2026)
        val counts = (1..sessions).map {
            val gate = RateGate(rng = rng)
            (1..videos).count { gate.decide(rate) }
        }
        val mean = counts.average()
        val std = sqrt(counts.map { (it - mean) * (it - mean) }.average())
        val binomialStd = sqrt(videos * rate * (1 - rate).toDouble())
        assertTrue("std=$std binomial=$binomialStd", std < binomialStd * 0.85)
        assertEquals(videos * rate.toDouble(), mean, 0.6)
    }

    @Test
    fun `trong so quan tam trung binh 1 khong lam lech ti le`() {
        val rng = Random(99)
        val gate = RateGate(rng = rng)
        val n = 20_000
        val hits = (1..n).count { gate.decide(0.3f, FarmBehavior.interestWeight(rng.nextDouble())) }
        assertEquals(0.3, hits.toDouble() / n, 0.03)
    }

    // ── FarmBehavior ─────────────────────────────────────────────────────

    @Test
    fun `paceFactor di tu 0,9 den 1,1 va trung binh 1`() {
        assertEquals(0.9, FarmBehavior.paceFactor(0.0), 1e-9)
        assertEquals(1.1, FarmBehavior.paceFactor(1.0), 1e-9)
        assertEquals(1.0, FarmBehavior.paceFactor(0.5), 1e-9)
        assertEquals(0.9, FarmBehavior.paceFactor(-5.0), 1e-9)   // kẹp biên
        assertEquals(1.1, FarmBehavior.paceFactor(5.0), 1e-9)
    }

    @Test
    fun `watchPlan khong bao gio duoi muc toi thieu va interest trong 0 den 1`() {
        val rng = Random(5)
        repeat(2_000) {
            val plan = FarmBehavior.watchPlan(3.0, 8.0, rng.nextDouble(), rng)
            assertTrue(plan.ms >= 3_000L)
            assertTrue(plan.ms <= (8_000 * 1.1).toLong())
            assertTrue(plan.interest in 0.0..1.0)
        }
    }

    @Test
    fun `watchPlan min bang max van hop le`() {
        val plan = FarmBehavior.watchPlan(4.0, 4.0, 0.5, Random(1))
        assertEquals(4_000L, plan.ms)
        assertEquals(0.5, plan.interest, 1e-9)
    }

    @Test
    fun `interestWeight nam trong 0,6 den 1,4`() {
        assertEquals(0.6, FarmBehavior.interestWeight(0.0), 1e-9)
        assertEquals(1.0, FarmBehavior.interestWeight(0.5), 1e-9)
        assertEquals(1.4, FarmBehavior.interestWeight(1.0), 1e-9)
    }
}
