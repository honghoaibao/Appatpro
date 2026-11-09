package com.atpro.sync

import android.content.Context
import androidx.room.withTransaction
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import com.atpro.db.entity.AccountEntity
import com.atpro.db.entity.AccountStatContributionEntity
import com.atpro.db.entity.ConfigEntity
import com.atpro.db.entity.SessionEntity
import com.atpro.scheduler.ScheduledFarmManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class BackupSnapshotBuildResult(
    val snapshot: BackupSnapshot,
    val earningsGeneration: Long,
    val earningsDirty: Boolean,
)

class BackupSnapshotException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Builds a local-only snapshot. It never performs network I/O, applies restored data,
 * queues workers, or starts automation.
 */
class BackupSnapshotBuilder(context: Context) {
    private val appContext = context.applicationContext
    private val database = AtProDatabase.getInstance(appContext)

    private data class RoomRows(
        val installationId: String,
        val accounts: List<AccountEntity>,
        val accountStatContributions: List<AccountStatContributionEntity>,
        val sessions: List<SessionEntity>,
        val configs: List<ConfigEntity>,
    )

    suspend fun build(): BackupSnapshotBuildResult = withContext(Dispatchers.IO) {
        EarningsStore.init(appContext)
        val earningsSnapshot = try {
            EarningsStore.readSnapshot()
        } catch (error: Exception) {
            throw BackupSnapshotException("Could not read the earnings backup source", error)
        }

        val now = System.currentTimeMillis()
        val retainedSince = now - TimeUnit.DAYS.toMillis(90)

        val roomRows = try {
            database.withTransaction {
                val metadata = database.syncMetadataDao().get()
                    ?: throw BackupSnapshotException("Sync metadata row is missing")
                validateInstallationId(metadata.installationId)

                val accounts = database.accountDao().getAll()
                val closedCount = database.sessionDao().countClosedForBackup(retainedSince)
                if (closedCount > MAX_CLOSED_SESSIONS) {
                    throw BackupSnapshotException(
                        "Too many retained closed sessions for a bounded snapshot: $closedCount"
                    )
                }

                val sessions = ArrayList<SessionEntity>(closedCount)
                var offset = 0
                while (offset < closedCount) {
                    val page = database.sessionDao().getClosedForBackup(
                        since = retainedSince,
                        limit = SESSION_PAGE_SIZE,
                        offset = offset,
                    )
                    if (page.isEmpty()) break
                    sessions.addAll(page)
                    offset += page.size
                }
                if (sessions.size != closedCount) {
                    throw BackupSnapshotException(
                        "Closed-session rows changed while the snapshot was being read"
                    )
                }

                RoomRows(
                    installationId = metadata.installationId,
                    accounts = accounts,
                    accountStatContributions = database.accountStatContributionDao().getAll(),
                    sessions = sessions,
                    configs = database.configDao().getAll(),
                )
            }
        } catch (error: BackupSnapshotException) {
            throw error
        } catch (error: Exception) {
            throw BackupSnapshotException("Could not read the local Room backup source", error)
        }

        val schedules = roomRows.configs
            .asSequence()
            .filter { BackupConfigPolicy.datasetFor(it.key) == "schedules" && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { row -> row.toBackupSchedule() }
            .toList()

        val safeConfigs = roomRows.configs
            .asSequence()
            .filter { BackupConfigPolicy.datasetFor(it.key) == "safeConfigs" }
            .sortedBy { it.key }
            .map { BackupSafeConfig(it.key, it.value, it.updatedAt) }
            .toList()

        val orderedAccounts = roomRows.accounts.sortedBy { it.username }
        val accountModels = orderedAccounts.map {
            BackupAccount(
                username = it.username,
                status = it.status,
                checkpoint = it.checkpoint,
                createdAt = it.createdAt,
                statusUpdatedAt = it.statusUpdatedAt,
                statsUpdatedAt = it.statsUpdatedAt,
            )
        }
        val accountContributions = roomRows.accountStatContributions
            .sortedWith(compareBy({ it.accountId }, { it.installationId }))
            .map {
                BackupAccountStatContribution(
                    accountId = it.accountId,
                    installationId = it.installationId,
                    sessionsCount = it.sessionsCount,
                    totalLikes = it.totalLikes,
                    totalFollows = it.totalFollows,
                    totalVideos = it.totalVideos,
                    updatedAt = it.updatedAt,
                )
            }

        val closedSessions = roomRows.sessions
            .asSequence()
            .filter { it.endedAt != null }
            .sortedBy { it.syncId }
            .map { session ->
                BackupClosedSession(
                    syncId = session.syncId,
                    accountId = session.accountId,
                    startedAt = session.startedAt,
                    endedAt = requireNotNull(session.endedAt),
                    likes = session.likes,
                    follows = session.follows,
                    comments = session.comments,
                    videosWatched = session.videosWatched,
                    durationSecs = session.durationSecs,
                )
            }
            .toList()

        val earningModels = earningsSnapshot.entries
            .sortedWith(compareBy({ it.atMs }, { it.id }))
            .map {
                BackupEarning(
                    id = it.id,
                    atMs = it.atMs,
                    kind = it.kind,
                    reward = it.reward,
                    account = it.account,
                )
            }

        val snapshot = BackupSnapshot(
            formatVersion = FORMAT_VERSION,
            createdAt = now,
            appVersion = readAppVersion(),
            sourceInstallId = roomRows.installationId,
            datasets = BackupSnapshotDatasets(
                accounts = accountModels,
                closedSessions = closedSessions,
                safeConfigs = safeConfigs,
                schedules = schedules,
                earnings = earningModels,
                accountStatContributions = accountContributions,
            ),
        )

        BackupSnapshotBuildResult(
            snapshot = snapshot,
            earningsGeneration = earningsSnapshot.dirtyState.generation,
            earningsDirty = earningsSnapshot.dirtyState.isDirty,
        )
    }

    private fun ConfigEntity.toBackupSchedule(): BackupSchedule {
        val scheduleId = key.removePrefix(SCHEDULE_PREFIX)
        val schedule = try {
            scheduleJson.decodeFromString<ScheduledFarmManager.FarmSchedule>(value)
        } catch (error: Exception) {
            throw BackupSnapshotException("Invalid saved schedule definition: $key", error)
        }
        if (schedule.id != scheduleId) {
            throw BackupSnapshotException("Schedule ID does not match its config key: $key")
        }
        return BackupSchedule(
            scheduleId = schedule.id,
            label = schedule.label,
            hourOfDay = schedule.hourOfDay,
            minute = schedule.minute,
            daysOfWeek = schedule.daysOfWeek.toList(),
            enabledAtSource = schedule.enabled,
            accounts = schedule.accounts.toList(),
            serviceMode = schedule.serviceMode,
            updatedAt = updatedAt,
        )
    }

    private fun validateInstallationId(value: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw BackupSnapshotException("Installation ID is not a valid UUID", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun readAppVersion(): String =
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

    private companion object {
        const val FORMAT_VERSION = BackupSnapshot.CURRENT_FORMAT_VERSION
        const val SCHEDULE_PREFIX = "sched_"
        const val SESSION_PAGE_SIZE = 500
        const val MAX_CLOSED_SESSIONS = 100_000

        val scheduleJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
