package com.atpro.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Durable local change journal. Rows are acknowledged only after a committed remote revision.
 * sequence preserves mutation order even when multiple writes share the same millisecond.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["dataset", "entityKey"]),
        Index(value = ["nextAttemptAt"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val eventId: String = UUID.randomUUID().toString(),
    val dataset: String,
    val entityKey: String,
    val operation: String,
    val payloadJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastErrorCode: String? = null,
)

object SyncOutboxOperation {
    const val UPSERT = "UPSERT"
    const val DELETE = "DELETE"
}
