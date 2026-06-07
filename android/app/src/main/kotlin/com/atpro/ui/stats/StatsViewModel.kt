package com.atpro.ui.stats

import android.content.Context
import androidx.lifecycle.*
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.SessionDao
import com.atpro.db.entity.DailyStatRow
import com.atpro.db.dao.TotalsRow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * StatsViewModel — tổng hợp dữ liệu farm từ SessionDao.
 *
 * Data sources:
 *   - SessionDao.getTotals(since)   → tổng toàn kỳ (1 row)
 *   - SessionDao.getDailyStats(since) → theo ngày × account
 *
 * Range selector: 7 ngày / 30 ngày / Tất cả
 * Thay đổi range → emit lại cả totals + daily list.
 */
class StatsViewModel(private val sessionDao: SessionDao) : ViewModel() {

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

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AtProDatabase.getInstance(ctx.applicationContext)
            return StatsViewModel(db.sessionDao()) as T
        }
    }
}

data class StatsUiState(
    val isLoading:  Boolean          = false,
    val totals:     TotalsRow?       = null,
    val dailyStats: List<DailyStatRow> = emptyList(),
    val error:      String?          = null,
)
