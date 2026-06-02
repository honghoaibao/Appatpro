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
    val coin:     Int         = 0,
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
)

// ── Statistics ────────────────────────────────────────────────────────────────

@Serializable
data class StatisticsResponse(
    val status:  Int     = 0,
    val success: Boolean = false,
    @SerialName("current_coin") val currentCoin: Int = 0,
    val tiktok:    PlatformStatsDto? = null,
    val facebook:  PlatformStatsDto? = null,
    val instagram: PlatformStatsDto? = null,
)

@Serializable
data class PlatformStatsDto(
    @SerialName("hold_coin")    val holdCoin:    Int = 0,
    @SerialName("pending_coin") val pendingCoin: Int = 0,
)

// ── Result wrapper ────────────────────────────────────────────────────────────

sealed class GolikeResult<out T> {
    data class Success<T>(val data: T) : GolikeResult<T>()
    data class Error(val message: String, val code: Int = 0) : GolikeResult<Nothing>()
}
