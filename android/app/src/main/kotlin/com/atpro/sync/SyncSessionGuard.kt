package com.atpro.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide fence between authenticated sessions and background backup work.
 *
 * Generation changes when the active owner changes or logout starts. A worker captures a
 * context after restoring Auth and before touching remote data. The shared mutex serializes
 * a remote CAS/local commit lease with logout, so after invalidate() returns no old context
 * can begin another protected write.
 *
 * Generation is process-local by design: after process death a worker must restore the
 * encrypted Auth session and capture a fresh context before doing work.
 */
data class SyncExecutionContext(
    val ownerUserId: String,
    val generation: Long,
    val installationId: String,
)

sealed interface SyncGuardExecution<out T> {
    data class Completed<T>(val value: T) : SyncGuardExecution<T>
    data object Invalidated : SyncGuardExecution<Nothing>
}

class SyncSessionGuard {
    private val mutex = Mutex()
    private var generation: Long = 0L
    private var activeOwnerUserId: String? = null

    suspend fun activate(ownerUserId: String) = mutex.withLock {
        val normalized = BackupMetadataRules.normalizeAuthUserId(ownerUserId)
            ?: throw IllegalArgumentException("A valid Auth owner is required")
        if (activeOwnerUserId != normalized) {
            advanceGeneration()
            activeOwnerUserId = normalized
        }
    }

    /** Called before local Auth storage is cleared, and again inside the Auth mutex. */
    suspend fun invalidate() = mutex.withLock {
        advanceGeneration()
        activeOwnerUserId = null
    }

    suspend fun capture(ownerUserId: String, installationId: String): SyncExecutionContext? =
        mutex.withLock {
            val normalized = BackupMetadataRules.normalizeAuthUserId(ownerUserId)
                ?: return@withLock null
            val install = installationId.trim().takeIf { it.isNotEmpty() }
                ?: return@withLock null
            if (activeOwnerUserId != normalized) return@withLock null
            SyncExecutionContext(normalized, generation, install)
        }

    suspend fun isCurrent(context: SyncExecutionContext): Boolean = mutex.withLock {
        matches(context)
    }

    suspend fun hasActiveSession(): Boolean = mutex.withLock {
        activeOwnerUserId != null
    }

    /**
     * Holds the gate through one protected operation. Logout waits for an already-started
     * operation, then invalidates the generation before it can report completion.
     */
    suspend fun <T> withLease(
        context: SyncExecutionContext,
        block: suspend () -> T,
    ): SyncGuardExecution<T> = mutex.withLock {
        if (!matches(context)) {
            SyncGuardExecution.Invalidated
        } else {
            SyncGuardExecution.Completed(block())
        }
    }

    private fun matches(context: SyncExecutionContext): Boolean =
        context.ownerUserId == activeOwnerUserId &&
            context.generation == generation &&
            context.installationId.isNotBlank()

    private fun advanceGeneration() {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
    }
}
