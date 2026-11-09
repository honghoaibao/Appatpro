package com.atpro.sync

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class EncodedBackupPayload(
    val schemaVersion: Int,
    val payload: String,
    val checksum: String,
    val uncompressedBytes: Int,
    val encodedBytes: Int,
)

class BackupCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Deterministic JSON -> UTF-8 -> GZIP -> Base64 codec.
 * SHA-256 is calculated over canonical uncompressed JSON, not compressed bytes.
 */
object BackupCodec {
    const val MAX_ENCODED_PAYLOAD_BYTES = 7 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun encode(snapshot: BackupSnapshot): EncodedBackupPayload {
        if (snapshot.formatVersion <= 0) {
            throw BackupCodecException("Unsupported snapshot format version")
        }
        validateSnapshot(snapshot)

        val canonical = try {
            json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        } catch (error: Exception) {
            throw BackupCodecException("Could not serialize the backup snapshot", error)
        }
        if (canonical.size > MAX_UNCOMPRESSED_BYTES) {
            throw BackupCodecException("Uncompressed backup exceeds the safety limit")
        }

        val checksum = sha256(canonical)
        val compressed = try {
            ByteArrayOutputStream().use { buffer ->
                GZIPOutputStream(buffer).use { gzip -> gzip.write(canonical) }
                buffer.toByteArray()
            }
        } catch (error: Exception) {
            throw BackupCodecException("Could not compress the backup snapshot", error)
        }

        val payload = Base64.encodeToString(compressed, Base64.NO_WRAP)
        val encodedBytes = payload.toByteArray(Charsets.UTF_8).size
        if (encodedBytes > MAX_ENCODED_PAYLOAD_BYTES) {
            throw BackupCodecException(
                "Encoded backup exceeds the 7 MiB application limit; local data was not changed"
            )
        }

        return EncodedBackupPayload(
            schemaVersion = snapshot.formatVersion,
            payload = payload,
            checksum = checksum,
            uncompressedBytes = canonical.size,
            encodedBytes = encodedBytes,
        )
    }

    /**
     * Validates the encoded limit, checksum, decompressed size, canonical JSON and version
     * before returning a model. No local state is modified by this method.
     */
    fun decode(
        payload: String,
        expectedChecksum: String,
        expectedSchemaVersion: Int,
    ): BackupSnapshot {
        if (expectedSchemaVersion <= 0) {
            throw BackupCodecException("Invalid expected schema version")
        }
        val encodedBytes = payload.toByteArray(Charsets.UTF_8).size
        if (encodedBytes > MAX_ENCODED_PAYLOAD_BYTES) {
            throw BackupCodecException("Encoded backup exceeds the 7 MiB application limit")
        }
        if (!expectedChecksum.matches(CHECKSUM_PATTERN)) {
            throw BackupCodecException("Backup checksum must be 64 lowercase hexadecimal characters")
        }

        val compressed = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (error: Exception) {
            throw BackupCodecException("Backup payload is not valid Base64", error)
        }
        val canonical = decompressBounded(compressed)
        val actualChecksum = sha256(canonical)
        if (!MessageDigest.isEqual(
                actualChecksum.toByteArray(Charsets.US_ASCII),
                expectedChecksum.toByteArray(Charsets.US_ASCII),
            )
        ) {
            throw BackupCodecException("Backup checksum verification failed")
        }

        val jsonText = try {
            String(canonical, Charsets.UTF_8)
        } catch (error: Exception) {
            throw BackupCodecException("Backup JSON is not valid UTF-8", error)
        }
        val snapshot = try {
            json.decodeFromString<BackupSnapshot>(jsonText)
        } catch (error: Exception) {
            throw BackupCodecException("Backup JSON is malformed or uses an unsupported shape", error)
        }

        if (snapshot.formatVersion != expectedSchemaVersion) {
            throw BackupCodecException(
                "Snapshot format ${snapshot.formatVersion} does not match database schema $expectedSchemaVersion"
            )
        }
        validateSnapshot(snapshot)
        val canonicalAgain = try {
            json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        } catch (error: Exception) {
            throw BackupCodecException("Could not validate canonical backup JSON", error)
        }
        if (!canonical.contentEquals(canonicalAgain)) {
            throw BackupCodecException("Backup JSON is valid but not in canonical form")
        }
        return snapshot
    }


    private fun validateSnapshot(snapshot: BackupSnapshot) =
        BackupSnapshotValidator.validate(snapshot)

    private fun decompressBounded(compressed: ByteArray): ByteArray {
        try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = gzip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_UNCOMPRESSED_BYTES) {
                            throw BackupCodecException("Decompressed backup exceeds the safety limit")
                        }
                        output.write(buffer, 0, read)
                    }
                    return output.toByteArray()
                }
            }
        } catch (error: BackupCodecException) {
            throw error
        } catch (error: Exception) {
            throw BackupCodecException("Backup is not a valid GZIP stream", error)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val output = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = hex[value ushr 4]
            output[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(output)
    }

    private val CHECKSUM_PATTERN = Regex("^[0-9a-f]{64}$")
}
