package com.atpro.sync

import kotlinx.serialization.Serializable

/**
 * Versioned, deterministic logical backup. Credentials, logs and device-local UI state
 * are intentionally absent.
 */
@Serializable
data class BackupSnapshot(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val sourceInstallId: String,
    val datasets: BackupSnapshotDatasets,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 4
        /** v3: chưa có dòng tổng thu nhập (earningRollups) và mã máy của dòng thu nhập. */
        const val V3_FORMAT_VERSION = 3
        const val LEGACY_FORMAT_VERSION = 2

        /** Mọi phiên bản dây (wire) mà bản này đọc được; luôn GHI [CURRENT_FORMAT_VERSION]. */
        val SUPPORTED_WIRE_VERSIONS: Set<Int> =
            setOf(LEGACY_FORMAT_VERSION, V3_FORMAT_VERSION, CURRENT_FORMAT_VERSION)
    }
}

/** Exact v2 wire shape, retained for safe decoding of old remote backups. */
@Serializable
internal data class BackupSnapshotV2(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val sourceInstallId: String,
    val datasets: BackupSnapshotDatasetsV2,
)

@Serializable
internal data class BackupSnapshotDatasetsV2(
    val accounts: List<BackupAccount>,
    val closedSessions: List<BackupClosedSession>,
    val safeConfigs: List<BackupSafeConfig>,
    val schedules: List<BackupSchedule>,
    val earnings: List<BackupEarningLegacy>,
    val accountStatContributions: List<BackupAccountStatContribution>,
)

/** Exact v3 wire shape (có tombstones, chưa có earningRollups / origin), giữ để đọc an toàn bản sao lưu v3. */
@Serializable
internal data class BackupSnapshotV3(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val sourceInstallId: String,
    val datasets: BackupSnapshotDatasetsV3,
)

@Serializable
internal data class BackupSnapshotDatasetsV3(
    val accounts: List<BackupAccount>,
    val closedSessions: List<BackupClosedSession>,
    val safeConfigs: List<BackupSafeConfig>,
    val schedules: List<BackupSchedule>,
    val earnings: List<BackupEarningLegacy>,
    val accountStatContributions: List<BackupAccountStatContribution>,
    val tombstones: List<BackupTombstone> = emptyList(),
)

internal fun BackupSnapshotV3.toCurrentSnapshot(): BackupSnapshot =
    BackupSnapshot(
        formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
        createdAt = createdAt,
        appVersion = appVersion,
        sourceInstallId = sourceInstallId,
        datasets = BackupSnapshotDatasets(
            accounts = datasets.accounts,
            closedSessions = datasets.closedSessions,
            safeConfigs = datasets.safeConfigs,
            schedules = datasets.schedules,
            earnings = datasets.earnings.map { it.toCurrent() },
            accountStatContributions = datasets.accountStatContributions,
            tombstones = datasets.tombstones,
            earningRollups = emptyList(),
        ),
    )

internal fun BackupSnapshotV2.toCurrentSnapshot(): BackupSnapshot =
    BackupSnapshot(
        formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
        createdAt = createdAt,
        appVersion = appVersion,
        sourceInstallId = sourceInstallId,
        datasets = BackupSnapshotDatasets(
            accounts = datasets.accounts,
            closedSessions = datasets.closedSessions,
            safeConfigs = datasets.safeConfigs,
            schedules = datasets.schedules,
            earnings = datasets.earnings.map { it.toCurrent() },
            accountStatContributions = datasets.accountStatContributions,
            tombstones = emptyList(),
        ),
    )

@Serializable
data class BackupSnapshotDatasets(
    val accounts: List<BackupAccount>,
    val closedSessions: List<BackupClosedSession>,
    val safeConfigs: List<BackupSafeConfig>,
    val schedules: List<BackupSchedule>,
    val earnings: List<BackupEarning>,
    val accountStatContributions: List<BackupAccountStatContribution>,
    val tombstones: List<BackupTombstone> = emptyList(),
    /** v4: dòng tổng của thu nhập cũ đã nén (xem EarningsRollupPolicy). */
    val earningRollups: List<BackupEarningRollup> = emptyList(),
)

@Serializable
data class BackupTombstone(
    val tombstoneId: String,
    val dataset: String,
    val entityKey: String,
    val sourceInstallId: String,
    val deletedAt: Long,
)

object BackupTombstoneDataset {
    const val ACCOUNTS = "accounts"
    const val SAFE_CONFIGS = "safeConfigs"
    const val SCHEDULES = "schedules"
}

@Serializable
data class BackupAccount(
    val username: String,
    val status: String,
    val checkpoint: Boolean,
    val createdAt: Long,
    val statusUpdatedAt: Long,
    val statsUpdatedAt: Long,
)

@Serializable
data class BackupAccountStatContribution(
    val accountId: String,
    val installationId: String,
    val sessionsCount: Long,
    val totalLikes: Long,
    val totalFollows: Long,
    val totalVideos: Long,
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
    /** v4: mã máy tạo sự kiện; null = dòng cũ (không bao giờ bị dòng tổng phủ). */
    val origin: String? = null,
)

/** Hình dạng dây v2/v3 của [BackupEarning] (không có origin) — giữ nguyên để kiểm tra dạng chuẩn khi đọc bản cũ. */
@Serializable
internal data class BackupEarningLegacy(
    val id: String,
    val atMs: Long,
    val kind: String,
    val reward: Double,
    val account: String,
) {
    fun toCurrent() = BackupEarning(id, atMs, kind, reward, account, origin = null)
}

/** v4: tổng thu nhập của MỘT máy trong MỘT ngày UTC cho (kind, account). */
@Serializable
data class BackupEarningRollup(
    val id: String,
    val origin: String,
    val epochDay: Long,
    val kind: String,
    val account: String,
    val coins: Double,
    val jobs: Long,
)
