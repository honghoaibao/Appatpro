package com.atpro.db.dao

import androidx.room.*
import com.atpro.db.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY username ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY username ASC")
    suspend fun getAll(): List<AccountEntity>

    /** Trả về chỉ các account có thể farm: active + chưa checkpoint. */
    @Query("SELECT * FROM accounts WHERE status = 'active' AND checkpoint = 0")
    suspend fun getActive(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(account: AccountEntity): Long

    @Query("""
        UPDATE accounts SET
            status      = :status,
            checkpoint  = :checkpoint,
            updatedAt   = MAX(updatedAt, :now),
            statusUpdatedAt = CASE
                WHEN statusUpdatedAt >= :now THEN
                    CASE
                        WHEN statusUpdatedAt < 9223372036854775807 THEN statusUpdatedAt + 1
                        ELSE statusUpdatedAt
                    END
                ELSE :now
            END
        WHERE username = :username
    """)
    suspend fun updateStatus(
        username:   String,
        status:     String,
        checkpoint: Boolean,
        now:        Long = System.currentTimeMillis(),
    )

    @Query("""
        UPDATE accounts SET
            sessionsCount = :sessionsCount,
            totalLikes = :totalLikes,
            totalFollows = :totalFollows,
            totalVideos = :totalVideos,
            statsUpdatedAt = :statsUpdatedAt,
            updatedAt = :updatedAt
        WHERE username = :username
    """)
    suspend fun updateAggregatedStats(
        username: String,
        sessionsCount: Int,
        totalLikes: Int,
        totalFollows: Int,
        totalVideos: Int,
        statsUpdatedAt: Long,
        updatedAt: Long,
    )

    @Query("DELETE FROM accounts WHERE username = :username")
    suspend fun delete(username: String)

    /** Restore applies a prevalidated merged account set inside one Room transaction. */
    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}
