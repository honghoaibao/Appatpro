package com.atpro.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.automation.LiveFarmStats
import com.atpro.data.FarmForegroundService
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log

// ── Farm mode ─────────────────────────────────────────────────

/**
 * FarmMode — 2 chế độ farm:
 *   ALL_LOCAL      → farm toàn bộ tài khoản active trong DB
 *   SELECTED_LIST  → farm theo danh sách username do user nhập tay
 */
enum class FarmMode { ALL_LOCAL, SELECTED_LIST }

// ── ViewModel ─────────────────────────────────────────────────

/**
 * DashboardViewModel — state holder cho DashboardActivity (native Compose).
 *
 * Data sources:
 *   1. Room (AccountDao.observeAll) → danh sách accounts, reactive
 *   2. Engine polling (500ms) → isFarming, isPaused, liveStats
 *
 * Service lifecycle (Fix: background drain):
 *   - FarmForegroundService được start khi startFarm() — không phải khi app mở
 *   - FarmForegroundService được stop khi stop() bằng ACTION_STOP
 *
 * Farm modes (Feature: dual-mode):
 *   ALL_LOCAL: farm tất cả active accounts từ DB (có thể rỗng → engine no-ops)
 *   SELECTED_LIST: farm theo danh sách username user nhập (1 dòng = 1 username)
 */
class DashboardViewModel(
    private val accountDao: AccountDao,
    private val appContext: Context,       // applicationContext — safe to hold in VM
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeAccounts()
        pollEngineState()
    }

    // ── Data sources ──────────────────────────────────────────

    private fun observeAccounts() {
        viewModelScope.launch {
            accountDao.observeAll().collect { all ->
                val active = all.filter { it.status == "active" && !it.checkpoint }
                _uiState.update { it.copy(
                    allAccounts    = all,
                    activeAccounts = active,
                )}
            }
        }
    }

    /**
     * Poll engine state mỗi 500ms.
     *
     * Lý do dùng polling thay Flow:
     *   - Engine nằm trong AccessibilityService — lifecycle độc lập
     *   - Engine có thể null nếu service chưa connect
     */
    private fun pollEngineState() {
        viewModelScope.launch {
            var prevEngineRef: Any? = null
            var statsJob: kotlinx.coroutines.Job? = null

            while (true) {
                val engine = TikTokAccessibilityService.instance?.engine

                if (engine !== prevEngineRef) {
                    statsJob?.cancel()
                    statsJob = if (engine != null) {
                        launch {
                            engine.liveFarmStats.collect { stats ->
                                _uiState.update { it.copy(liveStats = stats) }
                            }
                        }
                    } else null
                    prevEngineRef = engine
                }

                _uiState.update { it.copy(
                    serviceConnected = TikTokAccessibilityService.isRunning,
                    isFarming        = engine?.isFarming == true,
                    isPaused         = engine?.isPaused  == true,
                )}

                delay(500)
            }
        }
    }

    // ── Mode / list management ────────────────────────────────

    fun setFarmMode(mode: FarmMode) {
        _uiState.update { it.copy(farmMode = mode) }
    }

    fun setCustomAccounts(text: String) {
        _uiState.update { it.copy(customAccounts = text) }
    }

    // ── Actions ───────────────────────────────────────────────

    /**
     * Bắt đầu farm.
     *
     * Thứ tự:
     *   1. Lấy engine (trả về nếu không có — service chưa bật)
     *   2. Resolve danh sách accounts theo FarmMode
     *   3. Start FarmForegroundService (WS server + WakeLock) — TRƯỚC khi farm
     *   4. engine.startFarm(accounts)
     *
     * Nếu ALL_LOCAL và DB rỗng: truyền empty list → engine no-ops ngay lập tức.
     * Nếu SELECTED_LIST và customAccounts rỗng: canStart = false → không vào đây.
     */
    fun startFarm() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        val state  = _uiState.value

        val accounts: List<String> = when (state.farmMode) {
            FarmMode.ALL_LOCAL -> state.activeAccounts.map { it.username }
            FarmMode.SELECTED_LIST -> state.customAccounts
                .lines()
                .map { it.trim().removePrefix("@") }
                .filter { it.isNotEmpty() }
        }

        // Start service để giữ CPU active + WS server trong suốt phiên farm
        try {
            appContext.startForegroundService(FarmForegroundService.buildIntent(appContext))
        } catch (e: Exception) {
            Log.w("DashboardVM", "startForegroundService failed: ${e.message}")
        }

        engine.startFarm(accounts)
    }

    /**
     * Dừng farm + stop FarmForegroundService.
     * ACTION_STOP: service gọi engine.stop() nội bộ rồi stopSelf().
     */
    fun stop() {
        TikTokAccessibilityService.instance?.engine?.stop()
        try {
            val stopIntent = FarmForegroundService.buildIntent(appContext)
                .apply { action = FarmForegroundService.ACTION_STOP }
            appContext.startService(stopIntent)
        } catch (e: Exception) {
            Log.w("DashboardVM", "stopService failed: ${e.message}")
        }
    }

    fun pause()  { TikTokAccessibilityService.instance?.engine?.pause() }
    fun resume() { TikTokAccessibilityService.instance?.engine?.resume() }

    // ── Factory ───────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db  = AtProDatabase.getInstance(context.applicationContext)
            return DashboardViewModel(db.accountDao(), context.applicationContext) as T
        }
    }
}

// ── UiState ───────────────────────────────────────────────────

data class DashboardUiState(
    val serviceConnected: Boolean                = false,
    val isFarming:        Boolean                = false,
    val isPaused:         Boolean                = false,
    val allAccounts:      List<AccountEntity>    = emptyList(),
    val activeAccounts:   List<AccountEntity>    = emptyList(),
    val liveStats:        LiveFarmStats          = LiveFarmStats(),
    // ── Farm mode ──
    val farmMode:         FarmMode               = FarmMode.ALL_LOCAL,
    val customAccounts:   String                 = "",      // SELECTED_LIST mode: raw text input
) {
    val activeCount: Int get() = activeAccounts.size

    /**
     * Số tài khoản sẽ được farm dựa theo mode hiện tại.
     * ALL_LOCAL: số acc active trong DB.
     * SELECTED_LIST: số dòng hợp lệ trong customAccounts.
     */
    val displayCount: Int get() = when (farmMode) {
        FarmMode.ALL_LOCAL -> activeCount
        FarmMode.SELECTED_LIST -> customAccounts
            .lines().count { it.trim().isNotEmpty() }
    }

    /**
     * canStart — KHÔNG yêu cầu có accounts sẵn (Fix: farm flow).
     *   ALL_LOCAL      → cho phép ngay khi service connected (DB rỗng vẫn OK)
     *   SELECTED_LIST  → yêu cầu ít nhất 1 username trong text field
     */
    val canStart: Boolean get() = serviceConnected && !isFarming && when (farmMode) {
        FarmMode.ALL_LOCAL     -> true
        FarmMode.SELECTED_LIST -> customAccounts.isNotBlank()
    }

    val startHint: String? get() = when {
        !serviceConnected -> "Bật Accessibility Service trước"
        farmMode == FarmMode.SELECTED_LIST && customAccounts.isBlank() ->
            "Nhập danh sách tài khoản cần nuôi"
        else -> null
    }
}
