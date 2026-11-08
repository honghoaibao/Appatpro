package com.atpro.ui.f4f

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.tasks.TaskResult
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthManager
import com.atpro.tasks.f4f.F4fDashboard
import com.atpro.tasks.f4f.F4fEarnHistoryItem
import com.atpro.tasks.f4f.F4fEarnStats
import com.atpro.tasks.f4f.F4fJobReward
import com.atpro.tasks.f4f.F4fLeaderboard
import com.atpro.tasks.f4f.F4fNotifications
import com.atpro.tasks.f4f.F4fPage
import com.atpro.tasks.f4f.F4fRate
import com.atpro.tasks.f4f.F4fService
import com.atpro.tasks.f4f.F4fWithdrawInfo
import com.atpro.tasks.f4f.F4fWithdrawItem
import com.atpro.tasks.f4f.F4fWithdrawRequest
import com.atpro.tasks.f4f.F4fWithdrawStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Các tab của màn hình "Ví Follow4Follow". */
enum class F4fHubTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Tổng quan", Icons.Rounded.Dashboard),
    LEADERBOARD("Xếp hạng", Icons.Rounded.EmojiEvents),
    WITHDRAW("Rút tiền", Icons.Rounded.AccountBalanceWallet),
    HISTORY("Lịch sử", Icons.Rounded.History),
    NOTIFICATIONS("Thông báo", Icons.Rounded.Notifications),
}

/** Trạng thái tải một khối dữ liệu. */
sealed interface F4fLoad<out T> {
    object Idle : F4fLoad<Nothing>
    object Loading : F4fLoad<Nothing>
    data class Ok<T>(val data: T) : F4fLoad<T>
    data class Fail(val message: String) : F4fLoad<Nothing>
}

/** Kết quả một thao tác ghi (rút tiền) để UI hiện đúng 1 lần. */
data class F4fActionResult(val message: String, val success: Boolean)

data class F4fHubUiState(
    val dashboard:      F4fLoad<F4fDashboard> = F4fLoad.Idle,
    val earnStats:      F4fLoad<F4fEarnStats> = F4fLoad.Idle,
    val jobRewards:     F4fLoad<List<F4fJobReward>> = F4fLoad.Idle,
    val leaderboard:    F4fLoad<F4fLeaderboard> = F4fLoad.Idle,
    val withdrawStats:  F4fLoad<F4fWithdrawStats> = F4fLoad.Idle,
    val withdrawInfo:   F4fLoad<F4fWithdrawInfo> = F4fLoad.Idle,
    val withdrawList:   F4fLoad<List<F4fWithdrawItem>> = F4fLoad.Idle,
    val withdrawPage:   Int = 0,
    val withdrawHasMore: Boolean = false,
    val historyAction:  String = "follow",
    val history:        F4fLoad<List<F4fEarnHistoryItem>> = F4fLoad.Idle,
    val historyPage:    Int = 0,
    val historyHasMore: Boolean = false,
    val historyMoreBusy: Boolean = false,
    val notifications:  F4fLoad<F4fNotifications> = F4fLoad.Idle,
    /** Đang gửi yêu cầu rút tiền — khoá nút để không gửi trùng. */
    val withdrawBusy:   Boolean = false,
    /** Kết quả thao tác ghi gần nhất; UI gọi [F4fHubViewModel.consumeResult] sau khi hiển thị. */
    val result:         F4fActionResult? = null,
)

class F4fHubViewModel(
    private val service: F4fService,
    private val auth: F4fAuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow(F4fHubUiState())
    val state: StateFlow<F4fHubUiState> = _state.asStateFlow()

    /** Tải dữ liệu của tab nếu chưa có (mở tab lần đầu). */
    fun ensureLoaded(tab: F4fHubTab) {
        val s = _state.value
        val needs = when (tab) {
            F4fHubTab.OVERVIEW      -> s.dashboard is F4fLoad.Idle
            F4fHubTab.LEADERBOARD   -> s.leaderboard is F4fLoad.Idle
            F4fHubTab.WITHDRAW      -> s.withdrawStats is F4fLoad.Idle
            F4fHubTab.HISTORY       -> s.history is F4fLoad.Idle
            F4fHubTab.NOTIFICATIONS -> s.notifications is F4fLoad.Idle
        }
        if (needs) refresh(tab)
    }

    /** Tải lại dữ liệu của tab. */
    fun refresh(tab: F4fHubTab) {
        when (tab) {
            F4fHubTab.OVERVIEW -> {
                load({ service.dashboard() }) { s, v -> s.copy(dashboard = v) }
                load({ service.earnStats() }) { s, v -> s.copy(earnStats = v) }
                load({ service.jobRewards() }) { s, v -> s.copy(jobRewards = v) }
                viewModelScope.launch { auth.refresh() }          // cập nhật số dư ở thẻ Dịch vụ
            }
            F4fHubTab.LEADERBOARD -> load({ service.leaderboard(20) }) { s, v -> s.copy(leaderboard = v) }
            F4fHubTab.WITHDRAW -> {
                load({ service.withdrawStats() }) { s, v -> s.copy(withdrawStats = v) }
                load({ service.withdrawInfo() }) { s, v -> s.copy(withdrawInfo = v) }
                loadWithdrawPage(reset = true)
            }
            F4fHubTab.HISTORY       -> loadHistoryPage(reset = true)
            F4fHubTab.NOTIFICATIONS -> load({ service.notifications() }) { s, v -> s.copy(notifications = v) }
        }
    }

    // ── Lịch sử job ───────────────────────────────────────────────────────────

    fun setHistoryAction(action: String) {
        if (action == _state.value.historyAction) return
        _state.update { it.copy(historyAction = action, history = F4fLoad.Idle, historyPage = 0, historyHasMore = false) }
        loadHistoryPage(reset = true)
    }

    fun loadMoreHistory() {
        val s = _state.value
        if (s.historyMoreBusy || !s.historyHasMore) return
        loadHistoryPage(reset = false)
    }

    private fun loadHistoryPage(reset: Boolean) {
        viewModelScope.launch {
            val before = _state.value
            val page = if (reset) 1 else before.historyPage + 1
            if (reset) _state.update { it.copy(history = F4fLoad.Loading) } else _state.update { it.copy(historyMoreBusy = true) }
            when (val r = service.earnHistory(before.historyAction, page)) {
                is TaskResult.Success -> _state.update { s ->
                    val old = if (reset) emptyList() else (s.history as? F4fLoad.Ok)?.data.orEmpty()
                    s.copy(history = F4fLoad.Ok(old + r.data.items), historyPage = r.data.page,
                        historyHasMore = r.data.hasMore, historyMoreBusy = false)
                }
                is TaskResult.Error -> _state.update { s ->
                    if (reset) s.copy(history = F4fLoad.Fail(r.message)) else s.copy(historyMoreBusy = false, result = F4fActionResult(r.message, false))
                }
            }
        }
    }

    // ── Rút tiền ──────────────────────────────────────────────────────────────

    fun loadMoreWithdrawHistory() {
        if (!_state.value.withdrawHasMore) return
        loadWithdrawPage(reset = false)
    }

    private fun loadWithdrawPage(reset: Boolean) {
        viewModelScope.launch {
            val page = if (reset) 1 else _state.value.withdrawPage + 1
            if (reset) _state.update { it.copy(withdrawList = F4fLoad.Loading) }
            when (val r = service.withdrawHistory(page)) {
                is TaskResult.Success -> _state.update { s ->
                    val old = if (reset) emptyList() else (s.withdrawList as? F4fLoad.Ok)?.data.orEmpty()
                    s.copy(withdrawList = F4fLoad.Ok(old + r.data.items), withdrawPage = r.data.page, withdrawHasMore = r.data.hasMore)
                }
                is TaskResult.Error -> _state.update { s ->
                    if (reset) s.copy(withdrawList = F4fLoad.Fail(r.message)) else s.copy(result = F4fActionResult(r.message, false))
                }
            }
        }
    }

    /**
     * Gửi yêu cầu rút tiền. Chỉ gọi SAU khi người dùng đã bấm xác nhận trong hộp thoại.
     * Khoá bằng [F4fHubUiState.withdrawBusy] → không thể gửi trùng khi bấm nhanh.
     */
    fun submitWithdraw(request: F4fWithdrawRequest) {
        if (_state.value.withdrawBusy) return
        _state.update { it.copy(withdrawBusy = true) }
        viewModelScope.launch {
            val r = service.withdraw(request)
            when (r) {
                is TaskResult.Success -> {
                    _state.update { it.copy(withdrawBusy = false, result = F4fActionResult(r.data, true)) }
                    refresh(F4fHubTab.WITHDRAW)
                    refresh(F4fHubTab.OVERVIEW)               // số dư giảm
                }
                is TaskResult.Error -> _state.update {
                    it.copy(
                        withdrawBusy = false,
                        // Lỗi mạng khi gửi: server CÓ THỂ đã nhận → nhắc kiểm tra lịch sử trước khi gửi lại.
                        result = F4fActionResult(
                            if (r.message.startsWith("Lỗi mạng")) "${r.message}. Hãy kiểm tra Lịch sử rút tiền trước khi gửi lại." else r.message,
                            false,
                        ),
                    )
                }
            }
        }
    }

    fun consumeResult() = _state.update { it.copy(result = null) }

    // ── Tiện ích ──────────────────────────────────────────────────────────────

    private fun <T> load(call: suspend () -> TaskResult<T>, apply: (F4fHubUiState, F4fLoad<T>) -> F4fHubUiState) {
        viewModelScope.launch {
            _state.update { apply(it, F4fLoad.Loading) }
            val r = call()
            _state.update {
                apply(it, when (r) {
                    is TaskResult.Success -> F4fLoad.Ok(r.data)
                    is TaskResult.Error   -> F4fLoad.Fail(r.message)
                })
            }
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            F4fHubViewModel(F4fAuth.service(ctx), F4fAuth.manager(ctx)) as T
    }
}
