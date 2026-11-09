package com.atpro.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One local sync-state row. Installation identity is random and unrelated to hardware IDs.
 * ownerUserId is set only after an explicit user confirmation; it is never reassigned silently.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val ownerUserId: String? = null,
    val ownerBoundAt: Long? = null,
    val installationId: String = UUID.randomUUID().toString(),
    val lastRemoteRevision: Long = 0,
    val lastSuccessfulSyncAt: Long? = null,
    val lastRevisionConfirmedAt: Long? = null,
    val lastChecksum: String? = null,
    val formatVersion: Int = 1,
    val syncStatus: String = "IDLE",
    val updatedAt: Long = System.currentTimeMillis(),
)
