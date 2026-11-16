package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkSchedulingPolicyTest {

    @Test
    fun localMutationSkipsWhenNoDurableDataExists() {
        assertEquals(
            SyncEnqueueAction.SKIP,
            SyncWorkSchedulingPolicy.forLocalMutation(
                hasPendingData = false,
                hasRunningWork = false,
                hasQueuedFollowUp = false,
            ),
        )
    }

    @Test
    fun localMutationKeepsAnAlreadyQueuedWorker() {
        assertEquals(
            SyncEnqueueAction.KEEP,
            SyncWorkSchedulingPolicy.forLocalMutation(
                hasPendingData = true,
                hasRunningWork = false,
                hasQueuedFollowUp = false,
            ),
        )
    }

    @Test
    fun localMutationAppendsAtMostOneFollowUpWhileWorkerRuns() {
        assertEquals(
            SyncEnqueueAction.APPEND_AFTER_RUNNING,
            SyncWorkSchedulingPolicy.forLocalMutation(
                hasPendingData = true,
                hasRunningWork = true,
                hasQueuedFollowUp = false,
            ),
        )
        assertEquals(
            SyncEnqueueAction.SKIP,
            SyncWorkSchedulingPolicy.forLocalMutation(
                hasPendingData = true,
                hasRunningWork = true,
                hasQueuedFollowUp = true,
            ),
        )
    }

    @Test
    fun bootstrapIsNotDuplicatedWhenBootstrapAlreadyExists() {
        assertEquals(
            SyncEnqueueAction.SKIP,
            SyncWorkSchedulingPolicy.forBootstrap(
                hasActiveBootstrap = true,
                hasRunningWork = true,
            ),
        )
        assertEquals(
            SyncEnqueueAction.SKIP,
            SyncWorkSchedulingPolicy.forBootstrap(
                hasActiveBootstrap = true,
                hasRunningWork = false,
            ),
        )
    }

    @Test
    fun bootstrapAppendsAfterRunningWork() {
        assertEquals(
            SyncEnqueueAction.APPEND_AFTER_RUNNING,
            SyncWorkSchedulingPolicy.forBootstrap(
                hasActiveBootstrap = false,
                hasRunningWork = true,
            ),
        )
    }

    @Test
    fun bootstrapReplacesOnlyPendingNotRunningWork() {
        assertEquals(
            SyncEnqueueAction.REPLACE_PENDING_WITH_BOOTSTRAP,
            SyncWorkSchedulingPolicy.forBootstrap(
                hasActiveBootstrap = false,
                hasRunningWork = false,
            ),
        )
    }
}
