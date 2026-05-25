package com.atpro.ui.config

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.*
import com.atpro.data.LocalRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ConfigViewModel — load + save farm config qua LocalRepository/ConfigDao.
 *
 * Key map khớp với Flutter config_screen.dart và LocalRepository.loadFarmConfig().
 * _dirty = true khi user thay đổi giá trị nhưng chưa nhấn Lưu.
 */
class ConfigViewModel(private val repo: LocalRepository) : ViewModel() {

    private val _state   = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    private val _saved   = MutableSharedFlow<Unit>(replay = 0)
    val saved: SharedFlow<Unit> = _saved  // one-shot event → hiện SnackBar

    init { load() }

    // ── Load ──────────────────────────────────────────────────

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val s = ConfigUiState(
                isLoading           = false,
                minutesPerAccount   = repo.getConfigInt   ("minutes_per_account", 5),
                watchMin            = repo.getConfigDouble("watch_time_min",      3.0),
                watchMax            = repo.getConfigDouble("watch_time_max",      8.0),
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

    // ── Mutations (each returns a copy — ViewModel owns state) ─

    fun set(block: ConfigUiState.() -> ConfigUiState) {
        _state.update { block(it).copy(isDirty = true) }
    }

    // ── Save ──────────────────────────────────────────────────

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

    // ── TikTok version ────────────────────────────────────────

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

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConfigViewModel(LocalRepository.getInstance(ctx.applicationContext)) as T
    }
}

// ── UiState ───────────────────────────────────────────────────

data class ConfigUiState(
    val isLoading:         Boolean = true,
    val isSaving:          Boolean = false,
    val isDirty:           Boolean = false,
    // Timing
    val minutesPerAccount: Int     = 5,
    val watchMin:          Double  = 3.0,
    val watchMax:          Double  = 8.0,
    val enableRest:        Boolean = false,
    val restMinutes:       Int     = 2,
    val maxBackAttempts:   Int     = 5,
    // Actions
    val likeRate:          Float   = 0.30f,
    val followRate:        Float   = 0.15f,
    val skipLive:          Boolean = true,
    val verifyAccount:     Boolean = true,
    // Notifications
    val telegramToken:     String  = "",
    val telegramChatId:    String  = "",
    val discordWebhook:    String  = "",
    // Info
    val tikTokVersion:     String  = "Đang kiểm tra...",
)
