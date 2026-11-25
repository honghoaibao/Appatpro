package com.atpro.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.13.0: bản sao lưu v3 (trước dòng tổng thu nhập) phải đọc được và được nâng lên v4. */
class BackupSnapshotV3CompatibilityTest {
    private val installationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private val canonicalJson = Json {
        encodeDefaults = true
        prettyPrint = false
        isLenient = false
        ignoreUnknownKeys = false
    }

    private fun validV3() = BackupSnapshotV3(
        formatVersion = BackupSnapshot.V3_FORMAT_VERSION,
        createdAt = 1_700_000_000_000L,
        appVersion = "1.12.7",
        sourceInstallId = installationId,
        datasets = BackupSnapshotDatasetsV3(
            accounts = emptyList(),
            closedSessions = emptyList(),
            safeConfigs = listOf(BackupSafeConfig("like_rate", "0.3", 1L)),
            schedules = emptyList(),
            earnings = listOf(
                BackupEarningLegacy("dddddddd-dddd-4ddd-8ddd-dddddddddddd", 10L, "LIKE", 1.5, "acc"),
            ),
            accountStatContributions = emptyList(),
        ),
    )

    @Test
    fun upgradesCanonicalV3ToV4WithEmptyRollupsAndNoOrigin() {
        val converted = BackupCodec.decodeCanonicalJson(
            canonicalJson.encodeToString(validV3()),
            BackupSnapshot.V3_FORMAT_VERSION,
        )
        assertEquals(BackupSnapshot.CURRENT_FORMAT_VERSION, converted.formatVersion)
        assertTrue(converted.datasets.earningRollups.isEmpty())
        assertNull(converted.datasets.earnings.single().origin)
        assertEquals("acc", converted.datasets.earnings.single().account)
    }

    @Test
    fun v4RoundTripsAndRejectsVersionMismatch() {
        val v4 = BackupCodec.decodeCanonicalJson(
            canonicalJson.encodeToString(validV3()), BackupSnapshot.V3_FORMAT_VERSION,
        )
        val json = canonicalJson.encodeToString(v4)
        val again = BackupCodec.decodeCanonicalJson(json, BackupSnapshot.CURRENT_FORMAT_VERSION)
        assertEquals(v4, again)
        try {
            BackupCodec.decodeCanonicalJson(json, BackupSnapshot.V3_FORMAT_VERSION)
            throw AssertionError("v4 JSON must not decode as expected v3")
        } catch (_: BackupCodecException) {
        }
    }
}
