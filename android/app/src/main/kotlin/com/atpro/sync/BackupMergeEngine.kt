package com.atpro.sync

/**
 * A conflict is a logical identity for which the two validated snapshots do not provide
 * enough trustworthy information to choose one value automatically.
 */
enum class BackupMergeConflictKind {
    ACCOUNT_STATUS,
    ACCOUNT_CREATION_TIME,
    SESSION_CONTENT,
    CONFIG_VALUE,
    SCHEDULE_DEFINITION,
    EARNING_CONTENT,
    TOMBSTONE_ID_REUSE,
    DELETE_RECREATE_SAME_INSTALLATION,
    DUPLICATE_ENTITY_IDENTITY,
    DATASET_LIMIT_EXCEEDED,
}

data class BackupMergeConflict(
    val dataset: String,
    val entityKey: String,
    val kind: BackupMergeConflictKind,
)

sealed interface BackupMergeResult {
    data class Merged(val snapshot: BackupSnapshot) : BackupMergeResult
    data class Conflicted(val conflicts: List<BackupMergeConflict>) : BackupMergeResult
}

/**
 * Deterministic, in-memory-only merge of two already persisted snapshot shapes.
 *
 * This class has no Android, Room, file, scheduler, network or Supabase dependencies.
 * It never writes either source. The caller must stage the returned candidate and must
 * not apply it while conflicts are present.
 */
object BackupMergeEngine {
    const val MAX_CLOSED_SESSIONS = 100_000
    const val MAX_EARNINGS = 25_000

    private data class Sourced<T>(
        val value: T,
        val sourceInstallId: String,
    )

    fun merge(local: BackupSnapshot, remote: BackupSnapshot): BackupMergeResult {
        // Defensive validation: malformed snapshots never enter the merge domain.
        BackupSnapshotValidator.validate(local)
        BackupSnapshotValidator.validate(remote)

        val conflicts = linkedSetOf<BackupMergeConflict>()
        val tombstones = mergeTombstones(local, remote, conflicts)

        val accountRecords = filterDeleted(
            records = local.datasets.accounts.map { Sourced(it, local.sourceInstallId) } +
                remote.datasets.accounts.map { Sourced(it, remote.sourceInstallId) },
            dataset = BackupTombstoneDataset.ACCOUNTS,
            keyOf = { normalizeAccountKey(it.username) },
            tombstones = tombstones,
            conflicts = conflicts,
        )
        val mergedAccounts = mergeAccounts(accountRecords, conflicts)

        val safeConfigRecords = filterDeleted(
            records = local.datasets.safeConfigs.map { Sourced(it, local.sourceInstallId) } +
                remote.datasets.safeConfigs.map { Sourced(it, remote.sourceInstallId) },
            dataset = BackupTombstoneDataset.SAFE_CONFIGS,
            keyOf = { it.key },
            tombstones = tombstones,
            conflicts = conflicts,
        )
        val mergedSafeConfigs = mergeSafeConfigs(safeConfigRecords, conflicts)

        val scheduleRecords = filterDeleted(
            records = local.datasets.schedules.map { Sourced(it, local.sourceInstallId) } +
                remote.datasets.schedules.map { Sourced(it, remote.sourceInstallId) },
            dataset = BackupTombstoneDataset.SCHEDULES,
            keyOf = { it.scheduleId },
            tombstones = tombstones,
            conflicts = conflicts,
        )
        val mergedSchedules = mergeSchedules(scheduleRecords, conflicts)

        val mergedSessions = mergeSessions(
            local.datasets.closedSessions,
            remote.datasets.closedSessions,
            conflicts,
        )

        val mergedEarnings = mergeEarnings(
            local.datasets.earnings,
            remote.datasets.earnings,
            conflicts,
        )

        val retainedAccountKeys = mergedAccounts.mapTo(hashSetOf<String>()) { it.username }
        val contributionInputs = (
            local.datasets.accountStatContributions +
                remote.datasets.accountStatContributions
            )
            .map { contribution ->
                contribution.copy(accountId = normalizeAccountKey(contribution.accountId))
            }
            .filter { it.accountId in retainedAccountKeys }

        val mergedContributions = AccountStatContributionPolicy
            .mergeContributions(emptyList(), contributionInputs)
            .toMutableList()

        // The resulting snapshot is authored by the local installation. A remote-only
        // account therefore receives a zero-valued local baseline so it satisfies the
        // snapshot invariant without importing or summing aggregate counters.
        val finalAccounts = mergedAccounts.map { account ->
            val accountRows = mergedContributions.filter { it.accountId == account.username }
            if (accountRows.isEmpty()) {
                conflicts += BackupMergeConflict(
                    dataset = BackupTombstoneDataset.ACCOUNTS,
                    entityKey = account.username,
                    kind = BackupMergeConflictKind.DUPLICATE_ENTITY_IDENTITY,
                )
                account
            } else {
                val latestAt = accountRows.maxOf { it.updatedAt }
                if (accountRows.none { it.installationId == local.sourceInstallId }) {
                    mergedContributions += BackupAccountStatContribution(
                        accountId = account.username,
                        installationId = local.sourceInstallId,
                        sessionsCount = 0L,
                        totalLikes = 0L,
                        totalFollows = 0L,
                        totalVideos = 0L,
                        updatedAt = latestAt,
                    )
                }
                account.copy(statsUpdatedAt = latestAt)
            }
        }

        val orderedContributions = mergedContributions
            .filter { it.accountId in retainedAccountKeys }
            .sortedWith(compareBy({ it.accountId }, { it.installationId }))

        if (mergedSessions.size > MAX_CLOSED_SESSIONS) {
            conflicts += BackupMergeConflict(
                dataset = "closedSessions",
                entityKey = "*",
                kind = BackupMergeConflictKind.DATASET_LIMIT_EXCEEDED,
            )
        }
        if (tombstones.size > BackupSnapshotValidator.MAX_TOMBSTONES) {
            conflicts += BackupMergeConflict(
                dataset = "tombstones",
                entityKey = "*",
                kind = BackupMergeConflictKind.DATASET_LIMIT_EXCEEDED,
            )
        }

        val orderedConflicts = conflicts.sortedWith(
            compareBy<BackupMergeConflict>({ it.dataset }, { it.entityKey }, { it.kind.name })
        )
        if (orderedConflicts.isNotEmpty()) {
            return BackupMergeResult.Conflicted(orderedConflicts)
        }

        val snapshot = BackupSnapshot(
            formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
            createdAt = maxOf(local.createdAt, remote.createdAt),
            appVersion = local.appVersion,
            sourceInstallId = local.sourceInstallId,
            datasets = BackupSnapshotDatasets(
                accounts = finalAccounts.sortedBy { it.username },
                closedSessions = mergedSessions.sortedBy { it.syncId },
                safeConfigs = mergedSafeConfigs.sortedBy { it.key },
                schedules = mergedSchedules.sortedBy { it.scheduleId },
                earnings = mergedEarnings
                    .sortedWith(compareBy({ it.atMs }, { it.id }))
                    .takeLast(MAX_EARNINGS),
                accountStatContributions = orderedContributions,
                tombstones = tombstones.sortedWith(
                    compareBy({ it.dataset }, { it.entityKey }, { it.tombstoneId })
                ),
            ),
        )

        // A success result is guaranteed to be a valid v3 candidate for the bounded codec.
        BackupSnapshotValidator.validate(snapshot)
        return BackupMergeResult.Merged(snapshot)
    }

    private fun mergeTombstones(
        local: BackupSnapshot,
        remote: BackupSnapshot,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupTombstone> {
        val byId = linkedMapOf<String, BackupTombstone>()
        (local.datasets.tombstones + remote.datasets.tombstones)
            .sortedWith(compareBy({ it.tombstoneId }, { it.dataset }, { it.entityKey }))
            .forEach { incoming ->
                val existing = byId[incoming.tombstoneId]
                if (existing == null) {
                    byId[incoming.tombstoneId] = incoming
                } else if (existing != incoming) {
                    conflicts += BackupMergeConflict(
                        dataset = incoming.dataset,
                        entityKey = incoming.entityKey,
                        kind = BackupMergeConflictKind.TOMBSTONE_ID_REUSE,
                    )
                }
            }
        return byId.values.toList()
    }

    private fun <T> filterDeleted(
        records: List<Sourced<T>>,
        dataset: String,
        keyOf: (T) -> String,
        tombstones: List<BackupTombstone>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<Sourced<T>> {
        val markers = tombstones.filter { it.dataset == dataset }.groupBy { it.entityKey }
        return records.filter { record ->
            val key = keyOf(record.value)
            val matching = markers[key].orEmpty()
            if (matching.isEmpty()) {
                true
            } else {
                if (matching.any { it.sourceInstallId == record.sourceInstallId }) {
                    // The same installation contains both a delete marker and an entity
                    // with the same key. Without a generation ID, this may be recreation
                    // or a stale same-device snapshot; keep both sides untouched.
                    conflicts += BackupMergeConflict(
                        dataset = dataset,
                        entityKey = key,
                        kind = BackupMergeConflictKind.DELETE_RECREATE_SAME_INSTALLATION,
                    )
                }
                // A delete from another installation wins over this stale entity.
                false
            }
        }
    }

    private fun mergeAccounts(
        records: List<Sourced<BackupAccount>>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupAccount> {
        val grouped = records.groupBy { normalizeAccountKey(it.value.username) }
        return grouped.toSortedMap().mapNotNull { (key, group) ->
            if (group.size > 2 ||
                (group.size == 2 &&
                    group[0].sourceInstallId == group[1].sourceInstallId &&
                    group[0].value != group[1].value)
            ) {
                conflicts += conflict(
                    BackupTombstoneDataset.ACCOUNTS, key,
                    BackupMergeConflictKind.DUPLICATE_ENTITY_IDENTITY,
                )
                return@mapNotNull null
            }
            val first = group.first().value
            val merged = if (group.size == 1) {
                first
            } else {
                val second = group.last().value
                when {
                    first.status != second.status || first.checkpoint != second.checkpoint -> {
                        conflicts += conflict(
                            BackupTombstoneDataset.ACCOUNTS, key,
                            BackupMergeConflictKind.ACCOUNT_STATUS,
                        )
                        null
                    }
                    first.createdAt != second.createdAt -> {
                        conflicts += conflict(
                            BackupTombstoneDataset.ACCOUNTS, key,
                            BackupMergeConflictKind.ACCOUNT_CREATION_TIME,
                        )
                        null
                    }
                    else -> first.copy(
                        statusUpdatedAt = maxOf(first.statusUpdatedAt, second.statusUpdatedAt),
                    )
                }
            }
            merged?.copy(username = key)
        }
    }

    private fun mergeSafeConfigs(
        records: List<Sourced<BackupSafeConfig>>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupSafeConfig> {
        val grouped = records.groupBy { it.value.key }
        return grouped.toSortedMap().mapNotNull { (key, group) ->
            if (group.size > 2) {
                conflicts += conflict(
                    "safeConfigs", key, BackupMergeConflictKind.DUPLICATE_ENTITY_IDENTITY
                )
                return@mapNotNull null
            }
            val first = group.first().value
            if (group.size == 1) {
                first
            } else {
                val second = group.last().value
                if (first.value != second.value) {
                    conflicts += conflict(
                        "safeConfigs", key, BackupMergeConflictKind.CONFIG_VALUE
                    )
                    null
                } else {
                    first.copy(updatedAt = maxOf(first.updatedAt, second.updatedAt))
                }
            }
        }
    }

    private fun mergeSchedules(
        records: List<Sourced<BackupSchedule>>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupSchedule> {
        val grouped = records.groupBy { it.value.scheduleId }
        return grouped.toSortedMap().mapNotNull { (key, group) ->
            if (group.size > 2) {
                conflicts += conflict(
                    "schedules", key, BackupMergeConflictKind.DUPLICATE_ENTITY_IDENTITY
                )
                return@mapNotNull null
            }
            val first = group.first().value
            val merged = if (group.size == 1) {
                first
            } else {
                val second = group.last().value
                if (!sameScheduleDefinition(first, second)) {
                    conflicts += conflict(
                        "schedules", key, BackupMergeConflictKind.SCHEDULE_DEFINITION
                    )
                    null
                } else {
                    first.copy(updatedAt = maxOf(first.updatedAt, second.updatedAt))
                }
            }
            merged?.copy(enabledAtSource = false)
        }
    }

    private fun sameScheduleDefinition(left: BackupSchedule, right: BackupSchedule): Boolean =
        left.scheduleId == right.scheduleId &&
            left.label == right.label &&
            left.hourOfDay == right.hourOfDay &&
            left.minute == right.minute &&
            left.daysOfWeek == right.daysOfWeek &&
            left.accounts == right.accounts &&
            left.serviceMode == right.serviceMode

    private fun mergeSessions(
        local: List<BackupClosedSession>,
        remote: List<BackupClosedSession>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupClosedSession> {
        val grouped = (local + remote).groupBy { it.syncId }
        return grouped.toSortedMap().mapNotNull { (id, group) ->
            if (group.size > 2 || group.distinct().size > 1) {
                conflicts += conflict(
                    "closedSessions", id, BackupMergeConflictKind.SESSION_CONTENT
                )
                null
            } else {
                group.first()
            }
        }
    }

    private fun mergeEarnings(
        local: List<BackupEarning>,
        remote: List<BackupEarning>,
        conflicts: MutableSet<BackupMergeConflict>,
    ): List<BackupEarning> {
        val grouped = (local + remote).groupBy { it.id }
        return grouped.toSortedMap().mapNotNull { (id, group) ->
            if (group.size > 2 || group.distinct().size > 1) {
                conflicts += conflict(
                    "earnings", id, BackupMergeConflictKind.EARNING_CONTENT
                )
                null
            } else {
                group.first()
            }
        }
    }

    private fun normalizeAccountKey(username: String): String =
        username.trim().removePrefix("@")

    private fun conflict(
        dataset: String,
        key: String,
        kind: BackupMergeConflictKind,
    ) = BackupMergeConflict(dataset, key, kind)
}
