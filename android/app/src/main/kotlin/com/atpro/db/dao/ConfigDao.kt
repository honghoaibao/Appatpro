package com.atpro.db.dao

import androidx.room.*
import com.atpro.db.entity.ConfigEntity

@Dao
interface ConfigDao {

    /** Trả về null nếu key chưa tồn tại — caller tự handle default. */
    @Query("SELECT value FROM configs WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    /** REPLACE = upsert: insert nếu chưa có, update nếu đã có. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(config: ConfigEntity)

    @Query("DELETE FROM configs WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM configs")
    suspend fun getAll(): List<ConfigEntity>
}
