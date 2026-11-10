package com.atpro.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure bounded codec for the on-disk restore journal. Keeping this separate from AtomicFile
 * lets JVM tests verify journal metadata, version rejection and size bounds without Android runtime APIs.
 */
internal object BackupRestoreJournalCodec {
    const val MAX_JOURNAL_BYTES = BackupCodec.MAX_ENCODED_PAYLOAD_BYTES + 32 * 1024
    private const val MAX_CLOSED_SESSIONS = 100_000
    private const val MAX_EARNINGS = 25_000
    private const val JOURNAL_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(snapshot: BackupSnapshot): String {
        BackupSnapshotValidator.validate(snapshot)
        require(snapshot.formatVersion == BackupSnapshot.CURRENT_FORMAT_VERSION) {
            "Only snapshot v3 can be staged for local restore"
        }
        validateBounds(snapshot)
        val encoded = BackupCodec.encode(snapshot)
        val record = BackupRestoreJournalRecord(
            journalVersion = JOURNAL_VERSION,
            schemaVersion = encoded.schemaVersion,
            payload = encoded.payload,
            checksum = encoded.checksum,
        )
        val text = json.encodeToString(record)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JOURNAL_BYTES) {
            throw BackupRestoreException("Prepared restore journal exceeds the local safety bound")
        }
        return text
    }

    fun decode(text: String): BackupSnapshot {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JOURNAL_BYTES) {
            throw BackupRestoreException("Prepared restore journal exceeds the local safety bound")
        }
        val record = decodeRecord(text)
        val snapshot = BackupCodec.decode(
            payload = record.payload,
            expectedChecksum = record.checksum,
            expectedSchemaVersion = record.schemaVersion,
        )
        validateBounds(snapshot)
        return snapshot
    }

    private fun validateBounds(snapshot: BackupSnapshot) {
        if (snapshot.datasets.closedSessions.size > MAX_CLOSED_SESSIONS ||
            snapshot.datasets.earnings.size > MAX_EARNINGS ||
            snapshot.datasets.tombstones.size > BackupSnapshotValidator.MAX_TOMBSTONES
        ) {
            throw BackupRestoreException("Snapshot exceeds a restore dataset safety bound")
        }
    }

    internal fun decodeRecord(text: String): BackupRestoreJournalRecord {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JOURNAL_BYTES) {
            throw BackupRestoreException("Prepared restore journal exceeds the local safety bound")
        }
        val record = try {
            json.decodeFromString<BackupRestoreJournalRecord>(text)
        } catch (error: Exception) {
            throw BackupRestoreException("Prepared restore journal is malformed", error)
        }
        if (record.journalVersion != JOURNAL_VERSION ||
            record.schemaVersion != BackupSnapshot.CURRENT_FORMAT_VERSION ||
            record.payload.isBlank() ||
            !record.checksum.matches(Regex("^[0-9a-f]{64}$"))
        ) {
            throw BackupRestoreException("Unsupported or invalid local restore journal metadata")
        }
        return record
    }
}

@Serializable
internal data class BackupRestoreJournalRecord(
    val journalVersion: Int,
    val schemaVersion: Int,
    val payload: String,
    val checksum: String,
)
