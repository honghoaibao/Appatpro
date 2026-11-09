package com.atpro.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.atpro.auth.SupabaseConfig
import com.atpro.auth.SupabaseHttpClient
import com.atpro.auth.SupabaseServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

class BackupSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success()
        if (BackupStateStore.isOfflineMode(applicationContext)) return@withContext Result.success()

        val auth = SupabaseServices.auth(applicationContext)
        val session = auth.refreshSession() ?: return@withContext Result.success()
        val local = LocalBackupRepository(applicationContext)
        val remoteSource = SupabaseBackupDataSource(
            SupabaseHttpClient(SupabaseConfig.PROJECT_URL, SupabaseConfig.PUBLISHABLE_KEY)
        )

        try {
            val localHasData = local.hasLocalData()
            val remote = remoteSource.fetch(session.user.id, session.accessToken)
            if (remote != null && remote.ownerId != session.user.id) {
                return@withContext Result.failure()
            }

            if (remote == null) {
                if (!localHasData) {
                    BackupStateStore.markSynced(
                        applicationContext,
                        BackupStateStore.generation(applicationContext),
                        0L,
                    )
                    return@withContext Result.success()
                }
                val encoded = BackupSnapshotCodec.encode(local.createSnapshot())
                val generation = BackupStateStore.generation(applicationContext)
                when (val saved = remoteSource.save(
                    0L, BACKUP_SCHEMA_VERSION, encoded.json, encoded.sha256, session.accessToken
                )) {
                    is BackupWriteResult.Saved -> {
                        BackupStateStore.markSynced(applicationContext, generation, saved.revision)
                        return@withContext Result.success()
                    }
                    BackupWriteResult.Conflict -> {
                        BackupStateStore.setConflict(applicationContext, true)
                        return@withContext Result.success()
                    }
                    is BackupWriteResult.Failed -> return@withContext Result.retry()
                }
            }

            if (!localHasData) {
                val snapshot = BackupSnapshotCodec.decode(remote.payload, remote.checksum)
                val restored = local.restore(snapshot, allowReplace = true)
                if (restored is RestoreResult.Failed) return@withContext Result.failure()
                BackupStateStore.markSynced(
                    applicationContext,
                    BackupStateStore.generation(applicationContext),
                    remote.revision,
                )
                return@withContext Result.success()
            }

            val knownRevision = BackupStateStore.remoteRevision(applicationContext)
            val dirty = BackupStateStore.isDirty(applicationContext)
            if (dirty) {
                if (knownRevision != remote.revision) {
                    BackupStateStore.setConflict(applicationContext, true)
                    return@withContext Result.success()
                }
                val encoded = BackupSnapshotCodec.encode(local.createSnapshot())
                val generation = BackupStateStore.generation(applicationContext)
                when (val saved = remoteSource.save(
                    knownRevision, BACKUP_SCHEMA_VERSION, encoded.json, encoded.sha256, session.accessToken
                )) {
                    is BackupWriteResult.Saved -> {
                        BackupStateStore.markSynced(applicationContext, generation, saved.revision)
                        return@withContext Result.success()
                    }
                    BackupWriteResult.Conflict -> {
                        BackupStateStore.setConflict(applicationContext, true)
                        return@withContext Result.success()
                    }
                    is BackupWriteResult.Failed -> return@withContext Result.retry()
                }
            }

            // A non-empty local database with no known remote baseline must not be overwritten.
            if (knownRevision == 0L && remote.revision > 0L) {
                BackupStateStore.setConflict(applicationContext, true)
                return@withContext Result.success()
            }

            if (remote.revision != knownRevision) {
                val snapshot = BackupSnapshotCodec.decode(remote.payload, remote.checksum)
                val restored = local.restore(snapshot, allowReplace = true)
                if (restored is RestoreResult.Failed) return@withContext Result.failure()
            }
            BackupStateStore.markSynced(
                applicationContext,
                BackupStateStore.generation(applicationContext),
                remote.revision,
            )
            Result.success()
        } catch (_: SerializationException) {
            Result.failure()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
