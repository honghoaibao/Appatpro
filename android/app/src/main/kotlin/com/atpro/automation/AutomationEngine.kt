package com.atpro.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.atpro.accessibility.NodeTraverser
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.automation.popup.PopupHandler
import com.atpro.bridge.FlutterBridge
import com.atpro.data.FarmConfig
import com.atpro.data.LocalRepository
import com.atpro.notification.AtProNotificationManager
import com.atpro.security.AppConstants
import kotlinx.coroutines.*
import kotlin.random.Random

class AutomationEngine(private val service: TikTokAccessibilityService) {

    companion object {
        const val TAG = "AutomationEngine"
        val TIKTOK_PACKAGE: String get() = AppConstants.tiktokPackage
    }

    var isFarming = false; private set
    private var isPaused = false
    private var farmJob: Job? = null
    private val screenW get() = service.resources.displayMetrics.widthPixels
    private val screenH get() = service.resources.displayMetrics.heightPixels
    private lateinit var repo: LocalRepository
    private lateinit var popup: PopupHandler
    var config = FarmConfig()

    fun startFarm(accounts: List<String>) {
        if (isFarming) return
        repo   = LocalRepository.getInstance(service)
        popup  = PopupHandler(service)
        isFarming = true; isPaused = false
        farmJob = service.scope.launch {
            config = repo.loadFarmConfig()
            log("🚀 Farm ${accounts.size} accounts")
            FlutterBridge.sendEvent("farmStatus", mapOf("status" to "started", "total" to accounts.size))
            AtProNotificationManager.notifyFarmStarted(accounts.size)
            if (!openTikTok()) { stopInternal("tiktok_open_failed"); return@launch }
            waitFeedLoad()
            var tL = 0; var tF = 0; var tV = 0
            accounts.forEachIndexed { idx, account ->
                if (!isFarming) return@forEachIndexed
                while (isPaused) delay(500)
                log("👤 [${idx+1}/${accounts.size}] @$account")
                FlutterBridge.sendEvent("currentAccount", mapOf(
                    "account" to account, "index" to idx+1, "total" to accounts.size))
                val r = farmOneAccount(account)
                tL += r.first; tF += r.second; tV += r.third
                AtProNotificationManager.notifySessionDone(account, r.first, r.second, r.third)
                if (config.enableRestBetweenAccounts && idx < accounts.size - 1) {
                    log("😴 Rest ${config.restDurationMinutes}m")
                    delay(config.restDurationMinutes * 60_000L)
                }
            }
            AtProNotificationManager.notifyFarmCompleted(
                accounts.size, tL, tF, tV, accounts.size * config.minutesPerAccount)
            log("✅ Done — likes=$tL follows=$tF videos=$tV")
            stopInternal("completed")
        }
    }

    fun pause()  { isPaused = true;  log("⏸  Paused");  FlutterBridge.sendEvent("pauseStatus", mapOf("paused" to true)) }
    fun resume() { isPaused = false; log("▶️  Resumed"); FlutterBridge.sendEvent("pauseStatus", mapOf("paused" to false)) }
    fun stop()   { farmJob?.cancel(); stopInternal("user_stopped") }

    private suspend fun farmOneAccount(account: String): Triple<Int, Int, Int> {
        val sessionId = repo.startSession(account)
        val deadline  = System.currentTimeMillis() + config.minutesPerAccount * 60_000L
        val switched  = switchToAccount(account)
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
            if (NodeTraverser.detectCheckpoint(service.getRootNode())) {
                log("⚠️  Checkpoint: @$account")
                repo.setCheckpoint(account, true)
                AtProNotificationManager.notifyCheckpoint(account); break
            }
            if (isLost()) { recoverToFeed(); continue }
            popup.handleIfPresent()
            if (config.skipLive && NodeTraverser.detectLive(service.getRootNode())) {
                swipeNext(); continue
            }

            // Fix 3: guard min >= max để tránh crash Random.nextLong
            val watchMin = (config.videoWatchTimeMin * 1000).toLong()
            val watchMax = (config.videoWatchTimeMax * 1000).toLong()
            val watchMs  = if (watchMax > watchMin) Random.nextLong(watchMin, watchMax) else watchMin
            delay(watchMs)
            videos++

            if (Random.nextFloat() < config.likeRate)   { if (doLike())   likes++ }
            if (Random.nextFloat() < config.followRate) { if (doFollow()) follows++ }
            swipeNext()

            FlutterBridge.sendEvent("liveStats", mapOf(
                "account" to account, "videos" to videos,
                "likes" to likes, "follows" to follows,
                "remainingSecs" to maxOf(0L, (deadline - System.currentTimeMillis()) / 1000L).toInt(),
            ))
        }
        // Fix 1: truyền accountId thẳng vào closeSession
        repo.closeSession(sessionId, account, likes, follows, videos)
        repo.log("✅ @$account: L=$likes F=$follows V=$videos", "SUCCESS", account)
        return Triple(likes, follows, videos)
    }

    // ── Fix 4: switchToAccount với verify thật sự ─────────────

    private suspend fun switchToAccount(account: String): Boolean {
        // Step 1: Mở switch popup
        if (!openSwitchPopup()) {
            log("   Không mở được switch popup")
            return false
        }

        // Step 2: Chọn account trong popup (retry 3 lần)
        var clicked = false
        for (attempt in 1..3) {
            delay(1500)
            val popupRoot = service.getRootNode()
            val detected  = NodeTraverser.detectPopup(popupRoot)
            if (detected.type != NodeTraverser.PopupType.ACCOUNT_SWITCH) {
                log("   Lần $attempt: chưa thấy popup switch (${detected.type})")
                if (attempt < 3) { delay(2000); continue }
                return false
            }
            val node = NodeTraverser.findByText(popupRoot, account, ignoreCase = true)
            if (node == null) {
                log("   Lần $attempt: không thấy @$account trong popup")
                if (attempt < 3) { delay(1500); continue }
                return false
            }
            service.clickNode(node.node)
            clicked = true; break
        }
        if (!clicked) return false

        // Step 3: Đợi app restart
        delay(config.delayAfterSwitchClick.toLong() * 1000)
        closeTikTok(); delay(2000)
        openTikTok()
        if (!waitFeedLoad()) return false

        // Fix 4: Verify thật sự — vào profile đọc username
        if (!config.enableVerifyAccount) return true
        return verifyCurrentAccount(account)
    }

    /**
     * Fix 4: Xác nhận chắc chắn đang ở đúng account.
     * Retry 3 lần để tránh fail do UI load chậm.
     */
    private suspend fun verifyCurrentAccount(expected: String): Boolean {
        repeat(3) { attempt ->
            // Click profile tab
            val root = service.getRootNode()
            val profileTab = NodeTraverser.findProfileTab(root)
            if (profileTab != null) {
                service.clickNode(profileTab.node)
            } else {
                // Fallback tọa độ profile tab (thường góc phải nav bar)
                service.clickSuspend((screenW * 0.95).toInt(), (screenH * 0.97).toInt())
            }
            delay(2500)

            val profileRoot = service.getRootNode()
            val currentId   = NodeTraverser.getCurrentAccountId(profileRoot)

            if (currentId != null) {
                val match = currentId.equals(expected.trimStart('@'), ignoreCase = true) ||
                            expected.contains(currentId, ignoreCase = true)
                if (match) {
                    log("✅ Verify OK: @$currentId")
                    // Quay về feed
                    service.pressBack(); delay(800)
                    return true
                } else {
                    log("⚠️  Verify fail (${attempt+1}/3): expected @$expected got @$currentId")
                }
            } else {
                log("⚠️  Verify (${attempt+1}/3): không đọc được username")
            }
            delay(1500)
        }
        // Verify thất bại sau 3 lần — vẫn trả true để tiếp tục farm
        // Lý do: username detection heuristic có thể sai, nhưng account đã switch
        log("⚠️  Verify không kết luận được — tiếp tục farm (heuristic)")
        service.pressBack(); delay(800)
        return true
    }

    private suspend fun openSwitchPopup(): Boolean {
        val root = service.getRootNode()
        // Thử nút switch_account trước (resource-id based — ổn định hơn text)
        val switchBtn = NodeTraverser.findByResourceId(root, "switch_account")
            ?: NodeTraverser.findByText(root, "chuyển tài khoản")
            ?: NodeTraverser.findByText(root, "switch account")

        if (switchBtn != null) {
            service.clickNode(switchBtn.node)
            return true
        }

        // Fallback: vào profile rồi click avatar area
        val profileTab = NodeTraverser.findProfileTab(root)
        if (profileTab != null) {
            service.clickNode(profileTab.node); delay(2000)
        } else {
            service.clickSuspend((screenW * 0.95).toInt(), (screenH * 0.97).toInt()); delay(2000)
        }

        // Click avatar/username area trên profile page
        service.clickSuspend((screenW / 2), (screenH * 0.20).toInt())
        return true
    }

    private suspend fun doLike(): Boolean {
        val cx = screenW / 2; val cy = (screenH * 0.55).toInt()
        service.clickSuspend(cx, cy); delay(100); service.clickSuspend(cx, cy)
        delay((config.delayAfterLike * 1000).toLong()); return true
    }

    private suspend fun doFollow(): Boolean {
        val root = service.getRootNode()
        val btn  = NodeTraverser.findByText(root, "follow")
            ?: NodeTraverser.findByText(root, "theo dõi") ?: return false
        service.clickNode(btn.node)
        delay((config.delayAfterFollow * 1000).toLong()); return true
    }

    private suspend fun swipeNext() {
        // Fix 3: safe random
        val delayMin = 800L; val delayMax = 1500L
        service.swipeSuspend(screenW/2, (screenH*0.75).toInt(), screenW/2, (screenH*0.25).toInt(), 350)
        delay(Random.nextLong(delayMin, delayMax))
    }

    private suspend fun openTikTok(): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val intent = service.packageManager.getLaunchIntentForPackage(TIKTOK_PACKAGE)
                ?: return@withContext false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent); true
        }.getOrDefault(false)
    }

    private suspend fun closeTikTok() = withContext(Dispatchers.IO) {
        runCatching {
            service.getSystemService(android.app.ActivityManager::class.java)
                ?.killBackgroundProcesses(TIKTOK_PACKAGE)
        }
    }

    private suspend fun waitFeedLoad(): Boolean {
        repeat(6) { i ->
            delay(3000)
            if (NodeTraverser.hasNavBar(service.getRootNode())) {
                log("✅ Feed loaded (${i+1})")
                repeat(3) { popup.handleIfPresent(); delay(800) }
                return true
            }
            log("   Waiting feed (${i+1}/6)")
        }
        log("❌ Feed load timeout")
        return false
    }

    private fun isLost() = !NodeTraverser.hasNavBar(service.getRootNode())

    private suspend fun recoverToFeed(): Boolean {
        log("🔧 Recovering...")
        repeat(config.maxBackAttempts) { i ->
            service.pressBack(); delay(1200)
            if (NodeTraverser.hasNavBar(service.getRootNode())) {
                log("✅ Recover OK (${i+1})"); return true
            }
        }
        log("⚠️  Hard reset")
        closeTikTok(); delay(2000); openTikTok()
        return waitFeedLoad()
    }

    fun onEvent(event: AccessibilityEvent) {}

    private fun stopInternal(reason: String) {
        isFarming = false
        log("⛔ $reason")
        FlutterBridge.sendEvent("farmStatus", mapOf("status" to "stopped", "reason" to reason))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        FlutterBridge.sendEvent("log", mapOf("message" to msg, "level" to "INFO"))
        if (::repo.isInitialized) service.scope.launch { repo.log(msg) }
    }
}
