package com.atpro.golike

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1.2.9 — API Golike đôi khi trả `is_banned: 0` (int) thay vì `false` (bool).
 * Serializer này xử lý cả hai dạng: 0/1 và false/true.
 */
object IntOrBoolSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("IntOrBool", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder is JsonDecoder) {
            val el = decoder.decodeJsonElement()
            if (el is JsonPrimitive) {
                val content = el.content.trim()
                // Xử lý boolean literal
                if (content.equals("true",  ignoreCase = true)) return true
                if (content.equals("false", ignoreCase = true)) return false
                // Xử lý integer 0 / 1
                val asInt = content.toIntOrNull()
                if (asInt != null) return asInt != 0
            }
        }
        return try { decoder.decodeBoolean() } catch (_: Exception) { false }
    }
}

// ── Auth ─────────────────────────────────────────────────────────────────────

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val status:  Int            = 0,
    val success: Boolean        = false,
    val message: String         = "",
    val token:   String         = "",
    val data:    GolikeUserData? = null,
)

// ── User ─────────────────────────────────────────────────────────────────────

@Serializable
data class GolikeUserData(
    val id:       Int         = 0,
    val username: String      = "",
    val name:     String      = "",
    val email:    String      = "",
    val role:     String      = "user",
    val coin:     Double      = 0.0,
    @SerialName("total_notifications_unread") val totalNotificationsUnread: Int = 0,
    @SerialName("user_rank") val userRank: UserRankDto? = null,
)

@Serializable
data class UserRankDto(
    val rank:                 Int    = 0,
    @SerialName("rank_name") val rankName: String = "",
)

@Serializable
data class MeResponse(
    val status:  Int            = 0,
    val success: Boolean        = false,
    val message: String         = "",
    val data:    GolikeUserData? = null,
)

// ── TikTok Accounts ───────────────────────────────────────────────────────────

@Serializable
data class TikTokAccountDto(
    val id:       Int     = 0,
    @SerialName("unique_id")       val uniqueId:       String = "",
    /** Username đầy đủ (có @) — dùng cho get-jobs & complete-jobs */
    @SerialName("unique_username") val uniqueUsername: String = "",
    val nickname:     String  = "",
    @SerialName("avatar_thumb") val avatarThumb: String = "",
    val status:       Int     = 1,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("is_banned")
    @Serializable(with = IntOrBoolSerializer::class)
    val isBanned: Boolean = false,
)

@Serializable
data class TikTokAccountsResponse(
    val status:  Int                  = 0,
    val success: Boolean              = false,
    val data:    List<TikTokAccountDto> = emptyList(),
)

// ── TikTok Jobs [v1.2.5 — đồng bộ chính xác theo golike.py] ───────────────────

/**
 * v1.2.5 — Theo golike.py `get_next_job()`: field ID của job là `id`
 * (KHÔNG phải `job_id` như giả định v1.2.3 dựa trên docs reverse-engineer).
 * Giá tiền job có thể nằm ở `fix_coin_job` HOẶC `price_per_after_cost`
 * (golike.py: `job.get("fix_coin_job") or job.get("price_per_after_cost", 0)`)
 * → expose cả 2 field thô + computed `fixCoin` để code cũ gọi `.fixCoin` không cần sửa.
 */
@Serializable
data class TikTokJobDto(
    @SerialName("id")    val jobId: Int    = 0,
    val link:             String = "",
    val type:             String = "",
    @SerialName("object_id")          val objectId:          String = "",
    @SerialName("fix_coin_job")       val fixCoinJob:         Int?   = null,
    @SerialName("price_per_after_cost") val pricePerAfterCost: Int?   = null,
) {
    /** Computed — golike.py: fix_coin_job ưu tiên, fallback price_per_after_cost. */
    val fixCoin: Int get() = fixCoinJob ?: pricePerAfterCost ?: 0
}

/**
 * v1.2.5 — Theo golike.py: GET `.../tiktok/jobs?account_id=X&data=null` trả về
 * MỘT job duy nhất (`data` là object, không phải array) + `lock` cùng response
 * (KHÔNG cần gọi thêm endpoint job-detail riêng — endpoint đó không tồn tại).
 * `data == null` nghĩa là hiện không có job nào khả dụng cho account này.
 */
@Serializable
data class TikTokJobsResponse(
    val status:  Int           = 0,
    val success: Boolean       = false,
    val message: String?       = null,
    val data:    TikTokJobDto? = null,
    val lock:    JobLockDto?   = null,
)

/**
 * v1.2.5 — Theo golike.py `complete_job()`: body chỉ có 5 field, KHÔNG có
 * "success" (endpoint complete-jobs nghĩa là "tôi đã làm xong" — trường hợp
 * không làm được thì gọi skip-jobs riêng, không phải complete với success=false).
 */
@Serializable
data class CompleteJobRequest(
    @SerialName("account_id") val accountId: Int,
    @SerialName("ads_id")     val adsId:     Int,
    @SerialName("object_id")  val objectId:  String,
    val type: String,
    val link: String,
)

/** v1.2.5 — golike.py: `result.data.prices` chứa số coin vừa nhận được. */
@Serializable
data class CompleteJobDataDto(
    val prices: Int = 0,
)

@Serializable
data class CompleteJobResponse(
    val status:   Int                  = 0,
    val success:  Boolean               = false,
    val message:  String                = "",
    val data:     CompleteJobDataDto?   = null,
    /** v1.2.5 — golike.py `_handle_response`: số giây chờ trước khi retry khi success=false. */
    val cooldown: Int                   = 0,
)

/**
 * v1.2.5 — Theo golike.py `skip_job()`: body chỉ 3 field, KHÔNG có unique_username.
 */
@Serializable
data class SkipJobRequest(
    @SerialName("account_id") val accountId: Int,
    @SerialName("ads_id")     val adsId:     Int,
    @SerialName("object_id")  val objectId:  String,
)

@Serializable
data class SkipJobResponse(
    val status:  Int     = 0,
    val success: Boolean = false,
    val message: String  = "",
)

/**
 * v1.2.5 — `lock` đi kèm CÙNG response với job (field của [TikTokJobsResponse]),
 * không phải từ endpoint job-detail riêng (endpoint đó không có trong golike.py).
 * golike.py ưu tiên `lock.object_id` trước `job.object_id` khi cả 2 đều có.
 */
@Serializable
data class JobLockDto(
    @SerialName("user_id")    val userId:    Int    = 0,
    @SerialName("ads_id")     val adsId:     Int    = 0,
    @SerialName("account_id") val accountId: Int    = 0,
    @SerialName("object_id")  val objectId:  String = "",
    val type:      String = "",
    /** Số giây để publisher hoàn thành job trước khi tự unlock (golike.py default 120). */
    @SerialName("lock_time")  val lockTime:  String = "120",
)

// ── Statistics ────────────────────────────────────────────────────────────────

@Serializable
data class StatisticsResponse(
    val status:  Int     = 0,
    val success: Boolean = false,
    @SerialName("current_coin") val currentCoin: Double = 0.0,
    val tiktok:    PlatformStatsDto? = null,
    val facebook:  PlatformStatsDto? = null,
    val instagram: PlatformStatsDto? = null,
)

@Serializable
data class PlatformStatsDto(
    @SerialName("hold_coin")    val holdCoin:    Double = 0.0,
    @SerialName("pending_coin") val pendingCoin: Double = 0.0,
)

// ── Result wrapper ────────────────────────────────────────────────────────────

/**
 * v1.2.5 — Theo golike.py `_handle_response()`: lỗi có thể kèm `cooldown` (số giây
 * chờ trước khi retry) và phân biệt lỗi xác thực (401/403 → cần đăng nhập lại)
 * với lỗi khác (4xx/5xx khác → có thể retry/backoff).
 */
sealed class GolikeResult<out T> {
    data class Success<T>(val data: T) : GolikeResult<T>()
    data class Error(
        val message:     String,
        val code:        Int     = 0,
        val cooldown:     Int     = 0,
        val isAuthError:  Boolean = false,
    ) : GolikeResult<Nothing>()
}
