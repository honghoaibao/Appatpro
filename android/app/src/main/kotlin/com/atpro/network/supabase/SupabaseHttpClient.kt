package com.atpro.network.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A request to a relative Supabase API path. Tokens are never logged by this client. */
data class SupabaseHttpRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    val accessToken: String? = null,
)

data class SupabaseHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

/** Injectable transport boundary, allowing repository tests to use a fake implementation. */
fun interface SupabaseHttpTransport {
    suspend fun execute(
        config: SupabaseClientConfig,
        request: SupabaseHttpRequest,
    ): SupabaseHttpResponse
}

/**
 * Small REST transport based on Android's HttpURLConnection.
 *
 * No additional networking dependency is required. Calls run on Dispatchers.IO,
 * have bounded connection/read timeouts, and do not retry requests implicitly.
 */
class UrlConnectionSupabaseTransport : SupabaseHttpTransport {

    override suspend fun execute(
        config: SupabaseClientConfig,
        request: SupabaseHttpRequest,
    ): SupabaseHttpResponse = withContext(Dispatchers.IO) {
        val method = request.method.uppercase(Locale.ROOT)
        require(method in ALLOWED_METHODS) { "Unsupported HTTP method" }

        val pathOnly = request.path.substringBefore('?')
        require(
            request.path.startsWith("/") &&
                !request.path.startsWith("//") &&
                pathOnly.split('/').none { it == "." || it == ".." }
        ) {
            "Supabase path must be a safe relative API path"
        }

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

            request.accessToken
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output ->
                    output.write(bytes)
                }
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val responseBody = responseStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            SupabaseHttpResponse(statusCode = statusCode, body = responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        // PATCH is deliberately excluded: the JDK HttpURLConnection implementation
        // does not support it consistently. The sync layer can use POST/upsert.
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE")
    }
}

/** High-level client kept separate from the transport for easy testing and replacement. */
class SupabaseHttpClient(
    private val config: SupabaseClientConfig = AtProSupabaseConfig.current,
    private val transport: SupabaseHttpTransport = UrlConnectionSupabaseTransport(),
) {
    suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
        transport.execute(config, request)
}
