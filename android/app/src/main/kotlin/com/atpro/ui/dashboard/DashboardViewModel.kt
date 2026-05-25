package com.atpro.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.automation.FarmMode          // ← Moved to automation package
import com.atpro.automation.LiveFarmStats
import com.atpro.data.FarmForegroundService
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * DashboardViewModel — state holder cho DashboardActivity (native Compose).
 *
 * Data sources:
 *   1. Room (AccountDao.observeAll) → danh sách accounts, reactive
 *   2. Engine polling (500ms)       → isFarming, isPaused, liveStats
 *
 * Farm modes:
 *   ALL_LOCAL      → engine tự discover acc từ TikTok switch popup.
 *                    ViewModel chỉ cần truyền mode, không cần resolve list từ DB.
 *   SELECTED_LIST  → truyền inputList (đã trim + distinct) sang engine.
 *
 * startFarm() truyền FarmMode + inputList vào engine.startFarm(mode, inputList).
 * Engine hoàn toàn xử lý logic discover / TH1 / TH2 / farmedSet.
 */
class DashboardViewModel(
    private val accountDao: AccountDao,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeAccounts()
        pollEngineState()
    }

    // ── Data sources ──────────────────────────────────────────────────────────

    private fun observeAccounts() {
        viewModelScope.launch {
            accountDao.observeAll().collect { all ->
                val active = all.filter { it.status == "active" && !it.checkpoint }
                _uiState.update {
                    it.copy(allAccounts = all, activeAccounts = active)
                }
            }
        }
    }

    /**
     * Poll engine state mỗi 500ms.
     * Dùng polling vì engine nằm trong AccessibilityService, lifecycle độc lập.
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

                _uiState.update {
                    it.copy(
                        serviceConnected = TikTokAccessibilityService.isRunning,
                        isFarming        = engine?.isFarming == true,
                        isPaused         = engine?.isPaused  == true,
                    )
                }

                delay(500)
            }
        }
    }

    // ── Mode / list management ────────────────────────────────────────────────

    fun setFarmMode(mode: FarmMode) {
        _uiState.update { it.copy(farmMode = mode) }
    }

    fun setCustomAccounts(text: String) {
        _uiState.update { it.copy(customAccounts = text) }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Bắt đầu farm.
     *
     * ALL_LOCAL:
     *   Truyền inputList rỗng. Engine tự discover acc từ TikTok switch popup.
     *   Không cần DB có acc trước — engine sẽ auto-save khi discover xong.
     *
     * SELECTED_LIST:
     *   Parse + trim + removePrefix("@") + distinct() TRƯỚC khi truyền vào engine.
     *   Engine không cần lo dedup nữa.
     */
    fun startFarm() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        val state  = _uiState.value

        val inputList: List<String> = when (state.farmMode) {
            FarmMode.ALL_LOCAL ->
                // Engine tự discover — không truyền list
                emptyList()

            FarmMode.SELECTED_LIST ->
                state.customAccounts
                    .lines()
                    .map { it.trim().removePrefix("@") }
                    .filter { it.isNotEmpty() }
                    .distinct()   // ← dedup: đảm bảo không nuôi 1 acc 2 lần
        }

        // Start service để giữ CPU active + WS server trong suốt phiên farm
        try {
            appContext.startForegroundService(FarmForegroundService.buildIntent(appContext))
        } catch (e: Exception) {
            Log.w("DashboardVM", "startForegroundService failed: ${e.message}")
        }

        engine.startFarm(state.farmMode, inputList)
    }

    /**
     * Dừng farm + stop FarmForegroundService.
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

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AtProDatabase.getInstance(context.applicationContext)
            return DashboardViewModel(db.accountDao(), context.applicationContext) as T
        }
    }
}

// ── UiState ───────────────────────────────────────────────────────────────────

data class DashboardUiState(
    val serviceConnected: Boolean             = false,
    val isFarming:        Boolean             = false,
    val isPaused:         Boolean             = false,
    val allAccounts:      List<AccountEntity> = emptyList(),
    val activeAccounts:   List<AccountEntity> = emptyList(),
    val liveStats:        LiveFarmStats       = LiveFarmStats(),
    // ── Farm mode ──
    val farmMode:         FarmMode            = FarmMode.ALL_LOCAL,
    val customAccounts:   String              = "",
) {
    val activeCount: Int get() = activeAccounts.size

    /**
     * Số tài khoản sẽ được farm dựa theo mode hiện tại.
     *
     * ALL_LOCAL: hiển thị số acc active trong DB (tham khảo — engine sẽ tự discover thật).
     * SELECTED_LIST: số dòng hợp lệ trong customAccounts.
     */
    val displayCount: Int get() = when (farmMode) {
        FarmMode.ALL_LOCAL ->
            // Engine tự discover — DB count chỉ là ước tính tham khảo
            activeCount
        FarmMode.SELECTED_LIST ->
            customAccounts.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .size
    }

    /**
     * canStart — ALL_LOCAL: bật ngay khi service connected (không cần DB có acc).
     * Engine tự discover, kể cả khi DB đang rỗng.
     */
    val canStart: Boolean get() = serviceConnected && !isFarming && when (farmMode) {
        FarmMode.ALL_LOCAL     -> true
        FarmMode.SELECTED_LIST -> customAccounts.isNotBlank()
    }

    val startHint: String? get() = when {
        !serviceConnected ->
            "Bật Accessibility Service trước"
        farmMode == FarmMode.SELECTED_LIST && customAccounts.isBlank() ->
            "Nhập danh sách tài khoản cần nuôi"
        else -> null
    }
}
