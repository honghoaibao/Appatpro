package com.atpro.sync

import com.atpro.network.supabase.SupabaseHttpClient
import com.atpro.network.supabase.SupabaseHttpRequest
import com.atpro.network.supabase.SupabaseHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

data class RemoteBackupRecord(
    val revision: Long,
    val schemaVersion: Int,
    val payload: String,
    val checksum: String,
    val updatedAt: String?,
)

data class BackupRemoteFailure(
    val code: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
)

sealed interface BackupRemoteReadResult {
    data class Found(val record: RemoteBackupRecord) : BackupRemoteReadResult
    data object Missing : BackupRemoteReadResult
    data class Failed(val failure: BackupRemoteFailure) : BackupRemoteReadResult
}

sealed interface BackupRemoteCommitResult {
    data class Committed(val revision: Long) : BackupRemoteCommitResult
    data object RevisionConflict : BackupRemoteCommitResult
    data class Failed(val failure: BackupRemoteFailure) : BackupRemoteCommitResult
}

interface BackupRemoteStore {
    suspend fun read(ownerUserId: String, accessToken: String): BackupRemoteReadResult

    /** The RPC derives owner_id from auth.uid(); owner ID is intentionally not an argument. */
    suspend fun commit(
        expectedRevision: Long,
        encoded: EncodedBackupPayload,
        accessToken: String,
    ): BackupRemoteCommitResult
}

/** Narrow PostgREST adapter. No service key, direct table writes, retries or local-state access. */
class SupabaseBackupRemoteStore(
    private val httpClient: SupabaseHttpClient = SupabaseHttpClient(),
) : BackupRemoteStore {

    override suspend fun read(
        ownerUserId: String,
        accessToken: String,
    ): BackupRemoteReadResult {
        if (!BackupMetadataRules.isCanonicalUuid(ownerUserId) || accessToken.isBlank()) {
            return BackupRemoteReadResult.Failed(
                BackupRemoteFailure("invalid_auth_context", retryable = false)
            )
        }
        return try {
            val response = httpClient.execute(
                SupabaseHttpRequest(
                    method = "GET",
                    path = "$TABLE_PATH?select=revision,schema_version,payload,checksum,updated_at&owner_id=eq.$ownerUserId&limit=1",
                    accessToken = accessToken,
                    maxResponseBytes = SupabaseHttpRequest.MAX_ALLOWED_RESPONSE_BYTES,
                )
            )
            if (!response.isSuccessful) {
                BackupRemoteReadResult.Failed(response.toFailure())
            } else {
                parseReadResponse(response.body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            BackupRemoteReadResult.Failed(BackupRemoteFailure("network_io", retryable = true))
        } catch (_: Exception) {
            BackupRemoteReadResult.Failed(BackupRemoteFailure("remote_read_failed", retryable = false))
        }
    }

    override suspend fun commit(
        expectedRevision: Long,
        encoded: EncodedBackupPayload,
        accessToken: String,
    ): BackupRemoteCommitResult {
        if (expectedRevision < 0L || expectedRevision == Long.MAX_VALUE ||
            accessToken.isBlank() || encoded.schemaVersion != BackupSnapshot.CURRENT_FORMAT_VERSION ||
            encoded.payload.isBlank() ||
            encoded.payload.length % 4 != 0 ||
            !BASE64_PATTERN.matches(encoded.payload) ||
            encoded.encodedBytes != encoded.payload.toByteArray(Charsets.UTF_8).size ||
            encoded.encodedBytes > BackupCodec.MAX_ENCODED_PAYLOAD_BYTES ||
            encoded.uncompressedBytes <= 0 ||
            encoded.uncompressedBytes > BackupCodec.MAX_UNCOMPRESSED_BYTES ||
            !BackupMetadataRules.isValidChecksum(encoded.checksum)
        ) {
            return BackupRemoteCommitResult.Failed(
                BackupRemoteFailure("invalid_commit_input", retryable = false)
            )
        }
        return try {
            val body = kotlinx.serialization.json.buildJsonObject {
                    put("p_expected_revision", JsonPrimitive(expectedRevision))
                    put("p_schema_version", JsonPrimitive(encoded.schemaVersion))
                    put("p_payload", JsonPrimitive(encoded.payload))
                    put("p_checksum", JsonPrimitive(encoded.checksum))
                }.toString()
            val response = httpClient.execute(
                SupabaseHttpRequest(
                    method = "POST",
                    path = RPC_PATH,
                    body = body,
                    accessToken = accessToken,
                )
            )
            if (!response.isSuccessful) {
                if (response.isRevisionConflict()) {
                    BackupRemoteCommitResult.RevisionConflict
                } else {
                    BackupRemoteCommitResult.Failed(response.toFailure())
                }
            } else {
                parseCommitResponse(response.body, expectedRevision)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            BackupRemoteCommitResult.Failed(BackupRemoteFailure("network_io", retryable = true))
        } catch (_: Exception) {
            BackupRemoteCommitResult.Failed(BackupRemoteFailure("remote_commit_failed", retryable = false))
        }
    }

    private fun parseReadResponse(body: String): BackupRemoteReadResult {
        val rows = try {
            json.parseToJsonElement(body) as? JsonArray ?: return invalidRead()
        } catch (_: Exception) {
            return invalidRead()
        }
        if (rows.isEmpty()) return BackupRemoteReadResult.Missing
        if (rows.size != 1) return invalidRead()
        val row = rows.single() as? JsonObject ?: return invalidRead()
        val revision = row.long("revision") ?: return invalidRead()
        val schemaVersion = row.int("schema_version") ?: return invalidRead()
        val payload = row.string("payload") ?: return invalidRead()
        val checksum = row.string("checksum") ?: return invalidRead()
        val updatedAt = row.string("updated_at")
        if (revision < 0L ||
            schemaVersion !in SUPPORTED_REMOTE_SCHEMA_VERSIONS ||
            payload.isBlank() ||
            payload.toByteArray(Charsets.UTF_8).size > BackupCodec.MAX_ENCODED_PAYLOAD_BYTES ||
            payload.length % 4 != 0 ||
            !BASE64_PATTERN.matches(payload) ||
            !BackupMetadataRules.isValidChecksum(checksum)
        ) return invalidRead()
        return BackupRemoteReadResult.Found(
            RemoteBackupRecord(revision, schemaVersion, payload, checksum, updatedAt)
        )
    }

    private fun parseCommitResponse(
        body: String,
        expectedRevision: Long,
    ): BackupRemoteCommitResult {
        val rows = try {
            json.parseToJsonElement(body) as? JsonArray ?: return invalidCommit()
        } catch (_: Exception) {
            return invalidCommit()
        }
        if (rows.size != 1) return invalidCommit()
        val revision = (rows.single() as? JsonObject)?.long("revision") ?: return invalidCommit()
        return if (revision == expectedRevision + 1L) {
            BackupRemoteCommitResult.Committed(revision)
        } else {
            invalidCommit()
        }
    }

    private fun invalidRead() = BackupRemoteReadResult.Failed(
        BackupRemoteFailure("invalid_remote_record", retryable = false)
    )

    private fun invalidCommit() = BackupRemoteCommitResult.Failed(
        BackupRemoteFailure("invalid_rpc_response", retryable = false)
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun SupabaseHttpResponse.isRevisionConflict(): Boolean {
        if (statusCode != 400 && statusCode != 409) return false
        return try {
            val error = json.parseToJsonElement(body).jsonObject
            error["code"]?.jsonPrimitive?.contentOrNull == "P0001" &&
                error["message"]?.jsonPrimitive?.contentOrNull == "BACKUP_REVISION_CONFLICT"
        } catch (_: Exception) {
            false
        }
    }

    private fun SupabaseHttpResponse.toFailure(): BackupRemoteFailure {
        val code = when (statusCode) {
            401 -> "authentication_required"
            403 -> "permission_denied"
            408 -> "request_timeout"
            429 -> "rate_limited"
            in 500..599 -> "server_error"
            else -> "http_$statusCode"
        }
        return BackupRemoteFailure(
            code = code,
            retryable = statusCode == 408 || statusCode == 429 || statusCode in 500..599,
            httpStatus = statusCode,
        )
    }

    private companion object {
        const val TABLE_PATH = "/rest/v1/user_backups"
        const val RPC_PATH = "/rest/v1/rpc/save_user_backup"
        val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")
        val SUPPORTED_REMOTE_SCHEMA_VERSIONS =
            setOf(BackupSnapshot.LEGACY_FORMAT_VERSION, BackupSnapshot.CURRENT_FORMAT_VERSION)
        val json = Json { isLenient = false; ignoreUnknownKeys = false }
    }
}
