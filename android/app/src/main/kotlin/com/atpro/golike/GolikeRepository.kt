package com.atpro.golike

import com.atpro.data.LocalRepository

private const val KEY_TOKEN     = "golike_token"
private const val KEY_USERNAME  = "golike_username"
/** v1.2.3 — UUID ổn định cho header G-Device-Id (golike_api_docs.md §2.2). */
private const val KEY_DEVICE_ID = "golike_device_id"

/**
 * GolikeRepository — business logic layer cho Golike API.
 *
 * Credential storage dùng LocalRepository config key-value (Room DB).
 * Singleton theo pattern của LocalRepository.
 */
class GolikeRepository(private val local: LocalRepository) {

    // ── Token / session ───────────────────────────────────────────────────────

    suspend fun getSavedToken(): String? {
        val t = local.getConfig(KEY_TOKEN, "")
        return t.ifEmpty { null }
    }

    suspend fun getSavedUsername(): String =
        local.getConfig(KEY_USERNAME, "")

    /**
     * v1.2.3 — Trả về UUID ổn định cho header `G-Device-Id`. Sinh 1 lần và
     * lưu lại trong config — KHÔNG đổi giữa các phiên (giả lập 1 thiết bị
     * cố định, đúng tinh thần "Device-Id" theo golike_api_docs.md §2.2).
     */
    suspend fun getDeviceId(): String {
        val existing = local.getConfig(KEY_DEVICE_ID, "")
        if (existing.isNotEmpty()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        local.setConfig(KEY_DEVICE_ID, generated)
        return generated
    }

    private suspend fun saveToken(token: String) =
        local.setConfig(KEY_TOKEN, token)

    /**
     * v1.2.1: Lưu token nhận được từ WebView login (GolikeLoginWebActivity).
     * Gọi sau khi WebView phát hiện token từ localStorage của app.golike.net.
     */
    suspend fun saveWebToken(token: String) = saveToken(token)

    suspend fun clearToken() {
        local.setConfig(KEY_TOKEN, "")
        local.setConfig(KEY_USERNAME, "")
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String): GolikeResult<GolikeUserData> {
        val result = GolikeApi.login(LoginRequest(username, password))
        return when (result) {
            is GolikeResult.Success -> {
                val resp = result.data
                if (resp.success && resp.token.isNotEmpty()) {
                    saveToken(resp.token)
                    local.setConfig(KEY_USERNAME, username)
                    GolikeResult.Success(resp.data ?: GolikeUserData(username = username))
                } else {
                    GolikeResult.Error(resp.message.ifEmpty { "Đăng nhập thất bại" })
                }
            }
            is GolikeResult.Error -> result
        }
    }

    // ── User info ─────────────────────────────────────────────────────────────

    suspend fun getMe(): GolikeResult<GolikeUserData> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        val deviceId = getDeviceId()
        return when (val r = GolikeApi.getMe(token, deviceId)) {
            is GolikeResult.Success -> {
                val resp = r.data
                when {
                    // Có data object → dùng luôn
                    resp.data != null -> GolikeResult.Success(resp.data)
                    // HTTP 200 nhưng data null hoặc success field vắng mặt/false
                    // (JSON format có thể khác model) → vẫn coi là thành công
                    else              -> GolikeResult.Success(GolikeUserData())
                }
            }
            is GolikeResult.Error -> r
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    suspend fun getStatistics(): GolikeResult<StatisticsResponse> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        val deviceId = getDeviceId()
        return when (val r = GolikeApi.getStatistics(token, deviceId)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data)
                else GolikeResult.Error("Lỗi lấy thống kê")
            is GolikeResult.Error -> r
        }
    }

    // ── TikTok ────────────────────────────────────────────────────────────────

    suspend fun getTikTokAccounts(): GolikeResult<List<TikTokAccountDto>> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        val deviceId = getDeviceId()
        return when (val r = GolikeApi.getTikTokAccounts(token, deviceId)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data.data)
                else GolikeResult.Error("Không tìm thấy tài khoản TikTok")
            is GolikeResult.Error -> r
        }
    }

    /**
     * v1.2.5 — Theo golike.py `get_next_job()`: trả về MỘT job + lock CÙNG lúc
     * (không phải list). `data == null` trong response nghĩa là không có job
     * khả dụng lúc này cho account này.
     */
    suspend fun getTikTokJobs(accountId: Int): GolikeResult<TikTokJobsResponse> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401, isAuthError = true)
        val deviceId = getDeviceId()
        return GolikeApi.getTikTokJobs(token, accountId, deviceId)
    }

    /**
     * v1.2.5 — Theo golike.py `complete_job()`: body 5 field, KHÔNG có "success".
     * @param accountId TikTokAccountDto.id
     * @param adsId     TikTokJobDto.jobId (field JSON gốc là "id")
     * @param objectId  ưu tiên lock.objectId, fallback job.objectId
     * @param type      TikTokJobDto.type
     * @param link      TikTokJobDto.link
     */
    suspend fun completeTikTokJob(
        accountId: Int,
        adsId:     Int,
        objectId:  String,
        type:      String,
        link:      String,
    ): GolikeResult<CompleteJobResponse> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401, isAuthError = true)
        val deviceId = getDeviceId()
        val req      = CompleteJobRequest(accountId = accountId, adsId = adsId, objectId = objectId, type = type, link = link)
        return when (val r = GolikeApi.completeTikTokJob(token, req, deviceId)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data)
                else GolikeResult.Error(r.data.message.ifEmpty { "Lỗi hoàn thành nhiệm vụ" }, cooldown = r.data.cooldown)
            is GolikeResult.Error -> r
        }
    }

    /**
     * v1.2.5 — Theo golike.py `skip_job()`: body 3 field (account_id, ads_id, object_id).
     * Dùng khi job không thực hiện được trên thiết bị (acc bị block, lock hết hạn...).
     */
    suspend fun skipTikTokJob(
        accountId: Int,
        adsId:     Int,
        objectId:  String,
    ): GolikeResult<SkipJobResponse> {
        val token    = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401, isAuthError = true)
        val deviceId = getDeviceId()
        val req      = SkipJobRequest(accountId = accountId, adsId = adsId, objectId = objectId)
        return when (val r = GolikeApi.skipTikTokJob(token, req, deviceId)) {
            is GolikeResult.Success -> GolikeResult.Success(r.data)
            is GolikeResult.Error   -> r
        }
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: GolikeRepository? = null

        fun getInstance(local: LocalRepository): GolikeRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GolikeRepository(local).also { INSTANCE = it }
            }
    }
}
