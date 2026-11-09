package com.atpro.sync

import java.util.UUID

/**
 * Pure validation rules shared by owner binding and revision checkpointing.
 */
object BackupMetadataRules {
    fun normalizeAuthUserId(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { UUID.fromString(candidate) }.getOrNull() ?: return null
        val canonical = parsed.toString()
        return canonical.takeIf { it.equals(candidate, ignoreCase = true) }
    }

    fun isValidChecksum(value: String): Boolean =
        CHECKSUM.matches(value)

    fun isNextRevision(expectedCurrent: Long, committed: Long): Boolean =
        expectedCurrent >= 0L &&
            expectedCurrent < Long.MAX_VALUE &&
            committed == expectedCurrent + 1L

    private val CHECKSUM = Regex("^[0-9a-f]{64}$")
}
