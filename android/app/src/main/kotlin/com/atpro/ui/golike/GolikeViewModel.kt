package com.atpro.ui.golike

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.data.LocalRepository
import com.atpro.golike.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * GolikeViewModel — quản lý trạng thái toàn bộ tích hợp Golike.
 *
 * Dùng bởi:
 *   - DashboardScreen  → GolikeSummaryCard (summary coin/rank)
 *   - ConfigScreen     → EarnGolikeSection (login form + jobs)
 *
 * Mỗi Activity tạo instance riêng qua Factory; cả hai đều đọc/ghi
 * từ LocalRepository (Room DB) → tự đồng bộ qua persistent storage.
 */
class GolikeViewModel(
    private val repo: GolikeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GolikeUiState())
    val state: StateFlow<GolikeUiState> = _state.asStateFlow()

    /**
     * v1.2.3 — Job poll thông tin tài khoản Golike mỗi 15s (tên, số dư,
     * chờ duyệt...) sau khi đã có Auth + T-token hợp lệ (đăng nhập xong).
     */
    private var pollingJob: Job? = null

    init { loadSavedSession() }

    // ── Session ───────────────────────────────────────────────────────────────

    private fun loadSavedSession() {
        viewModelScope.launch {
            val token = repo.getSavedToken() ?: return@launch
            val saved = repo.getSavedUsername()
            // Set isLoggedIn=true ngay — có token hợp lệ trong DB
            // Không chờ API để tránh flash "chưa đăng nhập" khi app khởi động
            _state.update {
                it.copy(isLoggedIn = true, isLoading = true, savedUsername = saved)
            }
            refreshUserInfo()
            startPolling()
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(loginError = "Vui lòng nhập đầy đủ thông tin") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoggingIn = true, loginError = null) }
            when (val result = repo.login(username, password)) {
                is GolikeResult.Success -> {
                    _state.update {
                        it.copy(
                            isLoggedIn    = true,
                            isLoggingIn   = false,
                            user          = result.data,
                            savedUsername = username,
                            loginError    = null,
                        )
                    }
                    refreshStats()
                    loadTikTokAccounts()
                    startPolling()
                }
                is GolikeResult.Error ->
                    _state.update { it.copy(isLoggingIn = false, loginError = result.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            stopPolling()
            repo.clearToken()
            _state.value = GolikeUiState()
        }
    }

    /**
     * v1.2.1: Nhận token từ WebView login (GolikeLoginWebActivity).
     * Sau khi WebView phát hiện token trong localStorage, Activity gọi method này.
     * Tự động refresh thông tin user và TikTok accounts.
     */
    fun receiveTokenFromWebLogin(token: String) {
        if (token.isBlank()) return
        viewModelScope.launch {
            repo.saveWebToken(token)
            // Set isLoggedIn=true ngay — token đã được validate format trong WebActivity
            // (startsWith("eyJ") && length > 50). Không chờ API.
            _state.update { it.copy(isLoggedIn = true, isLoading = true, loginError = null) }
            // Refresh thông tin user (tên, coin, TikTok accounts) trong background
            refreshUserInfo()
            startPolling()
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    fun refreshUserInfo() {
        viewModelScope.launch {
            when (val result = repo.getMe()) {
                is GolikeResult.Success -> {
                    _state.update {
                        it.copy(
                            user       = result.data,
                            isLoading  = false,
                            isLoggedIn = true,
                        )
                    }
                    refreshStats()
                    if (_state.value.tikTokAccounts.isEmpty()) loadTikTokAccounts()
                }
                is GolikeResult.Error -> {
                    if (result.code == 401) {
                        // 401 = token hết hạn/không hợp lệ → logout
                        logout()
                    } else {
                        // Lỗi khác (network, server 5xx, JSON mismatch...) → giữ isLoggedIn
                        // như cũ, chỉ tắt loading. User vẫn được coi là đã đăng nhập.
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            when (val result = repo.getStatistics()) {
                is GolikeResult.Success -> _state.update { it.copy(stats = result.data) }
                is GolikeResult.Error   -> { /* silent — stats not critical */ }
            }
        }
    }

    /**
     * v1.2.3 — Sau khi Auth + T-token hợp lệ (đăng nhập xong / token đã lưu),
     * poll lại getMe() (→ kéo theo refreshStats()) mỗi 15 giây để cập nhật
     * tên tài khoản, số dư (coin), số dư đang chờ duyệt (hold/pending coin).
     *
     * GolikeApi tự đính kèm Authorization: Bearer + G-Auth + T (mã hoá session
     * time x3) + G-Device-Id cho mọi request — không cần truyền lại ở đây.
     *
     * Nếu refreshUserInfo() gặp 401 → logout() được gọi → stopPolling().
     */
    private fun startPolling() {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(15_000L)
                if (!_state.value.isLoggedIn) break
                refreshUserInfo()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    // ── TikTok accounts + jobs ────────────────────────────────────────────────

    fun loadTikTokAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAccounts = true) }
            when (val result = repo.getTikTokAccounts()) {
                is GolikeResult.Success -> {
                    _state.update { it.copy(tikTokAccounts = result.data, isLoadingAccounts = false) }
                    // Dùng acc.id (Int) cho API call — theo smali htool: account_id={INT}
                    result.data.forEach { acc -> loadJobsForAccount(acc) }
                }
                is GolikeResult.Error ->
                    _state.update { it.copy(isLoadingAccounts = false) }
            }
        }
    }

    private fun loadJobsForAccount(acc: TikTokAccountDto) {
        viewModelScope.launch {
            // API dùng acc.id (Int), state key dùng acc.uniqueUsername (String)
            when (val result = repo.getTikTokJobs(acc.id)) {
                is GolikeResult.Success ->
                    _state.update { s ->
                        s.copy(tikTokJobs = s.tikTokJobs + (acc.uniqueUsername to result.data))
                    }
                is GolikeResult.Error -> { /* no jobs for this account — ok */ }
            }
        }
    }

    /** Báo cáo hoàn thành job về server Golike. Theo dõi trạng thái loading + done. */
    fun completeJob(jobId: Int, uniqueUsername: String) {
        viewModelScope.launch {
            _state.update { it.copy(completingJobs = it.completingJobs + jobId) }
            val result = repo.completeTikTokJob(jobId, uniqueUsername)
            _state.update { s ->
                val success = result is GolikeResult.Success && result.data.success
                s.copy(
                    completingJobs = s.completingJobs - jobId,
                    completedJobs  = if (success) s.completedJobs + jobId else s.completedJobs,
                )
            }
            // Refresh jobs — tìm acc theo uniqueUsername để lấy acc.id cho API
            val acc = _state.value.tikTokAccounts.find { it.uniqueUsername == uniqueUsername }
            if (acc != null) loadJobsForAccount(acc)
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val local = LocalRepository.getInstance(ctx.applicationContext)
            val repo  = GolikeRepository.getInstance(local)
            return GolikeViewModel(repo) as T
        }
    }
}

// ── UiState ───────────────────────────────────────────────────────────────────

data class GolikeUiState(
    val isLoading:         Boolean                        = false,
    val isLoggedIn:        Boolean                        = false,
    val isLoggingIn:       Boolean                        = false,
    val loginError:        String?                        = null,
    val savedUsername:     String                         = "",
    val user:              GolikeUserData?                = null,
    val stats:             StatisticsResponse?            = null,
    val tikTokAccounts:    List<TikTokAccountDto>         = emptyList(),
    val tikTokJobs:        Map<String, List<TikTokJobDto>> = emptyMap(),
    val isLoadingAccounts: Boolean                        = false,
    /** Job IDs đang được gửi hoàn thành lên server — hiển thị spinner. */
    val completingJobs:    Set<Int>                       = emptySet(),
    /** Job IDs đã hoàn thành thành công trong session hiện tại — hiển thị checkmark. */
    val completedJobs:     Set<Int>                       = emptySet(),
) {
    val coin:          Double get() = user?.coin ?: stats?.currentCoin ?: 0.0
    val rankName:      String get() = user?.userRank?.rankName ?: ""
    val tiktokHold:    Double get() = stats?.tiktok?.holdCoin    ?: 0.0
    val tiktokPending: Double get() = stats?.tiktok?.pendingCoin ?: 0.0
    val totalJobCount: Int    get() = tikTokJobs.values.sumOf { it.size }
    val displayName:   String get() = user?.name?.ifEmpty { user?.username } ?: savedUsername

    /** Hiển thị coin đẹp: "250" thay vì "250.0", "12.5" thay vì "12.500000". */
    fun formatCoin(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString()
        else "%.1f".format(value)
}
