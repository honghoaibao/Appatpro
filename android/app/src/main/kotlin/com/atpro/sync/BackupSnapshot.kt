package com.atpro.sync

import kotlinx.serialization.Serializable

/**
 * Versioned, deterministic logical backup. Credentials, logs and device-local UI state
 * are intentionally absent from this model.
 */
@Serializable
data class BackupSnapshot(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val sourceInstallId: String,
    val datasets: BackupSnapshotDatasets,
)

@Serializable
data class BackupSnapshotDatasets(
    val accounts: List<BackupAccount>,
    val closedSessions: List<BackupClosedSession>,
    val safeConfigs: List<BackupSafeConfig>,
    val schedules: List<BackupSchedule>,
    val earnings: List<BackupEarning>,
    val accountStats: Map<String, BackupAccountStats>,
)

@Serializable
data class BackupAccount(
    val username: String,
    val status: String,
    val checkpoint: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupAccountStats(
    val sessionsCount: Int,
    val totalLikes: Int,
    val totalFollows: Int,
    val totalVideos: Int,
    val updatedAt: Long,
)

@Serializable
data class BackupClosedSession(
    val syncId: String,
    val accountId: String,
    val startedAt: Long,
    val endedAt: Long,
    val likes: Int,
    val follows: Int,
    val comments: Int,
    val videosWatched: Int,
    val durationSecs: Int,
)

@Serializable
data class BackupSafeConfig(
    val key: String,
    val value: String,
    val updatedAt: Long,
)

@Serializable
data class BackupSchedule(
    val scheduleId: String,
    val label: String,
    val hourOfDay: Int,
    val minute: Int,
    val daysOfWeek: List<Int>,
    val enabledAtSource: Boolean,
    val accounts: List<String>,
    val serviceMode: String,
    val updatedAt: Long,
)

@Serializable
data class BackupEarning(
    val id: String,
    val atMs: Long,
    val kind: String,
    val reward: Double,
    val account: String,
)
