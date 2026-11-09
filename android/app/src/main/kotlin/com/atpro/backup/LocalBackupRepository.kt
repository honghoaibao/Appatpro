package com.atpro.backup

import android.content.Context
import androidx.room.withTransaction
import com.atpro.data.EarningEntry
import com.atpro.data.EarningsStore
import com.atpro.db.AtProDatabase
import com.atpro.db.entity.AccountEntity
import com.atpro.db.entity.ConfigEntity
import com.atpro.db.entity.FarmLogEntity
import com.atpro.db.entity.SessionEntity
import com.atpro.ui.theme.AtThemeMode
import com.atpro.ui.theme.AtThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class RestoreResult {
    object Restored : RestoreResult()
    object NeedsConfirmation : RestoreResult()
    data class Failed(val reason: String) : RestoreResult()
}

class LocalBackupRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AtProDatabase.getInstance(appContext)
    private val repo = com.atpro.data.LocalRepository.getInstance(appContext)

    suspend fun createSnapshot(): BackupSnapshot = withContext(Dispatchers.IO) {
        repo.flushLogs()
        val data = db.withTransaction {
            SnapshotRows(
                accounts = db.accountDao().getAll(),
                sessions = db.sessionDao().getRecent(MAX_SESSIONS),
                configs = db.configDao().getAll().filterNot { isSensitiveConfigKey(it.key) },
                logs = db.logDao().getRecent(MAX_LOGS),
            )
        }
        val earnings = EarningsStore.readAll().takeLast(MAX_EARNINGS)
            .map { BackupEarning(it.atMs, it.kind, it.reward, it.account) }

        BackupSnapshot(
            exportedAt = System.currentTimeMillis(),
            accounts = data.accounts.map {
                BackupAccount(
                    username = it.username,
                    status = it.status,
                    checkpoint = it.checkpoint,
                    sessionsCount = it.sessionsCount,
                    totalLikes = it.totalLikes,
                    totalFollows = it.totalFollows,
                    totalVideos = it.totalVideos,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            sessions = data.sessions.map {
                BackupSession(
                    id = it.id,
                    accountId = it.accountId,
                    startedAt = it.startedAt,
                    endedAt = it.endedAt,
                    likes = it.likes,
                    follows = it.follows,
                    comments = it.comments,
                    videosWatched = it.videosWatched,
                    durationSecs = it.durationSecs,
                )
            },
            configs = data.configs.map { BackupConfig(it.key, it.value, it.updatedAt) },
            logs = data.logs.map {
                BackupLog(
                    id = it.id,
                    accountId = it.accountId,
                    level = it.level,
                    message = redactLog(it.message),
                    timestamp = it.timestamp,
                )
            },
            earnings = earnings,
            themeMode = AtThemeState.mode.name,
        )
    }

    suspend fun hasLocalData(): Boolean = withContext(Dispatchers.IO) {
        db.accountDao().count() > 0 ||
            db.sessionDao().count() > 0 ||
            db.logDao().count() > 0 ||
            db.configDao().getAll().isNotEmpty() ||
            EarningsStore.readAll().isNotEmpty()
    }

    /** Replaces only backup-managed data. Caller must explicitly allow replacement. */
    suspend fun restore(snapshot: BackupSnapshot, allowReplace: Boolean): RestoreResult =
        withContext(Dispatchers.IO) {
            if (snapshot.schemaVersion != BACKUP_SCHEMA_VERSION) {
                return@withContext RestoreResult.Failed("Backup version is not compatible")
            }
            val validAccounts = snapshot.accounts.map { it.username }.toSet()
            if (snapshot.sessions.any { it.accountId !in validAccounts }) {
                return@withContext RestoreResult.Failed("Backup contains sessions without an account")
            }
            if (!allowReplace && hasLocalData()) return@withContext RestoreResult.NeedsConfirmation

            try {
                db.withTransaction {
                    db.logDao().clearAll()
                    db.sessionDao().deleteAll()
                    db.accountDao().deleteAll()
                    db.configDao().deleteAll()

                    db.accountDao().upsertAll(snapshot.accounts.map {
                        AccountEntity(
                            username = it.username,
                            status = it.status,
                            checkpoint = it.checkpoint,
                            sessionsCount = it.sessionsCount,
                            totalLikes = it.totalLikes,
                            totalFollows = it.totalFollows,
                            totalVideos = it.totalVideos,
                            createdAt = it.createdAt,
                            updatedAt = it.updatedAt,
                        )
                    })
                    db.sessionDao().upsertAll(snapshot.sessions.map {
                        SessionEntity(
                            id = it.id,
                            accountId = it.accountId,
                            startedAt = it.startedAt,
                            endedAt = it.endedAt,
                            likes = it.likes,
                            follows = it.follows,
                            comments = it.comments,
                            videosWatched = it.videosWatched,
                            durationSecs = it.durationSecs,
                        )
                    })
                    db.configDao().upsertAll(snapshot.configs.map {
                        ConfigEntity(key = it.key, value = it.value, updatedAt = it.updatedAt)
                    })
                    db.logDao().insertAll(snapshot.logs.map {
                        FarmLogEntity(
                            id = it.id,
                            accountId = it.accountId,
                            level = it.level,
                            message = it.message,
                            timestamp = it.timestamp,
                        )
                    })
                }

                EarningsStore.replaceAll(snapshot.earnings.map {
                    EarningEntry(it.atMs, it.kind, it.reward, it.account)
                })
                val mode = AtThemeMode.values().firstOrNull { it.name == snapshot.themeMode }
                    ?: AtThemeMode.LIGHT
                AtThemeState.restore(appContext, mode)
                RestoreResult.Restored
            } catch (error: Exception) {
                RestoreResult.Failed(error.message ?: "Could not restore backup")
            }
        }

    private data class SnapshotRows(
        val accounts: List<AccountEntity>,
        val sessions: List<SessionEntity>,
        val configs: List<ConfigEntity>,
        val logs: List<FarmLogEntity>,
    )

    private fun isSensitiveConfigKey(key: String): Boolean {
        val normalized = key.lowercase()
        return listOf("token", "password", "secret", "webhook", "api_key", "apikey", "credential")
            .any(normalized::contains)
    }

    private fun redactLog(value: String): String {
        var result = value
        result = Regex("(?i)bearer\\s+[A-Za-z0-9._~-]+")
            .replace(result, "Bearer [REDACTED]")
        result = Regex("(?i)(access_token|refresh_token|password|x-sign)(\\s*[=:]\\s*)[^\\s,;]+")
            .replace(result, "[REDACTED]")
        return result.take(MAX_LOG_MESSAGE_CHARS)
    }

    companion object {
        private const val MAX_SESSIONS = 10_000
        private const val MAX_LOGS = 1_000
        private const val MAX_EARNINGS = 25_000
        private const val MAX_LOG_MESSAGE_CHARS = 2_000
    }
}
