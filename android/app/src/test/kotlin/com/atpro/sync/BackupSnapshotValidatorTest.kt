package com.atpro.sync

import org.junit.Assert.fail
import org.junit.Test

class BackupSnapshotValidatorTest {
    private val installationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val earningId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun validSnapshot() = BackupSnapshot(
        formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
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
                    statusUpdatedAt = 1_700_000_000_000L,
                    statsUpdatedAt = 1_700_000_001_000L,
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
            accountStatContributions = listOf(
                BackupAccountStatContribution(
                    accountId = "sample_account",
                    installationId = installationId,
                    sessionsCount = 1L,
                    totalLikes = 1L,
                    totalFollows = 0L,
                    totalVideos = 2L,
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
    fun rejectsUnsupportedFormatVersion() {
        assertRejected(validSnapshot().copy(formatVersion = 1))
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
        val invalidContribution = base.copy(
            datasets = base.datasets.copy(
                accountStatContributions = listOf(
                    base.datasets.accountStatContributions.single().copy(installationId = "1-1-1-1-1")
                )
            )
        )
        assertRejected(invalidContribution)
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
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accounts = listOf(base.datasets.accounts.single().copy(statusUpdatedAt = -1L))
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accounts = listOf(base.datasets.accounts.single().copy(statsUpdatedAt = -1L))
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    closedSessions = listOf(base.datasets.closedSessions.single().copy(endedAt = 0L))
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    earnings = listOf(base.datasets.earnings.single().copy(atMs = -1L))
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accountStatContributions = listOf(
                        base.datasets.accountStatContributions.single().copy(updatedAt = -1L)
                    )
                )
            )
        )
    }

    @Test
    fun rejectsStatsTimestampThatDoesNotMatchTheContributionWatermark() {
        val base = validSnapshot()
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accounts = listOf(base.datasets.accounts.single().copy(statsUpdatedAt = 1L))
                )
            )
        )
    }

    @Test
    fun requiresTheSourceInstallationContributionForEveryAccount() {
        val base = validSnapshot()
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accountStatContributions = listOf(
                        base.datasets.accountStatContributions.single().copy(
                            installationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
                        )
                    )
                )
            )
        )
    }

    @Test
    fun rejectsDuplicateOrUnknownAccountContributions() {
        val base = validSnapshot()
        val contribution = base.datasets.accountStatContributions.single()
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accountStatContributions = listOf(contribution, contribution)
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accountStatContributions = listOf(contribution.copy(accountId = "missing_account"))
                )
            )
        )
    }

    @Test
    fun rejectsNegativeContributionCountersAndMissingContribution() {
        val base = validSnapshot()
        val contribution = base.datasets.accountStatContributions.single()
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    accountStatContributions = listOf(contribution.copy(totalLikes = -1L))
                )
            )
        )
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(accountStatContributions = emptyList())
            )
        )
    }

    @Test
    fun rejectsInvalidSchedule() {
        val base = validSnapshot()
        assertRejected(
            base.copy(
                datasets = base.datasets.copy(
                    schedules = listOf(base.datasets.schedules.single().copy(daysOfWeek = listOf(1, 1)))
                )
            )
        )
    }
}
