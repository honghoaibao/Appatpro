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

/**
 * AutomationEngine — vòng lặp farm chính.
 *
 * Constructor nhận [IFarmHost] và [IFarmRepository] thay vì
 * TikTokAccessibilityService trực tiếp. Điều này cho phép unit test
 * bằng MockK mà không cần Android runtime.
 *
 * Lifecycle:
 *   TikTokAccessibilityService.onServiceConnected()
 *     → engine = AutomationEngine(this, LocalRepository.getInstance(this))
 *
 * Threading:
 *   - Control methods (start/stop/pause/resume) → any thread (no-suspend)
 *   - farmJob chạy trên host.scope (IO/Default trong production, TestScope trong tests)
 */
/**
 * LiveFarmStats — live state của phiên farm đang chạy.
 * Được observe bởi DashboardViewModel qua StateFlow.
 */
data class LiveFarmStats(
    val account:      String = "",
    val index:        Int    = 0,
    val total:        Int    = 0,
    val videos:       Int    = 0,
    val likes:        Int    = 0,
    val follows:      Int    = 0,
    val remainingSecs: Int   = 0,
)

class AutomationEngine(
    private val host: IFarmHost,
    private val repo: IFarmRepository,
) {

    companion object { const val TAG = "AutomationEngine" }

    var isFarming = false; private set
    var isPaused  = false; private set   // exposed for test assertions

    private val _liveFarmStats = MutableStateFlow(LiveFarmStats())
    /** Observed by native DashboardViewModel — updated each video loop iteration. */
    val liveFarmStats: StateFlow<LiveFarmStats> = _liveFarmStats.asStateFlow()

    private var farmJob: Job? = null
    private val popup by lazy { PopupHandler(host) }

    private val screenW get() = host.screenWidth
    private val screenH get() = host.screenHeight
    var config = FarmConfig()

    // ── Control ───────────────────────────────────────────────

    fun startFarm(accounts: List<String>) {
        if (isFarming) return
        isFarming = true; isPaused = false

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            log("🚀 Farm ${accounts.size} acc")
            LanWebSocketServer.broadcast("farmStatus", mapOf("status" to "started", "total" to accounts.size))
            AtProNotificationManager.notifyFarmStarted(accounts.size)

            host.showFarmOverlay()

            val totalSecs = accounts.size.toLong() * config.minutesPerAccount * 60L
            var elapsed   = 0L

            accounts.forEachIndexed { idx, account ->
                if (!isFarming) return@forEachIndexed
                while (isPaused) delay(500)

                log("👤 [${idx+1}/${accounts.size}] @$account")
                LanWebSocketServer.broadcast("currentAccount", mapOf(
                    "account" to account, "index" to idx+1, "total" to accounts.size))

                val r = farmOneAccount(account, idx, accounts.size, totalSecs - elapsed)
                elapsed += config.minutesPerAccount * 60L

                AtProNotificationManager.notifySessionDone(account, r.first, r.second, r.third)

                if (config.enableRestBetweenAccounts && idx < accounts.size - 1) {
                    log("😴 ${config.restDurationMinutes}m")
                    delay(config.restDurationMinutes * 60_000L)
                }
            }

            host.hideFarmOverlay()
            AtProNotificationManager.notifyFarmCompleted(
                accounts.size, 0, 0, 0, accounts.size * config.minutesPerAccount)
            stopInternal("completed")
        }
    }

    fun pause()  { isPaused = true;  LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to true)) }
    fun resume() { isPaused = false; LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to false)) }

    fun stop() {
        farmJob?.cancel()
        host.hideFarmOverlay()
        stopInternal("user_stopped")
    }

    // ── Farm one account ──────────────────────────────────────

    private suspend fun farmOneAccount(
        account:       String,
        idx:           Int,
        total:         Int,
        totalSecsLeft: Long,
    ): Triple<Int, Int, Int> {
        val sessionId = repo.startSession(account)
        val deadline  = System.currentTimeMillis() + config.minutesPerAccount * 60_000L

        val switched = switchToAccountViaSettings(account)
        if (!switched) {
            log("❌ Switch fail: @$account")
            repo.setCheckpoint(account, true)
            AtProNotificationManager.notifyCheckpoint(account)
            repo.closeSession(sessionId, account, 0, 0, 0)
            return Triple(0, 0, 0)
        }

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
                AtProNotificationManager.notifyCheckpoint(account); break
            }
            // BUG-FARM-005: popup phải được xử lý TRƯỚC isLost().
            // Popup full-screen → hasNavBar() = false → isLost() = true →
            // recoverToFeed() kill TikTok thay vì dismiss popup → loop vô tận.
            // Nếu popup được handled → continue để re-evaluate state sau khi dismiss.
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

            OverlayFarmMonitor.update(idx+1, total, account, sessionSecsLeft, 0L, "❤️ Thích")
            if (Random.nextFloat() < config.likeRate)   { doLike();   likes++ }
            if (Random.nextFloat() < config.followRate) { doFollow(); follows++ }
            swipeNext()

            // Update native StateFlow — consumed by DashboardViewModel
            _liveFarmStats.update { LiveFarmStats(
                account      = account,
                index        = idx + 1,
                total        = total,
                videos       = videos,
                likes        = likes,
                follows      = follows,
                remainingSecs = sessionSecsLeft.toInt(),
            )}

            LanWebSocketServer.broadcast("liveStats", mapOf(
                "account" to account, "videos" to videos,
                "likes" to likes, "follows" to follows,
                "remainingSecs" to sessionSecsLeft.toInt(),
            ))
        }

        repo.closeSession(sessionId, account, likes, follows, videos)
        return Triple(likes, follows, videos)
    }

    // ── Account switch flow ───────────────────────────────────

    private suspend fun switchToAccountViaSettings(account: String): Boolean {
        if (!host.launchTikTok()) return false
        if (!waitFeedLoad()) return false

        log("⚙️ Mở Settings...")
        host.openTikTokSettings()
        delay(2000)

        var switchFound = false
        repeat(8) {
            val root = host.getRootNode()
            val btn  = NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
                ?: NodeTraverser.findByText(root, "switch account",     ignoreCase = true)
                ?: NodeTraverser.findByText(root, "manage accounts",    ignoreCase = true)
            if (btn != null) {
                host.clickNode(btn.node); switchFound = true; return@repeat
            }
            host.swipeSuspend(screenW/2, (screenH*0.7).toInt(), screenW/2, (screenH*0.3).toInt(), 400)
            delay(700)
        }

        if (!switchFound) { log("❌ Không tìm thấy nút chuyển tài khoản"); return false }
        delay(1500)

        val popupRoot = host.getRootNode()
        val allAccounts = NodeTraverser.parseAccountList(popupRoot)
        if (allAccounts.isNotEmpty()) {
            log("📋 ${allAccounts.size} tài khoản — tự lưu")
            autoSaveAccounts(allAccounts)
        }

        for (attempt in 1..3) {
            val node = NodeTraverser.findByText(host.getRootNode(), account, ignoreCase = true)
            if (node != null) {
                host.clickNode(node.node)
                delay((config.delayAfterSwitchClick * 1000).toLong())
                break
            }
            delay(1500)
            if (attempt == 3) { log("❌ Không tìm thấy @$account"); return false }
        }

        host.killTikTok(); delay(2000)
        host.launchTikTok()
        if (!waitFeedLoad()) return false

        // BUG-FARM-006: SELECTED_LIST mode không lưu account vào DB.
        // parseAccountList() trả empty khi TikTok dùng display name thay @username,
        // hoặc chỉ 1 account logged in. Fix: sau feed load, đọc @username thật từ
        // profile page để vừa verify đúng account vừa auto-save vào DB.
        verifyAndSaveCurrentAccount(account)
        return true
    }

    /**
     * Sau khi switch account thành công + feed đã load:
     * 1. Tìm profile tab trên nav bar → click để vào profile page
     * 2. Đọc @username thật từ profile (getCurrentAccountId)
     * 3. Auto-save vào DB nếu chưa có — "check id + lưu acc" mà user mong đợi
     *
     * Fallback: nếu profile tab hoặc @username null → save expected name để
     * đảm bảo tối thiểu account được ghi nhận trong DB.
     */
    private suspend fun verifyAndSaveCurrentAccount(expected: String) {
        val root = host.getRootNode()
        val profileTab = NodeTraverser.findProfileTab(root)
        if (profileTab != null) {
            host.clickNode(profileTab.node)
            delay(2000)   // đợi profile page render
            val profileRoot = host.getRootNode()
            val actualId = NodeTraverser.getCurrentAccountId(profileRoot)
            if (actualId != null) {
                if (!actualId.equals(expected, ignoreCase = true)) {
                    log("⚠️ Account mismatch: expected=@$expected actual=@$actualId")
                }
                log("🪪 Profile confirmed: @$actualId")
                autoSaveAccounts(listOf(actualId))
                // Quay về feed để farm loop bắt đầu ở đúng màn hình
                host.pressBack(); delay(800)
            } else {
                log("⚠️ Không đọc được @username từ profile — fallback save @$expected")
                autoSaveAccounts(listOf(expected))
                host.pressBack(); delay(800)
            }
        } else {
            // Profile tab không tìm thấy (hiếm) — save theo expected name
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

    // ── Actions ───────────────────────────────────────────────

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
        host.swipeSuspend(screenW/2, (screenH*0.75).toInt(), screenW/2, (screenH*0.25).toInt(), 350)
        delay(Random.nextLong(800, 1500))
    }

    private suspend fun waitFeedLoad(): Boolean {
        repeat(6) { i ->
            delay(3000)
            if (NodeTraverser.hasNavBar(host.getRootNode())) {
                repeat(3) { popup.handleIfPresent(); delay(600) }
                return true
            }
            log("   Feed ${i+1}/6...")
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

    fun onEvent(event: AccessibilityEvent) {}

    private fun stopInternal(reason: String) {
        _liveFarmStats.update { LiveFarmStats() }   // reset live stats on stop
        isFarming = false
        LanWebSocketServer.broadcast("farmStatus", mapOf("status" to "stopped", "reason" to reason))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
        host.scope.launch { repo.log(msg) }
    }
}
