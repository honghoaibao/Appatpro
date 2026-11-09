package com.atpro.sync

/**
 * Pure snapshot invariants. Kept separate from Android Base64/GZIP APIs so the safety
 * rules can be unit-tested on the local JVM without a device or emulator.
 */
internal object BackupSnapshotValidator {
    fun validate(snapshot: BackupSnapshot) {
        if (snapshot.formatVersion <= 0 ||
            snapshot.createdAt < 0L ||
            snapshot.appVersion.isBlank() ||
            !BackupMetadataRules.isCanonicalUuid(snapshot.sourceInstallId)
        ) {
            throw BackupCodecException("Backup envelope is missing valid required metadata")
        }

        val accountNames = snapshot.datasets.accounts.map { it.username }
        if (accountNames.any { it.isBlank() || it != it.trim() } ||
            accountNames.distinct().size != accountNames.size ||
            snapshot.datasets.accounts.any { it.createdAt < 0L || it.updatedAt < 0L }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate accounts")
        }

        if (snapshot.datasets.accountStats.keys.any { it !in accountNames } ||
            snapshot.datasets.accountStats.values.any {
                it.sessionsCount < 0 || it.totalLikes < 0 || it.totalFollows < 0 ||
                    it.totalVideos < 0 || it.updatedAt < 0L
            }
        ) {
            throw BackupCodecException("Backup contains invalid account statistics")
        }

        val sessionIds = snapshot.datasets.closedSessions.map { it.syncId }
        if (sessionIds.distinct().size != sessionIds.size ||
            snapshot.datasets.closedSessions.any {
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

        val configKeys = snapshot.datasets.safeConfigs.map { it.key }
        if (configKeys.distinct().size != configKeys.size ||
            snapshot.datasets.safeConfigs.any {
                BackupConfigPolicy.datasetFor(it.key) != "safeConfigs" || it.updatedAt < 0L
            }
        ) {
            throw BackupCodecException("Backup contains a configuration key outside the safe allowlist")
        }

        val scheduleIds = snapshot.datasets.schedules.map { it.scheduleId }
        if (scheduleIds.distinct().size != scheduleIds.size ||
            snapshot.datasets.schedules.any {
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

        val earningIds = snapshot.datasets.earnings.map { it.id }
        if (earningIds.distinct().size != earningIds.size ||
            snapshot.datasets.earnings.any {
                !BackupMetadataRules.isCanonicalUuid(it.id) ||
                    it.kind.isBlank() ||
                    !it.reward.isFinite() ||
                    it.reward < 0.0 ||
                    it.atMs < 0L
            }
        ) {
            throw BackupCodecException("Backup contains invalid or duplicate earnings entries")
        }
    }
}
