package com.atpro.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.atpro.db.entity.SyncMetadataEntity

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncMetadataEntity?

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
        WHERE id = 1 AND ownerUserId IS NULL AND ownerBoundAt IS NULL
    """)
    suspend fun bindOwnerIfUnbound(
        ownerUserId: String,
        boundAt: Long,
        updatedAt: Long,
    ): Int

    /**
     * Commit the local checkpoint only after the server confirms remote revision R -> R+1.
     * expectedRevision is the captured local checkpoint; committedRevision may jump ahead
     * when another device advanced the remote revision. This remains a local compare-and-set.
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
          AND ownerBoundAt IS NOT NULL
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
