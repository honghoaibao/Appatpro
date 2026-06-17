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
                // [v1.2.0] Tim video theo nội dung
                likeByCaption             = repo.getConfigBool  ("like_by_caption",           false),
                captionKeywords           = repo.getConfig      ("caption_keywords",          ""),
                likeByHashtag             = repo.getConfigBool  ("like_by_hashtag",           false),
                hashtagKeywords           = repo.getConfig      ("hashtag_keywords",          ""),
                // [v1.2.0] Tìm kiếm theo từ khoá
                searchEnabled             = repo.getConfigBool  ("search_enabled",            false),
                searchKeywords            = repo.getConfig      ("search_keywords",           ""),
                searchVideosPerSession    = repo.getConfigInt   ("search_videos_per_session",  3),
                searchRate                = repo.getConfigDouble("search_rate",               0.05).toFloat(),
                // [v1.2.1] Task mode
                taskJobType               = repo.getConfig      ("task_job_type",             "BOTH"),
                taskFarmBeforeJobSecs     = repo.getConfigInt   ("task_farm_before_job_secs",  60),
                taskJobDelaySecs          = repo.getConfigInt   ("task_job_delay_secs",         4),
                taskJobsPerAccount        = repo.getConfigInt   ("task_jobs_per_account",       5),
                taskMaxConsecFailures     = repo.getConfigInt   ("task_max_consec_failures",    3),
                // [v1.2.4] Demo nuôi acc
                facebookNurtureDurationSecs = repo.getConfigInt   ("fb_nurture_duration_secs",  180),
                facebookLikeRate            = repo.getConfigDouble("fb_like_rate",              0.2).toFloat(),
                xNurtureDurationSecs        = repo.getConfigInt   ("x_nurture_duration_secs",   120),
                xLikeRate                   = repo.getConfigDouble("x_like_rate",               0.25).toFloat(),
                xRetweetRate                = repo.getConfigDouble("x_retweet_rate",            0.05).toFloat(),
                instagramNurtureDurationSecs= repo.getConfigInt   ("ig_nurture_duration_secs",  180),
                instagramLikeRate           = repo.getConfigDouble("ig_like_rate",              0.30).toFloat(),
                instagramFollowRate         = repo.getConfigDouble("ig_follow_rate",            0.08).toFloat(),
                threadsNurtureDurationSecs  = repo.getConfigInt   ("threads_nurture_duration_secs", 120),
                threadsLikeRate             = repo.getConfigDouble("threads_like_rate",         0.20).toFloat(),
                snapchatNurtureDurationSecs = repo.getConfigInt   ("snap_nurture_duration_secs", 90),
                snapchatStoryViewSecs       = repo.getConfigInt   ("snap_story_view_secs",       8),
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
            // [v1.2.0] Tim video theo nội dung
            repo.setConfig("like_by_caption",          "${s.likeByCaption}")
            repo.setConfig("caption_keywords",         s.captionKeywords)
            repo.setConfig("like_by_hashtag",          "${s.likeByHashtag}")
            repo.setConfig("hashtag_keywords",         s.hashtagKeywords)
            // [v1.2.0] Tìm kiếm theo từ khoá
            repo.setConfig("search_enabled",           "${s.searchEnabled}")
            repo.setConfig("search_keywords",          s.searchKeywords)
            repo.setConfig("search_videos_per_session","${s.searchVideosPerSession}")
            repo.setConfig("search_rate",              "${s.searchRate}")
            // [v1.2.1] Task mode
            repo.setConfig("task_job_type",            s.taskJobType)
            repo.setConfig("task_farm_before_job_secs","${s.taskFarmBeforeJobSecs}")
            repo.setConfig("task_job_delay_secs",      "${s.taskJobDelaySecs}")
            repo.setConfig("task_jobs_per_account",    "${s.taskJobsPerAccount}")
            repo.setConfig("task_max_consec_failures", "${s.taskMaxConsecFailures}")
            // [v1.2.4] Demo nuôi acc
            repo.setConfig("fb_nurture_duration_secs",       "${s.facebookNurtureDurationSecs}")
            repo.setConfig("fb_like_rate",                   "${s.facebookLikeRate}")
            repo.setConfig("x_nurture_duration_secs",        "${s.xNurtureDurationSecs}")
            repo.setConfig("x_like_rate",                    "${s.xLikeRate}")
            repo.setConfig("x_retweet_rate",                 "${s.xRetweetRate}")
            repo.setConfig("ig_nurture_duration_secs",       "${s.instagramNurtureDurationSecs}")
            repo.setConfig("ig_like_rate",                   "${s.instagramLikeRate}")
            repo.setConfig("ig_follow_rate",                 "${s.instagramFollowRate}")
            repo.setConfig("threads_nurture_duration_secs",  "${s.threadsNurtureDurationSecs}")
            repo.setConfig("threads_like_rate",              "${s.threadsLikeRate}")
            repo.setConfig("snap_nurture_duration_secs",     "${s.snapchatNurtureDurationSecs}")
            repo.setConfig("snap_story_view_secs",           "${s.snapchatStoryViewSecs}")

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
    // [v1.2.0] Tim video theo nội dung
    val likeByCaption:            Boolean = false,
    val captionKeywords:          String  = "",
    val likeByHashtag:            Boolean = false,
    val hashtagKeywords:          String  = "",
    // [v1.2.0] Tìm kiếm theo từ khoá
    val searchEnabled:            Boolean = false,
    val searchKeywords:           String  = "",
    val searchVideosPerSession:   Int     = 3,
    val searchRate:               Float   = 0.05f,
    // [v1.2.1] Task mode — làm nhiệm vụ Golike
    val taskJobType:              String  = "BOTH",   // "LIKE" | "FOLLOW" | "BOTH"
    val taskFarmBeforeJobSecs:    Int     = 60,
    val taskJobDelaySecs:         Int     = 4,
    val taskJobsPerAccount:       Int     = 5,
    val taskMaxConsecFailures:    Int     = 3,
    // [v1.2.4] Demo nuôi tài khoản các nền tảng khác
    val facebookNurtureDurationSecs: Int   = 180,
    val facebookLikeRate:            Float = 0.2f,
    val xNurtureDurationSecs:        Int   = 120,
    val xLikeRate:                   Float = 0.25f,
    val xRetweetRate:                Float = 0.05f,
    val instagramNurtureDurationSecs:Int   = 180,
    val instagramLikeRate:           Float = 0.30f,
    val instagramFollowRate:         Float = 0.08f,
    val threadsNurtureDurationSecs:  Int   = 120,
    val threadsLikeRate:             Float = 0.20f,
    val snapchatNurtureDurationSecs: Int   = 90,
    val snapchatStoryViewSecs:       Int   = 8,
    // ── Permission state (read-only, refreshed via refreshPermissions()) ──
    val accessibilityGranted:     Boolean = false,
    val overlayGranted:           Boolean = false,
    val notificationGranted:      Boolean = false,
)
