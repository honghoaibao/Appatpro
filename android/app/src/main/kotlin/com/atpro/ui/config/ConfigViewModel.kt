package com.atpro.ui.config

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.*
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.data.LocalRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConfigViewModel(private val repo: LocalRepository) : ViewModel() {

    private val _state   = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    private val _saved   = MutableSharedFlow<Unit>(replay = 0)
    val saved: SharedFlow<Unit> = _saved

    init { load() }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            var watchMin = repo.getConfigDouble("watch_time_min", 3.0)
            var watchMax = repo.getConfigDouble("watch_time_max", 8.0)

            // ── VALIDATION: Bảo vệ chống crash Slider ──────────────────────
            // Compose Slider assert range.start <= range.endInclusive — vi phạm = crash ngay.
            // Dữ liệu có thể bị ngược do: migration cũ, bug import, chỉnh tay DB.
            if (watchMin > watchMax) {
                // Swap thay vì reset — giữ lại ý định người dùng càng nhiều càng tốt
                val temp = watchMin; watchMin = watchMax; watchMax = temp
            }
            // Đảm bảo khoảng cách tối thiểu 0.5s (tránh degenerate range 5f..5f → steps crash)
            if (watchMax - watchMin < 0.5) watchMax = watchMin + 0.5

            val s = ConfigUiState(
                isLoading           = false,
                minutesPerAccount   = repo.getConfigInt   ("minutes_per_account", 5),
                watchMin            = watchMin,
                watchMax            = watchMax,
                enableRest          = repo.getConfigBool  ("enable_rest",         false),
                restMinutes         = repo.getConfigInt   ("rest_minutes",        2),
                maxBackAttempts     = repo.getConfigInt   ("max_back_attempts",   5),
                likeRate            = repo.getConfigDouble("like_rate",           0.30).toFloat(),
                followRate          = repo.getConfigDouble("follow_rate",         0.15).toFloat(),
                skipLive            = repo.getConfigBool  ("skip_live",           true),
                verifyAccount       = repo.getConfigBool  ("verify_account",      true),
                telegramToken       = repo.getConfig      ("telegram_token",      ""),
                telegramChatId      = repo.getConfig      ("telegram_chat_id",    ""),
                discordWebhook      = repo.getConfig      ("discord_webhook",     ""),
            )
            _state.value = s
        }
    }

    fun set(block: ConfigUiState.() -> ConfigUiState) {
        _state.update { block(it).copy(isDirty = true) }
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            repo.setConfig("minutes_per_account", "${s.minutesPerAccount}")
            repo.setConfig("watch_time_min",      "${s.watchMin}")
            repo.setConfig("watch_time_max",      "${s.watchMax}")
            repo.setConfig("enable_rest",         "${s.enableRest}")
            repo.setConfig("rest_minutes",        "${s.restMinutes}")
            repo.setConfig("max_back_attempts",   "${s.maxBackAttempts}")
            repo.setConfig("like_rate",           "${s.likeRate}")
            repo.setConfig("follow_rate",         "${s.followRate}")
            repo.setConfig("skip_live",           "${s.skipLive}")
            repo.setConfig("verify_account",      "${s.verifyAccount}")
            repo.setConfig("telegram_token",      s.telegramToken)
            repo.setConfig("telegram_chat_id",    s.telegramChatId)
            repo.setConfig("discord_webhook",     s.discordWebhook)
            _state.update { it.copy(isSaving = false, isDirty = false) }
            _saved.emit(Unit)
        }
    }

    fun loadTikTokVersion(context: Context) {
        viewModelScope.launch {
            val version = try {
                listOf(
                    "com.zhiliaoapp.musically",
                    "com.ss.android.ugc.trill",
                    "com.ss.android.ugc.aweme",
                ).firstNotNullOfOrNull { pkg ->
                    runCatching {
                        context.packageManager.getPackageInfo(pkg, 0).versionName
                    }.getOrNull()?.let { "$pkg\n$it" }
                } ?: "Không tìm thấy TikTok"
            } catch (_: Exception) { "Không tìm thấy TikTok" }
            _state.update { it.copy(tikTokVersion = version) }
        }
    }

    /** Đọc trạng thái quyền hệ thống — gọi mỗi lần ConfigScreen hiển thị. */
    fun refreshPermissions(context: Context) {
        viewModelScope.launch {
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            } else true
            _state.update {
                it.copy(
                    accessibilityGranted = AccessibilitySettingsHelper.isAccessibilityEnabled(context),
                    overlayGranted       = AccessibilitySettingsHelper.isOverlayGranted(context),
                    notificationGranted  = notif,
                )
            }
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConfigViewModel(LocalRepository.getInstance(ctx.applicationContext)) as T
    }
}

data class ConfigUiState(
    val isLoading:            Boolean = true,
    val isSaving:             Boolean = false,
    val isDirty:              Boolean = false,
    val minutesPerAccount:    Int     = 5,
    val watchMin:             Double  = 3.0,
    val watchMax:             Double  = 8.0,
    val enableRest:           Boolean = false,
    val restMinutes:          Int     = 2,
    val maxBackAttempts:      Int     = 5,
    val likeRate:             Float   = 0.30f,
    val followRate:           Float   = 0.15f,
    val skipLive:             Boolean = true,
    val verifyAccount:        Boolean = true,
    val telegramToken:        String  = "",
    val telegramChatId:       String  = "",
    val discordWebhook:       String  = "",
    val tikTokVersion:        String  = "Đang kiểm tra...",
    // ── Permission state (read-only, refreshed via refreshPermissions()) ──
    val accessibilityGranted: Boolean = false,
    val overlayGranted:       Boolean = false,
    val notificationGranted:  Boolean = false,
)
