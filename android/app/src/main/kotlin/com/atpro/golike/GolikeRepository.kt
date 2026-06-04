package com.atpro.golike

import com.atpro.data.LocalRepository

private const val KEY_TOKEN    = "golike_token"
private const val KEY_USERNAME = "golike_username"

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

    private suspend fun saveToken(token: String) =
        local.setConfig(KEY_TOKEN, token)

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
        val token = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        return when (val r = GolikeApi.getMe(token)) {
            is GolikeResult.Success -> {
                val resp = r.data
                if (resp.success && resp.data != null)
                    GolikeResult.Success(resp.data)
                else
                    GolikeResult.Error(resp.message.ifEmpty { "Lỗi lấy thông tin" }, resp.status)
            }
            is GolikeResult.Error -> r
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    suspend fun getStatistics(): GolikeResult<StatisticsResponse> {
        val token = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        return when (val r = GolikeApi.getStatistics(token)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data)
                else GolikeResult.Error("Lỗi lấy thống kê")
            is GolikeResult.Error -> r
        }
    }

    // ── TikTok ────────────────────────────────────────────────────────────────

    suspend fun getTikTokAccounts(): GolikeResult<List<TikTokAccountDto>> {
        val token = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        return when (val r = GolikeApi.getTikTokAccounts(token)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data.data)
                else GolikeResult.Error("Không tìm thấy tài khoản TikTok")
            is GolikeResult.Error -> r
        }
    }

    suspend fun getTikTokJobs(uniqueUsername: String): GolikeResult<List<TikTokJobDto>> {
        val token = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        return when (val r = GolikeApi.getTikTokJobs(token, uniqueUsername)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data.data)
                else GolikeResult.Error("Không có nhiệm vụ")
            is GolikeResult.Error -> r
        }
    }

    suspend fun completeTikTokJob(jobId: Int, uniqueUsername: String): GolikeResult<CompleteJobResponse> {
        val token = getSavedToken() ?: return GolikeResult.Error("Chưa đăng nhập", 401)
        val req   = CompleteJobRequest(jobId = jobId, uniqueUsername = uniqueUsername)
        return when (val r = GolikeApi.completeTikTokJob(token, req)) {
            is GolikeResult.Success ->
                if (r.data.success) GolikeResult.Success(r.data)
                else GolikeResult.Error(r.data.message.ifEmpty { "Lỗi hoàn thành nhiệm vụ" })
            is GolikeResult.Error -> r
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
