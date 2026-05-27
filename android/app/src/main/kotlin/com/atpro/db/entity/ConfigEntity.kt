package com.atpro.db.entity

import androidx.room.*

/**
 * ConfigEntity — key-value store cho cấu hình farm.
 *
 * Thay vì một bảng config cứng nhắc, dùng pattern key-value cho phép
 * thêm config mới mà không cần migration Room.
 *
 * Key conventions (xem [LocalRepository.loadFarmConfig]):
 *  - "minutes_per_account" → Int
 *  - "watch_time_min"      → Double (giây)
 *  - "watch_time_max"      → Double (giây)
 *  - "like_rate"           → Double (0.0–1.0)
 *  - "follow_rate"         → Double (0.0–1.0)
 *  - "enable_rest"         → Boolean ("true"/"false")
 *  - "rest_minutes"        → Int
 *  - "skip_live"           → Boolean
 *  - v.v — xem loadFarmConfig() đầy đủ
 */
@Entity(tableName = "configs")
data class ConfigEntity(
    @PrimaryKey
    val key:      String,
    val value:    String,
    val updatedAt: Long = System.currentTimeMillis(),
)
