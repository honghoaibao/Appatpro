package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBatchPolicyTest {
    private fun event(
        sequence: Long,
        dataset: String = "safeConfigs",
        operation: String = "UPSERT",
        payloadBytes: Int = 64,
        nextAttemptAt: Long = 0L,
    ) = SyncPendingEvent(
        eventId = "event-$sequence",
        sequence = sequence,
        dataset = dataset,
        operation = operation,
        payloadBytes = payloadBytes,
        nextAttemptAt = nextAttemptAt,
    )

    @Test
    fun returnsEmptyForAnEmptyOutbox() {
        assertTrue(
            SyncBatchPolicy.plan(emptyList(), nowEpochMillis = 100L, pendingAccountEvents = 0)
                is SyncBatchPlan.Empty,
        )
    }

    @Test
    fun preservesSequenceAndCapsBatchSize() {
        val events = (1L..60L).reversed().map { event(it) }
        val plan = SyncBatchPolicy.plan(events, nowEpochMillis = 100L, pendingAccountEvents = 0)
            as SyncBatchPlan.Candidate

        assertEquals(50, plan.eventCount)
        assertEquals((1L..50L).map { "event-$it" }, plan.eventIds)
    }

    @Test
    fun doesNotSkipAnEarlierRetryWindow() {
        val plan = SyncBatchPolicy.plan(
            pendingEvents = listOf(
                event(1L, nextAttemptAt = 500L),
                event(2L, nextAttemptAt = 0L),
            ),
            nowEpochMillis = 100L,
            pendingAccountEvents = 0,
        )

        assertTrue(plan is SyncBatchPlan.WaitingForRetry)
        assertEquals("event-1", (plan as SyncBatchPlan.WaitingForRetry).eventId)
    }

    @Test
    fun accountEventsGateTheWholeSnapshotBatch() {
        val plan = SyncBatchPolicy.plan(
            pendingEvents = listOf(event(1L)),
            nowEpochMillis = 100L,
            pendingAccountEvents = 3,
        )

        assertTrue(plan is SyncBatchPlan.AccountMergeGate)
        assertEquals(3, (plan as SyncBatchPlan.AccountMergeGate).pendingAccountEvents)
    }

    @Test
    fun rejectsAnOversizedFirstEventWithoutDroppingIt() {
        val plan = SyncBatchPolicy.plan(
            pendingEvents = listOf(
                event(1L, payloadBytes = SyncBatchPolicy.MAX_BATCH_PAYLOAD_BYTES + 1),
            ),
            nowEpochMillis = 100L,
            pendingAccountEvents = 0,
        )

        assertTrue(plan is SyncBatchPlan.OversizedFirstEvent)
        assertEquals("event-1", (plan as SyncBatchPlan.OversizedFirstEvent).eventId)
    }

    @Test
    fun capsCombinedPayloadBytes() {
        val max = SyncBatchPolicy.MAX_BATCH_PAYLOAD_BYTES
        val plan = SyncBatchPolicy.plan(
            pendingEvents = listOf(
                event(1L, payloadBytes = max / 2),
                event(2L, payloadBytes = max / 2),
                event(3L, payloadBytes = 1),
            ),
            nowEpochMillis = 100L,
            pendingAccountEvents = 0,
        ) as SyncBatchPlan.Candidate

        assertEquals(2, plan.eventCount)
        assertEquals(max, plan.payloadBytes)
    }
}
