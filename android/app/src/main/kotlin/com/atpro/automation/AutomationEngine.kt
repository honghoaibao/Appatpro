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
//
// Đặt ở đây (automation package) để AutomationEngine tự xử lý mode logic
// mà không phụ thuộc vào UI layer.
// DashboardViewModel import từ đây.
//
enum class FarmMode { ALL_LOCAL, SELECTED_LIST }

// ── Live stats ────────────────────────────────────────────────────────────────

/**
 * LiveFarmStats — live state của phiên farm đang chạy.
 * Được observe bởi DashboardViewModel qua StateFlow.
 */
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

/**
 * AutomationEngine — vòng lặp farm chính.
 *
 * Flow chuẩn (cả 2 mode):
 *
 *   Phase 1 — Init:
 *     launchTikTok → waitFeedLoad
 *     → detectCurrentAccount (vào profile tab → đọc @username → back)
 *     → openSwitchAndDiscover (settings → cuộn → nhấn chuyển đổi → parse + lưu list)
 *
 *   Phase 2 — Build farmList:
 *     ALL_LOCAL      → dùng list vừa discover từ TikTok (không cần DB sẵn)
 *     SELECTED_LIST  → dùng input list của user (dedup, chỉ giữ acc có trên thiết bị)
 *
 *   Phase 3 — Position acc đầu tiên (đang ở switch popup):
 *     TH1: currentAccount == farmList[0] → pressBack về feed luôn
 *     TH2: currentAccount != farmList[0] → click acc trong popup → kill/relaunch → waitFeedLoad
 *
 *   Phase 4 — Farm loop:
 *     Với mỗi acc (idx > 0): openSettings → cuộn → click switch → click acc → kill/relaunch → feed
 *     farmOneAccount() — chỉ chứa logic xem video/like/follow, KHÔNG switch acc
 *
 * Đảm bảo không nuôi 1 acc 2 lần: farmedSet track lowercase username đã nuôi.
 *
 * Constructor nhận [IFarmHost] và [IFarmRepository] để unit test được bằng mock.
 */
class AutomationEngine(
    private val host: IFarmHost,
    private val repo: IFarmRepository,
) {

    companion object { const val TAG = "AutomationEngine" }

    var isFarming = false; private set
    var isPaused  = false; private set

    private val _liveFarmStats = MutableStateFlow(LiveFarmStats())
    /** Observed by native DashboardViewModel. */
    val liveFarmStats: StateFlow<LiveFarmStats> = _liveFarmStats.asStateFlow()

    private var farmJob: Job? = null
    private val popup by lazy { PopupHandler(host) }

    private val screenW get() = host.screenWidth
    private val screenH get() = host.screenHeight
    var config = FarmConfig()

    // ── Control ───────────────────────────────────────────────────────────────

    /**
     * Bắt đầu farm.
     *
     * @param mode      ALL_LOCAL hoặc SELECTED_LIST
     * @param inputList Danh sách username user nhập (chỉ dùng cho SELECTED_LIST;
     *                  phải đã qua trim + removePrefix("@") + distinct() ở ViewModel)
     */
    fun startFarm(mode: FarmMode, inputList: List<String> = emptyList()) {
        if (isFarming) return
        isFarming = true; isPaused = false

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            // ── Phase 1: Launch TikTok + chờ feed ────────────
            log("🚀 Khởi động farm (mode=$mode)")
            if (!host.launchTikTok()) {
                log("❌ Không mở được TikTok")
                finishFarm("launch_failed"); return@launch
            }
            if (!waitFeedLoad()) {
                log("❌ Feed không load được")
                finishFarm("feed_timeout"); return@launch
            }

            // ── Phase 2: Đọc acc hiện tại từ profile ─────────
            // Làm trước khi mở switch popup để biết TH1 hay TH2 cho acc đầu.
            val currentAccount = detectCurrentAccount()
            log("📱 Acc đang active: @${currentAccount ?: "không xác định"}")

            // ── Phase 3: Mở switch popup → discover + lưu ────
            val (switchOpened, discoveredList) = openSwitchAndDiscover()
            if (!switchOpened) {
                log("❌ Không mở được switch popup")
                finishFarm("switch_failed"); return@launch
            }
            // Đang ở switch popup, chưa pressBack.

            // ── Phase 4: Build farmList ───────────────────────
            val farmList: List<String> = buildFarmList(mode, inputList, discoveredList)

            if (farmList.isEmpty()) {
                log("⚠️ Không có tài khoản để nuôi — thoát")
                host.pressBack()   // đóng switch popup
                delay(500)
                finishFarm("no_accounts"); return@launch
            }

            log("📋 Farm list (${farmList.size} acc): ${farmList.joinToString()}")
            LanWebSocketServer.broadcast("farmStatus", mapOf(
                "status" to "started", "total" to farmList.size))
            AtProNotificationManager.notifyFarmStarted(farmList.size)

            // ── Phase 5: Position acc đầu tiên ───────────────
            // Đang ở switch popup. TH1/TH2 dựa vào currentAccount vs farmList[0].
            val firstPositioned = positionFirstAccount(farmList.first(), currentAccount)
            if (!firstPositioned) {
                log("❌ Không thể chuyển sang acc đầu tiên @${farmList.first()}")
                finishFarm("position_failed"); return@launch
            }
            // Sau đây: đang ở feed, đúng acc farmList[0].

            // ── Phase 6: Farm loop ────────────────────────────
            val totalSecs  = farmList.size.toLong() * config.minutesPerAccount * 60L
            var elapsed    = 0L
            val farmedSet  = mutableSetOf<String>()   // lowercase, track đã nuôi

            farmList.forEachIndexed { idx, account ->
                if (!isFarming) return@forEachIndexed
                while (isPaused) delay(500)

                log("👤 [${idx + 1}/${farmList.size}] @$account")
                LanWebSocketServer.broadcast("currentAccount", mapOf(
                    "account" to account, "index" to idx + 1, "total" to farmList.size))

                if (idx > 0) {
                    // Acc tiếp theo: cần switch (đang ở feed của acc trước)
                    val switched = switchToNextAccount(account, farmedSet)
                    if (!switched) {
                        log("⚠️ Skip @$account — không switch được")
                        return@forEachIndexed
                    }
                }
                // idx == 0: đã được position ở Phase 5, vào farm ngay.

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
        stopInternal("user_stopped")
    }

    // ── Phase helpers ─────────────────────────────────────────────────────────

    /**
     * Đọc @username đang active bằng cách vào profile tab.
     * Gọi khi đang ở feed. Returns null nếu không đọc được.
     */
    private suspend fun detectCurrentAccount(): String? {
        val root       = host.getRootNode()
        val profileTab = NodeTraverser.findProfileTab(root) ?: run {
            log("⚠️ detectCurrentAccount: không tìm thấy profile tab")
            return null
        }
        host.clickNode(profileTab.node)
        delay(2000)
        val id = NodeTraverser.getCurrentAccountId(host.getRootNode())
        host.pressBack()
        delay(800)
        if (id == null) log("⚠️ detectCurrentAccount: không đọc được @username")
        return id
    }

    /**
     * Mở Settings → cuộn cho đến khi thấy nút chuyển đổi tài khoản → click.
     * Parse acc list từ popup, auto-save acc mới vào DB.
     *
     * Returns Pair(success, discoveredList).
     * Sau khi return thành công: UI đang ở switch popup (chưa pressBack).
     */
    private suspend fun openSwitchAndDiscover(): Pair<Boolean, List<String>> {
        log("⚙️ Mở Settings để đọc acc list...")
        host.openTikTokSettings()
        delay(2000)

        var switchFound = false
        repeat(8) {
            val root = host.getRootNode()
            val btn  = NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
                ?: NodeTraverser.findByText(root, "switch account",     ignoreCase = true)
                ?: NodeTraverser.findByText(root, "manage accounts",    ignoreCase = true)
            if (btn != null) {
                host.clickNode(btn.node)
                switchFound = true
                return@repeat
            }
            host.swipeSuspend(screenW / 2, (screenH * 0.7).toInt(),
                               screenW / 2, (screenH * 0.3).toInt(), 400)
            delay(700)
        }

        if (!switchFound) {
            log("❌ Không tìm thấy nút chuyển đổi tài khoản")
            return Pair(false, emptyList())
        }
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

    /**
     * Build danh sách acc cần nuôi dựa theo mode.
     *
     * ALL_LOCAL:
     *   Dùng discoveredList từ switch popup. Nếu rỗng (TikTok không dùng @username)
     *   thì fallback lấy từ DB — trường hợp này rất hiếm.
     *
     * SELECTED_LIST:
     *   Dùng inputList của user (đã dedup ở ViewModel).
     *   Không filter theo discoveredList để tránh bỏ sót acc chưa hiển thị @username.
     */
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
        FarmMode.SELECTED_LIST -> inputList   // đã dedup ở ViewModel
    }

    /**
     * Xử lý acc đầu tiên khi đang ở switch popup.
     *
     * TH1: currentAccount == target → pressBack về feed (không cần switch)
     * TH2: currentAccount != target → click acc trong popup → kill/relaunch → feed
     *
     * Returns true nếu đang ở feed của đúng acc target sau khi return.
     */
    private suspend fun positionFirstAccount(
        target:         String,
        currentAccount: String?,
    ): Boolean {
        val isCurrent = currentAccount != null &&
            currentAccount.equals(target, ignoreCase = true)

        return if (isCurrent) {
            // TH1: acc hiện tại đúng rồi, về feed ngay
            log("✅ TH1: @$target đang active — về feed")
            host.pressBack()
            delay(800)
            // Đảm bảo đang ở feed
            if (!NodeTraverser.hasNavBar(host.getRootNode())) {
                recoverToFeed()
            } else true
        } else {
            // TH2: cần switch sang acc đầu tiên
            log("🔀 TH2: switch sang @$target (current=@${currentAccount ?: "?"})")
            clickAccountInPopupAndRelaunch(target)
        }
    }

    /**
     * Switch sang acc tiếp theo khi đang ở feed.
     * Không switch nếu acc đã có trong farmedSet (tránh nuôi lại).
     *
     * Returns true nếu đang ở feed của đúng acc target sau khi return.
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

        var switchFound = false
        repeat(8) {
            val root = host.getRootNode()
            val btn  = NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
                ?: NodeTraverser.findByText(root, "switch account",     ignoreCase = true)
                ?: NodeTraverser.findByText(root, "manage accounts",    ignoreCase = true)
            if (btn != null) {
                host.clickNode(btn.node)
                switchFound = true
                return@repeat
            }
            host.swipeSuspend(screenW / 2, (screenH * 0.7).toInt(),
                               screenW / 2, (screenH * 0.3).toInt(), 400)
            delay(700)
        }

        if (!switchFound) {
            log("❌ Không tìm thấy nút chuyển tài khoản khi switch @$target")
            return false
        }
        delay(1500)

        return clickAccountInPopupAndRelaunch(target)
    }

    /**
     * Click acc target trong switch popup → kill TikTok → relaunch → chờ feed.
     * Dùng chung cho TH2 (acc đầu) và switchToNextAccount (acc tiếp theo).
     *
     * Returns true nếu feed load thành công.
     */
    private suspend fun clickAccountInPopupAndRelaunch(target: String): Boolean {
        // Thử tìm và click acc trong popup (retry 3 lần)
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
            host.pressBack()   // đóng popup, không để UI bị kẹt
            delay(500)
            return false
        }

        // Kill + relaunch để TikTok load sạch acc mới
        host.killTikTok()
        delay(2000)
        host.launchTikTok()
        if (!waitFeedLoad()) {
            log("❌ Feed không load sau khi switch @$target")
            return false
        }

        // Verify + save (BUG-FARM-006 fix)
        verifyAndSaveCurrentAccount(target)
        return true
    }

    // ── Farm one account ──────────────────────────────────────────────────────

    /**
     * Farm một acc đã được position sẵn tại feed.
     * KHÔNG gọi switch ở đây — switching xảy ra hoàn toàn bên ngoài.
     */
    private suspend fun farmOneAccount(
        account:       String,
        idx:           Int,
        total:         Int,
        totalSecsLeft: Long,
    ): Triple<Int, Int, Int> {
        val sessionId = repo.startSession(account)
        val deadline  = System.currentTimeMillis() + config.minutesPerAccount * 60_000L

        var likes = 0; var follows = 0; var videos = 0

        while (System.currentTimeMillis() < deadline && isFarming) {
            while (isPaused) delay(500)

            val sessionSecsLeft = maxOf(0L, (deadline - System.currentTimeMillis()) / 1000L)

            OverlayFarmMonitor.update(
                accountIndex    = idx + 1,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecsLeft,
                totalSecsLeft   = maxOf(0L, totalSecsLeft - (config.minutesPerAccount * 60L - sessionSecsLeft)),
                action          = if (videos == 0) "Tải feed..." else "Xem video #$videos",
            )

            if (NodeTraverser.detectCheckpoint(host.getRootNode())) {
                log("⚠️ Checkpoint: @$account")
                repo.setCheckpoint(account, true)
                AtProNotificationManager.notifyCheckpoint(account)
                break
            }

            // Popup phải xử lý TRƯỚC isLost() — BUG-FARM-005
            val popupResult = popup.handleIfPresent()
            if (popupResult.handled) continue

            if (isLost()) { recoverToFeed(); continue }

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

    /**
     * Sau khi feed load thành công sau switch:
     * vào profile tab → đọc @username thật → auto-save nếu chưa có → back về feed.
     * (BUG-FARM-006 fix: parseAccountList trả rỗng với display name)
     */
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
            host.pressBack(); delay(800)
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
                // Xử lý popup có thể xuất hiện ngay khi feed load
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun onEvent(event: AccessibilityEvent) {}

    private fun finishFarm(reason: String) {
        host.hideFarmOverlay()
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
