package com.atpro.ui.stats

import android.content.Context
import androidx.lifecycle.*
import com.atpro.data.LocalRepository
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.SessionDao
import com.atpro.db.entity.DailyStatRow
import com.atpro.db.dao.TotalsRow
import com.atpro.golike.GolikeRepository
import com.atpro.golike.GolikeResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * StatsViewModel — tổng hợp dữ liệu farm từ SessionDao.
 *
 * Data sources:
 *   - SessionDao.getTotals(since)   → tổng toàn kỳ (1 row)
 *   - SessionDao.getDailyStats(since) → theo ngày × account
 *   - v1.3.10: GolikeRepository.getMe()/getStatistics() → số dư/tiền giữ/rank
 *     Golike — độc lập với range selector (luôn là số liệu TRỰC TIẾP hiện tại
 *     từ Golike, không phải lịch sử cục bộ như SessionDao).
 *
 * Range selector: 7 ngày / 30 ngày / Tất cả
 * Thay đổi range → emit lại cả totals + daily list.
 */
class StatsViewModel(
    private val sessionDao: SessionDao,
    private val golikeRepo: GolikeRepository?,
) : ViewModel() {

    // ── Range selector ────────────────────────────────────────

    enum class Range(val label: String, val days: Int?) {
        WEEK("7 ngày",   7),
        MONTH("30 ngày", 30),
        ALL("Tất cả",    null),
    }

    private val _range = MutableStateFlow(Range.WEEK)
    val range: StateFlow<Range> = _range.asStateFlow()

    fun setRange(r: Range) { _range.value = r }

    // ── UI state ──────────────────────────────────────────────

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
            emit(StatsUiState(
                isLoading  = false,
                totals     = totals,
                dailyStats = daily,
            ))
        } catch (e: Exception) {
            emit(StatsUiState(isLoading = false, error = e.message))
        }
    }

    // ── v1.3.10: Golike stats (số dư/tiền giữ/rank) ────────────

    private val _golikeStats = MutableStateFlow(GolikeStatsUiState())
    val golikeStats: StateFlow<GolikeStatsUiState> = _golikeStats.asStateFlow()

    init {
        refreshGolikeStats()
    }

    /** Fetch lại số liệu Golike — gọi lúc init VÀ khi người dùng bấm nút làm mới. */
    fun refreshGolikeStats() {
        val repo = golikeRepo
        if (repo == null) {
            _golikeStats.value = GolikeStatsUiState(isLoading = false, isLoggedIn = false)
            return
        }
        viewModelScope.launch {
            _golikeStats.update { it.copy(isLoading = true) }

            val loggedIn = repo.getSavedToken() != null
            if (!loggedIn) {
                _golikeStats.value = GolikeStatsUiState(isLoading = false, isLoggedIn = false)
                return@launch
            }

            val meResult    = repo.getMe()
            val statsResult = repo.getStatistics()

            val coin = (meResult as? GolikeResult.Success)?.data?.coin ?: 0.0
            val rank = (meResult as? GolikeResult.Success)?.data?.userRank?.rankName ?: ""

            // v1.3.11: bỏ `holdCoin` — chỉ còn dùng `pendingCoin` cho "Chờ duyệt".
            val pendingCoin = (statsResult as? GolikeResult.Success)?.data?.tiktok?.pendingCoin ?: 0.0

            _golikeStats.value = GolikeStatsUiState(
                isLoading   = false,
                isLoggedIn  = true,
                coin        = coin,
                pendingCoin = pendingCoin,
                rank        = rank,
            )
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AtProDatabase.getInstance(ctx.applicationContext)
            val golikeRepo = GolikeRepository.getInstance(LocalRepository.getInstance(ctx.applicationContext))
            return StatsViewModel(db.sessionDao(), golikeRepo) as T
        }
    }
}

data class StatsUiState(
    val isLoading:  Boolean          = false,
    val totals:     TotalsRow?       = null,
    val dailyStats: List<DailyStatRow> = emptyList(),
    val error:      String?          = null,
)

/** v1.3.10 — Trạng thái card thống kê Golike (số dư/chờ duyệt/rank). */
data class GolikeStatsUiState(
    val isLoading:   Boolean = false,
    val isLoggedIn:  Boolean = false,
    val coin:        Double  = 0.0,
    val pendingCoin: Double  = 0.0,
    val rank:        String  = "",
)
