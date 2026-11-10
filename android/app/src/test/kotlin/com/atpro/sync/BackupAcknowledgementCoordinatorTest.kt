package com.atpro.sync

import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthUser
import com.atpro.data.EarningsDirtyState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAcknowledgementCoordinatorTest {
    private val owner = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    private val install = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun session(userId: String = owner) = AuthSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        tokenType = "bearer",
        expiresAtEpochSeconds = Long.MAX_VALUE,
        user = AuthUser(userId, null),
    )

    private fun build(
        events: List<BackupOutboxAckCandidate> = emptyList(),
        generation: Long = 7L,
        ownerUserId: String? = owner,
        localRevision: Long = 2L,
    ): BackupSnapshotBuildResult {
        val snap = BackupSnapshot(
            formatVersion = 3,
            createdAt = 100L,
            appVersion = "1.12.0",
            sourceInstallId = install,
            datasets = BackupSnapshotDatasets(
                accounts = listOf(BackupAccount("sample", "active", false, 1L, 1L, 1L)),
                closedSessions = emptyList(),
                safeConfigs = emptyList(),
                schedules = emptyList(),
                earnings = emptyList(),
                accountStatContributions = listOf(
                    BackupAccountStatContribution("sample", install, 1L, 0L, 0L, 0L, 1L)
                ),
                tombstones = emptyList(),
            )
        )
        return BackupSnapshotBuildResult(
            snapshot = snap,
            earningsGeneration = generation,
            earningsDirty = true,
            ownerUserId = ownerUserId,
            localExpectedRevision = localRevision,
            outboxEvents = events,
        )
    }

    private fun committed(
        build: BackupSnapshotBuildResult = build(),
        expectedRemoteRevision: Long = 4L,
        revision: Long = 5L,
    ) = BackupCasResult.Committed(
        buildResult = build,
        snapshot = build.snapshot,
        expectedRemoteRevision = expectedRemoteRevision,
        revision = revision,
        attempts = 1,
        checksum = "a".repeat(64),
    )

    private class FakeRestore(
        var fail: Boolean = false,
    ) : BackupAcknowledgementRestorePort {
        var called = 0
        var lastSnapshot: BackupSnapshot? = null
        override suspend fun restore(snapshot: BackupSnapshot, ownerUserId: String) {
            called++
            lastSnapshot = snapshot
            if (fail) error("restore interrupted")
        }
    }

    private class FakeRoom(
        var result: BackupRoomAcknowledgementResult? = null,
    ) : BackupAcknowledgementRoomPort {
        var called = 0
        var lastOwner: String? = null
        var lastPlan: BackupAcknowledgementPlan? = null
        override suspend fun acknowledge(
            ownerUserId: String,
            committed: BackupCasResult.Committed,
            plan: BackupAcknowledgementPlan,
        ): BackupRoomAcknowledgementResult {
            called++
            lastOwner = ownerUserId
            lastPlan = plan
            return result ?: BackupRoomAcknowledgementResult.Applied(plan.eventIds.size)
        }
    }

    private class FakeEarnings(
        var state: EarningsDirtyState = EarningsDirtyState(7L, 0L),
        var allowAck: Boolean = true,
    ) : BackupAcknowledgementEarningsPort {
        var acknowledgedGeneration: Long? = null
        override fun acknowledgeThrough(generation: Long): Boolean {
            acknowledgedGeneration = generation
            if (!allowAck) return false
            state = state.copy(acknowledgedGeneration = maxOf(state.acknowledgedGeneration, generation))
            return true
        }
        override fun dirtyState(): EarningsDirtyState = state
    }

    @Test
    fun acknowledgesOnlyCapturedOutboxIdsAndCapturedEarningsGeneration() = runBlocking {
        val event = BackupOutboxAckCandidate(eventId, 1L, "accounts", "sample", "UPSERT")
        val build = build(events = listOf(event), generation = 7L)
        val room = FakeRoom()
        val earnings = FakeEarnings()
        val restore = FakeRestore()
        val coordinator = BackupAcknowledgementCoordinator(restore, room, earnings)

        val result = coordinator.acknowledge(session(), committed(build))
        assertTrue(result is BackupAcknowledgementResult.Acknowledged)
        assertEquals(listOf(eventId), room.lastPlan?.eventIds)
        assertEquals(7L, earnings.acknowledgedGeneration)
        assertEquals(owner, room.lastOwner)
        assertEquals(1, restore.called)
        assertEquals(committed(build).snapshot, restore.lastSnapshot)
    }


    @Test
    fun failedLocalRestorePreventsCheckpointOutboxAndEarningsAcknowledgement() = runBlocking {
        val restore = FakeRestore(fail = true)
        val room = FakeRoom()
        val earnings = FakeEarnings()
        val result = BackupAcknowledgementCoordinator(restore, room, earnings)
            .acknowledge(session(), committed())

        assertTrue(result is BackupAcknowledgementResult.RetryRequired)
        assertEquals(1, restore.called)
        assertEquals(0, room.called)
        assertEquals(null, earnings.acknowledgedGeneration)
    }

    @Test
    fun roomConflictPreventsEarningsAcknowledgement() = runBlocking {
        val room = FakeRoom(BackupRoomAcknowledgementResult.Conflict("stale_checkpoint"))
        val earnings = FakeEarnings()
        val result = BackupAcknowledgementCoordinator(FakeRestore(), room, earnings)
            .acknowledge(session(), committed())

        assertTrue(result is BackupAcknowledgementResult.RetryRequired)
        assertEquals(null, earnings.acknowledgedGeneration)
    }

    @Test
    fun failedEarningsAckLeavesGenerationPendingAfterRoomCommit() = runBlocking {
        val earnings = FakeEarnings(allowAck = false)
        val result = BackupAcknowledgementCoordinator(FakeRestore(), FakeRoom(), earnings)
            .acknowledge(session(), committed())

        assertTrue(result is BackupAcknowledgementResult.RetryRequired)
        assertEquals(7L, earnings.state.generation)
        assertEquals(0L, earnings.state.acknowledgedGeneration)
    }

    @Test
    fun ownerMismatchIsRejectedBeforeRoomOrEarningsMutation() = runBlocking {
        val room = FakeRoom()
        val earnings = FakeEarnings()
        val result = BackupAcknowledgementCoordinator(FakeRestore(), room, earnings)
            .acknowledge(session("ffffffff-ffff-4fff-8fff-ffffffffffff"), committed())

        assertTrue(result is BackupAcknowledgementResult.Rejected)
        assertEquals(0, room.called)
        assertEquals(null, earnings.acknowledgedGeneration)
    }

    @Test
    fun remoteRevisionMayAdvanceBeyondLocalCheckpointButCannotGoBackwards() = runBlocking {
        val build = build(localRevision = 2L)
        val valid = BackupAcknowledgementCoordinator(FakeRestore(), FakeRoom(), FakeEarnings())
            .acknowledge(session(), committed(build, expectedRemoteRevision = 4L, revision = 5L))
        assertTrue(valid is BackupAcknowledgementResult.Acknowledged)

        val staleBuild = build(localRevision = 5L)
        val stale = BackupAcknowledgementCoordinator(FakeRestore(), FakeRoom(), FakeEarnings())
            .acknowledge(session(), committed(staleBuild, expectedRemoteRevision = 4L, revision = 5L))
        assertTrue(stale is BackupAcknowledgementResult.Rejected)
    }

    @Test
    fun unrepresentedHeadReturnsPartialWithoutSkippingLaterEvents() = runBlocking {
        val blockedId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val laterId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val events = listOf(
            BackupOutboxAckCandidate(blockedId, 1L, "closedSessions", "not-in-snapshot", "UPSERT"),
            BackupOutboxAckCandidate(laterId, 2L, "accounts", "sample", "UPSERT"),
        )
        val room = FakeRoom(BackupRoomAcknowledgementResult.Applied(0))
        val result = BackupAcknowledgementCoordinator(FakeRestore(), room, FakeEarnings())
            .acknowledge(session(), committed(build(events = events)))

        assertTrue(result is BackupAcknowledgementResult.Partial)
        assertTrue(room.lastPlan?.eventIds.isNullOrEmpty())
        assertEquals(blockedId, (result as BackupAcknowledgementResult.Partial).blockedEventId)
    }
}
