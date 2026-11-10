package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRestoreJournalCodecTest {
    private fun recordJson(
        journalVersion: Int = 1,
        schemaVersion: Int = BackupSnapshot.CURRENT_FORMAT_VERSION,
    ) = """{"journalVersion":$journalVersion,"schemaVersion":$schemaVersion,"payload":"Zm9v","checksum":"0000000000000000000000000000000000000000000000000000000000000000"}"""

    @Test
    fun journalRecordAcceptsOnlyTheCurrentJournalAndSnapshotVersions() {
        val record = BackupRestoreJournalCodec.decodeRecord(recordJson())
        assertEquals(1, record.journalVersion)
        assertEquals(BackupSnapshot.CURRENT_FORMAT_VERSION, record.schemaVersion)
        assertEquals("Zm9v", record.payload)
    }

    @Test
    fun journalRejectsUnknownJournalVersion() {
        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(recordJson(journalVersion = 99))
        }
    }

    @Test
    fun journalRejectsUnknownSnapshotVersion() {
        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(recordJson(schemaVersion = 99))
        }
    }

    @Test
    fun journalRejectsMalformedChecksum() {
        val malformed = recordJson().replace("0".repeat(64), "x".repeat(64))
        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(malformed)
        }
    }

    @Test
    fun journalRejectsOversizedInputBeforeDecoding() {
        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(" ".repeat(BackupRestoreJournalCodec.MAX_JOURNAL_BYTES + 1))
        }
    }
}
