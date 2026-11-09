package com.atpro.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atpro.db.entity.SyncMetadataEntity

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SyncMetadataEntity)

    /**
     * First binding only. The WHERE clause prevents a stale UI action or concurrent
     * login from changing an owner that another request already bound.
     */
    @Query("""
        UPDATE sync_metadata
        SET ownerUserId = :ownerUserId,
            ownerBoundAt = :boundAt,
            syncStatus = 'IDLE',
            updatedAt = :updatedAt
        WHERE id = 1 AND ownerUserId IS NULL
    """)
    suspend fun bindOwnerIfUnbound(
        ownerUserId: String,
        boundAt: Long,
        updatedAt: Long,
    ): Int

    /**
     * Commit the local checkpoint only after the server confirms an atomic R -> R+1 commit.
     * This is a local compare-and-set; it does not call Supabase.
     */
    @Query("""
        UPDATE sync_metadata
        SET lastRemoteRevision = :committedRevision,
            lastSuccessfulSyncAt = :confirmedAt,
            lastRevisionConfirmedAt = :confirmedAt,
            lastChecksum = :checksum,
            formatVersion = :formatVersion,
            syncStatus = 'IDLE',
            updatedAt = :confirmedAt
        WHERE id = 1
          AND ownerUserId = :ownerUserId
          AND lastRemoteRevision = :expectedRevision
    """)
    suspend fun recordCommittedRevision(
        ownerUserId: String,
        expectedRevision: Long,
        committedRevision: Long,
        checksum: String,
        formatVersion: Int,
        confirmedAt: Long,
    ): Int
}
