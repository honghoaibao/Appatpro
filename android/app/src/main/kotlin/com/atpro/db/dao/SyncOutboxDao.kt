package com.atpro.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atpro.db.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: SyncOutboxEntity)

    /** Coalesce older pending UPSERTs only; DELETE tombstones are never removed here. */
    @Query("""
        DELETE FROM sync_outbox
        WHERE dataset = :dataset
          AND entityKey = :entityKey
          AND operation = 'UPSERT'
    """)
    suspend fun deletePendingUpserts(dataset: String, entityKey: String)

    @Query("SELECT * FROM sync_outbox ORDER BY sequence ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 200): List<SyncOutboxEntity>

    /**
     * Read only a bounded Outbox head for scheduling decisions. Payload JSON stays in SQLite;
     * only its UTF-8 byte length is projected into memory.
     */
    @Query("""
        SELECT
            eventId,
            sequence,
            dataset,
            operation,
            length(CAST(COALESCE(payloadJson, '') AS BLOB)) AS payloadBytes,
            nextAttemptAt
        FROM sync_outbox
        ORDER BY sequence ASC
        LIMIT :limit
    """)
    suspend fun getPendingHead(limit: Int): List<SyncOutboxHeadRow>

    @Query("""
        SELECT
            eventId,
            sequence,
            dataset,
            entityKey,
            operation,
            length(CAST(COALESCE(payloadJson, '') AS BLOB)) AS payloadBytes,
            nextAttemptAt
        FROM sync_outbox
        ORDER BY sequence ASC
        LIMIT :limit
    """)
    suspend fun getPendingAckCandidates(limit: Int): List<SyncOutboxAckRow>

    @Query("SELECT * FROM sync_outbox WHERE eventId IN (:eventIds)")
    suspend fun getByEventIds(eventIds: List<String>): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE dataset = :dataset")
    suspend fun getPendingCountForDataset(dataset: String): Int

    @Query("DELETE FROM sync_outbox WHERE eventId IN (:eventIds)")
    suspend fun deleteAcknowledged(eventIds: List<String>): Int

    @Query("""
        UPDATE sync_outbox
        SET attempts = attempts + 1,
            nextAttemptAt = :nextAttemptAt,
            lastErrorCode = :lastErrorCode
        WHERE eventId = :eventId
    """)
    suspend fun markRetry(eventId: String, nextAttemptAt: Long, lastErrorCode: String?)
}

/** Exact acknowledgement identity captured in the same Room transaction as the snapshot. */
data class SyncOutboxAckRow(
    val eventId: String,
    val sequence: Long,
    val dataset: String,
    val entityKey: String,
    val operation: String,
    val payloadBytes: Int,
    val nextAttemptAt: Long,
)

/** Small query projection; no Outbox payload or secret-bearing config value is loaded. */
data class SyncOutboxHeadRow(
    val eventId: String,
    val sequence: Long,
    val dataset: String,
    val operation: String,
    val payloadBytes: Int,
    val nextAttemptAt: Long,
)
