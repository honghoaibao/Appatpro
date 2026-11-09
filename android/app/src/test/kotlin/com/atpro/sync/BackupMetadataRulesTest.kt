package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataRulesTest {
    @Test
    fun normalizesCanonicalUuidAndRejectsMalformedIdentity() {
        val id = "7a1b2c3d-4e5f-4678-9abc-def012345678"
        assertEquals(id, BackupMetadataRules.normalizeAuthUserId(id))
        assertEquals(id, BackupMetadataRules.normalizeAuthUserId(id.uppercase()))
        assertNull(BackupMetadataRules.normalizeAuthUserId("not-a-uuid"))
        assertNull(BackupMetadataRules.normalizeAuthUserId("1-1-1-1-1"))
        assertNull(BackupMetadataRules.normalizeAuthUserId(null))
        assertTrue(BackupMetadataRules.isCanonicalUuid(id))
        assertFalse(BackupMetadataRules.isCanonicalUuid("1-1-1-1-1"))
        assertFalse(BackupMetadataRules.isCanonicalUuid(id.uppercase()))
    }

    @Test
    fun acceptsOnlyLowercaseSha256Hex() {
        assertTrue(BackupMetadataRules.isValidChecksum("a".repeat(64)))
        assertFalse(BackupMetadataRules.isValidChecksum("A".repeat(64)))
        assertFalse(BackupMetadataRules.isValidChecksum("a".repeat(63)))
        assertFalse(BackupMetadataRules.isValidChecksum("z".repeat(64)))
    }

    @Test
    fun validatesOnlyConsistentMetadataStates() {
        val now = 1_700_000_000_000L
        val initial = com.atpro.db.entity.SyncMetadataEntity(updatedAt = now)
        assertTrue(BackupMetadataRules.isStructurallyValid(initial))
        assertFalse(BackupMetadataRules.isStructurallyValid(initial.copy(ownerBoundAt = now)))
        assertFalse(BackupMetadataRules.isStructurallyValid(initial.copy(lastChecksum = "a".repeat(64))))
        assertFalse(BackupMetadataRules.isStructurallyValid(initial.copy(lastRemoteRevision = 1L)))

        val owner = "7a1b2c3d-4e5f-4678-9abc-def012345678"
        val bound = initial.copy(ownerUserId = owner, ownerBoundAt = now)
        assertTrue(BackupMetadataRules.isStructurallyValid(bound))
        assertFalse(BackupMetadataRules.isStructurallyValid(bound.copy(ownerUserId = owner.uppercase())))

        val committed = bound.copy(
            lastRemoteRevision = 1L,
            lastSuccessfulSyncAt = now,
            lastRevisionConfirmedAt = now,
            lastChecksum = "b".repeat(64),
        )
        assertTrue(BackupMetadataRules.isStructurallyValid(committed))
        assertFalse(BackupMetadataRules.isStructurallyValid(committed.copy(lastSuccessfulSyncAt = null)))
        assertFalse(BackupMetadataRules.isStructurallyValid(committed.copy(lastChecksum = null)))
    }

    @Test
    fun revisionMustAdvanceExactlyOnce() {
        assertTrue(BackupMetadataRules.isNextRevision(0L, 1L))
        assertTrue(BackupMetadataRules.isNextRevision(18L, 19L))
        assertFalse(BackupMetadataRules.isNextRevision(18L, 18L))
        assertFalse(BackupMetadataRules.isNextRevision(18L, 20L))
        assertFalse(BackupMetadataRules.isNextRevision(-1L, 0L))
        assertFalse(BackupMetadataRules.isNextRevision(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
