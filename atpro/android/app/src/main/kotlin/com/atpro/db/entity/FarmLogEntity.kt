package com.atpro.db.entity

import androidx.room.*

/**
 * FarmLogEntity — log entry từ engine, popup handler, hoặc service.
 *
 * [level] dùng để filter theo severity trong UI:
 *  - "INFO"    — hoạt động bình thường
 *  - "WARNING" — cảnh báo (checkpoint phát hiện, popup không xử lý được...)
 *  - "ERROR"   — lỗi nghiêm trọng (switch fail, crash recovery...)
 *  - "SUCCESS" — hành động thành công đáng chú ý (session kết thúc tốt...)
 *
 * [accountId] = null nếu log là hệ thống (không gắn với account cụ thể).
 * Index trên [timestamp] hỗ trợ query và cleanup (deleteOlderThan).
 */
@Entity(
    tableName = "farm_logs",
    indices = [Index("timestamp")]
)
data class FarmLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id:        Long    = 0,
    val accountId: String? = null,
    val level:     String  = "INFO",
    val message:   String,
    val timestamp: Long    = System.currentTimeMillis(),
)
