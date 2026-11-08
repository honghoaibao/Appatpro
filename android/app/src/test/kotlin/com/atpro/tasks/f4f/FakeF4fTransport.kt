package com.atpro.tasks.f4f

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Một request mà [FakeF4fTransport] đã nhận (body đã parse lại thành object để so sánh). */
data class SeenRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: JsonObject?,
)

/** Cặp (mã HTTP, body JSON dạng chuỗi) mà route giả trả về. */
data class FakeReply(val httpCode: Int, val body: String, val serverTimeSecs: Long? = null)

/**
 * Transport giả: mỗi request đi qua [route]; ném [IOException] nếu route trả null (mô phỏng mất mạng).
 * Mọi request được ghi lại trong [seen] để test kiểm tra path/query/body/header.
 */
class FakeF4fTransport(private val route: (SeenRequest) -> FakeReply?) : F4fTransport {
    val seen = ArrayList<SeenRequest>()

    override suspend fun execute(request: F4fHttpRequest): F4fHttpResponse {
        val body = request.body?.takeIf { it.isNotBlank() }?.let { parseJson(it).asObj() }
        val req = SeenRequest(request.method, request.path, request.query, request.headers, body)
        seen.add(req)
        val reply = route(req) ?: throw IOException("mất kết nối giả lập")
        return F4fHttpResponse(reply.httpCode, reply.body, reply.serverTimeSecs)
    }

    fun requestsTo(path: String): List<SeenRequest> = seen.filter { it.path == path }
}

/** Dựng response đúng "phong bì" của server. */
fun envelope(data: String = "null", code: Int = 200, message: String = "", errors: String = "null"): String =
    """{"status_code":$code,"message":${jsonString(message)},"errors":$errors,"data":$data}"""

fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
