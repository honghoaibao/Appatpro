package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeEngineTest {
    private val installA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val installB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val accountKey = "sample_account"
    private val sessionId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val earningId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val tombstoneId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

    private fun contribution(
        installationId: String,
        sessions: Long = 1L,
        likes: Long = 10L,
        updatedAt: Long = 10L,
    ) = BackupAccountStatContribution(
        accountId = accountKey,
        installationId = installationId,
        sessionsCount = sessions,
        totalLikes = likes,
        totalFollows = 0L,
        totalVideos = 1L,
        updatedAt = updatedAt,
    )

    private fun account(
        status: String = "active",
        checkpoint: Boolean = false,
        createdAt: Long = 1L,
        statusUpdatedAt: Long = 10L,
    ) = BackupAccount(
        username = accountKey,
        status = status,
        checkpoint = checkpoint,
        createdAt = createdAt,
        statusUpdatedAt = statusUpdatedAt,
        statsUpdatedAt = 10L,
    )

    private fun snapshot(
        source: String,
        accounts: List<BackupAccount> = listOf(account()),
        contributions: List<BackupAccountStatContribution> =
            if (accounts.isEmpty()) emptyList() else listOf(contribution(source)),
        sessions: List<BackupClosedSession> = emptyList(),
        configs: List<BackupSafeConfig> = emptyList(),
        schedules: List<BackupSchedule> = emptyList(),
        earnings: List<BackupEarning> = emptyList(),
        tombstones: List<BackupTombstone> = emptyList(),
        createdAt: Long = 100L,
    ) = BackupSnapshot(
        formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
        createdAt = createdAt,
        appVersion = "1.12.0",
        sourceInstallId = source,
        datasets = BackupSnapshotDatasets(
            accounts = accounts,
            closedSessions = sessions,
            safeConfigs = configs,
            schedules = schedules,
            earnings = earnings,
            accountStatContributions = contributions,
            tombstones = tombstones,
        ),
    )

    private fun statusConflict(result: BackupMergeResult): BackupMergeConflict =
        (result as BackupMergeResult.Conflicted).conflicts.single()

    @Test
    fun mergesIndependentDataAndProducesAValidDeterministicV3Snapshot() {
        val local = snapshot(
            source = installA,
            sessions = listOf(
                BackupClosedSession(sessionId, accountKey, 1L, 2L, 1, 0, 0, 1, 1)
            ),
            configs = listOf(BackupSafeConfig("like_rate", "0.2", 10L)),
            schedules = listOf(
                BackupSchedule("night", "Night", 21, 0, listOf(1, 3), true,
                    listOf(accountKey), "NORMAL", 10L)
            ),
            earnings = listOf(BackupEarning(earningId, 10L, "reward", 1.5, accountKey)),
        )
        val remote = snapshot(
            source = installB,
            accounts = listOf(account().copy(statusUpdatedAt = 20L)),
            contributions = listOf(contribution(installB, sessions = 3L, likes = 40L)),
            configs = listOf(BackupSafeConfig("like_rate", "0.2", 20L)),
            schedules = listOf(
                BackupSchedule("night", "Night", 21, 0, listOf(1, 3), false,
                    listOf(accountKey), "NORMAL", 20L)
            ),
            earnings = listOf(BackupEarning(
                "ffffffff-ffff-4fff-8fff-ffffffffffff", 20L, "reward", 2.0, accountKey
            )),
            createdAt = 200L,
        )

        val first = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged
        val second = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged

        assertEquals(first.snapshot, second.snapshot)
        assertEquals(BackupSnapshot.CURRENT_FORMAT_VERSION, first.snapshot.formatVersion)
        assertEquals(installA, first.snapshot.sourceInstallId)
        assertEquals(200L, first.snapshot.createdAt)
        assertEquals(2, first.snapshot.datasets.accountStatContributions.size)
        assertEquals(4L, AccountStatContributionPolicy.aggregate(
            first.snapshot.datasets.accountStatContributions
        ).sessionsCount)
        assertFalse(first.snapshot.datasets.schedules.single().enabledAtSource)
        BackupSnapshotValidator.validate(first.snapshot)
    }

    @Test
    fun accountStatusConflictIgnoresDeviceTimestampOrdering() {
        val local = snapshot(installA, accounts = listOf(account("active", false, statusUpdatedAt = 10L)))
        val remote = snapshot(
            installB,
            accounts = listOf(account("banned", false, statusUpdatedAt = Long.MAX_VALUE)),
            contributions = listOf(contribution(installB)),
        )

        val result = BackupMergeEngine.merge(local, remote)
        assertEquals(BackupMergeConflictKind.ACCOUNT_STATUS, statusConflict(result).kind)
    }

    @Test
    fun accountCreationTimeDisagreementIsExplicitConflict() {
        val local = snapshot(installA, accounts = listOf(account(createdAt = 1L)))
        val remote = snapshot(
            installB,
            accounts = listOf(account(createdAt = 2L)),
            contributions = listOf(contribution(installB)),
        )
        assertEquals(
            BackupMergeConflictKind.ACCOUNT_CREATION_TIME,
            statusConflict(BackupMergeEngine.merge(local, remote)).kind,
        )
    }

    @Test
    fun sameInstallationCountersMergeByComponentMaximum() {
        val local = snapshot(installA, contributions = listOf(contribution(installA, 10L, 100L, 10L)))
        val remote = snapshot(
            installB,
            accounts = listOf(account().copy(statsUpdatedAt = 20L)),
            contributions = listOf(
                contribution(installA, 12L, 90L, 20L),
                contribution(installB, updatedAt = 10L),
            ),
        )
        val result = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged
        val merged = result.snapshot.datasets.accountStatContributions.single {
            it.installationId == installA
        }
        assertEquals(12L, merged.sessionsCount)
        assertEquals(100L, merged.totalLikes)
        assertEquals(20L, merged.updatedAt)
    }

    @Test
    fun differingConfigValuesConflictEvenWhenOneTimestampLooksNewer() {
        val local = snapshot(installA, configs = listOf(BackupSafeConfig("like_rate", "0.2", 10L)))
        val remote = snapshot(
            installB, configs = listOf(BackupSafeConfig("like_rate", "0.9", Long.MAX_VALUE))
        )
        assertEquals(
            BackupMergeConflictKind.CONFIG_VALUE,
            statusConflict(BackupMergeEngine.merge(local, remote)).kind,
        )
    }

    @Test
    fun identicalScheduleDefinitionsMergeAndAreAlwaysDisabled() {
        val local = snapshot(
            installA,
            schedules = listOf(BackupSchedule("night", "Night", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 10L))
        )
        val remote = snapshot(
            installB,
            schedules = listOf(BackupSchedule("night", "Night", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 20L))
        )
        val result = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged
        assertFalse(result.snapshot.datasets.schedules.single().enabledAtSource)
        assertEquals(20L, result.snapshot.datasets.schedules.single().updatedAt)
    }

    @Test
    fun differentScheduleDefinitionsConflict() {
        val local = snapshot(
            installA,
            schedules = listOf(BackupSchedule("night", "Night", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 10L))
        )
        val remote = snapshot(
            installB,
            schedules = listOf(BackupSchedule("night", "Changed", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 20L))
        )
        assertEquals(
            BackupMergeConflictKind.SCHEDULE_DEFINITION,
            statusConflict(BackupMergeEngine.merge(local, remote)).kind,
        )
    }

    @Test
    fun sessionIdentityWithDifferentContentsConflicts() {
        val localSession = BackupClosedSession(sessionId, accountKey, 1L, 2L, 1, 0, 0, 1, 1)
        val remoteSession = localSession.copy(likes = 2)
        val local = snapshot(installA, sessions = listOf(localSession))
        val remote = snapshot(installB, sessions = listOf(remoteSession))
        assertEquals(
            BackupMergeConflictKind.SESSION_CONTENT,
            statusConflict(BackupMergeEngine.merge(local, remote)).kind,
        )
    }

    @Test
    fun identicalSessionsAndEarningsAreIdempotent() {
        val session = BackupClosedSession(sessionId, accountKey, 1L, 2L, 1, 0, 0, 1, 1)
        val earning = BackupEarning(earningId, 10L, "reward", 1.5, accountKey)
        val result = BackupMergeEngine.merge(
            snapshot(installA, sessions = listOf(session), earnings = listOf(earning)),
            snapshot(installB, sessions = listOf(session), earnings = listOf(earning)),
        ) as BackupMergeResult.Merged

        assertEquals(1, result.snapshot.datasets.closedSessions.size)
        assertEquals(1, result.snapshot.datasets.earnings.size)
    }

    @Test
    fun earningIdWithDifferentContentsConflicts() {
        val first = BackupEarning(earningId, 10L, "reward", 1.5, accountKey)
        val second = first.copy(reward = 9.5)
        assertEquals(
            BackupMergeConflictKind.EARNING_CONTENT,
            statusConflict(
                BackupMergeEngine.merge(
                    snapshot(installA, earnings = listOf(first)),
                    snapshot(installB, earnings = listOf(second)),
                )
            ).kind,
        )
    }

    @Test
    fun tombstoneFromOtherInstallationSuppressesStaleAccountConfigAndSchedule() {
        val deleted = BackupTombstone(
            tombstoneId, BackupTombstoneDataset.ACCOUNTS, accountKey, installB, 30L
        )
        val configTombstone = BackupTombstone(
            "ffffffff-ffff-4fff-8fff-ffffffffffff",
            BackupTombstoneDataset.SAFE_CONFIGS, "like_rate", installB, 30L
        )
        val scheduleTombstone = BackupTombstone(
            "99999999-9999-4999-8999-999999999999",
            BackupTombstoneDataset.SCHEDULES, "night", installB, 30L
        )
        val local = snapshot(
            installA,
            configs = listOf(BackupSafeConfig("like_rate", "0.2", 10L)),
            schedules = listOf(BackupSchedule("night", "Night", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 10L)),
        )
        val remote = snapshot(
            installB,
            accounts = emptyList(),
            contributions = emptyList(),
            tombstones = listOf(deleted, configTombstone, scheduleTombstone),
        )
        val merged = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged
        assertTrue(merged.snapshot.datasets.accounts.isEmpty())
        assertTrue(merged.snapshot.datasets.safeConfigs.isEmpty())
        assertTrue(merged.snapshot.datasets.schedules.isEmpty())
        assertEquals(3, merged.snapshot.datasets.tombstones.size)
        assertTrue(merged.snapshot.datasets.accountStatContributions.isEmpty())
    }

    @Test
    fun sameInstallationEntityAndTombstoneRequiresExplicitResolution() {
        val tombstone = BackupTombstone(
            tombstoneId, BackupTombstoneDataset.ACCOUNTS, accountKey, installA, 30L
        )
        val result = BackupMergeEngine.merge(
            snapshot(installA, tombstones = listOf(tombstone)),
            snapshot(installB),
        )
        assertEquals(
            BackupMergeConflictKind.DELETE_RECREATE_SAME_INSTALLATION,
            statusConflict(result).kind,
        )
    }

    @Test
    fun reusingTombstoneIdForDifferentDeleteIsAConflict() {
        val first = BackupTombstone(
            tombstoneId, BackupTombstoneDataset.ACCOUNTS, "one", installA, 10L
        )
        val second = first.copy(entityKey = "two")
        val result = BackupMergeEngine.merge(
            snapshot(installA, accounts = emptyList(), contributions = emptyList(), tombstones = listOf(first)),
            snapshot(installB, accounts = emptyList(), contributions = emptyList(), tombstones = listOf(second)),
        )
        assertEquals(BackupMergeConflictKind.TOMBSTONE_ID_REUSE, statusConflict(result).kind)
    }

    @Test
    fun remoteOnlyAccountGetsLocalZeroBaselineAndValidStatsWatermark() {
        val remoteAccount = account()
        val remote = snapshot(
            installB,
            accounts = listOf(remoteAccount.copy(statsUpdatedAt = 40L)),
            contributions = listOf(contribution(installB, 4L, 44L, 40L)),
        )
        val local = snapshot(installA, accounts = emptyList(), contributions = emptyList())
        val merged = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged

        val rows = merged.snapshot.datasets.accountStatContributions
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.installationId == installA && it.sessionsCount == 0L })
        assertEquals(
            rows.maxOf { it.updatedAt },
            merged.snapshot.datasets.accounts.single().statsUpdatedAt,
        )
        BackupSnapshotValidator.validate(merged.snapshot)
    }

    @Test
    fun earningsAreTrimmedToTheExistingRetentionBound() {
        val entries = (0 until BackupMergeEngine.MAX_EARNINGS).map { index ->
            BackupEarning(
                id = java.util.UUID.nameUUIDFromBytes("earning-$index".toByteArray()).toString(),
                atMs = index.toLong(),
                kind = "reward",
                reward = 1.0,
                account = accountKey,
            )
        }
        val local = snapshot(installA, earnings = entries)
        val remote = snapshot(installB, earnings = listOf(
            BackupEarning("99999999-9999-4999-8999-999999999999", 99_999L, "reward", 1.0, accountKey)
        ))
        val merged = BackupMergeEngine.merge(local, remote) as BackupMergeResult.Merged
        assertEquals(BackupMergeEngine.MAX_EARNINGS, merged.snapshot.datasets.earnings.size)
        assertEquals(99_999L, merged.snapshot.datasets.earnings.last().atMs)
    }

    @Test
    fun conflictsAreSortedDeterministically() {
        val local = snapshot(
            installA,
            configs = listOf(BackupSafeConfig("like_rate", "0.2", 10L)),
            schedules = listOf(BackupSchedule("night", "A", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 10L)),
        )
        val remote = snapshot(
            installB,
            configs = listOf(BackupSafeConfig("like_rate", "0.3", 10L)),
            schedules = listOf(BackupSchedule("night", "B", 21, 0, listOf(1), true,
                listOf(accountKey), "NORMAL", 10L)),
        )
        val conflicts = (BackupMergeEngine.merge(local, remote) as BackupMergeResult.Conflicted).conflicts
        assertEquals(
            conflicts.sortedWith(compareBy({ it.dataset }, { it.entityKey }, { it.kind.name })),
            conflicts,
        )
    }
}
