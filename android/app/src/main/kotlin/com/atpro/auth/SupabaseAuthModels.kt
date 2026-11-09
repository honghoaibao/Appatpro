package com.atpro.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class SupabaseAuthUser(
    val id: String,
    val email: String? = null,
)

@Serializable
data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val user: SupabaseAuthUser,
)

sealed interface SupabaseAuthState {
    data object SignedOut : SupabaseAuthState
    data class SignedIn(val user: SupabaseAuthUser) : SupabaseAuthState
}

sealed interface AuthActionResult {
    data class Success(
        val user: SupabaseAuthUser?,
        val needsEmailConfirmation: Boolean = false,
    ) : AuthActionResult

    data class Failure(val message: String) : AuthActionResult
}

internal object SupabaseAuthJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSession(
        body: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): SupabaseAuthSession? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val accessToken = root.string("access_token")?.takeIf { it.isNotBlank() } ?: return null
            val refreshToken = root.string("refresh_token")?.takeIf { it.isNotBlank() } ?: return null
            val user = parseUser(root) ?: return null
            val expiresAt = root.long("expires_at")
                ?: (nowEpochSeconds + (root.long("expires_in") ?: 3_600L))
            SupabaseAuthSession(accessToken, refreshToken, expiresAt, user)
        } catch (_: Exception) {
            null
        }
    }

    fun parseUser(body: String): SupabaseAuthUser? = try {
        parseUser(json.parseToJsonElement(body).jsonObject)
    } catch (_: Exception) {
        null
    }

    fun errorMessage(body: String, statusCode: Int): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val raw = root?.string("msg")
            ?: root?.string("message")
            ?: root?.string("error_description")
            ?: root?.string("error")
        val normalized = raw.orEmpty().lowercase()

        return when {
            statusCode == 429 -> "Có quá nhiều yêu cầu. Vui lòng thử lại sau."
            "invalid login credentials" in normalized -> "Email hoặc mật khẩu không chính xác."
            "email not confirmed" in normalized -> "Email chưa được xác nhận. Hãy kiểm tra hộp thư."
            "user already registered" in normalized -> "Email này đã được đăng ký."
            "password should be" in normalized -> "Mật khẩu chưa đáp ứng yêu cầu bảo mật."
            "invalid email" in normalized -> "Địa chỉ email không hợp lệ."
            raw.isNullOrBlank() -> "Yêu cầu thất bại (HTTP $statusCode)."
            else -> raw.take(180)
        }
    }

    private fun parseUser(root: JsonObject): SupabaseAuthUser? {
        val userObject = root["user"]?.jsonObject ?: root
        val id = userObject.string("id")?.takeIf { it.isNotBlank() } ?: return null
        return SupabaseAuthUser(id = id, email = userObject.string("email"))
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
}
