package com.atpro.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One local sync-state row. Installation identity is random and unrelated to hardware IDs.
 * The owner is left null until the explicit owner-binding flow is implemented.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val ownerUserId: String? = null,
    val installationId: String = UUID.randomUUID().toString(),
    val lastRemoteRevision: Long = 0,
    val lastSuccessfulSyncAt: Long? = null,
    val lastChecksum: String? = null,
    val formatVersion: Int = 1,
    val syncStatus: String = "IDLE",
    val updatedAt: Long = System.currentTimeMillis(),
)
