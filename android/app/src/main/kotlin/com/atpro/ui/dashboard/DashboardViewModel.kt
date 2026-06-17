package com.atpro.ui.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.automation.FarmMode
import com.atpro.automation.LiveFarmStats
import com.atpro.automation.ServiceMode
import com.atpro.data.FarmForegroundService
import com.atpro.data.LocalRepository
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import com.atpro.golike.GolikeRepository
import com.atpro.golike.GolikeResult
import com.atpro.golike.TikTokAccountDto
import com.atpro.network.UpdateChecker
import com.atpro.network.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

// ── ViewModel ─────────────────────────────────────────────────────────────────

class DashboardViewModel(
    private val accountDao:  AccountDao,
    private val appContext:  Context,
    private val golikeRepo:  GolikeRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeAccounts()
        pollEngineState()
        pollGolikeLoginState()
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

    /** v1.2.2: Poll trạng thái đăng nhập Golike mỗi 5s để cập nhật canStart. */
    private fun pollGolikeLoginState() {
        val repo = golikeRepo ?: return
        viewModelScope.launch {
            while (true) {
                val loggedIn = repo.getSavedToken() != null
                _uiState.update { it.copy(isGolikeLoggedIn = loggedIn) }
                delay(5_000)
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

                // [v1.0.4] Bỏ polling accessibilityGranted/overlayGranted/notificationGranted.
                // Permission state đã chuyển sang ConfigViewModel.refreshPermissions().
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

    /** v1.2.1: Chuyển dashboard sang chế độ farm hoặc task. */
    fun setServiceMode(mode: ServiceMode) {
        _uiState.update { it.copy(serviceMode = mode) }
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

    /**
     * v1.2.1: Khởi động task mode — lấy danh sách acc Golike rồi gọi engine.startTask().
     */
    fun startTask() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        val state  = _uiState.value

        val inputList: List<String> = when (state.farmMode) {
            FarmMode.ALL_LOCAL     -> emptyList()
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
            Log.w("DashboardVM", "startForegroundService (task) failed: ${e.message}")
        }

        viewModelScope.launch {
            val golikeAccounts: List<TikTokAccountDto> = if (golikeRepo != null) {
                when (val r = golikeRepo.getTikTokAccounts()) {
                    is GolikeResult.Success -> r.data
                    is GolikeResult.Error   -> {
                        Log.w("DashboardVM", "Không lấy được acc Golike: ${r.message}")
                        emptyList()
                    }
                }
            } else emptyList()

            engine.startTask(state.farmMode, inputList, golikeAccounts)
        }
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

    /**
     * v1.2.3 — Bắt đầu demo nuôi tài khoản Facebook.
     * Flow: mở Facebook → lướt feed → ngẫu nhiên thích bài đăng → đóng app.
     */
    fun startFacebookNurture() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        try { appContext.startForegroundService(FarmForegroundService.buildIntent(appContext)) }
        catch (e: Exception) { Log.w("DashboardVM", "startForegroundService (facebook) failed: ${e.message}") }
        engine.startFacebookNurture()
    }

    /** v1.2.4 — Demo nuôi tài khoản X (Twitter). */
    fun startXNurture() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        try { appContext.startForegroundService(FarmForegroundService.buildIntent(appContext)) }
        catch (e: Exception) { Log.w("DashboardVM", "startForegroundService (x) failed: ${e.message}") }
        engine.startXNurture()
    }

    /** v1.2.4 — Demo nuôi tài khoản Instagram. */
    fun startInstagramNurture() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        try { appContext.startForegroundService(FarmForegroundService.buildIntent(appContext)) }
        catch (e: Exception) { Log.w("DashboardVM", "startForegroundService (instagram) failed: ${e.message}") }
        engine.startInstagramNurture()
    }

    /** v1.2.4 — Demo nuôi tài khoản Threads. */
    fun startThreadsNurture() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        try { appContext.startForegroundService(FarmForegroundService.buildIntent(appContext)) }
        catch (e: Exception) { Log.w("DashboardVM", "startForegroundService (threads) failed: ${e.message}") }
        engine.startThreadsNurture()
    }

    /** v1.2.4 — Demo nuôi tài khoản Snapchat. */
    fun startSnapchatNurture() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        try { appContext.startForegroundService(FarmForegroundService.buildIntent(appContext)) }
        catch (e: Exception) { Log.w("DashboardVM", "startForegroundService (snapchat) failed: ${e.message}") }
        engine.startSnapchatNurture()
    }

    fun pause()  { TikTokAccessibilityService.instance?.engine?.pause() }
    fun resume() { TikTokAccessibilityService.instance?.engine?.resume() }

    // ── Update checker ────────────────────────────────────────────────────────

    /**
     * Kiểm tra phiên bản mới từ GitHub. Gọi một lần khi Dashboard mở.
     * Kết quả được lưu vào `DashboardUiState.updateInfo`; UI sẽ hiển thị dialog.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateChecker.check(appContext)
            if (info != null) {
                _uiState.update { it.copy(updateInfo = info) }
                Log.i("DashboardVM", "Update available: ${info.tagName}")
            }
        }
    }

    /** Ẩn dialog cập nhật (nút "Để sau"). */
    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null, downloadProgress = -1) }
    }

    /**
     * v1.0.9 Bắt đầu cập nhật: kiểm tra quyền cài đặt APK, sau đó tải và cài tự động.
     * Nếu chưa có quyền → mở Settings để người dùng cấp, user cần bấm "Cập nhật" lại sau khi cấp.
     */
    fun startUpdate() {
        val info = _uiState.value.updateInfo ?: return

        // API 26+: cần quyền "Cài ứng dụng từ nguồn khác" từ người dùng
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            // Không thể tiếp tục — chờ user cấp quyền rồi bấm Cập nhật lại
            return
        }

        downloadAndInstall(info)
    }

    /**
     * v1.0.9 Tải APK về cache rồi mở hệ thống installer.
     * downloadProgress: -1=idle, 0–99=đang tải, -2=lỗi.
     */
    private fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = 0) }
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val dir  = File(appContext.cacheDir, "apk_updates").also { it.mkdirs() }
                    val file = File(dir, "atpro_update.apk")

                    val conn = URL(info.downloadUrl).openConnection().also {
                        it.connectTimeout = 15_000
                        it.readTimeout    = 60_000
                        it.connect()
                    }
                    val total = conn.contentLength.toLong()

                    conn.getInputStream().use { inp ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(16_384)
                            var downloaded = 0L
                            var read: Int
                            while (inp.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                                    _uiState.update { it.copy(downloadProgress = pct) }
                                }
                            }
                        }
                    }
                    file
                }

                // Chia sẻ đường dẫn file APK qua FileProvider rồi mở installer
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile,
                )
                val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(installIntent)

                // Đóng dialog sau khi mở installer thành công
                _uiState.update { it.copy(updateInfo = null, downloadProgress = -1) }

            } catch (e: Exception) {
                Log.e("DashboardVM", "Tải APK thất bại: ${e.message}")
                _uiState.update { it.copy(downloadProgress = -2) }
            }
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db    = AtProDatabase.getInstance(context.applicationContext)
            val local = LocalRepository.getInstance(context.applicationContext)
            val golike = GolikeRepository.getInstance(local)
            return DashboardViewModel(db.accountDao(), context.applicationContext, golike) as T
        }
    }
}

// ── UiState ───────────────────────────────────────────────────────────────────

data class DashboardUiState(
    val serviceConnected:  Boolean             = false,
    val isFarming:         Boolean             = false,
    val isPaused:          Boolean             = false,
    val allAccounts:       List<AccountEntity> = emptyList(),
    val activeAccounts:    List<AccountEntity> = emptyList(),
    val liveStats:         LiveFarmStats       = LiveFarmStats(),
    val farmMode:          FarmMode            = FarmMode.ALL_LOCAL,
    /** v1.2.1: Chế độ dịch vụ hiện tại — FARM hoặc TASK. Set từ ServicesScreen. */
    val serviceMode:       ServiceMode         = ServiceMode.FARM,
    val customAccounts:    String              = "",
    val startupStatus:     String              = "",
    /** v1.2.2: Trạng thái đăng nhập Golike — block start task khi chưa đăng nhập. */
    val isGolikeLoggedIn:  Boolean             = false,
    // [v1.0.5] Thông tin phiên bản mới từ GitHub — non-null = hiển thị dialog cập nhật
    val updateInfo:        UpdateInfo?         = null,
    // [v1.0.9] Tiến độ tải APK: -1=idle, 0–99=đang tải, -2=lỗi
    val downloadProgress:  Int                 = -1,
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

    // v1.2.3: FACEBOOK_NURTURE không cần farmMode/Golike — chỉ cần service connected.
    // v1.2.4: Tương tự cho X_NURTURE, INSTAGRAM_NURTURE, THREADS_NURTURE, SNAPCHAT_NURTURE.
    val isDemoMode: Boolean get() = serviceMode in listOf(
        ServiceMode.FACEBOOK_NURTURE, ServiceMode.X_NURTURE,
        ServiceMode.INSTAGRAM_NURTURE, ServiceMode.THREADS_NURTURE, ServiceMode.SNAPCHAT_NURTURE,
    )

    val canStart: Boolean get() = serviceConnected && !isFarming &&
        (serviceMode != ServiceMode.TASK || isGolikeLoggedIn) &&
        (isDemoMode || when (farmMode) {
            FarmMode.ALL_LOCAL     -> true
            FarmMode.SELECTED_LIST -> customAccounts.isNotBlank()
        })

    val startHint: String? get() = when {
        !serviceConnected ->
            "Bật Accessibility Service trước"
        serviceMode == ServiceMode.TASK && !isGolikeLoggedIn ->
            "Đăng nhập Golike trong tab Dịch vụ trước"
        !isDemoMode && farmMode == FarmMode.SELECTED_LIST && customAccounts.isBlank() ->
            "Nhập danh sách tài khoản cần nuôi"
        else -> null
    }

    /** True khi đang trong giai đoạn khởi động (có status text + đang farm). */
    val isStartingUp: Boolean get() = isFarming && startupStatus.isNotEmpty()
}
