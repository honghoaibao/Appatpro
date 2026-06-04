package com.atpro.ui.config

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.*
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.data.LocalRepository
import com.atpro.notification.AtProNotificationManager
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
            if (watchMin > watchMax) {
                val temp = watchMin; watchMin = watchMax; watchMax = temp
            }
            if (watchMax - watchMin < 0.5) watchMax = watchMin + 0.5

            val s = ConfigUiState(
                isLoading                = false,
                minutesPerAccount        = repo.getConfigInt   ("minutes_per_account", 5),
                watchMin                 = watchMin,
                watchMax                 = watchMax,
                enableRest               = repo.getConfigBool  ("enable_rest",         false),
                restMinutes              = repo.getConfigInt   ("rest_minutes",        2),
                maxBackAttempts          = repo.getConfigInt   ("max_back_attempts",   5),
                likeRate                 = repo.getConfigDouble("like_rate",           0.30).toFloat(),
                followRate               = repo.getConfigDouble("follow_rate",         0.15).toFloat(),
                skipLive                 = repo.getConfigBool  ("skip_live",           true),
                skipAds                  = repo.getConfigBool  ("skip_ads",            true),
                // [v1.1.8] Like content-aware
                likeAds                  = repo.getConfigBool  ("like_ads",            false),
                verifyAccount            = repo.getConfigBool  ("verify_account",      true),
                enableSystemNotifications = repo.getConfigBool ("enable_system_notifications", true),
                // [v1.1.9+] Tab ghé thăm
                inboxViewRate             = repo.getConfigDouble("inbox_view_rate",          0.0).toFloat(),
                inboxViewDurationSecs     = repo.getConfigInt   ("inbox_view_duration_secs", 15),
                shopViewRate              = repo.getConfigDouble("shop_view_rate",           0.0).toFloat(),
                shopScrollCount           = repo.getConfigInt   ("shop_scroll_count",        3),
            )
            // [v1.1.4.1 FIX] Dùng update thay vì assignment trực tiếp để giữ lại
            // trạng thái quyền đã được refreshPermissions() cập nhật. Nếu dùng
            // _state.value = s (với permissions mặc định = false), load() sẽ ghi đè
            // kết quả của refreshPermissions() nếu nó chạy trước khi load() hoàn tất.
            _state.update { prev ->
                s.copy(
                    accessibilityGranted = prev.accessibilityGranted,
                    overlayGranted       = prev.overlayGranted,
                    notificationGranted  = prev.notificationGranted,
                )
            }
        }
    }

    fun set(block: ConfigUiState.() -> ConfigUiState) {
        _state.update { block(it).copy(isDirty = true) }
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            repo.setConfig("minutes_per_account",          "${s.minutesPerAccount}")
            repo.setConfig("watch_time_min",               "${s.watchMin}")
            repo.setConfig("watch_time_max",               "${s.watchMax}")
            repo.setConfig("enable_rest",                  "${s.enableRest}")
            repo.setConfig("rest_minutes",                 "${s.restMinutes}")
            repo.setConfig("max_back_attempts",            "${s.maxBackAttempts}")
            repo.setConfig("like_rate",                    "${s.likeRate}")
            repo.setConfig("follow_rate",                  "${s.followRate}")
            repo.setConfig("skip_live",                    "${s.skipLive}")
            repo.setConfig("skip_ads",                     "${s.skipAds}")
            repo.setConfig("like_ads",                     "${s.likeAds}")  // [v1.1.8]
            repo.setConfig("verify_account",               "${s.verifyAccount}")
            repo.setConfig("enable_system_notifications",  "${s.enableSystemNotifications}")
            // [v1.1.9+] Tab ghé thăm
            repo.setConfig("inbox_view_rate",          "${s.inboxViewRate}")
            repo.setConfig("inbox_view_duration_secs", "${s.inboxViewDurationSecs}")
            repo.setConfig("shop_view_rate",           "${s.shopViewRate}")
            repo.setConfig("shop_scroll_count",        "${s.shopScrollCount}")

            // Áp dụng ngay vào runtime manager
            AtProNotificationManager.enableSystemNotifications = s.enableSystemNotifications

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
    val isLoading:                Boolean = true,
    val isSaving:                 Boolean = false,
    val isDirty:                  Boolean = false,
    val minutesPerAccount:        Int     = 5,
    val watchMin:                 Double  = 3.0,
    val watchMax:                 Double  = 8.0,
    val enableRest:               Boolean = false,
    val restMinutes:              Int     = 2,
    val maxBackAttempts:          Int     = 5,
    val likeRate:                 Float   = 0.30f,
    val followRate:               Float   = 0.15f,
    val skipLive:                 Boolean = true,
    val skipAds:                  Boolean = true,
    // [v1.1.8] Like quảng cáo khi skipAds = false.
    val likeAds:                  Boolean = false,
    val verifyAccount:            Boolean = true,
    val enableSystemNotifications: Boolean = true,
    val tikTokVersion:            String  = "Đang kiểm tra...",
    // [v1.1.9+] Tab ghé thăm
    val inboxViewRate:            Float   = 0.0f,
    val inboxViewDurationSecs:    Int     = 15,
    val shopViewRate:             Float   = 0.0f,
    val shopScrollCount:          Int     = 3,
    // ── Permission state (read-only, refreshed via refreshPermissions()) ──
    val accessibilityGranted:     Boolean = false,
    val overlayGranted:           Boolean = false,
    val notificationGranted:      Boolean = false,
)
