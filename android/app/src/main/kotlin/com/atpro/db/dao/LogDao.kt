package com.atpro.db.dao

import androidx.room.*
import com.atpro.db.entity.FarmLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    /** Flow realtime để Flutter observe qua EventChannel. */
    @Query("SELECT * FROM farm_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<FarmLogEntity>>

    @Insert
    suspend fun insert(log: FarmLogEntity)

    /** Dọn log cũ — gọi từ LocalRepository.cleanup() (7-day retention). */
    @Query("DELETE FROM farm_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM farm_logs")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM farm_logs")
    suspend fun count(): Int
}
