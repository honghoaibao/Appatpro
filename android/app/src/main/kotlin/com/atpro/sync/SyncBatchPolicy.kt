package com.atpro.sync

/**
 * Metadata-only projection of one Outbox row. The scheduler never loads event payloads
 * just to decide whether a bounded batch could be prepared.
 */
data class SyncPendingEvent(
    val eventId: String,
    val sequence: Long,
    val dataset: String,
    val operation: String,
    val payloadBytes: Int,
    val nextAttemptAt: Long,
)

sealed interface SyncBatchPlan {
    data object Empty : SyncBatchPlan
    data class WaitingForRetry(val eventId: String, val nextAttemptAt: Long) : SyncBatchPlan
    data class InvalidEvent(val eventId: String) : SyncBatchPlan
    data class OversizedFirstEvent(val eventId: String, val payloadBytes: Int) : SyncBatchPlan
    data class Candidate(
        val eventIds: List<String>,
        val eventCount: Int,
        val payloadBytes: Int,
    ) : SyncBatchPlan
}

/**
 * Bounded, sequence-preserving local batch planner.
 *
 * It does not acknowledge, mutate, upload or restore anything. Account events are eligible
 * because the deterministic Phase 5.3 merge engine handles them and returns explicit conflicts.
 */
object SyncBatchPolicy {
    const val MAX_EVENTS_PER_BATCH = 50
    const val MAX_BATCH_PAYLOAD_BYTES = 512 * 1024
    const val MAX_PENDING_EVENTS_TO_INSPECT = MAX_EVENTS_PER_BATCH

    fun plan(
        pendingEvents: List<SyncPendingEvent>,
        nowEpochMillis: Long,
    ): SyncBatchPlan {
        if (pendingEvents.isEmpty()) return SyncBatchPlan.Empty

        val ordered = pendingEvents.sortedBy { it.sequence }
        val selectedIds = ArrayList<String>(minOf(ordered.size, MAX_EVENTS_PER_BATCH))
        var selectedBytes = 0

        for (event in ordered) {
            if (selectedIds.size >= MAX_EVENTS_PER_BATCH) break
            if (event.eventId.isBlank() || event.sequence <= 0L ||
                event.payloadBytes < 0 ||
                event.operation != OPERATION_UPSERT && event.operation != OPERATION_DELETE
            ) {
                if (selectedIds.isEmpty()) return SyncBatchPlan.InvalidEvent(event.eventId)
                break
            }

            // Preserve global Outbox sequence: a delayed head item blocks later events.
            if (event.nextAttemptAt > nowEpochMillis) {
                if (selectedIds.isEmpty()) {
                    return SyncBatchPlan.WaitingForRetry(event.eventId, event.nextAttemptAt)
                }
                break
            }

            if (event.payloadBytes > MAX_BATCH_PAYLOAD_BYTES) {
                if (selectedIds.isEmpty()) {
                    return SyncBatchPlan.OversizedFirstEvent(event.eventId, event.payloadBytes)
                }
                break
            }

            if (selectedBytes + event.payloadBytes > MAX_BATCH_PAYLOAD_BYTES) break

            selectedIds += event.eventId
            selectedBytes += event.payloadBytes
        }

        return if (selectedIds.isEmpty()) {
            SyncBatchPlan.Empty
        } else {
            SyncBatchPlan.Candidate(
                eventIds = selectedIds,
                eventCount = selectedIds.size,
                payloadBytes = selectedBytes,
            )
        }
    }

    private const val OPERATION_UPSERT = "UPSERT"
    private const val OPERATION_DELETE = "DELETE"
}


/** Bounded per-event retry delay for a committed batch that cannot yet acknowledge its head. */
object SyncRetryPolicy {
    const val BASE_DELAY_MILLIS = 60_000L
    const val MAX_DELAY_MILLIS = 6L * 60L * 60L * 1_000L

    fun delayMillis(previousAttempts: Int): Long {
        val exponent = previousAttempts.coerceAtLeast(0).coerceAtMost(9)
        var delay = BASE_DELAY_MILLIS
        repeat(exponent) {
            delay = (delay * 2L).coerceAtMost(MAX_DELAY_MILLIS)
        }
        return delay.coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun nextAttemptAt(nowEpochMillis: Long, previousAttempts: Int): Long =
        nowEpochMillis + delayMillis(previousAttempts)
}
