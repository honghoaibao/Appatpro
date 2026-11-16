package com.atpro.sync

/**
 * Pure queue policy shared by SyncWorkScheduler and JVM tests.
 *
 * A single unique-work chain serializes every sync. When a worker is already running,
 * one tagged follow-up may be appended; later local writes coalesce into that request.
 */
internal enum class SyncEnqueueAction {
    SKIP,
    KEEP,
    APPEND_AFTER_RUNNING,
    REPLACE_PENDING_WITH_BOOTSTRAP,
}

internal object SyncWorkSchedulingPolicy {

    fun forLocalMutation(
        hasPendingData: Boolean,
        hasRunningWork: Boolean,
        hasQueuedLocalFollowUp: Boolean,
    ): SyncEnqueueAction = when {
        !hasPendingData -> SyncEnqueueAction.SKIP
        hasRunningWork && hasQueuedLocalFollowUp -> SyncEnqueueAction.SKIP
        hasRunningWork -> SyncEnqueueAction.APPEND_AFTER_RUNNING
        else -> SyncEnqueueAction.KEEP
    }

    fun forBootstrap(
        hasActiveBootstrap: Boolean,
        hasRunningWork: Boolean,
    ): SyncEnqueueAction = when {
        hasActiveBootstrap -> SyncEnqueueAction.SKIP
        hasRunningWork -> SyncEnqueueAction.APPEND_AFTER_RUNNING
        else -> SyncEnqueueAction.REPLACE_PENDING_WITH_BOOTSTRAP
    }
}
