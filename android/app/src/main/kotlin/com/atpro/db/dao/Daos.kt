package com.atpro.db.dao

import androidx.room.*
import com.atpro.db.entity.*
import kotlinx.coroutines.flow.Flow

// ── Account DAO ───────────────────────────────────────────────

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY username ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY username ASC")
    suspend fun getAll(): List<AccountEntity>

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
    suspend fun updateStatus(username: String, status: String, checkpoint: Boolean, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE accounts SET
            sessionsCount = sessionsCount + 1,
            totalLikes    = totalLikes    + :likes,
            totalFollows  = totalFollows  + :follows,
            totalVideos   = totalVideos   + :videos,
            updatedAt     = :now
        WHERE username = :username
    """)
    suspend fun addStats(username: String, likes: Int, follows: Int, videos: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM accounts WHERE username = :username")
    suspend fun delete(username: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

// ── Session DAO ───────────────────────────────────────────────

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE accountId = :accountId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getForAccount(accountId: String, limit: Int = 30): List<SessionEntity>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt, durationSecs = :duration WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long = System.currentTimeMillis(), duration: Int)

    @Query("""
        SELECT
            strftime('%Y-%m-%d', startedAt / 1000, 'unixepoch') AS date,
            accountId,
            SUM(likes)         AS likes,
            SUM(follows)       AS follows,
            SUM(videosWatched) AS videosWatched,
            COUNT(*)           AS sessionCount
        FROM sessions
        WHERE startedAt > :since
        GROUP BY date, accountId
        ORDER BY date DESC
    """)
    suspend fun getDailyStats(since: Long): List<DailyStatRow>

    @Query("""
        SELECT
            COALESCE(SUM(likes),          0) AS likes,
            COALESCE(SUM(follows),        0) AS follows,
            COALESCE(SUM(videosWatched),  0) AS videosWatched,
            COUNT(*)                         AS sessionCount
        FROM sessions
        WHERE startedAt > :since
    """)
    suspend fun getTotals(since: Long): TotalsRow

    @Query("DELETE FROM sessions WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class TotalsRow(
    val likes:        Int,
    val follows:      Int,
    val videosWatched: Int,
    val sessionCount: Int,
)

// ── Log DAO ───────────────────────────────────────────────────

@Dao
interface LogDao {

    @Query("SELECT * FROM farm_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<FarmLogEntity>>

    @Insert
    suspend fun insert(log: FarmLogEntity)

    @Query("DELETE FROM farm_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM farm_logs")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM farm_logs")
    suspend fun count(): Int
}

// ── Config DAO ────────────────────────────────────────────────

@Dao
interface ConfigDao {

    @Query("SELECT value FROM configs WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(config: ConfigEntity)

    @Query("DELETE FROM configs WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM configs")
    suspend fun getAll(): List<ConfigEntity>
}
