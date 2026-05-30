package com.atpro.db.entity

import androidx.room.*

/**
 * SessionEntity — một lượt farm của một tài khoản.
 *
 * Mỗi lần [AutomationEngine] chạy một account là một session độc lập.
 * [endedAt] = null khi session đang chạy (chưa đóng bởi closeSession).
 * [durationSecs] được tính khi session kết thúc (endedAt - startedAt).
 *
 * Index trên [accountId] hỗ trợ query "sessions theo account".
 * Index trên [startedAt] hỗ trợ query stats theo khoảng thời gian.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index("accountId"),
        Index("startedAt"),
    ]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id:            Long    = 0,
    val accountId:     String,
    val startedAt:     Long    = System.currentTimeMillis(),
    val endedAt:       Long?   = null,
    val likes:         Int     = 0,
    val follows:       Int     = 0,
    val comments:      Int     = 0,
    val videosWatched: Int     = 0,
    val durationSecs:  Int     = 0,
)

/**
 * DailyStatRow — projection dùng làm return type của `SessionDao.getDailyStats`.
 * Không phải entity Room — không có bảng riêng. Group by (date, accountId).
 */
data class DailyStatRow(
    val date:          String,
    val accountId:     String,
    val likes:         Int,
    val follows:       Int,
    val videosWatched: Int,
    val sessionCount:  Int,
)
