package com.atpro.sync

import org.junit.Assert.fail
import org.junit.Test

class BackupSnapshotValidatorTest {
    private val installationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val earningId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun validSnapshot() = BackupSnapshot(
        formatVersion = 1,
        createdAt = 1_700_000_000_000L,
        appVersion = "1.12.0",
        sourceInstallId = installationId,
        datasets = BackupSnapshotDatasets(
            accounts = listOf(
                BackupAccount(
                    username = "sample_account",
                    status = "active",
                    checkpoint = false,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                )
            ),
            closedSessions = listOf(
                BackupClosedSession(
                    syncId = sessionId,
                    accountId = "sample_account",
                    startedAt = 1_700_000_000_000L,
                    endedAt = 1_700_000_001_000L,
                    likes = 1,
                    follows = 0,
                    comments = 0,
                    videosWatched = 2,
                    durationSecs = 1,
                )
            ),
            safeConfigs = listOf(BackupSafeConfig("like_rate", "0.3", 1L)),
            schedules = listOf(
                BackupSchedule(
                    scheduleId = "night",
                    label = "Night",
                    hourOfDay = 21,
                    minute = 0,
                    daysOfWeek = listOf(1, 3, 5),
                    enabledAtSource = true,
                    accounts = listOf("sample_account"),
                    serviceMode = "NORMAL",
                    updatedAt = 1L,
                )
            ),
            earnings = listOf(
                BackupEarning(
                    id = earningId,
                    atMs = 1_700_000_000_000L,
                    kind = "reward",
                    reward = 2.5,
                    account = "sample_account",
                )
            ),
            accountStats = mapOf(
                "sample_account" to BackupAccountStats(
                    sessionsCount = 1,
                    totalLikes = 1,
                    totalFollows = 0,
                    totalVideos = 2,
                    updatedAt = 1_700_000_001_000L,
                )
            ),
        ),
    )

    private fun assertRejected(snapshot: BackupSnapshot) {
        try {
            BackupSnapshotValidator.validate(snapshot)
            fail("Expected invalid snapshot to be rejected")
        } catch (_: BackupCodecException) {
            // Expected: invalid snapshots must fail before restore/merge.
        }
    }

    @Test
    fun acceptsAValidSnapshot() {
        BackupSnapshotValidator.validate(validSnapshot())
    }

    @Test
    fun rejectsNonCanonicalIdentifiers() {
        val base = validSnapshot()
        assertRejected(base.copy(sourceInstallId = "1-1-1-1-1"))
        val invalidSession = base.copy(
            datasets = base.datasets.copy(
                closedSessions = listOf(base.datasets.closedSessions.single().copy(syncId = "1-1-1-1-1"))
            )
        )
        assertRejected(invalidSession)
        val invalidEarning = base.copy(
            datasets = base.datasets.copy(
                earnings = listOf(base.datasets.earnings.single().copy(id = "1-1-1-1-1"))
            )
        )
        assertRejected(invalidEarning)
    }

    @Test
    fun rejectsNegativeOrReversedTimestamps() {
        val base = validSnapshot()
        assertRejected(base.copy(createdAt = -1L))
        val reversed = base.copy(
            datasets = base.datasets.copy(
                closedSessions = listOf(base.datasets.closedSessions.single().copy(endedAt = 0L))
            )
        )
        assertRejected(reversed)
        val negativeEarningTime = base.copy(
            datasets = base.datasets.copy(
                earnings = listOf(base.datasets.earnings.single().copy(atMs = -1L))
            )
        )
        assertRejected(negativeEarningTime)
    }

    @Test
    fun rejectsInvalidScheduleAndStatsTimestamps() {
        val base = validSnapshot()
        val duplicateDays = base.copy(
            datasets = base.datasets.copy(
                schedules = listOf(base.datasets.schedules.single().copy(daysOfWeek = listOf(1, 1)))
            )
        )
        assertRejected(duplicateDays)
        val invalidStats = base.copy(
            datasets = base.datasets.copy(
                accountStats = mapOf("sample_account" to base.datasets.accountStats.getValue("sample_account").copy(updatedAt = -1L))
            )
        )
        assertRejected(invalidStats)
    }
}
