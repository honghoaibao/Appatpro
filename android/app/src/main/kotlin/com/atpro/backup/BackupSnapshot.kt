package com.atpro.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class BackupSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    val accounts: List<BackupAccount> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val configs: List<BackupConfig> = emptyList(),
    val logs: List<BackupLog> = emptyList(),
    val earnings: List<BackupEarning> = emptyList(),
    @SerialName("theme_mode") val themeMode: String = "LIGHT",
)

@Serializable
data class BackupAccount(
    val username: String,
    val status: String,
    val checkpoint: Boolean,
    @SerialName("sessions_count") val sessionsCount: Int,
    @SerialName("total_likes") val totalLikes: Int,
    @SerialName("total_follows") val totalFollows: Int,
    @SerialName("total_videos") val totalVideos: Int,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class BackupSession(
    val id: Long,
    @SerialName("account_id") val accountId: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ended_at") val endedAt: Long? = null,
    val likes: Int,
    val follows: Int,
    val comments: Int,
    @SerialName("videos_watched") val videosWatched: Int,
    @SerialName("duration_secs") val durationSecs: Int,
)

@Serializable
data class BackupConfig(
    val key: String,
    val value: String,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class BackupLog(
    val id: Long,
    @SerialName("account_id") val accountId: String? = null,
    val level: String,
    val message: String,
    val timestamp: Long,
)

@Serializable
data class BackupEarning(
    @SerialName("at_ms") val atMs: Long,
    val kind: String,
    val reward: Double,
    val account: String,
)

data class EncodedBackup(val json: String, val sha256: String, val byteCount: Int)

object BackupSnapshotCodec {
    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(snapshot: BackupSnapshot): EncodedBackup {
        require(snapshot.schemaVersion == BACKUP_SCHEMA_VERSION) { "Unsupported backup version" }
        val value = jsonCodec.encodeToString(snapshot)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Backup payload exceeds 8 MB" }
        return EncodedBackup(value, sha256(bytes), bytes.size)
    }

    fun decode(value: String, expectedSha256: String): BackupSnapshot {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Backup payload exceeds 8 MB" }
        require(sha256(bytes).equals(expectedSha256, ignoreCase = true)) {
            "Backup checksum mismatch"
        }
        val snapshot = jsonCodec.decodeFromString<BackupSnapshot>(value)
        require(snapshot.schemaVersion == BACKUP_SCHEMA_VERSION) { "Unsupported backup version" }
        return snapshot
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
