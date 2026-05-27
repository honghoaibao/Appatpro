package com.atpro.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.atpro.accessibility.NodeTraverser
import com.atpro.automation.popup.PopupHandler
import com.atpro.network.LanWebSocketServer
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.notification.AtProNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

// ── Farm mode ─────────────────────────────────────────────────────────────────
enum class FarmMode { ALL_LOCAL, SELECTED_LIST }

// ── Live stats ────────────────────────────────────────────────────────────────

data class LiveFarmStats(
    val account:       String = "",
    val index:         Int    = 0,
    val total:         Int    = 0,
    val videos:        Int    = 0,
    val likes:         Int    = 0,
    val follows:       Int    = 0,
    val remainingSecs: Int    = 0,
)

// ── Engine ────────────────────────────────────────────────────────────────────

class AutomationEngine(
    private val host: IFarmHost,
    private val repo: IFarmRepository,
) {

    companion object { const val TAG = "AutomationEngine" }

    var isFarming = false; private set
    var isPaused  = false; private set

    private val _liveFarmStats = MutableStateFlow(LiveFarmStats())
    val liveFarmStats: StateFlow<LiveFarmStats> = _liveFarmStats.asStateFlow()

    // ── [THÊM] Startup status để hiển thị popup trạng thái ─────────────────
    private val _startupStatus = MutableStateFlow("")
    /** Observed by DashboardViewModel để hiển thị popup trạng thái khởi động. */
    val startupStatus: StateFlow<String> = _startupStatus.asStateFlow()

    private var farmJob: Job? = null
    private val popup by lazy { PopupHandler(host) }

    private val screenW get() = host.screenWidth
    private val screenH get() = host.screenHeight
    var config = FarmConfig()

    // ── Control ───────────────────────────────────────────────────────────────

    fun startFarm(mode: FarmMode, inputList: List<String> = emptyList()) {
        if (isFarming) return
        isFarming = true; isPaused = false

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            // ── Phase 1: Launch TikTok + chờ feed ────────────
            log("🚀 Khởi động farm (mode=$mode)")
            setStatus("🚀 Đang mở TikTok...")
            if (!host.launchTikTok()) {
                log("❌ Không mở được TikTok")
                setStatus("")
                finishFarm("launch_failed"); return@launch
            }

            setStatus("⏳ Chờ feed tải...")
            if (!waitFeedLoad()) {
                log("❌ Feed không load được")
                setStatus("")
                finishFarm("feed_timeout"); return@launch
            }

            // ── Phase 2: Đọc acc hiện tại từ profile ─────────
            setStatus("🔍 Đang đọc tài khoản hiện tại...")
            val currentAccount = detectCurrentAccount()
            log("📱 Acc đang active: @${currentAccount ?: "không xác định"}")

            // ── Phase 3: Mở switch popup → discover + lưu ────
            setStatus("⚙️ Đang mở Cài đặt TikTok...")
            val (switchOpened, discoveredList) = openSwitchAndDiscover()
            if (!switchOpened) {
                log("❌ Không mở được switch popup")
                setStatus("")
                finishFarm("switch_failed"); return@launch
            }

            // ── Phase 4: Build farmList ───────────────────────
            setStatus("📋 Đang phân tích danh sách tài khoản...")
            val farmList: List<String> = buildFarmList(mode, inputList, discoveredList)

            if (farmList.isEmpty()) {
                log("⚠️ Không có tài khoản để nuôi — thoát")
                setStatus("")
                host.pressBack()
                delay(500)
                finishFarm("no_accounts"); return@launch
            }

            log("📋 Farm list (${farmList.size} acc): ${farmList.joinToString()}")
            LanWebSocketServer.broadcast("farmStatus", mapOf(
                "status" to "started", "total" to farmList.size))
            AtProNotificationManager.notifyFarmStarted(farmList.size)

            // ── Phase 5: Position acc đầu tiên ───────────────
            setStatus("🔀 Đang chuyển sang tài khoản đầu tiên...")
            val firstPositioned = positionFirstAccount(farmList.first(), currentAccount)
            if (!firstPositioned) {
                log("❌ Không thể chuyển sang acc đầu tiên @${farmList.first()}")
                setStatus("")
                finishFarm("position_failed"); return@launch
            }

            // Xoá status popup — bắt đầu farm thật sự
            setStatus("")

            // ── Phase 6: Farm loop ────────────────────────────
            val totalSecs  = farmList.size.toLong() * config.minutesPerAccount * 60L
            var elapsed    = 0L
            val farmedSet  = mutableSetOf<String>()

            farmList.forEachIndexed { idx, account ->
                if (!isFarming) return@forEachIndexed
                while (isPaused) delay(500)

                log("👤 [${idx + 1}/${farmList.size}] @$account")
                LanWebSocketServer.broadcast("currentAccount", mapOf(
                    "account" to account, "index" to idx + 1, "total" to farmList.size))

                if (idx > 0) {
                    val switched = switchToNextAccount(account, farmedSet)
                    if (!switched) {
                        log("⚠️ Skip @$account — không switch được")
                        return@forEachIndexed
                    }
                }

                farmedSet.add(account.lowercase())

                val r = farmOneAccount(account, idx, farmList.size, totalSecs - elapsed)
                elapsed += config.minutesPerAccount * 60L

                AtProNotificationManager.notifySessionDone(account, r.first, r.second, r.third)

                if (config.enableRestBetweenAccounts && idx < farmList.size - 1) {
                    log("😴 Nghỉ ${config.restDurationMinutes}m trước acc tiếp")
                    delay(config.restDurationMinutes * 60_000L)
                }
            }

            AtProNotificationManager.notifyFarmCompleted(
                farmList.size, 0, 0, 0, farmList.size * config.minutesPerAccount)
            finishFarm("completed")
        }
    }

    fun pause()  { isPaused = true;  LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to true)) }
    fun resume() { isPaused = false; LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to false)) }

    fun stop() {
        farmJob?.cancel()
        host.hideFarmOverlay()
        setStatus("")
        stopInternal("user_stopped")
    }

    // ── Phase helpers ─────────────────────────────────────────────────────────

    private suspend fun detectCurrentAccount(): String? {
        val root       = host.getRootNode()
        val profileTab = NodeTraverser.findProfileTab(root) ?: run {
            log("⚠️ detectCurrentAccount: không tìm thấy profile tab")
            return null
        }
        host.clickNode(profileTab.node)
        delay(2000)
        val id = NodeTraverser.getCurrentAccountId(host.getRootNode())
        // [FIX BUG 1] Dùng Home tab để về feed thay vì pressBack().
        // pressBack() trên tab chính TikTok (Profile/Home) không navigate
        // về feed — nó thoát hẳn app vì không có back stack tại màn chính.
        navigateToFeedTab()
        if (id == null) log("⚠️ detectCurrentAccount: không đọc được @username")
        return id
    }

    /**
     * Mở Settings → cuộn cho đến khi thấy nút chuyển đổi tài khoản.
     *
     * [FIX BUG SCROLL] Tách scroll và click thành 2 giai đoạn:
     *   Giai đoạn 1: Chỉ cuộn + tìm, KHÔNG click ngay khi thấy.
     *                return@repeat trong repeat{} chỉ là "continue", không phải "break" —
     *                nếu click bên trong, vòng lặp vẫn tiếp tục và cuộn thêm trên popup.
     *   Giai đoạn 2: Sau khi thoát repeat, delay cho màn hình đứng hẳn, tìm lại fresh
     *                node (tránh stale ref) rồi mới click.
     */
    private suspend fun openSwitchAndDiscover(): Pair<Boolean, List<String>> {
        log("⚙️ Mở Settings để đọc acc list...")
        setStatus("⚙️ Đang mở Cài đặt TikTok...")
        host.openTikTokSettings()
        delay(2000)

        // Giai đoạn 1: Cuộn + tìm, KHÔNG click bên trong repeat
        var switchFound = false
        repeat(8) {
            if (switchFound) return@repeat          // Guard: thoát sớm nếu đã tìm thấy
            val root = host.getRootNode()
            val btn  = NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
                ?: NodeTraverser.findByText(root, "switch account",     ignoreCase = true)
                ?: NodeTraverser.findByText(root, "manage accounts",    ignoreCase = true)
            if (btn != null) {
                switchFound = true
                return@repeat                       // Dừng tìm, KHÔNG cuộn thêm, KHÔNG click
            }
            setStatus("🔍 Đang tìm nút chuyển đổi tài khoản...")
            host.swipeSuspend(screenW / 2, (screenH * 0.7).toInt(),
                               screenW / 2, (screenH * 0.3).toInt(), 400)
            delay(700)
        }

        if (!switchFound) {
            log("❌ Không tìm thấy nút chuyển đổi tài khoản")
            return Pair(false, emptyList())
        }

        // Giai đoạn 2: Đợi màn hình đứng hẳn → tìm lại fresh node → click
        delay(800)
        setStatus("🔄 Đang mở danh sách chuyển đổi tài khoản...")
        val freshRoot = host.getRootNode()
        val freshBtn  = NodeTraverser.findByText(freshRoot, "chuyển đổi tài khoản", ignoreCase = true)
            ?: NodeTraverser.findByText(freshRoot, "switch account",     ignoreCase = true)
            ?: NodeTraverser.findByText(freshRoot, "manage accounts",    ignoreCase = true)

        if (freshBtn == null) {
            log("❌ Nút chuyển đổi biến mất sau delay — thử lại lần sau")
            return Pair(false, emptyList())
        }
        host.clickNode(freshBtn.node)
        delay(1500)

        val popupRoot    = host.getRootNode()
        val discovered   = NodeTraverser.parseAccountList(popupRoot)
        if (discovered.isNotEmpty()) {
            log("📋 Discover ${discovered.size} acc từ switch popup")
            autoSaveAccounts(discovered)
        } else {
            log("⚠️ parseAccountList rỗng — TikTok có thể hiển thị display name thay @username")
        }

        return Pair(true, discovered)
    }

    private suspend fun buildFarmList(
        mode:           FarmMode,
        inputList:      List<String>,
        discoveredList: List<String>,
    ): List<String> = when (mode) {
        FarmMode.ALL_LOCAL -> {
            if (discoveredList.isNotEmpty()) {
                discoveredList
            } else {
                log("⚠️ ALL_LOCAL: discoveredList rỗng, fallback DB")
                repo.getAccounts()
                    .filter { it.status == "active" && !it.checkpoint }
                    .map { it.username }
            }
        }
        FarmMode.SELECTED_LIST -> inputList
    }

    private suspend fun positionFirstAccount(
        target:         String,
        currentAccount: String?,
    ): Boolean {
        val isCurrent = currentAccount != null &&
            currentAccount.equals(target, ignoreCase = true)

        return if (isCurrent) {
            log("✅ TH1: @$target đang active — về feed")
            host.pressBack()
            delay(800)
            if (!NodeTraverser.hasNavBar(host.getRootNode())) {
                recoverToFeed()
            } else true
        } else {
            log("🔀 TH2: switch sang @$target (current=@${currentAccount ?: "?"})")
            clickAccountInPopupAndRelaunch(target)
        }
    }

    /**
     * Switch sang acc tiếp theo khi đang ở feed.
     *
     * [FIX BUG SCROLL] Áp dụng cùng fix như openSwitchAndDiscover:
     * Tách scroll (repeat) và click (sau repeat + delay) để tránh cuộn thêm sau khi click.
     */
    private suspend fun switchToNextAccount(
        target:    String,
        farmedSet: Set<String>,
    ): Boolean {
        if (target.lowercase() in farmedSet) {
            log("⚠️ @$target đã nuôi trong phiên này — skip")
            return false
        }

        log("🔄 Chuyển acc → @$target")
        host.openTikTokSettings()
        delay(2000)

        // Giai đoạn 1: Chỉ cuộn + tìm, KHÔNG click bên trong repeat
        var switchFound = false
        repeat(8) {
            if (switchFound) return@repeat          // Guard
            val root = host.getRootNode()
            val btn  = NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
                ?: NodeTraverser.findByText(root, "switch account",     ignoreCase = true)
                ?: NodeTraverser.findByText(root, "manage accounts",    ignoreCase = true)
            if (btn != null) {
                switchFound = true
                return@repeat                       // Dừng tìm, KHÔNG cuộn, KHÔNG click
            }
            host.swipeSuspend(screenW / 2, (screenH * 0.7).toInt(),
                               screenW / 2, (screenH * 0.3).toInt(), 400)
            delay(700)
        }

        if (!switchFound) {
            log("❌ Không tìm thấy nút chuyển tài khoản khi switch @$target")
            return false
        }

        // Giai đoạn 2: Đợi settle → tìm fresh node → click
        delay(800)
        val freshRoot = host.getRootNode()
        val freshBtn  = NodeTraverser.findByText(freshRoot, "chuyển đổi tài khoản", ignoreCase = true)
            ?: NodeTraverser.findByText(freshRoot, "switch account",     ignoreCase = true)
            ?: NodeTraverser.findByText(freshRoot, "manage accounts",    ignoreCase = true)

        if (freshBtn == null) {
            log("❌ Nút chuyển đổi biến mất sau delay khi switch @$target")
            return false
        }
        host.clickNode(freshBtn.node)
        delay(1500)

        return clickAccountInPopupAndRelaunch(target)
    }

    private suspend fun clickAccountInPopupAndRelaunch(target: String): Boolean {
        var clicked = false
        for (attempt in 1..3) {
            val node = NodeTraverser.findByText(host.getRootNode(), target, ignoreCase = true)
            if (node != null) {
                host.clickNode(node.node)
                delay((config.delayAfterSwitchClick * 1000).toLong())
                clicked = true
                break
            }
            delay(1500)
        }

        if (!clicked) {
            log("❌ Không tìm thấy @$target trong switch popup")
            host.pressBack()
            delay(500)
            return false
        }

        host.killTikTok()
        delay(2000)
        host.launchTikTok()
        if (!waitFeedLoad()) {
            log("❌ Feed không load sau khi switch @$target")
            return false
        }

        verifyAndSaveCurrentAccount(target)
        return true
    }

    // ── Farm one account ──────────────────────────────────────────────────────

    /**
     * Farm một acc đã được position sẵn tại feed.
     *
     * [FIX BUG FEED STUCK] Hai fix:
     *   1. Thêm delay ổn định 2s trước khi bắt đầu vòng lặp, tránh accessibility tree
     *      chưa kịp build xong sau khi feed load → isLost() false positive.
     *   2. Thêm consecutiveLostCount: chỉ gọi recoverToFeed() khi isLost() xảy ra
     *      liên tiếp >= 3 lần (tránh 1 lần random false positive trigger toàn bộ recover).
     */
    private suspend fun farmOneAccount(
        account:       String,
        idx:           Int,
        total:         Int,
        totalSecsLeft: Long,
    ): Triple<Int, Int, Int> {
        val sessionId = repo.startSession(account)

        // [FIX 2] Đợi accessibility tree ổn định sau khi feed vừa load
        delay(2000)

        val deadline  = System.currentTimeMillis() + config.minutesPerAccount * 60_000L

        var likes = 0; var follows = 0; var videos = 0
        var consecutiveLostCount = 0   // [FIX 2] Đếm số lần isLost() liên tiếp

        while (System.currentTimeMillis() < deadline && isFarming) {
            while (isPaused) delay(500)

            val sessionSecsLeft = maxOf(0L, (deadline - System.currentTimeMillis()) / 1000L)

            // [FIX 2] Action text rõ ràng hơn: "Chuẩn bị..." thay vì "Tải feed..."
            // vì feed đã được load trước khi farmOneAccount() được gọi.
            OverlayFarmMonitor.update(
                accountIndex    = idx + 1,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecsLeft,
                totalSecsLeft   = maxOf(0L, totalSecsLeft - (config.minutesPerAccount * 60L - sessionSecsLeft)),
                action          = when {
                    videos == 0 -> "⏳ Chuẩn bị xem video..."
                    else        -> "▶ Xem video #$videos"
                },
            )

            if (NodeTraverser.detectCheckpoint(host.getRootNode())) {
                log("⚠️ Checkpoint: @$account")
                repo.setCheckpoint(account, true)
                AtProNotificationManager.notifyCheckpoint(account)
                break
            }

            val popupResult = popup.handleIfPresent()
            if (popupResult.handled) { consecutiveLostCount = 0; continue }

            // [FIX v1.0.4] Chỉ recover khi isLost() xảy ra LIÊN TIẾP >= 3 lần.
            // Sau khi recover: nếu thất bại → break (tránh vòng lặp vô hạn pressBack).
            // Nếu thành công → delay 2s ổn định trước khi tiếp tục farming.
            if (isLost()) {
                consecutiveLostCount++
                if (consecutiveLostCount >= 3) {
                    log("⚠️ isLost() liên tiếp $consecutiveLostCount lần → recoverToFeed()")
                    val recovered = recoverToFeed()
                    consecutiveLostCount = 0
                    if (!recovered) {
                        log("❌ recoverToFeed() thất bại — kết thúc sớm @$account")
                        break
                    }
                    delay(2000) // Chờ accessibility tree ổn định sau khi khôi phục
                } else {
                    delay(1000)  // Chờ accessibility tree update
                }
                continue
            }
            consecutiveLostCount = 0  // Reset khi feed ổn định

            if (config.skipLive && NodeTraverser.detectLive(host.getRootNode())) {
                swipeNext(); continue
            }

            val wMin = (config.videoWatchTimeMin  * 1000).toLong()
            val wMax = (config.videoWatchTimeMax * 1000).toLong()
            delay(if (wMax > wMin) Random.nextLong(wMin, wMax) else wMin)
            videos++

            OverlayFarmMonitor.update(idx + 1, total, account, sessionSecsLeft, 0L, "❤️ Thích")
            if (Random.nextFloat() < config.likeRate)   { doLike();   likes++ }
            if (Random.nextFloat() < config.followRate) { doFollow(); follows++ }
            swipeNext()

            _liveFarmStats.update {
                LiveFarmStats(
                    account       = account,
                    index         = idx + 1,
                    total         = total,
                    videos        = videos,
                    likes         = likes,
                    follows       = follows,
                    remainingSecs = sessionSecsLeft.toInt(),
                )
            }

            LanWebSocketServer.broadcast("liveStats", mapOf(
                "account"      to account,
                "videos"       to videos,
                "likes"        to likes,
                "follows"      to follows,
                "remainingSecs" to sessionSecsLeft.toInt(),
            ))
        }

        repo.closeSession(sessionId, account, likes, follows, videos)
        return Triple(likes, follows, videos)
    }

    // ── Verify + save sau switch ──────────────────────────────────────────────

    private suspend fun verifyAndSaveCurrentAccount(expected: String) {
        val root       = host.getRootNode()
        val profileTab = NodeTraverser.findProfileTab(root)
        if (profileTab != null) {
            host.clickNode(profileTab.node)
            delay(2000)
            val actualId = NodeTraverser.getCurrentAccountId(host.getRootNode())
            if (actualId != null) {
                if (!actualId.equals(expected, ignoreCase = true)) {
                    log("⚠️ Account mismatch: expected=@$expected actual=@$actualId")
                } else {
                    log("🪪 Xác nhận: @$actualId")
                }
                autoSaveAccounts(listOf(actualId))
            } else {
                log("⚠️ Không đọc được @username từ profile — fallback save @$expected")
                autoSaveAccounts(listOf(expected))
            }
            // [FIX BUG 1] Click Home tab thay vì pressBack để về feed
            navigateToFeedTab()
        } else {
            log("⚠️ Không tìm thấy profile tab — fallback save @$expected")
            autoSaveAccounts(listOf(expected))
        }
    }

    private suspend fun autoSaveAccounts(accounts: List<String>) {
        val existing = repo.getAccounts().map { it.username }.toSet()
        accounts.forEach { acc ->
            if (acc !in existing) {
                repo.addAccount(acc)
                log("💾 Auto-saved: @$acc")
                LanWebSocketServer.broadcast("accountDiscovered", mapOf("username" to acc))
            }
        }
        LanWebSocketServer.broadcast("accountsUpdated", mapOf("count" to accounts.size))
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private suspend fun doLike() {
        val cx = screenW / 2; val cy = (screenH * 0.55).toInt()
        host.clickSuspend(cx, cy); delay(100); host.clickSuspend(cx, cy)
        delay((config.delayAfterLike * 1000).toLong())
    }

    private suspend fun doFollow() {
        val root = host.getRootNode()
        val btn  = NodeTraverser.findByText(root, "follow")
            ?: NodeTraverser.findByText(root, "theo dõi") ?: return
        host.clickNode(btn.node)
        delay((config.delayAfterFollow * 1000).toLong())
    }

    private suspend fun swipeNext() {
        host.swipeSuspend(screenW / 2, (screenH * 0.75).toInt(),
                          screenW / 2, (screenH * 0.25).toInt(), 350)
        delay(Random.nextLong(800, 1500))
    }

    private suspend fun waitFeedLoad(): Boolean {
        repeat(6) { i ->
            delay(3000)
            if (NodeTraverser.hasNavBar(host.getRootNode())) {
                repeat(3) { popup.handleIfPresent(); delay(600) }
                return true
            }
            log("   Chờ feed ${i + 1}/6...")
        }
        return false
    }

    private fun isLost() = !NodeTraverser.hasNavBar(host.getRootNode())

    private suspend fun recoverToFeed(): Boolean {
        repeat(config.maxBackAttempts) {
            host.pressBack(); delay(1200)
            if (NodeTraverser.hasNavBar(host.getRootNode())) return true
        }
        host.killTikTok(); delay(2000)
        host.launchTikTok()
        return waitFeedLoad()
    }

    // ── Startup status helper ─────────────────────────────────────────────────

    /** Emit trạng thái khởi động để DashboardScreen hiển thị popup. */
    private fun setStatus(msg: String) {
        _startupStatus.update { msg }
        // [FIX BUG 2] Cũng cập nhật system overlay để thấy được khi TikTok ở foreground.
        // StartupStatusDialog trong AT Pro Activity không hiển thị khi TikTok đang active,
        // nhưng OverlayFarmMonitor (TYPE_APPLICATION_OVERLAY) hiển thị được trên mọi app.
        OverlayFarmMonitor.setStartupStatus(msg)
    }

    /**
     * [FIX BUG 1] Điều hướng về Feed/Home tab sau khi đang ở Profile tab.
     *
     * pressBack() trên màn hình chính TikTok (tab chính, không có back stack)
     * sẽ thoát hẳn ứng dụng thay vì về feed.
     * Hàm này click Home tab hoặc swipe nếu không tìm thấy tab.
     *
     * [FIX v1.0.4] Tăng delay sau khi click Home tab từ 1000ms → 2000ms.
     * 1000ms không đủ để accessibility tree phản ánh trạng thái feed mới,
     * dẫn đến hasNavBar() trả false trong farmOneAccount() gây vòng lặp back.
     */
    private suspend fun navigateToFeedTab() {
        val root    = host.getRootNode()
        val homeTab = NodeTraverser.findHomeTab(root)
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(2000) // [FIX v1.0.4] 1000 → 2000ms
        } else {
            // Fallback: vẫn pressBack + kiểm tra ngay sau đó
            host.pressBack()
            delay(1500) // [FIX v1.0.4] 1000 → 1500ms
            // Nếu TikTok thoát (no NavBar), relaunch lại
            if (!NodeTraverser.hasNavBar(host.getRootNode())) {
                log("⚠️ navigateToFeedTab: pressBack thoát TikTok — relaunch")
                host.launchTikTok()
                waitFeedLoad()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onEvent(event: AccessibilityEvent) {}

    private fun finishFarm(reason: String) {
        host.hideFarmOverlay()
        setStatus("")
        stopInternal(reason)
    }

    private fun stopInternal(reason: String) {
        _liveFarmStats.update { LiveFarmStats() }
        isFarming = false
        LanWebSocketServer.broadcast("farmStatus", mapOf("status" to "stopped", "reason" to reason))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
        host.scope.launch { repo.log(msg) }
    }
}
