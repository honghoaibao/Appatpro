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

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE syncId IN (:syncIds)")
    suspend fun getBySyncIds(syncIds: List<String>): List<SessionEntity>

    /** Inserts only absent closed-session UUIDs; active local rows are never replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBackupSessions(sessions: List<SessionEntity>): List<Long>

    /**
     * Đóng session: ghi endedAt, tính durationSecs và lưu stats.
     * v1.1.4 FIX: Thêm likes/follows/comments/videosWatched — trước đây
     * các trường này không được update → session record luôn giữ giá trị 0.
     */
    @Query("""
        UPDATE sessions SET
            endedAt       = :endedAt,
            durationSecs  = :duration,
            likes         = :likes,
            follows       = :follows,
            comments      = :comments,
            videosWatched = :videosWatched
        WHERE id = :id
    """)
    suspend fun close(
        id:            Long,
        endedAt:       Long = System.currentTimeMillis(),
        duration:      Int,
        likes:         Int,
        follows:       Int,
        comments:      Int,
        videosWatched: Int,
    )

    /**
     * Tổng hợp theo ngày + account trong khoảng [since] → hiện tại.
     * Dùng strftime SQLite để group theo ngày UTC.
     *
     * v1.2.3: thêm SUM(comments) — dùng để tính tỷ lệ % bình luận/video.
     */
    @Query("""
        SELECT
            strftime('%Y-%m-%d', startedAt / 1000, 'unixepoch') AS date,
            accountId,
            SUM(likes)         AS likes,
            SUM(follows)       AS follows,
            SUM(comments)      AS comments,
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
     *
     * v1.2.3: thêm SUM(comments) — dùng để tính tỷ lệ % bình luận/video.
     */
    @Query("""
        SELECT
            COALESCE(SUM(likes),         0) AS likes,
            COALESCE(SUM(follows),       0) AS follows,
            COALESCE(SUM(comments),      0) AS comments,
            COALESCE(SUM(videosWatched), 0) AS videosWatched,
            COUNT(*)                        AS sessionCount
        FROM sessions
        WHERE startedAt > :since
    """)
    suspend fun getTotals(since: Long): TotalsRow

    /**
     * Include locally retained sessions plus older sessions whose immutable UPSERT is
     * still pending. The latter must survive local cleanup until the cloud confirms them.
     */
    @Query("""
        SELECT COUNT(*) FROM sessions
        WHERE endedAt IS NOT NULL
          AND (
              startedAt >= :since
              OR EXISTS (
                  SELECT 1 FROM sync_outbox
                  WHERE sync_outbox.dataset = 'closedSessions'
                    AND sync_outbox.entityKey = sessions.syncId
                    AND sync_outbox.operation = 'UPSERT'
              )
          )
    """)
    suspend fun countClosedForBackup(since: Long): Int

    /** Stable paging includes pending old rows without extending normal local retention. */
    @Query("""
        SELECT * FROM sessions
        WHERE endedAt IS NOT NULL
          AND (
              startedAt >= :since
              OR EXISTS (
                  SELECT 1 FROM sync_outbox
                  WHERE sync_outbox.dataset = 'closedSessions'
                    AND sync_outbox.entityKey = sessions.syncId
                    AND sync_outbox.operation = 'UPSERT'
              )
          )
        ORDER BY syncId ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getClosedForBackup(since: Long, limit: Int, offset: Int): List<SessionEntity>

    /**
     * Local retention is 90 days, but an old session is not removed while its cloud
     * UPSERT remains pending. The acknowledgement transaction calls this after ack too.
     */
    @Query("""
        DELETE FROM sessions
        WHERE startedAt < :before
          AND NOT EXISTS (
              SELECT 1 FROM sync_outbox
              WHERE sync_outbox.dataset = 'closedSessions'
                AND sync_outbox.entityKey = sessions.syncId
                AND sync_outbox.operation = 'UPSERT'
          )
    """)
    suspend fun deleteOlderThan(before: Long)
}

/**
 * TotalsRow — projection cho `SessionDao.getTotals`.
 * Không phải Room entity. Companion với SessionDao vì chỉ được dùng ở đây.
 *
 * v1.2.3: thêm [comments] + các tỷ lệ % phần trăm dựa trên [videosWatched]
 * (likeRate/followRate/commentRate) — dùng cho card "Tỷ lệ tương tác" ở StatsScreen.
 */
data class TotalsRow(
    val likes:         Int,
    val follows:       Int,
    val videosWatched: Int,
    val sessionCount:  Int,
    val comments:      Int = 0,
) {
    /** % video có thả LIKE, trong khoảng 0–100. */
    val likeRate: Float    get() = if (videosWatched > 0) likes    * 100f / videosWatched else 0f
    /** % video có FOLLOW kèm theo, trong khoảng 0–100. */
    val followRate: Float  get() = if (videosWatched > 0) follows  * 100f / videosWatched else 0f
    /** % video có BÌNH LUẬN kèm theo, trong khoảng 0–100. */
    val commentRate: Float get() = if (videosWatched > 0) comments * 100f / videosWatched else 0f
    /** Số video trung bình mỗi phiên — chỉ số hiệu suất. */
    val avgVideosPerSession: Float get() = if (sessionCount > 0) videosWatched.toFloat() / sessionCount else 0f
}
