package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAcknowledgementPolicyTest {
    private val install = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val accountId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val configId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val tombstoneId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    private fun snapshot(
        accounts: List<BackupAccount> = emptyList(),
        safeConfigs: List<BackupSafeConfig> = emptyList(),
        tombstones: List<BackupTombstone> = emptyList(),
        closedSessions: List<BackupClosedSession> = emptyList(),
    ) = BackupSnapshot(
        formatVersion = 3,
        createdAt = 100L,
        appVersion = "1.12.0",
        sourceInstallId = install,
        datasets = BackupSnapshotDatasets(
            accounts = accounts,
            closedSessions = closedSessions,
            safeConfigs = safeConfigs,
            schedules = emptyList(),
            earnings = emptyList(),
            accountStatContributions = emptyList(),
            tombstones = tombstones,
        )
    )

    private fun event(
        id: String,
        sequence: Long,
        dataset: String,
        entityKey: String,
        operation: String = "UPSERT",
    ) = BackupOutboxAckCandidate(id, sequence, dataset, entityKey, operation)

    private fun build(
        snap: BackupSnapshot,
        events: List<BackupOutboxAckCandidate>,
    ) = BackupSnapshotBuildResult(
        snapshot = snap,
        earningsGeneration = 5L,
        earningsDirty = true,
        ownerUserId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        localExpectedRevision = 2L,
        outboxEvents = events,
    )

    @Test
    fun acknowledgesOnlyLiveEntitiesRepresentedByTheSnapshot() {
        val snap = snapshot(
            accounts = listOf(BackupAccount("sample", "active", false, 1L, 1L, 1L)),
            safeConfigs = listOf(BackupSafeConfig("like_rate", "0.3", 10L)),
        )
        val events = listOf(
            event(accountId, 1L, "accounts", "sample"),
            event(configId, 2L, "safeConfigs", "like_rate"),
        )

        val result = BackupAcknowledgementPolicy.plan(build(snap, events), snap)
            as BackupAcknowledgementPlanResult.Ready
        assertEquals(listOf(accountId, configId), result.plan.eventIds)
        assertEquals(null, result.plan.blockedEventId)
        assertTrue(result.plan.requiredTombstones.isEmpty())
    }

    @Test
    fun deleteAcknowledgementRequiresExactDurableTombstoneAndKeepsIt() {
        val tombstone = BackupTombstone(
            tombstoneId = tombstoneId,
            dataset = "accounts",
            entityKey = "sample",
            sourceInstallId = install,
            deletedAt = 50L,
        )
        val snap = snapshot(tombstones = listOf(tombstone))
        val events = listOf(
            event(tombstoneId, 1L, "accounts", "sample", "DELETE")
        )

        val result = BackupAcknowledgementPolicy.plan(build(snap, events), snap)
            as BackupAcknowledgementPlanResult.Ready
        assertEquals(listOf(tombstoneId), result.plan.eventIds)
        assertEquals(listOf(tombstone), result.plan.requiredTombstones)
        // Policy captures the marker; it never schedules tombstone deletion.
    }

    @Test
    fun olderUpsertCanBeAcknowledgedWhenSnapshotCarriesItsLaterDeleteMarker() {
        val upsertId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val tombstone = BackupTombstone(
            tombstoneId = tombstoneId,
            dataset = "accounts",
            entityKey = "sample",
            sourceInstallId = install,
            deletedAt = 50L,
        )
        val events = listOf(
            event(upsertId, 1L, "accounts", "sample", "UPSERT"),
            event(tombstoneId, 2L, "accounts", "sample", "DELETE"),
        )

        val result = BackupAcknowledgementPolicy.plan(
            build(snapshot(tombstones = listOf(tombstone)), events),
            snapshot(tombstones = listOf(tombstone)),
        ) as BackupAcknowledgementPlanResult.Ready
        assertEquals(listOf(upsertId, tombstoneId), result.plan.eventIds)
        assertEquals(listOf(tombstone), result.plan.requiredTombstones)
    }

    @Test
    fun stopsAtFirstUnrepresentedEventWithoutSkippingLaterRows() {
        val events = listOf(
            event(accountId, 1L, "closedSessions", "missing-session"),
            event(configId, 2L, "safeConfigs", "like_rate"),
        )
        val candidate = snapshot(safeConfigs = listOf(BackupSafeConfig("like_rate", "0.3", 1L)))
        val result = BackupAcknowledgementPolicy.plan(
            build(candidate, events),
            candidate,
        ) as BackupAcknowledgementPlanResult.Ready

        assertTrue(result.plan.eventIds.isEmpty())
        assertEquals(accountId, result.plan.blockedEventId)
    }

    @Test
    fun deleteWithoutItsOwnTombstoneIsNotAcknowledged() {
        val result = BackupAcknowledgementPolicy.plan(
            build(snapshot(), listOf(event(tombstoneId, 1L, "accounts", "sample", "DELETE"))),
            snapshot(),
        ) as BackupAcknowledgementPlanResult.Ready
        assertTrue(result.plan.eventIds.isEmpty())
        assertEquals(tombstoneId, result.plan.blockedEventId)
    }


    @Test
    fun deleteMatchesItsExactMarkerWhenSeveralTombstonesShareAnEntityKey() {
        val older = BackupTombstone(
            tombstoneId = "11111111-1111-4111-8111-111111111111",
            dataset = "accounts",
            entityKey = "sample",
            sourceInstallId = install,
            deletedAt = 40L,
        )
        val current = BackupTombstone(
            tombstoneId = tombstoneId,
            dataset = "accounts",
            entityKey = "sample",
            sourceInstallId = install,
            deletedAt = 50L,
        )
        val result = BackupAcknowledgementPolicy.plan(
            build(
                snapshot(tombstones = listOf(older, current)),
                listOf(event(tombstoneId, 1L, "accounts", "sample", "DELETE")),
            ),
            snapshot(tombstones = listOf(older, current)),
        ) as BackupAcknowledgementPlanResult.Ready

        assertEquals(listOf(tombstoneId), result.plan.eventIds)
        assertEquals(listOf(current), result.plan.requiredTombstones)
    }


    @Test
    fun usesCommittedMergedSnapshotForCoverageOfRemoteTombstones() {
        val local = snapshot(
            accounts = listOf(BackupAccount("sample", "active", false, 1L, 1L, 1L))
        )
        val remoteTombstone = BackupTombstone(
            tombstoneId = "11111111-1111-4111-8111-111111111111",
            dataset = "accounts",
            entityKey = "sample",
            sourceInstallId = "99999999-9999-4999-8999-999999999999",
            deletedAt = 60L,
        )
        val merged = snapshot(tombstones = listOf(remoteTombstone))
        val event = event(accountId, 1L, "accounts", "sample", "UPSERT")

        val result = BackupAcknowledgementPolicy.plan(build(local, listOf(event)), merged)
            as BackupAcknowledgementPlanResult.Ready
        assertEquals(listOf(accountId), result.plan.eventIds)
        assertEquals(listOf(remoteTombstone), result.plan.requiredTombstones)
    }

    @Test
    fun duplicateOrOutOfOrderCapturedEventsAreRejected() {
        val duplicate = listOf(
            event(accountId, 1L, "accounts", "sample"),
            event(accountId, 2L, "accounts", "sample"),
        )
        assertTrue(
            BackupAcknowledgementPolicy.plan(build(snapshot(), duplicate), snapshot())
                is BackupAcknowledgementPlanResult.Rejected
        )
        val outOfOrder = listOf(
            event(accountId, 2L, "accounts", "sample"),
            event(configId, 1L, "safeConfigs", "like_rate"),
        )
        assertTrue(
            BackupAcknowledgementPolicy.plan(build(snapshot(), outOfOrder), snapshot())
                is BackupAcknowledgementPlanResult.Rejected
        )
    }
}
