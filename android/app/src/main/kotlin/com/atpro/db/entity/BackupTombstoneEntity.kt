package com.atpro.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Durable delete marker used to prevent stale snapshots from resurrecting an entity.
 * Tombstones are retained indefinitely until a multi-device causal watermark exists.
 */
@Entity(
    tableName = "backup_tombstones",
    indices = [
        Index(value = ["dataset", "entityKey"]),
        Index(value = ["sourceInstallId"]),
        Index(value = ["deletedAt"]),
    ],
)
data class BackupTombstoneEntity(
    @PrimaryKey val tombstoneId: String = UUID.randomUUID().toString(),
    val dataset: String,
    val entityKey: String,
    val sourceInstallId: String,
    val deletedAt: Long = System.currentTimeMillis(),
)
