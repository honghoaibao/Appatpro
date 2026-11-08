package com.atpro.tasks.f4f

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Một request HTTP đã ký xong, sẵn sàng gửi. */
data class F4fHttpRequest(
    val method: String,
    /** Path tương đối bắt đầu bằng "/", KHÔNG gồm query (vd `/user/earn-coin`). */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Chuỗi JSON, hoặc null nếu không có body. */
    val body: String?,
)

data class F4fHttpResponse(
    val httpCode: Int,
    val body: String,
    /** Giờ server (giây, từ header `Date`) — dùng để phát hiện đồng hồ máy lệch. Null nếu không có. */
    val serverTimeSecs: Long?,
)

/** Tầng truyền tải tách riêng để unit test thay bằng bản giả. Ném [IOException] khi lỗi mạng. */
interface F4fTransport {
    @Throws(IOException::class)
    suspend fun execute(request: F4fHttpRequest): F4fHttpResponse
}

/** Triển khai thật bằng `HttpURLConnection` (không thêm thư viện mới vào dự án). */
class UrlConnectionTransport(
    private val baseUrl: String = F4fApiClient.BASE_URL,
) : F4fTransport {

    override suspend fun execute(request: F4fHttpRequest): F4fHttpResponse = withContext(Dispatchers.IO) {
        val url = URL(baseUrl + request.path + queryString(request.query))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (request.method == "POST" || request.method == "PUT") {
                // Luôn gửi body (rỗng nếu null) để có Content-Length: 0 — một số server chặn POST không body.
                val bytes = (request.body ?: "").toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val dateMs = conn.getHeaderFieldDate("Date", 0L)
            F4fHttpResponse(code, text, if (dateMs > 0L) dateMs / 1000L else null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** `?a=1&b=2` (đã mã hoá) hoặc "" nếu không có tham số. */
        fun queryString(query: Map<String, String>): String =
            if (query.isEmpty()) ""
            else query.entries.joinToString("&", prefix = "?") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
    }
}
