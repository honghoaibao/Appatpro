package com.atpro.sync

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.atpro.data.EarningEntry
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import com.atpro.db.entity.AccountEntity
import com.atpro.db.entity.AccountStatContributionEntity
import com.atpro.db.entity.BackupTombstoneEntity
import com.atpro.db.entity.ConfigEntity
import com.atpro.db.entity.SessionEntity
import com.atpro.scheduler.ScheduledFarmManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupRestoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Applies an already merged v3 snapshot locally. The journal is durable before the first
 * mutation. Room commits atomically; EarningsStore then merges by UUID through AtomicFile.
 * If the process stops between those stores, startup replays the journal idempotently.
 *
 * This class performs no network I/O, Outbox acknowledgement, remote revision update,
 * WorkManager enqueue, automation start, or Supabase operation.
 */
class BackupRestoreCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val database = AtProDatabase.getInstance(appContext)
    private val journalFile = File(appContext.noBackupFilesDir, JOURNAL_FILE)
    private val journal = AtomicFile(journalFile)

    init {
        EarningsStore.init(appContext)
    }

    suspend fun restore(
        mergedSnapshot: BackupSnapshot,
        authenticatedOwnerUserId: String?,
    ) = restoreMutex.withLock {
        withContext(Dispatchers.IO) {
            // Finish an earlier interrupted restore before staging another candidate.
            recoverPendingLocked()
            try {
                BackupSnapshotValidator.validate(mergedSnapshot)
                if (mergedSnapshot.formatVersion != BackupSnapshot.CURRENT_FORMAT_VERSION) {
                    throw BackupRestoreException("Only validated snapshot v3 can be applied")
                }
                val ownerId = BackupMetadataRules.normalizeAuthUserId(authenticatedOwnerUserId)
                    ?: throw BackupRestoreException("Restore requires a valid authenticated owner")
                val metadata = database.syncMetadataDao().get()
                    ?: throw BackupRestoreException("Local sync metadata row is missing")
                if (!BackupMetadataRules.isStructurallyValid(metadata) || metadata.ownerUserId != ownerId) {
                    throw BackupRestoreException("Authenticated owner is not bound to this local backup")
                }
                if (mergedSnapshot.sourceInstallId != metadata.installationId) {
                    throw BackupRestoreException("Merged snapshot belongs to a different local installation")
                }

                // The remote snapshot may contain years of cloud history. Only stage the
                // device-local retention window in the crash-recovery journal.
                val localSnapshot = mergedSnapshot.copy(
                    datasets = mergedSnapshot.datasets.copy(
                        closedSessions = BackupSessionRetentionPolicy.retainLocally(
                            mergedSnapshot.datasets.closedSessions,
                            System.currentTimeMillis(),
                        )
                    )
                )
                val journalText = BackupRestoreJournalCodec.encode(localSnapshot, ownerId)
                writeJournal(journalText)
                applyJournalText(journalText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BackupRestoreException) {
                throw error
            } catch (error: Exception) {
                throw BackupRestoreException("Could not stage or apply the merged backup", error)
            }
        }
    }

    /** Called before app work is scheduled; safe to call repeatedly. */
    suspend fun recoverPending(): Boolean = restoreMutex.withLock {
        withContext(Dispatchers.IO) { recoverPendingLocked() }
    }

    private suspend fun recoverPendingLocked(): Boolean {
        if (!hasPending(appContext)) return false
        try {
            val journalText = readJournal()
            applyJournalText(journalText)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw BackupRestoreException(
                "A prepared restore journal could not be replayed; local state was left fail-closed",
                error,
            )
        }
    }

    private suspend fun applyJournalText(journalText: String) {
        val prepared = BackupRestoreJournalCodec.decode(journalText)
        val snapshot = prepared.snapshot
        val scheduleIds = applyRoomTransaction(snapshot, prepared.ownerUserId)

        // This merge is idempotent by earning UUID and retains entries appended since staging.
        EarningsStore.mergeFromRestore(
            snapshot.datasets.earnings.map {
                EarningEntry(
                    id = it.id,
                    atMs = it.atMs,
                    kind = it.kind,
                    reward = it.reward,
                    account = it.account,
                )
            }
        )

        // Cancel only. Never enqueue imported schedules. FarmWorker also treats disabled
        // or removed definitions as successful no-ops if it races this cancellation.
        ScheduledFarmManager.cancelRestoredSchedules(appContext, scheduleIds)
        journal.delete()
    }

    private suspend fun applyRoomTransaction(
        snapshot: BackupSnapshot,
        expectedOwnerUserId: String,
    ): Set<String> =
        database.withTransaction {
            if (snapshot.datasets.closedSessions.size > MAX_CLOSED_SESSIONS ||
                snapshot.datasets.earnings.size > MAX_EARNINGS ||
                snapshot.datasets.tombstones.size > BackupSnapshotValidator.MAX_TOMBSTONES
            ) {
                throw BackupRestoreException("Snapshot exceeds a restore dataset safety bound")
            }
            val metadata = database.syncMetadataDao().get()
                ?: throw BackupRestoreException("Local sync metadata row is missing")
            if (!BackupMetadataRules.isStructurallyValid(metadata) ||
                metadata.ownerUserId != expectedOwnerUserId
            ) {
                throw BackupRestoreException("Backup owner binding changed during restore")
            }
            if (metadata.installationId != snapshot.sourceInstallId) {
                throw BackupRestoreException("Installation identity changed during restore")
            }

            val tombstones = snapshot.datasets.tombstones
            val accountDeletes = tombstones.filter {
                it.dataset == BackupTombstoneDataset.ACCOUNTS
            }
            val safeConfigDeletes = tombstones.filter {
                it.dataset == BackupTombstoneDataset.SAFE_CONFIGS
            }
            val scheduleDeletes = tombstones.filter {
                it.dataset == BackupTombstoneDataset.SCHEDULES
            }
            val accountDeleteKeys = accountDeletes.mapTo(hashSetOf()) { it.entityKey }
            val safeConfigDeleteKeys = safeConfigDeletes.mapTo(hashSetOf()) { it.entityKey }
            val scheduleDeleteIds = scheduleDeletes.mapTo(hashSetOf()) { it.entityKey }

            val incomingAccounts = snapshot.datasets.accounts.associateBy { it.username }
            val incomingSafeConfigs = snapshot.datasets.safeConfigs.associateBy { it.key }
            val incomingSchedules = snapshot.datasets.schedules.associateBy { it.scheduleId }
            if (accountDeleteKeys.any { it in incomingAccounts } ||
                safeConfigDeleteKeys.any { it in incomingSafeConfigs } ||
                scheduleDeleteIds.any { it in incomingSchedules }
            ) {
                throw BackupRestoreException("Snapshot contains an entity and its tombstone simultaneously")
            }

            // Catch deletes created locally after this candidate was staged; never resurrect
            // a row merely because its new tombstone was absent from the staged snapshot.
            val tombstoneDao = database.backupTombstoneDao()
            suspend fun currentMarkers(dataset: String, keys: Set<String>) =
                keys.toList().chunked(500).flatMap { tombstoneDao.getForDatasetKeys(dataset, it) }
            val stagedAccountMarkers = currentMarkers(BackupTombstoneDataset.ACCOUNTS, incomingAccounts.keys)
            val stagedConfigMarkers = currentMarkers(BackupTombstoneDataset.SAFE_CONFIGS, incomingSafeConfigs.keys)
            val stagedScheduleMarkers = currentMarkers(BackupTombstoneDataset.SCHEDULES, incomingSchedules.keys)
            if (stagedAccountMarkers.isNotEmpty() ||
                stagedConfigMarkers.isNotEmpty() ||
                stagedScheduleMarkers.isNotEmpty()
            ) {
                throw BackupRestoreException("A local tombstone changed after merge staging; re-merge is required")
            }

            val existingAccounts = database.accountDao().getAll()
            val retainedAccounts = LinkedHashMap<String, BackupAccount>()
            incomingAccounts.forEach { (key, value) -> retainedAccounts[key] = value }

            existingAccounts.forEach { current ->
                val incoming = incomingAccounts[current.username]
                when {
                    incoming != null -> {
                        if (incoming.status != current.status ||
                            incoming.checkpoint != current.checkpoint ||
                            incoming.createdAt != current.createdAt
                        ) {
                            throw BackupRestoreException(
                                "Account changed locally after merge staging: ${current.username}"
                            )
                        }
                        retainedAccounts[current.username] = incoming
                    }
                    current.username in accountDeleteKeys -> {
                        if (accountDeletes.any {
                                it.entityKey == current.username &&
                                    it.sourceInstallId == metadata.installationId
                            }
                        ) {
                            throw BackupRestoreException(
                                "Same-installation account delete/recreate needs explicit resolution: ${current.username}"
                            )
                        }
                        // A tombstone from another installation wins over this stale local row.
                    }
                    else -> {
                        // Preserve an account created locally after the merge snapshot was built.
                        retainedAccounts[current.username] = current.toBackupAccount()
                    }
                }
            }

            val incomingContributions = snapshot.datasets.accountStatContributions
            val currentContributions = database.accountStatContributionDao().getAll()
                .map { it.toBackupContribution() }
            val mergedContributions = AccountStatContributionPolicy.mergeContributions(
                currentContributions,
                incomingContributions,
            ).filter { it.accountId in retainedAccounts }

            val contributionsByAccount = mergedContributions.groupBy { it.accountId }.toMutableMap()
            retainedAccounts.keys.forEach { accountId ->
                val rows = contributionsByAccount[accountId].orEmpty()
                if (rows.none { it.installationId == metadata.installationId }) {
                    val baseline = BackupAccountStatContribution(
                        accountId = accountId,
                        installationId = metadata.installationId,
                        sessionsCount = 0L,
                        totalLikes = 0L,
                        totalFollows = 0L,
                        totalVideos = 0L,
                        updatedAt = rows.maxOfOrNull { it.updatedAt } ?: 0L,
                    )
                    contributionsByAccount[accountId] = rows + baseline
                }
            }
            val finalContributions = contributionsByAccount.values.flatten()
                .sortedWith(compareBy({ it.accountId }, { it.installationId }))

            val finalAccounts = retainedAccounts.values.map { account ->
                val current = existingAccounts.firstOrNull { it.username == account.username }
                val totals = AccountStatContributionPolicy.aggregate(
                    finalContributions.filter { it.accountId == account.username }
                )
                AccountEntity(
                    username = account.username,
                    status = account.status,
                    checkpoint = account.checkpoint,
                    sessionsCount = AccountStatContributionPolicy.toDisplayInt(totals.sessionsCount),
                    totalLikes = AccountStatContributionPolicy.toDisplayInt(totals.totalLikes),
                    totalFollows = AccountStatContributionPolicy.toDisplayInt(totals.totalFollows),
                    totalVideos = AccountStatContributionPolicy.toDisplayInt(totals.totalVideos),
                    createdAt = account.createdAt,
                    updatedAt = maxOf(
                        current?.updatedAt ?: 0L,
                        account.statusUpdatedAt,
                        totals.updatedAt,
                    ),
                    statusUpdatedAt = maxOf(current?.statusUpdatedAt ?: 0L, account.statusUpdatedAt),
                    statsUpdatedAt = totals.updatedAt,
                )
            }

            // Check existing session identities before the IGNORE insert so a same-UUID
            // content mismatch cannot be silently hidden by SQLite conflict handling.
            val restoreSessionCutoff = BackupSessionRetentionPolicy.cutoff(System.currentTimeMillis())
            val incomingSessions = snapshot.datasets.closedSessions.filter {
                it.startedAt >= restoreSessionCutoff
            }
            val existingSessions = incomingSessions.map { it.syncId }
                .chunked(500)
                .flatMap { database.sessionDao().getBySyncIds(it) }
            val existingSessionsById = existingSessions.associateBy { it.syncId }
            incomingSessions.forEach { incoming ->
                val current = existingSessionsById[incoming.syncId] ?: return@forEach
                if (current.toBackupClosedSession() != incoming) {
                    throw BackupRestoreException(
                        "Session UUID content changed locally after merge staging: ${incoming.syncId}"
                    )
                }
            }
            val newSessions = incomingSessions
                .filter { it.syncId !in existingSessionsById }
                .map {
                    SessionEntity(
                        syncId = it.syncId,
                        accountId = it.accountId,
                        startedAt = it.startedAt,
                        endedAt = it.endedAt,
                        likes = it.likes,
                        follows = it.follows,
                        comments = it.comments,
                        videosWatched = it.videosWatched,
                        durationSecs = it.durationSecs,
                    )
                }

            val currentConfigs = database.configDao().getAll()
            val finalEligibleConfigs = LinkedHashMap<String, ConfigEntity>()
            val scheduleIdsToCancel = linkedSetOf<String>()

            currentConfigs.filter { BackupConfigPolicy.datasetFor(it.key) == "schedules" }
                .forEach { scheduleIdsToCancel += it.key.removePrefix(SCHEDULE_PREFIX) }

            currentConfigs.forEach { current ->
                when (BackupConfigPolicy.datasetFor(current.key)) {
                    "safeConfigs" -> {
                        if (current.key in safeConfigDeleteKeys) {
                            if (safeConfigDeletes.any {
                                    it.entityKey == current.key &&
                                        it.sourceInstallId == metadata.installationId
                                }
                            ) {
                                throw BackupRestoreException(
                                    "Same-installation config delete/recreate needs explicit resolution: ${current.key}"
                                )
                            }
                        } else {
                            val incoming = incomingSafeConfigs[current.key]
                            if (incoming != null && incoming.value != current.value) {
                                throw BackupRestoreException(
                                    "Configuration changed locally after merge staging: ${current.key}"
                                )
                            }
                            finalEligibleConfigs[current.key] = if (incoming != null) {
                                ConfigEntity(current.key, incoming.value, maxOf(current.updatedAt, incoming.updatedAt))
                            } else current
                        }
                    }
                    "schedules" -> {
                        val scheduleId = current.key.removePrefix(SCHEDULE_PREFIX)
                        scheduleIdsToCancel += scheduleId
                        if (scheduleId in scheduleDeleteIds) {
                            if (scheduleDeletes.any {
                                    it.entityKey == scheduleId &&
                                        it.sourceInstallId == metadata.installationId
                                }
                            ) {
                                throw BackupRestoreException(
                                    "Same-installation schedule delete/recreate needs explicit resolution: $scheduleId"
                                )
                            }
                        } else {
                            val incoming = incomingSchedules[scheduleId]
                            if (incoming != null) {
                                val currentDefinition = decodeSchedule(current.value, scheduleId)
                                    ?: throw BackupRestoreException(
                                        "Existing schedule cannot be safely compared during restore: $scheduleId"
                                    )
                                if (!sameScheduleDefinition(currentDefinition, incoming)) {
                                    throw BackupRestoreException(
                                        "Schedule changed locally after merge staging: $scheduleId"
                                    )
                                }
                                finalEligibleConfigs[current.key] = scheduleConfig(incoming)
                                    .copy(updatedAt = maxOf(current.updatedAt, incoming.updatedAt))
                            } else {
                                // A schedule created after staging is retained but disabled.
                                val currentDefinition = decodeSchedule(current.value, scheduleId)
                                finalEligibleConfigs[current.key] = if (currentDefinition == null) {
                                    current
                                } else {
                                    ConfigEntity(
                                        current.key,
                                        scheduleJson.encodeToString(currentDefinition.copy(enabled = false)),
                                        current.updatedAt,
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit // Unknown/device-local settings are deliberately untouched.
                }
            }

            incomingSafeConfigs.values.forEach { incoming ->
                if (incoming.key !in safeConfigDeleteKeys) {
                    finalEligibleConfigs.putIfAbsent(
                        incoming.key,
                        ConfigEntity(incoming.key, incoming.value, incoming.updatedAt),
                    )
                }
            }
            incomingSchedules.values.forEach { incoming ->
                if (incoming.scheduleId !in scheduleDeleteIds) {
                    val key = SCHEDULE_PREFIX + incoming.scheduleId
                    finalEligibleConfigs[key] = scheduleConfig(incoming)
                    scheduleIdsToCancel += incoming.scheduleId
                }
            }
            scheduleDeleteIds.forEach { scheduleIdsToCancel += it }

            // Verify tombstone UUID reuse against local durable markers before idempotent insert.
            val incomingTombstones = tombstones.map {
                BackupTombstoneEntity(
                    tombstoneId = it.tombstoneId,
                    dataset = it.dataset,
                    entityKey = it.entityKey,
                    sourceInstallId = it.sourceInstallId,
                    deletedAt = it.deletedAt,
                )
            }
            val existingTombstones = if (incomingTombstones.isEmpty()) {
                emptyMap()
            } else {
                incomingTombstones.map { it.tombstoneId }
                    .chunked(500)
                    .flatMap { database.backupTombstoneDao().getByIds(it) }
                    .associateBy { it.tombstoneId }
            }
            incomingTombstones.forEach { incoming ->
                val existing = existingTombstones[incoming.tombstoneId]
                if (existing != null && existing != incoming) {
                    throw BackupRestoreException(
                        "Tombstone UUID was reused for different delete metadata: ${incoming.tombstoneId}"
                    )
                }
            }

            // Room transaction: accounts first, then their FK-dependent contributions.
            database.accountDao().deleteAll()
            if (finalAccounts.isNotEmpty()) database.accountDao().upsertAll(finalAccounts)
            database.accountStatContributionDao().deleteAll()
            if (finalContributions.isNotEmpty()) {
                database.accountStatContributionDao().upsertAll(
                    finalContributions.map { it.toContributionEntity() }
                )
            }
            if (newSessions.isNotEmpty()) database.sessionDao().insertBackupSessions(newSessions)

            currentConfigs.filter { BackupConfigPolicy.datasetFor(it.key) != null }
                .forEach { database.configDao().delete(it.key) }
            finalEligibleConfigs.values.forEach { database.configDao().set(it) }

            val newTombstones = incomingTombstones.filter {
                it.tombstoneId !in existingTombstones
            }
            if (newTombstones.isNotEmpty()) {
                database.backupTombstoneDao().insertAllIgnore(newTombstones)
            }

            scheduleIdsToCancel
        }

    private fun scheduleConfig(schedule: BackupSchedule): ConfigEntity {
        val disabled = ScheduledFarmManager.FarmSchedule(
            id = schedule.scheduleId,
            label = schedule.label,
            hourOfDay = schedule.hourOfDay,
            minute = schedule.minute,
            daysOfWeek = schedule.daysOfWeek,
            enabled = false,
            accounts = schedule.accounts,
            serviceMode = schedule.serviceMode,
        )
        return ConfigEntity(
            key = SCHEDULE_PREFIX + schedule.scheduleId,
            value = scheduleJson.encodeToString(disabled),
            updatedAt = schedule.updatedAt,
        )
    }

    private fun decodeSchedule(value: String, expectedId: String): ScheduledFarmManager.FarmSchedule? {
        val schedule = runCatching {
            scheduleJson.decodeFromString<ScheduledFarmManager.FarmSchedule>(value)
        }.getOrNull() ?: return null
        if (schedule.id != expectedId) {
            throw BackupRestoreException("Saved schedule ID does not match its config key")
        }
        return schedule
    }

    private fun sameScheduleDefinition(
        local: ScheduledFarmManager.FarmSchedule,
        incoming: BackupSchedule,
    ): Boolean =
        local.id == incoming.scheduleId &&
            local.label == incoming.label &&
            local.hourOfDay == incoming.hourOfDay &&
            local.minute == incoming.minute &&
            local.daysOfWeek == incoming.daysOfWeek &&
            local.accounts == incoming.accounts &&
            local.serviceMode == incoming.serviceMode

    private fun AccountEntity.toBackupAccount() = BackupAccount(
        username = username,
        status = status,
        checkpoint = checkpoint,
        createdAt = createdAt,
        statusUpdatedAt = statusUpdatedAt,
        statsUpdatedAt = statsUpdatedAt,
    )

    private fun AccountStatContributionEntity.toBackupContribution() =
        BackupAccountStatContribution(
            accountId = accountId,
            installationId = installationId,
            sessionsCount = sessionsCount,
            totalLikes = totalLikes,
            totalFollows = totalFollows,
            totalVideos = totalVideos,
            updatedAt = updatedAt,
        )

    private fun BackupAccountStatContribution.toContributionEntity() =
        AccountStatContributionEntity(
            accountId = accountId,
            installationId = installationId,
            sessionsCount = sessionsCount,
            totalLikes = totalLikes,
            totalFollows = totalFollows,
            totalVideos = totalVideos,
            updatedAt = updatedAt,
        )

    private fun SessionEntity.toBackupClosedSession(): BackupClosedSession? {
        val end = endedAt ?: return null
        return BackupClosedSession(
            syncId = syncId,
            accountId = accountId,
            startedAt = startedAt,
            endedAt = end,
            likes = likes,
            follows = follows,
            comments = comments,
            videosWatched = videosWatched,
            durationSecs = durationSecs,
        )
    }

    private fun writeJournal(jsonText: String) {
        var output: FileOutputStream? = null
        try {
            output = journal.startWrite()
            output.write(jsonText.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            journal.finishWrite(output)
        } catch (error: Exception) {
            output?.let { journal.failWrite(it) }
            throw error
        }
    }

    private fun readJournal(): String {
        val baseLength = journalFile.length()
        val backupLength = File(journalFile.path + ".bak").length()
        if (maxOf(baseLength, backupLength) > BackupRestoreJournalCodec.MAX_JOURNAL_BYTES) {
            throw BackupRestoreException("Prepared restore journal exceeds the local safety bound")
        }
        return journal.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        private const val JOURNAL_FILE = "backup_restore.pending"
        private const val SCHEDULE_PREFIX = "sched_"
        private const val MAX_CLOSED_SESSIONS = 100_000
        private const val MAX_EARNINGS = 25_000
        private val restoreMutex = Mutex()

        /** Includes AtomicFile's backup path so startup also recovers an interrupted rename. */
        fun hasPending(context: Context): Boolean {
            val file = File(context.applicationContext.noBackupFilesDir, JOURNAL_FILE)
            return file.exists() || File(file.path + ".bak").exists()
        }

        private val scheduleJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

