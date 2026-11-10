package com.atpro.sync

/**
 * Pure snapshot invariants. Kept separate from Android Base64/GZIP APIs so the safety
 * rules can be unit-tested on the local JVM without a device or emulator.
 */
internal object BackupSnapshotValidator {
    const val MAX_TOMBSTONES = 100_000

    fun validate(snapshot: BackupSnapshot) {
        validateEnvelope(
            formatVersion = snapshot.formatVersion,
            expectedVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
            createdAt = snapshot.createdAt,
            appVersion = snapshot.appVersion,
            sourceInstallId = snapshot.sourceInstallId,
        )
        validateDatasets(
            accounts = snapshot.datasets.accounts,
            closedSessions = snapshot.datasets.closedSessions,
            safeConfigs = snapshot.datasets.safeConfigs,
            schedules = snapshot.datasets.schedules,
            earnings = snapshot.datasets.earnings,
            accountStatContributions = snapshot.datasets.accountStatContributions,
            sourceInstallId = snapshot.sourceInstallId,
            tombstones = snapshot.datasets.tombstones,
        )
    }

    fun validateV2(snapshot: BackupSnapshotV2) {
        validateEnvelope(
            formatVersion = snapshot.formatVersion,
            expectedVersion = BackupSnapshot.LEGACY_FORMAT_VERSION,
            createdAt = snapshot.createdAt,
            appVersion = snapshot.appVersion,
            sourceInstallId = snapshot.sourceInstallId,
        )
        validateDatasets(
            accounts = snapshot.datasets.accounts,
            closedSessions = snapshot.datasets.closedSessions,
            safeConfigs = snapshot.datasets.safeConfigs,
            schedules = snapshot.datasets.schedules,
            earnings = snapshot.datasets.earnings,
            accountStatContributions = snapshot.datasets.accountStatContributions,
            sourceInstallId = snapshot.sourceInstallId,
            tombstones = emptyList(),
        )
    }

    private fun validateEnvelope(
        formatVersion: Int,
        expectedVersion: Int,
        createdAt: Long,
        appVersion: String,
        sourceInstallId: String,
    ) {
        if (formatVersion != expectedVersion ||
            createdAt < 0L ||
            appVersion.isBlank() ||
            !BackupMetadataRules.isCanonicalUuid(sourceInstallId)
        ) {
            throw BackupCodecException("Backup envelope is missing valid required metadata")
        }
    }

    private fun validateDatasets(
        accounts: List<BackupAccount>,
        closedSessions: List<BackupClosedSession>,
        safeConfigs: List<BackupSafeConfig>,
        schedules: List<BackupSchedule>,
        earnings: List<BackupEarning>,
        accountStatContributions: List<BackupAccountStatContribution>,
        sourceInstallId: String,
        tombstones: List<BackupTombstone>,
    ) {
        val accountNames = accounts.map { it.username }
        if (accountNames.any { it.isBlank() || it != it.trim() } ||
            accountNames.distinct().size != accountNames.size ||
            accounts.any {
                it.createdAt < 0L || it.statusUpdatedAt < 0L ||
                    it.statsUpdatedAt < 0L || it.status.isBlank()
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate accounts")
        }

        val contributionsByAccount = accountStatContributions.groupBy { it.accountId }
        val accountByName = accounts.associateBy { it.username }
        val contributionKeys = accountStatContributions.map {
            it.accountId to it.installationId
        }
        if (contributionKeys.distinct().size != contributionKeys.size ||
            accountStatContributions.any {
                it.accountId !in accountNames ||
                    !BackupMetadataRules.isCanonicalUuid(it.installationId) ||
                    it.sessionsCount < 0L || it.totalLikes < 0L ||
                    it.totalFollows < 0L || it.totalVideos < 0L || it.updatedAt < 0L
            } ||
            accountNames.any { name ->
                val contributions = contributionsByAccount[name].orEmpty()
                val latestContributionAt = contributions.maxOfOrNull { it.updatedAt }
                latestContributionAt == null ||
                    latestContributionAt != accountByName[name]?.statsUpdatedAt ||
                    contributions.none { it.installationId == sourceInstallId }
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate per-installation account statistics")
        }

        val sessionIds = closedSessions.map { it.syncId }
        if (sessionIds.distinct().size != sessionIds.size ||
            closedSessions.any {
                !BackupMetadataRules.isCanonicalUuid(it.syncId) ||
                    it.accountId.isBlank() ||
                    it.startedAt < 0L ||
                    it.endedAt < it.startedAt ||
                    it.likes < 0 ||
                    it.follows < 0 ||
                    it.comments < 0 ||
                    it.videosWatched < 0 ||
                    it.durationSecs < 0
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate closed sessions")
        }

        val configKeys = safeConfigs.map { it.key }
        if (configKeys.distinct().size != configKeys.size ||
            safeConfigs.any {
                BackupConfigPolicy.datasetFor(it.key) != "safeConfigs" || it.updatedAt < 0L
            }
        ) {
            throw BackupCodecException("Backup contains a configuration key outside the safe allowlist")
        }

        val scheduleIds = schedules.map { it.scheduleId }
        if (scheduleIds.distinct().size != scheduleIds.size ||
            schedules.any {
                it.scheduleId.isBlank() ||
                    it.hourOfDay !in 0..23 ||
                    it.minute !in 0..59 ||
                    it.daysOfWeek.any { day -> day !in 1..7 } ||
                    it.daysOfWeek.distinct().size != it.daysOfWeek.size ||
                    it.accounts.any(String::isBlank) ||
                    it.serviceMode.isBlank() ||
                    it.updatedAt < 0L
            }
        ) {
            throw BackupCodecException("Backup contains an invalid schedule definition")
        }

        val earningIds = earnings.map { it.id }
        if (earningIds.distinct().size != earningIds.size ||
            earnings.any {
                !BackupMetadataRules.isCanonicalUuid(it.id) ||
                    it.kind.isBlank() ||
                    !it.reward.isFinite() ||
                    it.reward < 0.0 ||
                    it.atMs < 0L
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate earnings entries")
        }

        validateTombstones(tombstones)
    }

    private fun validateTombstones(tombstones: List<BackupTombstone>) {
        if (tombstones.size > MAX_TOMBSTONES) {
            throw BackupCodecException("Backup contains too many tombstones")
        }

        val tombstoneIds = tombstones.map { it.tombstoneId }
        if (tombstoneIds.distinct().size != tombstoneIds.size ||
            tombstones.any { tombstone ->
                !BackupMetadataRules.isCanonicalUuid(tombstone.tombstoneId) ||
                    !BackupMetadataRules.isCanonicalUuid(tombstone.sourceInstallId) ||
                    tombstone.deletedAt < 0L ||
                    !isValidTombstoneKey(tombstone.dataset, tombstone.entityKey)
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate tombstones")
        }
    }

    private fun isValidTombstoneKey(dataset: String, entityKey: String): Boolean {
        if (entityKey.isBlank() || entityKey != entityKey.trim()) return false
        return when (dataset) {
            BackupTombstoneDataset.ACCOUNTS -> !entityKey.startsWith("@")
            BackupTombstoneDataset.SAFE_CONFIGS ->
                BackupConfigPolicy.datasetFor(entityKey) == "safeConfigs"
            BackupTombstoneDataset.SCHEDULES -> true
            else -> false
        }
    }
}
