package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupOwnershipUiPolicyTest {
    private val ownerA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val ownerB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun signedOutOverridesThePreviousLinkedStateImmediately() {
        val previous = BackupOwnershipState.Linked(8L, 100L)
        assertEquals(
            BackupOwnershipState.SignedOut,
            BackupOwnershipUiPolicy.resolve(null, ownerA, previous),
        )
    }

    @Test
    fun unresolvedNewOwnerDoesNotDisplayPreviousOwnersLinkedState() {
        val previous = BackupOwnershipState.Linked(8L, 100L)
        assertEquals(
            BackupOwnershipState.Checking,
            BackupOwnershipUiPolicy.resolve(ownerB, ownerA, previous),
        )
    }

    @Test
    fun ownerComparisonIsCanonicalAndCaseInsensitive() {
        val linked = BackupOwnershipState.Linked(4L, 200L)
        assertEquals(
            linked,
            BackupOwnershipUiPolicy.resolve(ownerA.uppercase(), ownerA, linked),
        )
    }

    @Test
    fun invalidAuthIdentityFailsClosed() {
        assertEquals(
            BackupOwnershipState.InvalidAuthIdentity,
            BackupOwnershipUiPolicy.resolve("not-a-user-id", null, BackupOwnershipState.Linked(1L, 1L)),
        )
    }

    @Test
    fun resolvedDifferentAccountStateIsShownAfterRefresh() {
        assertEquals(
            BackupOwnershipState.DifferentAccount,
            BackupOwnershipUiPolicy.resolve(ownerB, ownerB, BackupOwnershipState.DifferentAccount),
        )
    }

    @Test
    fun resolvedNeedsConfirmationStateIsShownAfterRefresh() {
        val needsConfirmation = BackupOwnershipState.NeedsConfirmation(3L)
        assertEquals(
            needsConfirmation,
            BackupOwnershipUiPolicy.resolve(ownerA, ownerA, needsConfirmation),
        )
    }
}
