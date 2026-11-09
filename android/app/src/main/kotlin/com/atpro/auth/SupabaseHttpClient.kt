package com.atpro.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class SupabaseHttpResponse(val statusCode: Int, val body: String) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/** Small HTTPS REST transport using platform APIs already available in AT PRO. */
class SupabaseHttpClient(
    private val projectUrl: String,
    private val publishableKey: String,
) {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        accessToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): SupabaseHttpResponse = withContext(Dispatchers.IO) {
        require(projectUrl.startsWith("https://")) { "Supabase URL chưa được cấu hình HTTPS" }
        require(publishableKey.isNotBlank()) { "Supabase publishable key chưa được cấu hình" }

        val connection = (URL(projectUrl.trimEnd('/') + "/" + path.trimStart('/'))
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", publishableKey)
            setRequestProperty(
                "Authorization",
                "Bearer " + (accessToken?.takeIf { it.isNotBlank() } ?: publishableKey),
            )
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            SupabaseHttpResponse(status, response)
        } finally {
            connection.disconnect()
        }
    }
}
