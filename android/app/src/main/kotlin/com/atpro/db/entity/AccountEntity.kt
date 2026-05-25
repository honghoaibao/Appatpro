package com.atpro.db.entity

import androidx.room.*

/**
 * AccountEntity — một tài khoản TikTok được quản lý bởi AT PRO.
 *
 * Trạng thái [status]:
 *  - "active"      — đang hoạt động bình thường
 *  - "checkpoint"  — cần xác minh (phone/email/captcha)
 *  - "banned"      — bị cấm vĩnh viễn, bỏ qua khi farm
 *
 * [checkpoint] mirror boolean của status == "checkpoint" để query nhanh
 * mà không cần string comparison trong WHERE clause.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    val username:       String,
    val status:         String  = "active",
    val checkpoint:     Boolean = false,
    val sessionsCount:  Int     = 0,
    val totalLikes:     Int     = 0,
    val totalFollows:   Int     = 0,
    val totalVideos:    Int     = 0,
    val createdAt:      Long    = System.currentTimeMillis(),
    val updatedAt:      Long    = System.currentTimeMillis(),
)
