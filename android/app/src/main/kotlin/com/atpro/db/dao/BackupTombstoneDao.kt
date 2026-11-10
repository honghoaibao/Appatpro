package com.atpro.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atpro.db.entity.BackupTombstoneEntity

@Dao
interface BackupTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tombstone: BackupTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tombstones: List<BackupTombstoneEntity>)

    @Query("SELECT * FROM backup_tombstones ORDER BY dataset ASC, entityKey ASC, deletedAt ASC, tombstoneId ASC")
    suspend fun getAll(): List<BackupTombstoneEntity>

    @Query("SELECT * FROM backup_tombstones WHERE tombstoneId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<BackupTombstoneEntity>

    /** Reserved for the later exact-acknowledgement phase; not called by Phase 5.2. */
    @Query("DELETE FROM backup_tombstones WHERE tombstoneId IN (:ids)")
    suspend fun deleteAcknowledged(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM backup_tombstones")
    suspend fun count(): Int
}
