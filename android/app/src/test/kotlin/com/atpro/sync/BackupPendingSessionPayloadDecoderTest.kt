package com.atpro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPendingSessionPayloadDecoderTest {
    private val sessionId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test
    fun decodesAnExactPendingClosedSessionPayload() {
        val payload = """{"syncId":"$sessionId","accountId":"sample","startedAt":10,"endedAt":20,"likes":1,"follows":2,"comments":0,"videosWatched":3,"durationSecs":10}"""
        val decoded = BackupPendingSessionPayloadDecoder.decode(sessionId, payload)

        assertEquals(sessionId, decoded.syncId)
        assertEquals("sample", decoded.accountId)
        assertEquals(3, decoded.videosWatched)
    }

    @Test
    fun rejectsPayloadWhoseSessionIdDoesNotMatchOutboxEntityKey() {
        val payload = """{"syncId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","accountId":"sample","startedAt":10,"endedAt":20,"likes":1,"follows":2,"comments":0,"videosWatched":3,"durationSecs":10}"""
        val error = runCatching {
            BackupPendingSessionPayloadDecoder.decode(sessionId, payload)
        }.exceptionOrNull()

        assertTrue(error is BackupSnapshotException)
    }

    @Test
    fun rejectsOversizedOrUnknownFieldPayloads() {
        val oversized = " ".repeat(BackupPendingSessionPayloadDecoder.MAX_PAYLOAD_BYTES + 1)
        assertTrue(runCatching {
            BackupPendingSessionPayloadDecoder.decode(sessionId, oversized)
        }.exceptionOrNull() is BackupSnapshotException)

        val withUnknown = """{"syncId":"$sessionId","accountId":"sample","startedAt":10,"endedAt":20,"likes":1,"follows":2,"comments":0,"videosWatched":3,"durationSecs":10,"extra":"x"}"""
        assertTrue(runCatching {
            BackupPendingSessionPayloadDecoder.decode(sessionId, withUnknown)
        }.exceptionOrNull() is BackupSnapshotException)
    }
}
