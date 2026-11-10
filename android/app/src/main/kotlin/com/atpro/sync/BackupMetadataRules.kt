package com.atpro.sync

import com.atpro.db.entity.SyncMetadataEntity
import java.util.Locale
import java.util.UUID

/**
 * Pure validation rules shared by owner binding, revision checkpointing and backup validation.
 */
object BackupMetadataRules {
    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val CHECKSUM = Regex("^[0-9a-f]{64}$")

    /** Auth UUIDs may arrive in uppercase, but are always stored in canonical lowercase form. */
    fun normalizeAuthUserId(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = candidate.lowercase(Locale.ROOT)
        return normalized.takeIf(::isCanonicalUuid)
    }

    /** True only for the exact lowercase 8-4-4-4-12 UUID representation used in backups. */
    fun isCanonicalUuid(value: String): Boolean =
        UUID_PATTERN.matches(value) &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    fun isValidChecksum(value: String): Boolean = CHECKSUM.matches(value)

    fun isNextRevision(expectedCurrent: Long, committed: Long): Boolean =
        expectedCurrent >= 0L &&
            expectedCurrent < Long.MAX_VALUE &&
            committed == expectedCurrent + 1L

    /**
     * Fail-closed validation for the single local metadata row.
     * Revision zero is the only valid state before the first confirmed remote commit.
     */
    fun isStructurallyValid(metadata: SyncMetadataEntity): Boolean {
        if (metadata.id != 1 ||
            !isCanonicalUuid(metadata.installationId) ||
            metadata.lastRemoteRevision < 0L ||
            metadata.formatVersion <= 0 ||
            metadata.updatedAt <= 0L ||
            metadata.syncStatus.isBlank()
        ) {
            return false
        }

        val ownerId = metadata.ownerUserId
        if (ownerId == null) {
            if (metadata.ownerBoundAt != null || metadata.lastRemoteRevision > 0L) return false
        } else {
            if (normalizeAuthUserId(ownerId) != ownerId ||
                metadata.ownerBoundAt == null ||
                metadata.ownerBoundAt <= 0L
            ) {
                return false
            }
        }

        val checksum = metadata.lastChecksum
        if (checksum != null && !isValidChecksum(checksum)) return false

        return if (metadata.lastRemoteRevision == 0L) {
            checksum == null &&
                metadata.lastRevisionConfirmedAt == null &&
                metadata.lastSuccessfulSyncAt == null
        } else {
            ownerId != null &&
                checksum != null &&
                metadata.lastRevisionConfirmedAt != null &&
                metadata.lastRevisionConfirmedAt > 0L &&
                metadata.lastSuccessfulSyncAt == metadata.lastRevisionConfirmedAt
        }
    }
}
