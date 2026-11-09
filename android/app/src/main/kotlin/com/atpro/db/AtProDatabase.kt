package com.atpro.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import com.atpro.db.dao.*
import com.atpro.db.entity.*

// ═══════════════════════════════════════════════════════════════
//  AtProDatabase — Room SQLite database
//  File: /data/data/com.atpro/databases/atpro.db
//  Thay thế hoàn toàn Supabase — không cần internet để đọc/ghi data
// ═══════════════════════════════════════════════════════════════

@Database(
    entities = [
        AccountEntity::class,
        SessionEntity::class,
        FarmLogEntity::class,
        ConfigEntity::class,
        SyncMetadataEntity::class,
        SyncOutboxEntity::class,
    ],
    version  = 3,
    exportSchema = false,
)
abstract class AtProDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun sessionDao(): SessionDao
    abstract fun logDao():     LogDao
    abstract fun configDao():  ConfigDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        /**
         * Additive v1 to v2 migration. Existing rows are preserved; legacy sessions
         * receive stable UUIDs before the unique index is created.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                val cursor = db.query("SELECT id FROM sessions WHERE syncId = ''")
                try {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val syncId = UUID.randomUUID().toString()
                        db.execSQL("UPDATE sessions SET syncId = ? WHERE id = ?", arrayOf(syncId, id))
                    }
                } finally {
                    cursor.close()
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_syncId ON sessions(syncId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        id INTEGER NOT NULL PRIMARY KEY,
                        ownerUserId TEXT,
                        installationId TEXT NOT NULL,
                        lastRemoteRevision INTEGER NOT NULL,
                        lastSuccessfulSyncAt INTEGER,
                        lastChecksum TEXT,
                        formatVersion INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        dataset TEXT NOT NULL,
                        entityKey TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        payloadJson TEXT,
                        createdAt INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL,
                        lastErrorCode TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_eventId ON sync_outbox(eventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_createdAt ON sync_outbox(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_dataset_entityKey ON sync_outbox(dataset, entityKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_nextAttemptAt ON sync_outbox(nextAttemptAt)")
                val installationId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO sync_metadata (
                        id, ownerUserId, installationId, lastRemoteRevision,
                        lastSuccessfulSyncAt, lastChecksum, formatVersion, syncStatus, updatedAt
                    ) VALUES (1, NULL, ?, 0, NULL, NULL, 1, 'IDLE', ?)
                """.trimIndent(), arrayOf(installationId, now))
            }
        }

        /**
         * Add owner-binding and revision-checkpoint timestamps without touching any user data.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_metadata ADD COLUMN ownerBoundAt INTEGER")
                db.execSQL("ALTER TABLE sync_metadata ADD COLUMN lastRevisionConfirmedAt INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: AtProDatabase? = null

        fun getInstance(context: Context): AtProDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AtProDatabase::class.java,
                    "atpro.db",
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Fresh installs do not execute migrations; seed the singleton row here too.
                        db.execSQL("""
                            INSERT OR IGNORE INTO sync_metadata (
                                id, ownerUserId, installationId, lastRemoteRevision,
                                lastSuccessfulSyncAt, lastChecksum, formatVersion, syncStatus, updatedAt
                            ) VALUES (1, NULL, ?, 0, NULL, NULL, 1, 'IDLE', ?)
                        """.trimIndent(), arrayOf(UUID.randomUUID().toString(), System.currentTimeMillis()))
                    }
                })
                .build()
                .also { INSTANCE = it }
            }
    }
}
