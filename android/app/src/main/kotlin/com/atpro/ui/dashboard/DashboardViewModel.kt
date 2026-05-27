package com.atpro.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.automation.FarmMode
import com.atpro.automation.LiveFarmStats
import com.atpro.data.FarmForegroundService
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.atpro.data.AccessibilitySettingsHelper

// ── ViewModel ─────────────────────────────────────────────────────────────────

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

    private fun pollEngineState() {
        viewModelScope.launch {
            var prevEngineRef: Any? = null
            var statsJob: kotlinx.coroutines.Job? = null
            var statusJob: kotlinx.coroutines.Job? = null  // [THÊM] Job observe startupStatus

            while (true) {
                val engine = TikTokAccessibilityService.instance?.engine

                if (engine !== prevEngineRef) {
                    statsJob?.cancel()
                    statusJob?.cancel()

                    if (engine != null) {
                        // Observe live stats
                        statsJob = launch {
                            engine.liveFarmStats.collect { stats ->
                                _uiState.update { it.copy(liveStats = stats) }
                            }
                        }
                        // [THÊM] Observe startup status để hiển thị popup trạng thái
                        statusJob = launch {
                            engine.startupStatus.collect { status ->
                                _uiState.update { it.copy(startupStatus = status) }
                            }
                        }
                    } else {
                        statsJob  = null
                        statusJob = null
                        // Engine gone → clear status
                        _uiState.update { it.copy(startupStatus = "") }
                    }

                    prevEngineRef = engine
                }

                val accessibilityGranted = AccessibilitySettingsHelper.isAccessibilityEnabled(appContext)
                val overlayGranted       = AccessibilitySettingsHelper.isOverlayGranted(appContext)
                val notificationGranted  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    NotificationManagerCompat.from(appContext).areNotificationsEnabled()
                } else true

                _uiState.update {
                    it.copy(
                        serviceConnected     = TikTokAccessibilityService.isRunning,
                        isFarming            = engine?.isFarming == true,
                        isPaused             = engine?.isPaused  == true,
                        accessibilityGranted = accessibilityGranted,
                        overlayGranted       = overlayGranted,
                        notificationGranted  = notificationGranted,
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

    fun startFarm() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        val state  = _uiState.value

        val inputList: List<String> = when (state.farmMode) {
            FarmMode.ALL_LOCAL ->
                emptyList()

            FarmMode.SELECTED_LIST ->
                state.customAccounts
                    .lines()
                    .map { it.trim().removePrefix("@") }
                    .filter { it.isNotEmpty() }
                    .distinct()
        }

        try {
            appContext.startForegroundService(FarmForegroundService.buildIntent(appContext))
        } catch (e: Exception) {
            Log.w("DashboardVM", "startForegroundService failed: ${e.message}")
        }

        engine.startFarm(state.farmMode, inputList)
    }

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
    val serviceConnected:     Boolean             = false,
    val accessibilityGranted: Boolean             = false,
    val overlayGranted:       Boolean             = false,
    val notificationGranted:  Boolean             = true,
    val isFarming:            Boolean             = false,
    val isPaused:             Boolean             = false,
    val allAccounts:          List<AccountEntity> = emptyList(),
    val activeAccounts:       List<AccountEntity> = emptyList(),
    val liveStats:            LiveFarmStats       = LiveFarmStats(),
    val farmMode:             FarmMode            = FarmMode.ALL_LOCAL,
    val customAccounts:       String              = "",
    // [THÊM] Trạng thái khởi động — rỗng khi không có gì cần hiển thị
    val startupStatus:        String              = "",
) {
    val activeCount: Int get() = activeAccounts.size

    val displayCount: Int get() = when (farmMode) {
        FarmMode.ALL_LOCAL ->
            activeCount
        FarmMode.SELECTED_LIST ->
            customAccounts.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .size
    }

    val permissionsReady: Boolean get() = accessibilityGranted

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

    /** True khi đang trong giai đoạn khởi động (có status text + đang farm). */
    val isStartingUp: Boolean get() = isFarming && startupStatus.isNotEmpty()
}
