package com.atpro.ui.stats

import android.content.Context
import androidx.lifecycle.*
import com.atpro.data.EarningsStore
import com.atpro.data.EarningsSummary
import com.atpro.data.summarizeEarnings
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.SessionDao
import com.atpro.db.entity.DailyStatRow
import com.atpro.db.dao.TotalsRow
import com.atpro.tasks.TaskResult
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthState
import com.atpro.ui.f4f.WalletNumbers
import com.atpro.ui.f4f.walletNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * StatsViewModel — dữ liệu cho màn Thống kê.
 *
 * Nuôi acc : SessionDao.getTotals(since) + getDailyStats(since).
 * Kiếm tiền (v1.10.0): [EarningsStore] (nhật ký thu nhập từng job, ghi từ engine) tổng hợp bằng [summarizeEarnings];
 *            số liệu phía server (xu chờ / hôm nay / tổng) lấy từ Follow4Follow khi đã đăng nhập.
 * Khoảng thời gian (7 ngày / 30 ngày / Tất cả) dùng chung cho cả hai tab.
 */
class StatsViewModel(
    private val sessionDao: SessionDao,
    private val appContext: Context,
) : ViewModel() {

    init { EarningsStore.init(appContext) }

    // ── Range selector ────────────────────────────────────────

    enum class Range(val label: String, val days: Int?) {
        WEEK("7 ngày",   7),
        MONTH("30 ngày", 30),
        ALL("Tất cả",    null),
    }

    private val _range = MutableStateFlow(Range.WEEK)
    val range: StateFlow<Range> = _range.asStateFlow()

    fun setRange(r: Range) { _range.value = r }

    // ── Nuôi acc ──────────────────────────────────────────────

    // flatMapLatest (experimental trong coroutines 1.7.x): cần thiết để HUỶ lần tải cũ khi đổi khoảng thời gian.
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatsUiState> = _range
        .flatMapLatest { r ->
            val since = r.days?.let {
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it.toLong())
            } ?: 0L
            loadStats(since)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun loadStats(since: Long): Flow<StatsUiState> = flow {
        emit(StatsUiState(isLoading = true))
        try {
            val totals = sessionDao.getTotals(since)
            val daily  = sessionDao.getDailyStats(since)
            emit(StatsUiState(isLoading = false, totals = totals, dailyStats = daily))
        } catch (e: Exception) {
            emit(StatsUiState(isLoading = false, error = e.message))
        }
    }

    // ── Kiếm tiền ─────────────────────────────────────────────

    private val refreshTick = MutableStateFlow(0)

    /** Đọc lại nhật ký thu nhập (gọi khi mở tab Kiếm tiền — có thể đã có job mới). */
    fun refreshEarnings() { refreshTick.value++ }

    val earnings: StateFlow<EarningsSummary> = combine(_range, refreshTick) { r, _ -> r }
        .map { r ->
            withContext(Dispatchers.IO) {
                summarizeEarnings(EarningsStore.readAll(), System.currentTimeMillis(), r.days)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EarningsSummary.EMPTY)

    private val _server = MutableStateFlow<WalletNumbers?>(null)
    internal val server: StateFlow<WalletNumbers?> = _server.asStateFlow()

    /** Số liệu ví từ Follow4Follow (xu chờ · hôm nay · tổng). Bỏ qua nếu chưa đăng nhập; lỗi mạng → giữ số cũ. */
    fun loadServer() {
        if (F4fAuth.manager(appContext).state.value !is F4fAuthState.LoggedIn) {
            _server.value = null
            return
        }
        viewModelScope.launch {
            val service = F4fAuth.service(appContext)
            val dashboard = (service.dashboard() as? TaskResult.Success)?.data ?: return@launch
            val stats = (service.earnStats() as? TaskResult.Success)?.data
            _server.value = walletNumbers(dashboard, stats)
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = ctx.applicationContext
            val db = AtProDatabase.getInstance(app)
            return StatsViewModel(db.sessionDao(), app) as T
        }
    }
}

data class StatsUiState(
    val isLoading:  Boolean          = false,
    val totals:     TotalsRow?       = null,
    val dailyStats: List<DailyStatRow> = emptyList(),
    val error:      String?          = null,
)
