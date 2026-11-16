package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRestoreJournalCodecTest {
    private fun recordJson(
        journalVersion: Int = 1,
        schemaVersion: Int = BackupSnapshot.CURRENT_FORMAT_VERSION,
        ownerUserId: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        remoteCheckpointJson: String? = null,
    ): String {
        val checkpoint = remoteCheckpointJson?.let { ",\\"remoteCheckpoint\\":$it" }.orEmpty()
        return """{"journalVersion":$journalVersion,"ownerUserId":"$ownerUserId","schemaVersion":$schemaVersion,"payload":"Zm9v","checksum":"0000000000000000000000000000000000000000000000000000000000000000"$checkpoint}"""
    }

    @Test
    fun journalRecordAcceptsOnlyTheCurrentJournalAndSnapshotVersions() {
        val record = BackupRestoreJournalCodec.decodeRecord(recordJson())
        assertEquals(1, record.journalVersion)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", record.ownerUserId)
        assertEquals(BackupSnapshot.CURRENT_FORMAT_VERSION, record.schemaVersion)
        assertEquals("Zm9v", record.payload)
    }

    @Test
    fun journalAcceptsValidatedRemoteCheckpointMetadata() {
        val remoteChecksum = "a".repeat(64)
        val checkpointJson = """{"expectedLocalRevision":0,"remoteRevision":6,"remoteChecksum":"$remoteChecksum","remoteSchemaVersion":3}"""
        val record = BackupRestoreJournalCodec.decodeRecord(
            recordJson(remoteCheckpointJson = checkpointJson)
        )

        assertEquals(0L, record.remoteCheckpoint?.expectedLocalRevision)
        assertEquals(6L, record.remoteCheckpoint?.remoteRevision)
        assertEquals(remoteChecksum, record.remoteCheckpoint?.remoteChecksum)
        assertEquals(3, record.remoteCheckpoint?.remoteSchemaVersion)
    }

    @Test
    fun journalRejectsRemoteCheckpointBehindLocalRevision() {
        val checkpointJson = """{"expectedLocalRevision":5,"remoteRevision":4,"remoteChecksum":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","remoteSchemaVersion":3}"""

        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(
                recordJson(remoteCheckpointJson = checkpointJson)
            )
        }
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
    fun journalRejectsInvalidOwnerUuid() {
        assertThrows(BackupRestoreException::class.java) {
            BackupRestoreJournalCodec.decodeRecord(recordJson(ownerUserId = "not-a-uuid"))
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
