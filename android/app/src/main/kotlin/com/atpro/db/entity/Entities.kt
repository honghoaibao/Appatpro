package com.atpro.db.entity

import androidx.room.*

// ═══════════════════════════════════════════════════════════════
//  Room Entities — thay thế hoàn toàn Supabase tables
//  Tất cả data lưu local SQLite, không cần internet
// ═══════════════════════════════════════════════════════════════

// ── Accounts ─────────────────────────────────────────────────

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    val username:       String,
    val status:         String  = "active",     // active | checkpoint | banned
    val checkpoint:     Boolean = false,
    val sessionsCount:  Int     = 0,
    val totalLikes:     Int     = 0,
    val totalFollows:   Int     = 0,
    val totalVideos:    Int     = 0,
    val createdAt:      Long    = System.currentTimeMillis(),
    val updatedAt:      Long    = System.currentTimeMillis(),
)

// ── Sessions ──────────────────────────────────────────────────

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

// ── Farm Logs ─────────────────────────────────────────────────

@Entity(
    tableName = "farm_logs",
    indices = [Index("timestamp")]
)
data class FarmLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id:        Long   = 0,
    val accountId: String? = null,
    val level:     String  = "INFO",    // INFO | WARNING | ERROR | SUCCESS
    val message:   String,
    val timestamp: Long    = System.currentTimeMillis(),
)

// ── Config ────────────────────────────────────────────────────

@Entity(tableName = "configs")
data class ConfigEntity(
    @PrimaryKey
    val key:   String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

// ── Daily Stats (view — aggregated by Room query) ─────────────
// Không phải entity — dùng làm return type của DAO query

data class DailyStatRow(
    val date:          String,
    val accountId:     String,
    val likes:         Int,
    val follows:       Int,
    val videosWatched: Int,
    val sessionCount:  Int,
)
