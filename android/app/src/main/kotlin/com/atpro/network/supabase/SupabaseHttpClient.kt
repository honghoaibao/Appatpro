package com.atpro.network.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A request to a relative Supabase API path. Tokens are never logged by this client. */
data class SupabaseHttpRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    val accessToken: String? = null,
    /** Per-request response cap. Backup reads may use the full bounded snapshot allowance. */
    val maxResponseBytes: Int = SupabaseHttpRequest.DEFAULT_MAX_RESPONSE_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_ALLOWED_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

data class SupabaseHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

fun interface SupabaseHttpTransport {
    suspend fun execute(config: SupabaseClientConfig, request: SupabaseHttpRequest): SupabaseHttpResponse
}

/** Small REST transport with bounded timeouts and bounded response memory. */
class UrlConnectionSupabaseTransport : SupabaseHttpTransport {
    override suspend fun execute(
        config: SupabaseClientConfig,
        request: SupabaseHttpRequest,
    ): SupabaseHttpResponse = withContext(Dispatchers.IO) {
        val method = request.method.uppercase(Locale.ROOT)
        require(method in ALLOWED_METHODS) { "Unsupported HTTP method" }
        require(request.maxResponseBytes in 1..SupabaseHttpRequest.MAX_ALLOWED_RESPONSE_BYTES) {
            "Supabase response size limit is outside the supported bound"
        }

        val pathOnly = request.path.substringBefore('?')
        require(
            request.path.startsWith("/") && !request.path.startsWith("//") &&
                pathOnly.split('/').none { it == "." || it == ".." }
        ) { "Supabase path must be a safe relative API path" }

        val connection = URL(config.projectUrl.trimEnd('/') + request.path)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", config.publishableKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Client-Info", "atpro-android")
            request.accessToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { input ->
                val output = ByteArrayOutputStream(minOf(request.maxResponseBytes, 8 * 1024))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= request.maxResponseBytes) {
                        "Supabase response exceeded its configured size limit"
                    }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }.orEmpty()
            SupabaseHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE")
    }
}

class SupabaseHttpClient(
    private val config: SupabaseClientConfig = AtProSupabaseConfig.current,
    private val transport: SupabaseHttpTransport = UrlConnectionSupabaseTransport(),
) {
    suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
        transport.execute(config, request)
}
