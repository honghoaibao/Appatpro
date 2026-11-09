package com.atpro.backup

import com.atpro.auth.SupabaseHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteBackup(
    val ownerId: String,
    val revision: Long,
    val payload: String,
    val checksum: String,
    val schemaVersion: Int,
)

sealed class BackupWriteResult {
    data class Saved(val revision: Long) : BackupWriteResult()
    object Conflict : BackupWriteResult()
    data class Failed(val reason: String) : BackupWriteResult()
}

/** Targets the user_backups table and save_user_backup RPC created in phase 4. */
class SupabaseBackupDataSource(private val http: SupabaseHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(ownerId: String, accessToken: String): RemoteBackup? {
        val response = http.request(
            method = "GET",
            path = "rest/v1/user_backups?select=owner_id,revision,payload,checksum,schema_version" +
                "&owner_id=eq." + ownerId + "&limit=1",
            accessToken = accessToken,
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("Không thể tải bản sao lưu (${response.statusCode})")
        }
        val rows = json.parseToJsonElement(response.body).jsonArray
        val row = rows.firstOrNull()?.jsonObject ?: return null
        return RemoteBackup(
            ownerId = row["owner_id"]!!.jsonPrimitive.content,
            revision = row["revision"]!!.jsonPrimitive.content.toLong(),
            payload = row["payload"].toString(),
            checksum = row["checksum"]!!.jsonPrimitive.content,
            schemaVersion = row["schema_version"]!!.jsonPrimitive.content.toInt(),
        )
    }

    suspend fun save(
        expectedRevision: Long,
        schemaVersion: Int,
        payloadJson: String,
        checksum: String,
        accessToken: String,
    ): BackupWriteResult {
        val body = buildJsonObject {
            put("p_expected_revision", expectedRevision)
            put("p_schema_version", schemaVersion)
            put("p_payload", json.parseToJsonElement(payloadJson))
            put("p_checksum", checksum)
        }.toString()

        val response = try {
            http.request(
                method = "POST",
                path = "rest/v1/rpc/save_user_backup",
                body = body,
                accessToken = accessToken,
            )
        } catch (_: Exception) {
            return BackupWriteResult.Failed("Không thể kết nối máy chủ sao lưu")
        }
        if (response.statusCode == 409 || response.body.contains("BACKUP_REVISION_CONFLICT")) {
            return BackupWriteResult.Conflict
        }
        if (!response.isSuccessful) {
            return BackupWriteResult.Failed("Máy chủ từ chối sao lưu (${response.statusCode})")
        }

        val revision = runCatching {
            val element = json.parseToJsonElement(response.body)
            val row = if (element is kotlinx.serialization.json.JsonArray) {
                element.first().jsonObject
            } else element.jsonObject
            row["revision"]!!.jsonPrimitive.content.toLong()
        }.getOrNull() ?: return BackupWriteResult.Failed("Máy chủ không trả về revision")
        return BackupWriteResult.Saved(revision)
    }
}
