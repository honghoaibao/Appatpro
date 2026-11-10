package com.atpro.sync

import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthUser
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCasCoordinatorTest {
    private val installA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val installB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val owner = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val accountKey = "sample_account"

    private fun authSession(userId: String = owner) = AuthSession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        tokenType = "bearer",
        expiresAtEpochSeconds = Long.MAX_VALUE,
        user = AuthUser(userId, null),
    )

    private fun snapshot(
        source: String = installA,
        status: String = "active",
        createdAt: Long = 10L,
    ) = BackupSnapshot(
        formatVersion = 3,
        createdAt = createdAt,
        appVersion = "1.12.0",
        sourceInstallId = source,
        datasets = BackupSnapshotDatasets(
            accounts = listOf(BackupAccount(accountKey, status, false, 1L, 1L, 1L)),
            closedSessions = emptyList(),
            safeConfigs = emptyList(),
            schedules = emptyList(),
            earnings = emptyList(),
            accountStatContributions = listOf(
                BackupAccountStatContribution(accountKey, source, 1L, 1L, 0L, 0L, 1L)
            ),
            tombstones = emptyList(),
        )
    )

    private class FakeCodec : BackupSnapshotCodecPort {
        val snapshots = mutableMapOf<String, BackupSnapshot>()
        var nextId = 0
        override fun encode(snapshot: BackupSnapshot): EncodedBackupPayload {
            val payload = Base64.getEncoder().encodeToString(("payload-" + ++nextId).toByteArray())
            snapshots[payload] = snapshot
            return EncodedBackupPayload(3, payload, nextId.toString().padStart(64, '0'), 10, payload.length)
        }
        override fun decode(payload: String, checksum: String, schemaVersion: Int): BackupSnapshot {
            require(schemaVersion == 2 || schemaVersion == 3)
            return snapshots[payload] ?: error("unknown fake payload")
        }
    }

    private class FakeRemote : BackupRemoteStore {
        val reads = ArrayDeque<BackupRemoteReadResult>()
        val commits = ArrayDeque<BackupRemoteCommitResult>()
        val expectedRevisions = mutableListOf<Long>()
        var readCount = 0
        override suspend fun read(ownerUserId: String, accessToken: String): BackupRemoteReadResult {
            readCount++
            return reads.removeFirst()
        }
        override suspend fun commit(
            expectedRevision: Long,
            encoded: EncodedBackupPayload,
            accessToken: String,
        ): BackupRemoteCommitResult {
            expectedRevisions += expectedRevision
            return commits.removeFirst()
        }
    }

    @Test
    fun missingRemoteRowCreatesRevisionOneFromLocalV3() = runBlocking {
        val remote = FakeRemote().apply {
            reads += BackupRemoteReadResult.Missing
            commits += BackupRemoteCommitResult.Committed(1L)
        }
        val result = BackupCasCoordinator(remote, BackupSnapshotProvider { snapshot() }, BackupOwnerBindingValidator { true }, FakeCodec())
            .run(authSession())
        assertTrue(result is BackupCasResult.Committed)
        assertEquals(1L, (result as BackupCasResult.Committed).revision)
        assertEquals(listOf(0L), remote.expectedRevisions)
    }

    @Test
    fun revisionConflictRereadsRemoteRebuildsLocalAndRemergesBeforeRetry() = runBlocking {
        val codec = FakeCodec()
        val remoteSnapshot = snapshot(source = installB, createdAt = 20L)
        val remotePayload = codec.encode(remoteSnapshot)
        val latestRemoteSnapshot = remoteSnapshot.copy(
            createdAt = 30L,
            datasets = remoteSnapshot.datasets.copy(
                safeConfigs = listOf(BackupSafeConfig("like_rate", "0.2", 30L))
            )
        )
        val latestRemotePayload = codec.encode(latestRemoteSnapshot)
        val remote = FakeRemote().apply {
            reads += BackupRemoteReadResult.Found(
                RemoteBackupRecord(2L, 3, remotePayload.payload, remotePayload.checksum, null)
            )
            reads += BackupRemoteReadResult.Found(
                RemoteBackupRecord(3L, 3, latestRemotePayload.payload, latestRemotePayload.checksum, null)
            )
            commits += BackupRemoteCommitResult.RevisionConflict
            commits += BackupRemoteCommitResult.Committed(4L)
        }
        var localBuilds = 0
        val result = BackupCasCoordinator(
            remote,
            BackupSnapshotProvider { localBuilds++; snapshot(createdAt = 10L + localBuilds) },
            BackupOwnerBindingValidator { it == owner },
            codec,
        ).run(authSession())
        assertTrue(result is BackupCasResult.Committed)
        assertEquals(2, localBuilds)
        assertEquals(2, remote.readCount)
        assertEquals(listOf(2L, 3L), remote.expectedRevisions)
        val committed = result as BackupCasResult.Committed
        assertEquals("0.2", committed.snapshot.datasets.safeConfigs.single().value)
    }

    @Test
    fun threeRevisionConflictsStopWithoutAcknowledgingAnything() = runBlocking {
        val remote = FakeRemote().apply {
            repeat(3) {
                reads += BackupRemoteReadResult.Missing
                commits += BackupRemoteCommitResult.RevisionConflict
            }
        }
        val result = BackupCasCoordinator(remote, BackupSnapshotProvider { snapshot() }, BackupOwnerBindingValidator { true }, FakeCodec())
            .run(authSession())
        assertEquals(BackupCasResult.RetryLater(3), result)
        assertEquals(3, remote.readCount)
        assertEquals(listOf(0L, 0L, 0L), remote.expectedRevisions)
    }

    @Test
    fun mergeConflictPreventsAnyRemoteWrite() = runBlocking {
        val codec = FakeCodec()
        val remotePayload = codec.encode(snapshot(source = installB, status = "banned"))
        val remote = FakeRemote().apply {
            reads += BackupRemoteReadResult.Found(
                RemoteBackupRecord(1L, 3, remotePayload.payload, remotePayload.checksum, null)
            )
        }
        val result = BackupCasCoordinator(remote, BackupSnapshotProvider { snapshot() }, BackupOwnerBindingValidator { true }, codec)
            .run(authSession())
        assertTrue(result is BackupCasResult.Conflicted)
        assertTrue(remote.expectedRevisions.isEmpty())
    }

    @Test
    fun transientReadFailureDoesNotBlindlyRetryOrWrite() = runBlocking {
        val remote = FakeRemote().apply {
            reads += BackupRemoteReadResult.Failed(
                BackupRemoteFailure("server_error", retryable = true, httpStatus = 503)
            )
        }
        val result = BackupCasCoordinator(remote, BackupSnapshotProvider { snapshot() }, BackupOwnerBindingValidator { true }, FakeCodec())
            .run(authSession())
        assertTrue(result is BackupCasResult.Failed)
        assertEquals(1, remote.readCount)
        assertTrue(remote.expectedRevisions.isEmpty())
    }


    @Test
    fun differentLocalOwnerIsRejectedBeforeAnyRemoteRead() = runBlocking {
        val remote = FakeRemote()
        val result = BackupCasCoordinator(
            remote,
            BackupSnapshotProvider { snapshot() },
            BackupOwnerBindingValidator { false },
            FakeCodec(),
        ).run(authSession())
        assertTrue(result is BackupCasResult.Rejected)
        assertEquals(0, remote.readCount)
    }

    @Test
    fun invalidAuthContextIsRejectedBeforeRemoteCall() = runBlocking {
        val remote = FakeRemote()
        val result = BackupCasCoordinator(remote, BackupSnapshotProvider { snapshot() }, BackupOwnerBindingValidator { true }, FakeCodec())
            .run(authSession("not-a-uuid"))
        assertTrue(result is BackupCasResult.Rejected)
        assertEquals(0, remote.readCount)
    }
}
