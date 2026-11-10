package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSessionRetentionPolicyTest {
    private val day = 24L * 60L * 60L * 1_000L

    @Test
    fun keepsTheExactNinetyDayBoundaryAndNewerSessionsLocally() {
        val now = 200L * day
        val cutoff = now - 90L * day
        assertTrue(BackupSessionRetentionPolicy.shouldKeepLocally(cutoff, now))
        assertTrue(BackupSessionRetentionPolicy.shouldKeepLocally(cutoff + 1L, now))
        assertFalse(BackupSessionRetentionPolicy.shouldKeepLocally(cutoff - 1L, now))
    }

    @Test
    fun filtersCloudHistoryOnlyForLocalRestoreWithoutMutatingTheSourceList() {
        val now = 200L * day
        val old = BackupClosedSession(
            syncId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            accountId = "sample",
            startedAt = now - 91L * day,
            endedAt = now - 91L * day + 1_000L,
            likes = 1,
            follows = 0,
            comments = 0,
            videosWatched = 2,
            durationSecs = 10,
        )
        val recent = old.copy(
            syncId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            startedAt = now - 90L * day,
            endedAt = now - 90L * day + 1_000L,
        )
        val cloudHistory = listOf(old, recent)

        assertEquals(listOf(recent), BackupSessionRetentionPolicy.retainLocally(cloudHistory, now))
        assertEquals(2, cloudHistory.size)
    }

    @Test
    fun usesStartedAtForTheSameRetentionBoundaryAsRoomCleanup() {
        val now = 200L * day
        val justStartedBeforeCutoff = now - 90L * day - 1L
        assertFalse(BackupSessionRetentionPolicy.shouldKeepLocally(justStartedBeforeCutoff, now))
    }
}
