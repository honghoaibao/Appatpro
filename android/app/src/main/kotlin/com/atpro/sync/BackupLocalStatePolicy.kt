package com.atpro.sync

/**
 * Distinguishes a genuinely empty local backup from a device with portable data or
 * explicit delete intent. This decision is made from the validated snapshot plus the
 * exact local work captured with it; metadata/checkpoint alone is not proof that data exists.
 */
internal object BackupLocalStatePolicy {

    fun hasPortableData(build: BackupSnapshotBuildResult): Boolean {
        if (build.outboxEvents.isNotEmpty() || build.earningsDirty) return true

        val datasets = build.snapshot.datasets
        return datasets.accounts.isNotEmpty() ||
            datasets.closedSessions.isNotEmpty() ||
            datasets.safeConfigs.isNotEmpty() ||
            datasets.schedules.isNotEmpty() ||
            datasets.earnings.isNotEmpty() ||
            datasets.accountStatContributions.isNotEmpty() ||
            datasets.tombstones.isNotEmpty()
    }

    fun isTrulyEmpty(build: BackupSnapshotBuildResult): Boolean = !hasPortableData(build)
}
