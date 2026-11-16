package com.atpro.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSessionGuardTest {
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val install = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun logoutAndReloginCreateANewGenerationEvenForTheSameOwner() = runBlocking {
        val guard = SyncSessionGuard()
        guard.activate(owner)
        val old = guard.capture(owner, install)!!

        guard.invalidate()
        guard.activate(owner)
        val fresh = guard.capture(owner, install)!!

        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(fresh))
        assertTrue(fresh.generation > old.generation)
    }

    @Test
    fun differentOwnerCannotCaptureTheActiveGeneration() = runBlocking {
        val guard = SyncSessionGuard()
        guard.activate(owner)

        assertEquals(null, guard.capture("cccccccc-cccc-4ccc-8ccc-cccccccccccc", install))
    }

    @Test
    fun staleContextDoesNotExecuteProtectedBlock() = runBlocking {
        val guard = SyncSessionGuard()
        guard.activate(owner)
        val context = guard.capture(owner, install)!!
        guard.invalidate()
        var invoked = false

        val result = guard.withLease(context) {
            invoked = true
        }

        assertTrue(result is SyncGuardExecution.Invalidated)
        assertFalse(invoked)
    }

    @Test
    fun logoutWaitsForAnAlreadyStartedProtectedOperation() = runBlocking {
        val guard = SyncSessionGuard()
        guard.activate(owner)
        val context = guard.capture(owner, install)!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val operation = async {
            guard.withLease(context) {
                entered.complete(Unit)
                release.await()
                "committed"
            }
        }
        entered.await()
        val logout = async { guard.invalidate() }
        yield()

        assertFalse(logout.isCompleted)
        release.complete(Unit)
        val operationResult = operation.await()
        logout.await()

        assertTrue(operationResult is SyncGuardExecution.Completed)
        assertFalse(guard.isCurrent(context))
        assertFalse(guard.hasActiveSession())
    }
}
