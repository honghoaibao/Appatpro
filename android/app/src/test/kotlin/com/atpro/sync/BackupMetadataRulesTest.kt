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
        assertNull(BackupMetadataRules.normalizeAuthUserId(null))
    }

    @Test
    fun acceptsOnlyLowercaseSha256Hex() {
        assertTrue(BackupMetadataRules.isValidChecksum("a".repeat(64)))
        assertFalse(BackupMetadataRules.isValidChecksum("A".repeat(64)))
        assertFalse(BackupMetadataRules.isValidChecksum("a".repeat(63)))
        assertFalse(BackupMetadataRules.isValidChecksum("z".repeat(64)))
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
