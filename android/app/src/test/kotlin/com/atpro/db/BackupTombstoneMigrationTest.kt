package com.atpro.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class BackupTombstoneMigrationTest {
    @Test
    fun migrationCreatesIndexesAndBackfillsPendingDeletesWithStableIds() {
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)
        val pendingCursor = mockk<Cursor>(relaxed = true)
        val changesCursor = mockk<Cursor>(relaxed = true)

        every {
            database.query(match<String> { it.contains("SELECT COUNT(*) FROM sync_outbox") })
        } returns pendingCursor
        every { database.query("SELECT changes()") } returns changesCursor
        every { pendingCursor.moveToFirst() } returns true
        every { pendingCursor.getLong(0) } returns 2L
        every { changesCursor.moveToFirst() } returns true
        every { changesCursor.getLong(0) } returns 2L

        AtProDatabase.MIGRATION_4_5.migrate(database)

        verify {
            database.execSQL(match {
                it.contains("CREATE TABLE IF NOT EXISTS backup_tombstones") &&
                    it.contains("tombstoneId TEXT NOT NULL PRIMARY KEY") &&
                    it.contains("sourceInstallId TEXT NOT NULL") &&
                    it.contains("deletedAt INTEGER NOT NULL")
            })
        }
        verify {
            database.execSQL(match { it.contains("index_backup_tombstones_dataset_entityKey") })
        }
        verify {
            database.execSQL(match { it.contains("index_backup_tombstones_sourceInstallId") })
        }
        verify {
            database.execSQL(match { it.contains("index_backup_tombstones_deletedAt") })
        }
        verify {
            database.execSQL(match {
                it.contains("INSERT INTO backup_tombstones") &&
                    it.contains("outbox.eventId") &&
                    it.contains("metadata.installationId") &&
                    it.contains("outbox.createdAt") &&
                    it.contains("outbox.operation = 'DELETE'")
            })
        }
    }

    @Test
    fun migrationFailsClosedIfAnEligibleDeleteCannotBeBackfilled() {
        val database = mockk<SupportSQLiteDatabase>(relaxed = true)
        val pendingCursor = mockk<Cursor>(relaxed = true)
        val changesCursor = mockk<Cursor>(relaxed = true)

        every {
            database.query(match<String> { it.contains("SELECT COUNT(*) FROM sync_outbox") })
        } returns pendingCursor
        every { database.query("SELECT changes()") } returns changesCursor
        every { pendingCursor.moveToFirst() } returns true
        every { pendingCursor.getLong(0) } returns 1L
        every { changesCursor.moveToFirst() } returns true
        every { changesCursor.getLong(0) } returns 0L

        try {
            AtProDatabase.MIGRATION_4_5.migrate(database)
            throw AssertionError("Migration must reject an incomplete tombstone backfill")
        } catch (_: IllegalStateException) {
            // Expected fail-closed migration behavior.
        }
    }
}
