package com.atpro.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLocalStatePolicyTest {
    private val installId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private fun build(
        datasets: BackupSnapshotDatasets = emptyDatasets(),
        outboxEvents: List<BackupOutboxAckCandidate> = emptyList(),
        earningsDirty: Boolean = false,
    ) = BackupSnapshotBuildResult(
        snapshot = BackupSnapshot(
            formatVersion = BackupSnapshot.CURRENT_FORMAT_VERSION,
            createdAt = 1L,
            appVersion = "1.12.6",
            sourceInstallId = installId,
            datasets = datasets,
        ),
        earningsGeneration = if (earningsDirty) 1L else 0L,
        earningsDirty = earningsDirty,
        ownerUserId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        localExpectedRevision = 0L,
        outboxEvents = outboxEvents,
    )

    private fun emptyDatasets() = BackupSnapshotDatasets(
        accounts = emptyList(),
        closedSessions = emptyList(),
        safeConfigs = emptyList(),
        schedules = emptyList(),
        earnings = emptyList(),
        accountStatContributions = emptyList(),
        tombstones = emptyList(),
    )

    @Test
    fun cleanFreshInstallIsTrulyEmpty() {
        assertTrue(BackupLocalStatePolicy.isTrulyEmpty(build()))
    }

    @Test
    fun explicitTombstoneIsPortableDeleteIntent() {
        val deleted = BackupTombstone(
            tombstoneId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            dataset = BackupTombstoneDataset.ACCOUNTS,
            entityKey = "sample_account",
            sourceInstallId = installId,
            deletedAt = 10L,
        )
        val datasets = emptyDatasets().copy(tombstones = listOf(deleted))

        assertTrue(BackupLocalStatePolicy.hasPortableData(build(datasets)))
    }

    @Test
    fun pendingOutboxEventPreventsEmptyClassification() {
        val event = BackupOutboxAckCandidate(
            eventId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            sequence = 1L,
            dataset = BackupTombstoneDataset.ACCOUNTS,
            entityKey = "sample_account",
            operation = "DELETE",
        )

        assertTrue(BackupLocalStatePolicy.hasPortableData(build(outboxEvents = listOf(event))))
    }

    @Test
    fun dirtyEarningsPreventEmptyClassification() {
        assertTrue(BackupLocalStatePolicy.hasPortableData(build(earningsDirty = true)))
    }

    @Test
    fun businessRowsPreventEmptyClassification() {
        val datasets = emptyDatasets().copy(
            safeConfigs = listOf(BackupSafeConfig("like_rate", "0.5", 1L))
        )

        assertFalse(BackupLocalStatePolicy.isTrulyEmpty(build(datasets)))
    }
}
