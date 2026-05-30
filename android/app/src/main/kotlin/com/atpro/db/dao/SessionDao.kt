package com.atpro.db.dao

import androidx.room.*
import com.atpro.db.entity.DailyStatRow
import com.atpro.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

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

    /** Đóng session: ghi endedAt và tính durationSecs. */
    @Query("UPDATE sessions SET endedAt = :endedAt, durationSecs = :duration WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long = System.currentTimeMillis(), duration: Int)

    /**
     * Tổng hợp theo ngày + account trong khoảng [since] → hiện tại.
     * Dùng strftime SQLite để group theo ngày UTC.
     */
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

    /**
     * Tổng cộng tất cả sessions từ [since] → hiện tại.
     * COALESCE đảm bảo không trả về null khi bảng rỗng.
     */
    @Query("""
        SELECT
            COALESCE(SUM(likes),         0) AS likes,
            COALESCE(SUM(follows),       0) AS follows,
            COALESCE(SUM(videosWatched), 0) AS videosWatched,
            COUNT(*)                        AS sessionCount
        FROM sessions
        WHERE startedAt > :since
    """)
    suspend fun getTotals(since: Long): TotalsRow

    /** Dọn sessions cũ để không phình DB vô hạn. */
    @Query("DELETE FROM sessions WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

/**
 * TotalsRow — projection cho `SessionDao.getTotals`.
 * Không phải Room entity. Companion với SessionDao vì chỉ được dùng ở đây.
 */
data class TotalsRow(
    val likes:         Int,
    val follows:       Int,
    val videosWatched: Int,
    val sessionCount:  Int,
)
