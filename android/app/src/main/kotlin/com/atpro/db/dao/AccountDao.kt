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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("""
        UPDATE accounts SET
            status      = :status,
            checkpoint  = :checkpoint,
            updatedAt   = :now
        WHERE username = :username
    """)
    suspend fun updateStatus(
        username:   String,
        status:     String,
        checkpoint: Boolean,
        now:        Long = System.currentTimeMillis(),
    )

    /**
     * Cộng dồn stats sau mỗi session — dùng SQL arithmetic để tránh
     * race condition nếu nhiều session chạy song song trong tương lai.
     */
    @Query("""
        UPDATE accounts SET
            sessionsCount = sessionsCount + 1,
            totalLikes    = totalLikes    + :likes,
            totalFollows  = totalFollows  + :follows,
            totalVideos   = totalVideos   + :videos,
            updatedAt     = :now
        WHERE username = :username
    """)
    suspend fun addStats(
        username: String,
        likes:    Int,
        follows:  Int,
        videos:   Int,
        now:      Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM accounts WHERE username = :username")
    suspend fun delete(username: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}
