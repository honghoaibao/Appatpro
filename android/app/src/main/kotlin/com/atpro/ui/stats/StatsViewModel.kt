package com.atpro.ui.stats

import android.content.Context
import androidx.lifecycle.*
import com.atpro.data.EarningsStore
import com.atpro.data.EarningsSummary
import com.atpro.data.FarmSessionRow
import com.atpro.data.FarmStatsSummary
import com.atpro.data.summarizeEarnings
import com.atpro.data.summarizeFarmStats
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.SessionDao
import com.atpro.db.entity.SessionEntity
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
 * Nuôi acc (v1.12.5): các phiên đã đóng ([SessionDao.observeClosedSince]) tổng hợp bằng [summarizeFarmStats] — ngày theo giờ
 *            địa phương, chỉ phiên đã đóng, so với kỳ trước, tự cập nhật khi có phiên mới đóng.
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
    val farm: StateFlow<FarmStatsUiState> = _range
        .flatMapLatest { r ->
            // Lấy dư để đủ cả kỳ hiện tại + kỳ liền trước + lệch múi giờ/ranh giới ngày: (2N + 2) ngày; Tất cả = từ đầu.
            val since = r.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2L * it + 2) } ?: 0L
            sessionDao.observeClosedSince(since)
                .map<List<SessionEntity>, FarmStatsUiState> { rows ->
                    FarmStatsUiState(
                        isLoading = false,
                        summary   = summarizeFarmStats(rows.map { it.toRow() }, System.currentTimeMillis(), r.days),
                    )
                }
                .onStart { emit(FarmStatsUiState(isLoading = true)) }
                .catch { e -> emit(FarmStatsUiState(isLoading = false, error = e.message ?: "Lỗi đọc dữ liệu")) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FarmStatsUiState(isLoading = true))

    private fun SessionEntity.toRow() = FarmSessionRow(
        accountId = accountId, startedAt = startedAt, endedAt = endedAt,
        likes = likes, follows = follows, comments = comments, videos = videosWatched, durationSecs = durationSecs,
    )

    // ── Kiếm tiền ─────────────────────────────────────────────

    private val refreshTick = MutableStateFlow(0)

    /** Đọc lại nhật ký thu nhập (gọi khi mở tab Kiếm tiền — có thể đã có job mới). */
    fun refreshEarnings() { refreshTick.value++ }

    val earnings: StateFlow<EarningsSummary> = combine(_range, refreshTick) { r, _ -> r }
        .map { r ->
            withContext(Dispatchers.IO) {
                // Một lần đọc duy nhất cho cả dòng thô và dòng tổng — tránh đếm đôi nếu việc nén chen vào giữa hai lần đọc.
                val snapshot = runCatching { EarningsStore.readSnapshot() }.getOrNull()
                summarizeEarnings(
                    snapshot?.entries.orEmpty(),
                    System.currentTimeMillis(),
                    r.days,
                    rollups = snapshot?.rollups.orEmpty(),
                )
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

/** Trạng thái tab "Nuôi acc": [summary] rỗng khi chưa có phiên nào đã đóng trong khoảng đang chọn. */
data class FarmStatsUiState(
    val isLoading: Boolean           = false,
    val summary:   FarmStatsSummary  = FarmStatsSummary.EMPTY,
    val error:     String?           = null,
)
