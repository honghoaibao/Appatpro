package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStatContributionPolicyTest {
    private val installA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val installB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun contribution(
        installationId: String,
        sessions: Long,
        likes: Long,
        follows: Long = 0L,
        videos: Long = 0L,
        updatedAt: Long = 1L,
    ) = BackupAccountStatContribution(
        accountId = "sample_account",
        installationId = installationId,
        sessionsCount = sessions,
        totalLikes = likes,
        totalFollows = follows,
        totalVideos = videos,
        updatedAt = updatedAt,
    )

    @Test
    fun newerStatusWinsWithoutLookingAtStatsTimestamp() {
        val result = AccountStatContributionPolicy.mergeStatus(
            AccountStatusValue("banned", false, 20L),
            AccountStatusValue("active", false, 10L),
        )
        assertEquals(
            AccountStatusMergeResult.Resolved(AccountStatusValue("banned", false, 20L)),
            result,
        )
    }

    @Test
    fun equalTimestampWithDifferentStatusIsUnresolved() {
        val result = AccountStatContributionPolicy.mergeStatus(
            AccountStatusValue("banned", false, 20L),
            AccountStatusValue("active", false, 20L),
        )
        assertTrue(result is AccountStatusMergeResult.Conflict)
    }

    @Test
    fun sameInstallationUsesComponentWiseMaximumInsteadOfAddingSnapshots() {
        val merged = AccountStatContributionPolicy.mergeContributions(
            local = listOf(contribution(installA, sessions = 10, likes = 100, videos = 200)),
            remote = listOf(contribution(installA, sessions = 12, likes = 90, videos = 250, updatedAt = 2)),
        )
        assertEquals(1, merged.size)
        assertEquals(12L, merged.single().sessionsCount)
        assertEquals(100L, merged.single().totalLikes)
        assertEquals(250L, merged.single().totalVideos)
        assertEquals(2L, merged.single().updatedAt)
    }

    @Test
    fun distinctInstallationsRemainDistinctAndAggregateOnce() {
        val merged = AccountStatContributionPolicy.mergeContributions(
            local = listOf(contribution(installA, sessions = 10, likes = 100, videos = 200)),
            remote = listOf(contribution(installB, sessions = 5, likes = 30, videos = 50)),
        )
        val totals = AccountStatContributionPolicy.aggregate(merged)
        assertEquals(2, merged.size)
        assertEquals(15L, totals.sessionsCount)
        assertEquals(130L, totals.totalLikes)
        assertEquals(250L, totals.totalVideos)
    }

    @Test
    fun aggregateSaturatesInsteadOfOverflowing() {
        val totals = AccountStatContributionPolicy.aggregate(
            listOf(
                contribution(installA, sessions = Long.MAX_VALUE, likes = Long.MAX_VALUE),
                contribution(installB, sessions = 1L, likes = 1L),
            )
        )
        assertEquals(Long.MAX_VALUE, totals.sessionsCount)
        assertEquals(Long.MAX_VALUE, totals.totalLikes)
        assertEquals(Int.MAX_VALUE, AccountStatContributionPolicy.toDisplayInt(totals.totalLikes))
    }
}
