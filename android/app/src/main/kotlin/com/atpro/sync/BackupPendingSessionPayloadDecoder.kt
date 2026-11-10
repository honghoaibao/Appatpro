package com.atpro.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Recovers immutable closed-session data from its durable Outbox payload when Room's
 * 90-day cleanup removed the row before it could be uploaded.
 */
object BackupPendingSessionPayloadDecoder {
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun decode(entityKey: String, payloadJson: String): BackupClosedSession {
        if (entityKey.isBlank() || entityKey != entityKey.trim() ||
            !BackupMetadataRules.isCanonicalUuid(entityKey)
        ) {
            throw BackupSnapshotException("Pending session Outbox key is invalid")
        }
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8).size
        if (payloadJson.isBlank() || payloadBytes !in 1..MAX_PAYLOAD_BYTES) {
            throw BackupSnapshotException("Pending session Outbox payload is empty or oversized")
        }

        val session = try {
            json.decodeFromString<BackupClosedSession>(payloadJson)
        } catch (error: Exception) {
            throw BackupSnapshotException("Pending session Outbox payload is malformed", error)
        }

        if (session.syncId != entityKey) {
            throw BackupSnapshotException("Pending session payload does not match its Outbox entity key")
        }
        return session
    }
}
