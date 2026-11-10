package com.atpro.sync

import com.atpro.network.supabase.SupabaseClientConfig
import com.atpro.network.supabase.SupabaseHttpClient
import com.atpro.network.supabase.SupabaseHttpRequest
import com.atpro.network.supabase.SupabaseHttpResponse
import com.atpro.network.supabase.SupabaseHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseBackupRemoteStoreTest {
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val token = "test-access-token"

    private class FakeTransport(var response: SupabaseHttpResponse) : SupabaseHttpTransport {
        var request: SupabaseHttpRequest? = null
        override suspend fun execute(
            config: SupabaseClientConfig,
            request: SupabaseHttpRequest,
        ): SupabaseHttpResponse {
            this.request = request
            return response
        }
    }

    private fun store(fake: FakeTransport) = SupabaseBackupRemoteStore(
        SupabaseHttpClient(
            SupabaseClientConfig("https://example.supabase.co", "public-test-key"),
            fake,
        )
    )

    @Test
    fun readUsesOwnerFilterJwtAndBoundedResponse() = runBlocking {
        val checksum = "a".repeat(64)
        val fake = FakeTransport(
            SupabaseHttpResponse(
                200,
                """[{"revision":4,"schema_version":3,"payload":"YWJj","checksum":"$checksum","updated_at":"2026-11-10T00:00:00Z"}]"""
            )
        )
        val result = store(fake).read(owner, token) as BackupRemoteReadResult.Found
        assertEquals(4L, result.record.revision)
        assertEquals(3, result.record.schemaVersion)
        assertEquals(token, fake.request?.accessToken)
        assertTrue(fake.request?.path?.contains("owner_id=eq.$owner") == true)
        assertTrue(fake.request?.path?.contains("select=revision,schema_version,payload,checksum,updated_at") == true)
        assertEquals(8 * 1024 * 1024, fake.request?.maxResponseBytes)
    }

    @Test
    fun readReturnsMissingOnlyForAnEmptyArray() = runBlocking {
        assertTrue(store(FakeTransport(SupabaseHttpResponse(200, "[]")))
            .read(owner, token) is BackupRemoteReadResult.Missing)
    }

    @Test
    fun readRejectsDuplicateRowsAndUnsupportedSchema() = runBlocking {
        val checksum = "a".repeat(64)
        val duplicate = """[{"revision":1,"schema_version":3,"payload":"YWJj","checksum":"$checksum"},{"revision":2,"schema_version":3,"payload":"YWJj","checksum":"$checksum"}]"""
        assertTrue(store(FakeTransport(SupabaseHttpResponse(200, duplicate)))
            .read(owner, token) is BackupRemoteReadResult.Failed)
        val unsupported = """[{"revision":1,"schema_version":99,"payload":"YWJj","checksum":"$checksum"}]"""
        assertTrue(store(FakeTransport(SupabaseHttpResponse(200, unsupported)))
            .read(owner, token) is BackupRemoteReadResult.Failed)
    }

    @Test
    fun commitUsesOnlyCasArgumentsAndDoesNotSendOwnerId() = runBlocking {
        val fake = FakeTransport(SupabaseHttpResponse(200, """[{"revision":8}]"""))
        val encoded = EncodedBackupPayload(3, "YWJj", "a".repeat(64), 3, 4)
        val result = store(fake).commit(7L, encoded, token)
        assertEquals(BackupRemoteCommitResult.Committed(8L), result)
        assertEquals("POST", fake.request?.method)
        assertEquals("/rest/v1/rpc/save_user_backup", fake.request?.path)
        assertEquals(token, fake.request?.accessToken)
        val body = Json.parseToJsonElement(fake.request!!.body!!).jsonObject
        assertEquals("7", body["p_expected_revision"].toString())
        assertEquals("3", body["p_schema_version"].toString())
        assertFalse("owner_id" in body)
    }

    @Test
    fun commitRecognizesOnlyExactServerRevisionConflict() = runBlocking {
        val fake = FakeTransport(
            SupabaseHttpResponse(400, """{"code":"P0001","message":"BACKUP_REVISION_CONFLICT"}""")
        )
        val encoded = EncodedBackupPayload(3, "YWJj", "a".repeat(64), 3, 4)
        assertTrue(store(fake).commit(1L, encoded, token) is BackupRemoteCommitResult.RevisionConflict)
    }

    @Test
    fun authenticationFailureIsNotMarkedRetryable() = runBlocking {
        val fake = FakeTransport(SupabaseHttpResponse(401, """{"message":"expired"}"""))
        val result = store(fake).read(owner, token) as BackupRemoteReadResult.Failed
        assertEquals("authentication_required", result.failure.code)
        assertFalse(result.failure.retryable)
    }

    @Test
    fun invalidOwnerAndOversizedPayloadAreRejectedBeforeTransport() = runBlocking {
        val fake = FakeTransport(SupabaseHttpResponse(200, "[]"))
        assertTrue(store(fake).read("not-a-uuid", token) is BackupRemoteReadResult.Failed)
        val size = BackupCodec.MAX_ENCODED_PAYLOAD_BYTES + 1
        val oversized = EncodedBackupPayload(3, "A".repeat(size), "a".repeat(64), 1, size)
        assertTrue(store(fake).commit(0, oversized, token) is BackupRemoteCommitResult.Failed)
        assertEquals(null, fake.request)
    }
}
