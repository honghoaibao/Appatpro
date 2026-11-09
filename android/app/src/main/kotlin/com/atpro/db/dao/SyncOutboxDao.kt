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

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun getPendingCount(): Int

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
