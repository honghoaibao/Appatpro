package com.atpro.golike

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("is_banned")   val isBanned:   Boolean = false,
)

@Serializable
data class TikTokAccountsResponse(
    val status:  Int                  = 0,
    val success: Boolean              = false,
    val data:    List<TikTokAccountDto> = emptyList(),
)

// ── TikTok Jobs ───────────────────────────────────────────────────────────────

@Serializable
data class TikTokJobDto(
    @SerialName("job_id")   val jobId:    Int    = 0,
    val link:     String = "",
    val type:     String = "",
    @SerialName("fix_coin") val fixCoin:  Int    = 0,
    /** v1.2.3 — golike_api_docs.md §4.2: cần cho body complete-jobs/skip-jobs. */
    @SerialName("object_id") val objectId: String = "",
)

@Serializable
data class TikTokJobsResponse(
    val status:  Int                = 0,
    val success: Boolean            = false,
    val data:    List<TikTokJobDto> = emptyList(),
)

@Serializable
data class CompleteJobRequest(
    @SerialName("job_id")          val jobId:          Int,
    @SerialName("unique_username") val uniqueUsername: String,
    val success: Boolean = true,
)

@Serializable
data class CompleteJobResponse(
    val status:  Int     = 0,
    val success: Boolean = false,
    val message: String  = "",
    /** v1.2.3 — golike_api_docs.md §4.3: số giây phải chờ trước khi retry nếu status=400. */
    val cooldown: Int     = 0,
)

// ── TikTok Job Detail / Skip [v1.2.3] ─────────────────────────────────────────

/**
 * v1.2.3 — golike_api_docs.md §4.4 (POST /api/advertising/publishers/tiktok/skip-jobs).
 * Doc không show body mẫu — dùng tạm cùng shape với CompleteJobRequest (job_id +
 * unique_username) theo quy ước chung của publisher API. Cần verify lại bằng HAR
 * capture thực tế khi có job bị skip.
 */
@Serializable
data class SkipJobRequest(
    @SerialName("job_id")          val jobId:          Int,
    @SerialName("unique_username") val uniqueUsername: String,
)

@Serializable
data class SkipJobResponse(
    val status:  Int     = 0,
    val success: Boolean = false,
    val message: String  = "",
)

/**
 * v1.2.3 — golike_api_docs.md §4.2 (GET /api/jobs/tiktok/job-detail).
 * `lock.lock_time` = số giây app có để hoàn thành job trước khi tự unlock (vd "120").
 */
@Serializable
data class JobDetailResponse(
    val status:  Int             = 0,
    val success: Boolean         = false,
    val message: String?         = null,
    val data:    JobDetailDto?    = null,
    val lock:    JobLockDto?      = null,
)

@Serializable
data class JobDetailDto(
    val id:        Int    = 0,
    val link:      String = "",
    @SerialName("object_id") val objectId: String = "",
    val type:      String = "",
    val quantity:  Int    = 0,
    @SerialName("price_per_after_cost") val pricePerAfterCost: Int = 0,
)

@Serializable
data class JobLockDto(
    @SerialName("user_id")    val userId:    Int    = 0,
    @SerialName("ads_id")     val adsId:     Int    = 0,
    @SerialName("account_id") val accountId: Int    = 0,
    @SerialName("object_id")  val objectId:  String = "",
    val type:      String = "",
    /** Số giây để publisher hoàn thành job trước khi tự unlock (server trả dạng String, vd "120"). */
    @SerialName("lock_time")  val lockTime:  String = "0",
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

sealed class GolikeResult<out T> {
    data class Success<T>(val data: T) : GolikeResult<T>()
    data class Error(val message: String, val code: Int = 0) : GolikeResult<Nothing>()
}
