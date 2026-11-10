package com.atpro.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSnapshotV2CompatibilityTest {
    private val installationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private val canonicalJson = Json {
        encodeDefaults = true
        prettyPrint = false
        isLenient = false
        ignoreUnknownKeys = false
    }

    private fun validV2Snapshot() = BackupSnapshotV2(
        formatVersion = BackupSnapshot.LEGACY_FORMAT_VERSION,
        createdAt = 1_700_000_000_000L,
        appVersion = "1.12.0",
        sourceInstallId = installationId,
        datasets = BackupSnapshotDatasetsV2(
            accounts = emptyList(),
            closedSessions = emptyList(),
            safeConfigs = listOf(BackupSafeConfig("like_rate", "0.3", 1L)),
            schedules = emptyList(),
            earnings = emptyList(),
            accountStatContributions = emptyList(),
        ),
    )

    @Test
    fun convertsCanonicalV2IntoV3StagingModelWithoutInventingTombstones() {
        val legacyJson = canonicalJson.encodeToString(validV2Snapshot())

        val converted = BackupCodec.decodeCanonicalJson(
            jsonText = legacyJson,
            expectedSchemaVersion = BackupSnapshot.LEGACY_FORMAT_VERSION,
        )

        assertEquals(BackupSnapshot.CURRENT_FORMAT_VERSION, converted.formatVersion)
        assertEquals(installationId, converted.sourceInstallId)
        assertEquals(listOf(BackupSafeConfig("like_rate", "0.3", 1L)), converted.datasets.safeConfigs)
        assertTrue(converted.datasets.tombstones.isEmpty())
    }

    @Test
    fun rejectsFutureAndMismatchedSnapshotVersions() {
        val futureJson = canonicalJson.encodeToString(validV2Snapshot().copy(formatVersion = 4))
        assertRejected {
            BackupCodec.decodeCanonicalJson(futureJson, BackupSnapshot.LEGACY_FORMAT_VERSION)
        }
        assertRejected {
            BackupCodec.decodeCanonicalJson(
                canonicalJson.encodeToString(validV2Snapshot()),
                expectedSchemaVersion = 4,
            )
        }
        assertRejected {
            BackupCodec.decodeCanonicalJson(
                canonicalJson.encodeToString(validV2Snapshot()),
                expectedSchemaVersion = 1,
            )
        }
    }

    @Test
    fun rejectsNonCanonicalLegacyJson() {
        val prettyJson = canonicalJson.encodeToString(validV2Snapshot()).replace("{", "{\n  ")
        assertRejected {
            BackupCodec.decodeCanonicalJson(prettyJson, BackupSnapshot.LEGACY_FORMAT_VERSION)
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected unsupported or non-canonical backup to be rejected")
        } catch (_: BackupCodecException) {
            // Expected fail-closed compatibility behavior.
        }
    }
}
