package com.atpro.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.atpro.accessibility.NodeTraverser
import com.atpro.automation.popup.PopupHandler
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.network.LanWebSocketServer
import com.atpro.notification.AtProNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Public enums / data classes
// ─────────────────────────────────────────────────────────────────────────────

enum class FarmMode { ALL_LOCAL, SELECTED_LIST }

/** Thống kê live — emit ra DashboardScreen qua StateFlow. */
data class LiveFarmStats(
    val account:       String = "",
    val index:         Int    = 0,
    val total:         Int    = 0,
    val videos:        Int    = 0,
    val likes:         Int    = 0,
    val follows:       Int    = 0,
    val comments:      Int    = 0,
    val remainingSecs: Int    = 0,
)

// ─────────────────────────────────────────────────────────────────────────────
// Private sealed class — state machine
// ─────────────────────────────────────────────────────────────────────────────

/** Các pha farm. Engine dịch chuyển tuần tự cho đến Done hoặc Failed. */
private sealed class FarmPhase {
    /** Mở TikTok, chờ feed sẵn sàng. */
    object Launch : FarmPhase()

    /** Đọc acc hiện tại, mở switch popup, build danh sách, position acc đầu. */
    object Discover : FarmPhase()

    /** Vòng lặp nuôi từng acc theo thứ tự. */
    data class FarmLoop(val farmList: List<String>) : FarmPhase()

    /** Kết thúc bình thường. */
    object Done : FarmPhase()

    /** Thất bại — lý do ghi vào [reason]. */
    data class Failed(val reason: String) : FarmPhase()
}

// ─────────────────────────────────────────────────────────────────────────────
// Private data classes
// ─────────────────────────────────────────────────────────────────────────────

/** Kết quả nuôi 1 acc. Trả từ farmOneAccount(). */
private data class AccountResult(
    val username: String,
    val videos:   Int     = 0,
    val likes:    Int     = 0,
    val follows:  Int     = 0,
    val comments: Int     = 0,
    val reason:   String  = "completed",
)

// ─────────────────────────────────────────────────────────────────────────────
// Human — hành vi giống người thật
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tập trung tất cả random timing vào một nơi — dễ tune, dễ test.
 *
 * Nguyên tắc:
 *   - Không delay đều đặn → TikTok bot-detection khó phân biệt.
 *   - Jitter nhỏ (±80ms) trên mọi action.
 *   - occasionalPause(): đôi khi người dùng "thẫn thờ" 3–10s.
 *   - swipeDuration(): biên độ ±100ms xung quanh giá trị cơ sở.
 */
private object Human {
    /** Delay ngẫu nhiên trong [minMs, maxMs]. */
    suspend fun delay(minMs: Long, maxMs: Long = minMs) =
        kotlinx.coroutines.delay(
            if (maxMs > minMs) Random.nextLong(minMs, maxMs) else minMs
        )

    /** Micro-pause sau click/swipe — người thật không thao tác tức thì. */
    suspend fun microPause() = kotlinx.coroutines.delay(Random.nextLong(60, 220))

    /**
     * Thỉnh thoảng dừng lại lâu — mô phỏng người dùng bị phân tâm.
     * Mặc định 6% cơ hội, dừng 3–9s.
     */
    suspend fun occasionalPause(chance: Float = 0.06f) {
        if (Random.nextFloat() < chance)
            kotlinx.coroutines.delay(Random.nextLong(3_000, 9_000))
    }

    /** Thời gian swipe ngẫu nhiên xung quanh [baseMs]. */
    fun swipeDuration(baseMs: Long = 350): Long =
        (baseMs + Random.nextLong(-80, 100)).coerceAtLeast(150)

    /** Jitter tọa độ click — tránh click cùng pixel mỗi lần. */
    fun jitter(range: Int = 25): Int = Random.nextInt(-range, range)
}

// ─────────────────────────────────────────────────────────────────────────────
// retry — helper chạy lại với exponential backoff
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Retry [block] tối đa [times] lần.
 * Trả null nếu tất cả lần đều fail.
 * Delay giữa các lần tăng dần: [delayMs] * [backoff] ^ attempt.
 */
private suspend fun <T> retry(
    times:    Int,
    delayMs:  Long   = 1_000L,
    backoff:  Double = 1.5,
    tag:      String = "",
    block:    suspend (attempt: Int) -> T?,
): T? {
    var wait = delayMs
    repeat(times) { attempt ->
        block(attempt)?.let { return it }
        if (attempt < times - 1) {
            if (tag.isNotEmpty())
                Log.w("retry", "[$tag] attempt ${attempt + 1}/$times → retry in ${wait}ms")
            kotlinx.coroutines.delay(wait)
            wait = (wait * backoff).toLong().coerceAtMost(12_000L)
        }
    }
    return null
}

// ─────────────────────────────────────────────────────────────────────────────
// AutomationEngine — v2.0
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AutomationEngine v2.0
 *
 * Thay đổi so với v1.0.9:
 *
 * Kiến trúc:
 *   [1] State machine FarmPhase → không còn chuỗi if/return dài trong startFarm().
 *   [2] retry { } helper thay thế vòng lặp retry thủ công.
 *   [3] Human object tập trung timing ngẫu nhiên.
 *   [4] AccountResult thay Triple<Int,Int,Int> → rõ ràng hơn.
 *   [5] try/catch/finally đảm bảo overlay luôn được ẩn khi crash.
 *
 * Tính năng mới:
 *   [6] Comment — doComment() gõ text ngẫu nhiên từ config.commentTexts.
 *   [7] Stuck-video detection — phát hiện TikTok bị kẹt cùng 1 video, force-swipe.
 *   [8] Watchdog coroutine — cảnh báo nếu video count không tăng trong X giây.
 *   [9] forceSwipeNext() — swipe mạnh hơn khi bị kẹt.
 *   [10] Tổng hợp stats (likes/follows/videos) cho toàn session → báo cáo chính xác.
 *
 * Switch flow:
 *   Giữ popup đang mở sau openSwitchPopup() để position acc đầu tiên trực tiếp
 *   mà không cần đóng + mở lại.
 *
 * Tương thích ngược:
 *   Giữ nguyên IFarmHost, IFarmRepository, FarmConfig (extended), PopupHandler,
 *   NodeTraverser, OverlayFarmMonitor, AtProNotificationManager APIs.
 */
class AutomationEngine(
    private val host: IFarmHost,
    private val repo: IFarmRepository,
) {
    companion object { const val TAG = "AutomationEngine" }

    var isFarming = false; private set
    var isPaused  = false; private set

    private val _liveFarmStats = MutableStateFlow(LiveFarmStats())
    val liveFarmStats: StateFlow<LiveFarmStats> = _liveFarmStats.asStateFlow()

    /** Trạng thái khởi động — DashboardScreen và Overlay đọc để hiển thị popup/status. */
    private val _startupStatus = MutableStateFlow("")
    val startupStatus: StateFlow<String> = _startupStatus.asStateFlow()

    private var farmJob:     Job? = null
    private var watchdogJob: Job? = null
    private val popup by lazy { PopupHandler(host) }

    private val screenW get() = host.screenWidth
    private val screenH get() = host.screenHeight
    private var config = FarmConfig()

    // Watchdog state — cập nhật bởi farmOneAccount()
    @Volatile private var watchdogVideoCount = 0
    @Volatile private var watchdogLastTick   = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun startFarm(mode: FarmMode, inputList: List<String> = emptyList()) {
        if (isFarming) return
        isFarming = true
        isPaused  = false

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            try {
                runStateMachine(mode, inputList)
            } catch (e: CancellationException) {
                log("⏹ Farm bị hủy bởi người dùng")
                throw e
            } catch (e: Exception) {
                log("ERR: Lỗi không mong đợi: ${e.javaClass.simpleName} — ${e.message}")
            } finally {
                watchdogJob?.cancel()
                host.hideFarmOverlay()
                setStatus("")
                stopInternal("completed")
            }
        }
    }

    fun pause() {
        isPaused = true
        OverlayFarmMonitor.syncPausedState(true)
        LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to true))
    }

    fun resume() {
        isPaused = false
        OverlayFarmMonitor.syncPausedState(false)
        LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to false))
    }

    fun stop() {
        farmJob?.cancel()
        watchdogJob?.cancel()
        host.hideFarmOverlay()
        setStatus("")
        stopInternal("user_stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State machine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Điều phối các pha farm theo state machine.
     *
     * Thứ tự bình thường: Launch → Discover → FarmLoop → Done
     * Bất kỳ pha nào lỗi → Failed → thoát.
     */
    private suspend fun runStateMachine(mode: FarmMode, inputList: List<String>) {
        var phase: FarmPhase = FarmPhase.Launch

        while (phase !is FarmPhase.Done && phase !is FarmPhase.Failed) {
            awaitResumed()
            phase = when (phase) {
                is FarmPhase.Launch    -> phaseLaunch()
                is FarmPhase.Discover  -> phaseDiscover(mode, inputList)
                is FarmPhase.FarmLoop  -> phaseFarmLoop(phase.farmList)
                else -> phase  // Done / Failed — không thể reach
            }
        }

        if (phase is FarmPhase.Failed) {
            log("ERR: Farm kết thúc sớm: ${(phase as FarmPhase.Failed).reason}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 — Launch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mở TikTok và chờ feed sẵn sàng.
     *
     * Retry launchTikTok() tối đa 3 lần với backoff 2s → 3s → 4.5s.
     * Sau khi launch thành công, waitFeedLoad() thêm tối đa 18s.
     */
    private suspend fun phaseLaunch(): FarmPhase {
        log(">> [Phase 1] Khởi động TikTok")
        setStatus(">> Đang mở TikTok...")

        val launched = retry(times = 3, delayMs = 2_000, tag = "launchTikTok") {
            if (host.launchTikTok()) true else null
        }
        if (launched == null) {
            log("ERR: Không mở được TikTok sau 3 lần thử")
            setStatus("")
            return FarmPhase.Failed("launch_failed")
        }

        setStatus("⏳ Chờ feed tải...")
        if (!waitFeedLoad()) {
            log("ERR: Feed không load trong 18s")
            setStatus("")
            return FarmPhase.Failed("feed_timeout")
        }

        return FarmPhase.Discover
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2 — Discover + Build + Position
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Đọc acc hiện tại → mở switch popup → build danh sách → position acc đầu.
     *
     * `v2.0` Gộp 3 pha thành 1 để tận dụng popup đang mở:
     *   v1.0.x mở popup → đọc → đóng (pressBack×2) → mở lại để switch.
     *   v2.0   mở popup → đọc → giữ nguyên → position trực tiếp từ popup.
     *   → Tiết kiệm ~5s và giảm thao tác không cần thiết.
     */
    private suspend fun phaseDiscover(mode: FarmMode, inputList: List<String>): FarmPhase {
        log("SCAN: [Phase 2] Discover accounts")

        // Bước 1: Đọc acc đang active (Profile tab → lấy @username → về feed)
        setStatus("SCAN: Đang đọc tài khoản hiện tại...")
        val currentAcc = detectCurrentAccount()
        log("DEV: Acc đang active: @${currentAcc ?: "không xác định"}")

        // Bước 2: Mở switch popup + đọc danh sách (popup giữ nguyên sau đây)
        setStatus("CFG: Đang mở danh sách tài khoản...")
        val (popupOpened, discovered) = openSwitchPopup()
        if (!popupOpened) {
            setStatus("")
            return FarmPhase.Failed("switch_failed")
        }

        // Bước 3: Build danh sách farm
        setStatus("LIST: Đang phân tích danh sách tài khoản...")
        val farmList = buildFarmList(mode, inputList, discovered)
        if (farmList.isEmpty()) {
            log("WARN: Không có tài khoản nào để nuôi")
            setStatus("")
            // Đóng popup + settings rồi thoát
            host.pressBack(); delay(500)
            host.pressBack(); delay(500)
            return FarmPhase.Failed("no_accounts")
        }

        log("LIST: Farm list (${farmList.size} acc): ${farmList.joinToString()}")
        LanWebSocketServer.broadcast("farmStatus",
            mapOf("status" to "started", "total" to farmList.size))
        AtProNotificationManager.notifyFarmStarted(farmList.size)

        // Bước 4: Position acc đầu tiên (popup đang mở — dùng trực tiếp)
        setStatus("SWITCH: Đang chuyển sang tài khoản đầu tiên...")
        val positioned = positionFirstAccount(farmList.first(), currentAcc)
        if (!positioned) {
            log("ERR: Không thể position acc đầu: @${farmList.first()}")
            setStatus("")
            return FarmPhase.Failed("position_failed")
        }

        setStatus("")
        return FarmPhase.FarmLoop(farmList)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3 — Farm loop
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nuôi từng account trong farmList theo thứ tự.
     *
     * `v2.0` Tích lũy stats toàn session → notifyFarmCompleted() báo chính xác.
     */
    private suspend fun phaseFarmLoop(farmList: List<String>): FarmPhase {
        val totalSecs = farmList.size.toLong() * config.minutesPerAccount * 60L
        val farmedSet = mutableSetOf<String>()
        var elapsed   = 0L

        var sessionLikes    = 0
        var sessionFollows  = 0
        var sessionVideos   = 0

        startWatchdog()

        // [FIX-B] forEachIndexed không thể break khi stop() được gọi —
        // return@forEachIndexed chỉ skip 1 phần tử, vòng lặp vẫn tiếp tục.
        // Dùng for loop để break ngay khi isFarming = false.
        for ((idx, account) in farmList.withIndex()) {
            if (!isFarming) break
            awaitResumed()

            log("ACC: [${idx + 1}/${farmList.size}] @$account")
            LanWebSocketServer.broadcast("currentAccount", mapOf(
                "account" to account,
                "index"   to idx + 1,
                "total"   to farmList.size,
            ))

            // Switch sang acc tiếp theo (acc đầu đã được position ở Phase 2)
            if (idx > 0) {
                val switched = switchToAccount(account, farmedSet)
                if (!switched) {
                    log("WARN: Skip @$account — switch thất bại")
                    continue
                }
            }

            farmedSet.add(account.lowercase())
            resetWatchdog()

            val secsLeft = maxOf(0L, totalSecs - elapsed)
            // [FIX-C] Đo thời gian thực của farmOneAccount thay vì cộng cố định.
            // Acc có thể kết thúc sớm (checkpoint, lostStreak...) → elapsed
            // cộng thực tế tránh secsLeft bị âm/quá nhỏ cho acc tiếp theo.
            val accountStartMs = System.currentTimeMillis()
            val result = farmOneAccount(account, idx, farmList.size, secsLeft)
            elapsed += (System.currentTimeMillis() - accountStartMs) / 1_000L

            sessionLikes   += result.likes
            sessionFollows += result.follows
            sessionVideos  += result.videos

            AtProNotificationManager.notifySessionDone(
                account, result.likes, result.follows, result.videos)

            // Nghỉ giữa acc (không nghỉ sau acc cuối)
            if (config.enableRestBetweenAccounts && idx < farmList.size - 1) {
                val restSecs = config.restDurationMinutes * 60L
                log("REST: Nghỉ ${config.restDurationMinutes}m trước acc tiếp...")
                setStatus("REST: Đang nghỉ ${config.restDurationMinutes} phút...")
                delay(restSecs * 1_000L)
                setStatus("")
            }
        }

        watchdogJob?.cancel()

        AtProNotificationManager.notifyFarmCompleted(
            farmList.size, sessionLikes, sessionFollows, sessionVideos,
            farmList.size * config.minutesPerAccount,
        )

        return FarmPhase.Done
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Switch popup helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mở Settings → cuộn → tìm nút "chuyển đổi tài khoản" → click → đọc popup.
     *
     * `v2.0` Tái sử dụng findSwitchButton() + findSwitchBtnNode() — không duplicate.
     * Trả về (success, discoveredList). Popup VẪN ĐỂ MỞ sau khi return.
     */
    private suspend fun openSwitchPopup(): Pair<Boolean, List<String>> {
        log("CFG: Mở Settings để đọc danh sách acc...")
        host.openTikTokSettings()
        delay(2_000)

        // Giai đoạn A: cuộn tìm nút — KHÔNG click trong vòng lặp
        if (!scrollUntilSwitchFound(maxScrolls = 8)) {
            log("ERR: Không tìm thấy nút chuyển đổi tài khoản")
            return Pair(false, emptyList())
        }

        // Giai đoạn B: settle → fresh node → click
        delay(800)
        val btn = findSwitchBtnNode()
        if (btn == null) {
            log("ERR: Nút chuyển đổi biến mất sau delay")
            return Pair(false, emptyList())
        }
        host.clickNode(btn.node)
        delay(1_500)

        // Đọc danh sách từ popup đang mở
        val discovered = NodeTraverser.parseAccountList(host.getRootNode())
        if (discovered.isNotEmpty()) {
            log("LIST: Discover ${discovered.size} acc từ switch popup")
            autoSaveAccounts(discovered)
        } else {
            log("WARN: parseAccountList rỗng — TikTok dùng display name thay @username?")
        }

        return Pair(true, discovered)
    }

    /**
     * Cuộn Settings cho đến khi thấy nút switch.
     * KHÔNG click — chỉ tìm. Tránh bug cũ: scroll thêm sau khi đã click.
     */
    private suspend fun scrollUntilSwitchFound(maxScrolls: Int): Boolean {
        repeat(maxScrolls) {
            if (findSwitchBtnNode() != null) return true
            setStatus("SCAN: Đang tìm nút chuyển đổi tài khoản...")
            host.swipeSuspend(
                screenW / 2, (screenH * 0.7).toInt(),
                screenW / 2, (screenH * 0.3).toInt(),
                Human.swipeDuration(400),
            )
            delay(700)
        }
        return false
    }

    /** Tìm node nút "chuyển đổi tài khoản" — fresh read từ getRootNode(). */
    private fun findSwitchBtnNode(): NodeTraverser.NodeResult? {
        val root = host.getRootNode()
        return NodeTraverser.findByText(root, "chuyển đổi tài khoản", ignoreCase = true)
            ?: NodeTraverser.findByText(root, "switch account",          ignoreCase = true)
            ?: NodeTraverser.findByText(root, "manage accounts",         ignoreCase = true)
    }

    /**
     * Position acc đầu tiên sau khi popup đã mở (từ openSwitchPopup()).
     *
     * TH1 — currentAcc == target: đóng popup + settings → về feed.
     * TH2 — khác: click trong popup đang mở → relaunch → feed.
     */
    private suspend fun positionFirstAccount(target: String, currentAcc: String?): Boolean {
        val isCurrent = currentAcc != null &&
            currentAcc.equals(target, ignoreCase = true)

        return if (isCurrent) {
            log("OK: @$target đang active — kill + relaunch TikTok để clean state")
            // [FIX-D] Thay pressBack×2 + waitFeedLoad() bằng kill → relaunch.
            //
            // Lý do: pressBack×2 đóng Settings rồi quay về feed, nhưng:
            //   1. Accessibility tree chưa ổn định → isOnFeedTab() false-negative.
            //   2. Popup/overlay còn sót từ Settings → PopupHandler nhầm → pressBack trên feed.
            //   3. TikTok state không clean → isLostWithRetry() bắn ngay iteration đầu.
            //
            // Kill + relaunch đảm bảo:
            //   - TikTok khởi động từ đầu, không có state tồn đọng.
            //   - waitFeedLoad() chờ feed ổn định hoàn toàn trước khi farm.
            //   - Nhất quán với flow của relaunchAndVerify() (TH2 và switchToAccount).
            host.pressBack()  // Đóng switch popup trước khi kill (tránh dialog "confirm exit")
            delay(500)
            host.killTikTok()
            delay(2_000)
            host.launchTikTok()

            if (!waitFeedLoad()) {
                log("ERR: Feed không load sau kill+relaunch @$target")
                return false
            }
            log("OK: @$target — TikTok đã restart, feed sẵn sàng")
            true
        } else {
            log("SWITCH: Cần switch sang @$target (hiện tại: @${currentAcc ?: "?"})")
            // Popup đang mở — click trực tiếp không cần mở lại
            clickInPopup(target) && relaunchAndVerify(target)
        }
    }

    /**
     * Switch sang acc tiếp theo từ feed (không phải acc đầu tiên).
     *
     * Quy trình: openTikTokSettings → scroll → click switch btn → click acc → relaunch.
     */
    private suspend fun switchToAccount(target: String, farmedSet: Set<String>): Boolean {
        if (target.lowercase() in farmedSet) {
            log("WARN: @$target đã nuôi trong phiên — skip")
            return false
        }

        log("RELOAD: Chuyển acc → @$target")
        host.openTikTokSettings()
        delay(2_000)

        if (!scrollUntilSwitchFound(maxScrolls = 8)) {
            log("ERR: Không tìm thấy nút switch khi chuyển @$target")
            return false
        }

        delay(800)
        val btn = findSwitchBtnNode()
        if (btn == null) {
            log("ERR: Nút switch biến mất sau delay — @$target")
            return false
        }
        host.clickNode(btn.node)
        delay(1_500)

        return clickInPopup(target) && relaunchAndVerify(target)
    }

    /**
     * Click vào username trong switch popup.
     * Retry 3 lần × delay 1.5s nếu chưa thấy node (popup chưa render xong).
     */
    private suspend fun clickInPopup(target: String): Boolean {
        val node = retry(times = 3, delayMs = 1_500, tag = "clickPopup($target)") {
            NodeTraverser.findByText(host.getRootNode(), target, ignoreCase = true)
        }
        return if (node != null) {
            host.clickNode(node.node)
            delay((config.delayAfterSwitchClick * 1_000).toLong())
            true
        } else {
            log("ERR: Không tìm thấy @$target trong switch popup")
            host.pressBack()
            delay(500)
            false
        }
    }

    /**
     * Kill TikTok → relaunch → chờ feed → verify acc (nếu config.enableVerifyAccount).
     */
    private suspend fun relaunchAndVerify(target: String): Boolean {
        host.killTikTok()
        delay(2_000)
        host.launchTikTok()

        if (!waitFeedLoad()) {
            log("ERR: Feed không load sau khi switch @$target")
            return false
        }

        if (config.enableVerifyAccount) verifyCurrentAccount(target)
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Farm one account
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nuôi một acc đã được position tại feed.
     *
     * Vòng lặp chạy cho đến khi hết thời gian (deadline) hoặc bị gián đoạn.
     *
     * Thứ tự ưu tiên trong mỗi iteration:
     *   1. checkPaused()
     *   2. detectCheckpoint()
     *   3. popup.handleIfPresent()
     *   4. isLostWithRetry() → recoverToFeed()
     *   5. Stuck video detection → forceSwipeNext()
     *   6. Watch video (delay)
     *   7. doLike() / doFollow() / doComment() theo rate
     *   8. swipeNext()
     *   9. Emit LiveFarmStats
     */
    private suspend fun farmOneAccount(
        account:       String,
        idx:           Int,
        total:         Int,
        totalSecsLeft: Long,
    ): AccountResult {
        val sessionId = repo.startSession(account)

        // [FIX-E1] Tăng delay từ 2s → 4s: accessibility tree cần thêm thời gian
        // ổn định sau kill+relaunch. 2s gây isLostWithRetry() false-positive.
        delay(4_000)
        // Clear popup trước khi vào vòng lặp — tránh PopupHandler nhầm ngay iteration đầu.
        repeat(2) { popup.handleIfPresent(); delay(600) }

        val deadline = System.currentTimeMillis() + config.minutesPerAccount * 60_000L

        var videos   = 0; var likes = 0; var follows = 0; var comments = 0
        var lostStreak     = 0  // Số lần isLostWithRetry() liên tiếp
        var sameVideoCount = 0  // Số lần cùng 1 video text liên tiếp
        var lastVideoSnap  = "" // Snapshot text video để phát hiện kẹt

        while (System.currentTimeMillis() < deadline && isFarming) {
            awaitResumed()
            val secsLeft = maxOf(0L, (deadline - System.currentTimeMillis()) / 1_000L)

            // ── [0] Update overlay ngay đầu iteration ────────────────────
            // [FIX-E2] Trước fix: overlay chỉ update ở step [5], sau checkpoint +
            // popup + isLostWithRetry() (~3–5s). UI trông như "treo" trong thời gian đó.
            // Fix: update overlay ngay đầu mỗi iteration để hiển thị liên tục.
            val _secsRemainForOverlay = secsLeft
            val _totalRemainForOverlay = maxOf(0L,
                totalSecsLeft - (config.minutesPerAccount * 60L - _secsRemainForOverlay))
            OverlayFarmMonitor.update(
                accountIndex    = idx + 1,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = _secsRemainForOverlay,
                totalSecsLeft   = _totalRemainForOverlay,
                action          = if (videos == 0) "⏳ Khởi động @$account..." else "▶ Xem video #$videos",
            )

            // ── [1] Checkpoint ────────────────────────────────────────────
            if (NodeTraverser.detectCheckpoint(host.getRootNode())) {
                log("WARN: Checkpoint phát hiện: @$account")
                repo.setCheckpoint(account, true)
                AtProNotificationManager.notifyCheckpoint(account)
                break
            }

            // ── [2] Popup ─────────────────────────────────────────────────
            val popupResult = popup.handleIfPresent()
            if (popupResult.handled) {
                lostStreak = 0
                delay(500)
                continue
            }

            // ── [3] Lost detection ────────────────────────────────────────
            if (isLostWithRetry()) {
                val dbgTexts = NodeTraverser.dumpScreenTexts(host.getRootNode())
                log("SCAN: Lost #${lostStreak + 1}: ${dbgTexts.take(10).joinToString(" | ")}")

                lostStreak++
                if (lostStreak >= 2) {
                    log("WARN: Lạc $lostStreak lần liên tiếp → recoverToFeed()")
                    val recovered = recoverToFeed()
                    lostStreak = 0
                    if (!recovered) {
                        log("ERR: recoverToFeed() thất bại — kết thúc sớm @$account")
                        break
                    }
                    delay(2_000) // Chờ accessibility tree ổn định
                }
                continue
            }
            lostStreak = 0

            // ── [4] Stuck video detection ─────────────────────────────────
            // Lấy snapshot text đầu tiên của màn hình làm "video fingerprint".
            // Nếu fingerprint giống >= 3 lần liên tiếp → TikTok bị kẹt.
            val snap = NodeTraverser.dumpScreenTexts(host.getRootNode())
                .firstOrNull()?.take(40) ?: ""
            if (snap.isNotBlank() && snap == lastVideoSnap) {
                sameVideoCount++
                if (sameVideoCount >= 3) {
                    log("WARN: Kẹt cùng video $sameVideoCount lần → force swipe")
                    forceSwipeNext()
                    sameVideoCount = 0
                    continue
                }
            } else {
                lastVideoSnap  = snap
                sameVideoCount = 0
            }

            // ── [5] Update overlay ────────────────────────────────────────
            val sessionSecsRemain = secsLeft
            val totalSecsRemain   = maxOf(0L,
                totalSecsLeft - (config.minutesPerAccount * 60L - sessionSecsRemain))
            OverlayFarmMonitor.update(
                accountIndex    = idx + 1,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecsRemain,
                totalSecsLeft   = totalSecsRemain,
                action          = if (videos == 0) "⏳ Chuẩn bị xem video..." else "▶ Xem video #$videos",
            )

            // ── [6] Skip live ─────────────────────────────────────────────
            if (config.skipLive && NodeTraverser.detectLive(host.getRootNode())) {
                swipeNext()
                continue
            }

            // ── [7] Watch video ───────────────────────────────────────────
            val watchMs = randomWatchTimeMs()
            delay(watchMs)
            videos++
            watchdogVideoCount = videos
            watchdogLastTick   = System.currentTimeMillis()

            // Thỉnh thoảng dừng lâu (giả lập người dùng mất tập trung)
            Human.occasionalPause(config.occasionalPauseChance)

            // ── [8] Like ──────────────────────────────────────────────────
            if (Random.nextFloat() < config.likeRate) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "LIKE: Thích")
                doLike()
                likes++
            }

            // ── [9] Follow ────────────────────────────────────────────────
            if (Random.nextFloat() < config.followRate) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "FOLLOW: Theo dõi")
                if (doFollow()) follows++
            }

            // ── [10] Comment [v2.0] ───────────────────────────────────────
            if (config.commentRate > 0f
                && config.commentTexts.isNotEmpty()
                && Random.nextFloat() < config.commentRate
            ) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "CMT: Bình luận")
                if (doComment(config.commentTexts)) comments++
            }

            // ── [11] Swipe next ───────────────────────────────────────────
            swipeNext()

            // ── [12] Emit live stats ──────────────────────────────────────
            _liveFarmStats.update {
                LiveFarmStats(
                    account       = account,
                    index         = idx + 1,
                    total         = total,
                    videos        = videos,
                    likes         = likes,
                    follows       = follows,
                    comments      = comments,
                    remainingSecs = secsLeft.toInt(),
                )
            }
            LanWebSocketServer.broadcast("liveStats", mapOf(
                "account"       to account,
                "videos"        to videos,
                "likes"         to likes,
                "follows"       to follows,
                "comments"      to comments,
                "remainingSecs" to secsLeft.toInt(),
            ))
        }

        repo.closeSession(sessionId, account, likes, follows, videos, comments)
        return AccountResult(account, videos, likes, follows, comments)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Double-tap vào vùng giữa màn hình để like.
     *
     * `v2.0` Thêm jitter tọa độ (±25px) để tránh click cùng pixel mỗi lần.
     * TikTok phân biệt bot qua pattern tọa độ chính xác lặp lại.
     */
    private suspend fun doLike() {
        val cx = screenW / 2 + Human.jitter(25)
        val cy = (screenH * 0.55).toInt() + Human.jitter(20)
        host.clickSuspend(cx, cy)
        Human.microPause()
        host.clickSuspend(cx, cy)
        delay((config.delayAfterLike * 1_000).toLong())
    }

    /**
     * Follow tác giả video hiện tại.
     *
     * Kiểm tra text nút trước khi click — bỏ qua nếu đã follow.
     * Trả true nếu đã thực sự click (chưa follow).
     */
    private suspend fun doFollow(): Boolean {
        val root = host.getRootNode()
        val btn = NodeTraverser.findByText(root, "follow",     ignoreCase = true)
            ?: NodeTraverser.findByText(root, "theo dõi",     ignoreCase = true)
            ?: NodeTraverser.findByText(root, "đăng ký",      ignoreCase = true)
            ?: return false

        // Đã follow rồi → bỏ qua
        val t = btn.text?.lowercase() ?: ""
        if ("following" in t || "đang theo dõi" in t || "đã theo dõi" in t) return false

        host.clickNode(btn.node)
        delay((config.delayAfterFollow * 1_000).toLong())
        return true
    }

    /**
     * Để lại comment ngẫu nhiên từ danh sách config.commentTexts.
     *
     * `v2.0` Tính năng hoàn toàn mới.
     *
     * Flow:
     *   1. Tìm nút comment → click mở panel
     *   2. Chờ EditText xuất hiện → typeText()
     *   3. Tìm nút Post/Gửi → click
     *   4. Đóng panel nếu còn mở
     *
     * Trả true nếu đã gửi comment thành công.
     */
    private suspend fun doComment(texts: List<String>): Boolean {
        val root = host.getRootNode()

        // Tìm nút mở comment panel
        val commentBtn = NodeTraverser.findByResourceId(root, "btn_comment")
            ?: NodeTraverser.findByResourceId(root, "comment_btn")
            ?: NodeTraverser.findByResourceId(root, "comment_layout")
            ?: NodeTraverser.findByText(root, "comment",    ignoreCase = true)
            ?: NodeTraverser.findByText(root, "bình luận",  ignoreCase = true)
            ?: return false

        host.clickNode(commentBtn.node)
        Human.delay(1_200, 1_800)

        // Tìm ô nhập — retry vì panel animation chưa xong
        val input = retry(times = 3, delayMs = 800, tag = "commentInput") {
            NodeTraverser.findByResourceId(host.getRootNode(), "comment_input")
                ?: NodeTraverser.findByResourceId(host.getRootNode(), "et_comment")
                ?: NodeTraverser.findAllByClass(host.getRootNode(), "EditText").firstOrNull()
        }
        if (input == null) {
            host.pressBack()
            delay(500)
            return false
        }

        val text = texts[Random.nextInt(texts.size)]
        host.typeText(input.node, text)
        Human.delay(600, 1_000)

        // Tìm nút gửi
        val sendBtn = NodeTraverser.findByText(host.getRootNode(), "post",  ignoreCase = true)
            ?: NodeTraverser.findByText(host.getRootNode(), "gửi",          ignoreCase = true)
            ?: NodeTraverser.findByText(host.getRootNode(), "đăng",         ignoreCase = true)
            ?: NodeTraverser.findByResourceId(host.getRootNode(), "btn_send")

        if (sendBtn != null) {
            host.clickNode(sendBtn.node)
            Human.delay(1_000, 1_500)
        } else {
            host.pressBack()
            delay(500)
        }

        // Đóng panel nếu vẫn còn mở
        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
            host.pressBack()
            delay(800)
        }

        log("CMT: Comment: \"${text.take(20)}${if (text.length > 20) "..." else ""}\"")
        delay((config.delayAfterComment * 1_000).toLong())
        return true
    }

    /**
     * Swipe lên xem video tiếp theo.
     *
     * `v2.0` Jitter ngang nhỏ — người thật không swipe thẳng đứng hoàn toàn.
     */
    private suspend fun swipeNext() {
        val xOff = Human.jitter(12)
        host.swipeSuspend(
            screenW / 2 + xOff, (screenH * 0.75).toInt(),
            screenW / 2 + xOff, (screenH * 0.25).toInt(),
            Human.swipeDuration(350),
        )
        Human.delay(700, 1_400)
    }

    /**
     * Force swipe mạnh khi bị kẹt ở cùng 1 video.
     * Swipe 2 lần liên tiếp, nhanh hơn bình thường.
     */
    private suspend fun forceSwipeNext() {
        repeat(2) {
            host.swipeSuspend(
                screenW / 2, (screenH * 0.82).toInt(),
                screenW / 2, (screenH * 0.08).toInt(),
                200,
            )
            delay(500)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Watchdog coroutine: cảnh báo nếu video count không tăng trong `config.watchdogTimeoutSecs`.
     *
     * Chỉ log — không tự can thiệp. farmOneAccount() xử lý recovery qua isLostWithRetry().
     */
    private fun startWatchdog() {
        watchdogVideoCount = 0
        watchdogLastTick   = System.currentTimeMillis()

        watchdogJob = host.scope.launch {
            while (isActive && isFarming) {
                delay(30_000L)
                if (!isFarming || isPaused) continue
                val stuckSecs = (System.currentTimeMillis() - watchdogLastTick) / 1_000
                if (stuckSecs > config.watchdogTimeoutSecs) {
                    log("WDG: Watchdog: không có video mới trong ${stuckSecs}s — engine có thể bị kẹt")
                }
            }
        }
    }

    private fun resetWatchdog() {
        watchdogVideoCount = 0
        watchdogLastTick   = System.currentTimeMillis()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feed navigation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Chờ feed load — poll mỗi 3s, tối đa 6 lần (18s).
     * Mỗi lần xác nhận feed xong: xử lý popup 3 lần để dọn sạch.
     */
    private suspend fun waitFeedLoad(): Boolean {
        repeat(6) { i ->
            delay(3_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) {
                repeat(3) { popup.handleIfPresent(); delay(600) }
                return true
            }
            log("   Chờ feed ${i + 1}/6...")
        }
        return false
    }

    /**
     * Xác nhận bị lạc (không ở feed tab) với bộ đệm thời gian.
     *
     * Phải sai LIÊN TỤC 3 lần × 1.5s = tổng ~3s mới kết luận bị lạc.
     * Loại bỏ ~90% false-positive từ animation lag / accessibility tree chưa update.
     */
    private suspend fun isLostWithRetry(): Boolean {
        if (NodeTraverser.isOnFeedTab(host.getRootNode())) return false
        delay(1_500)
        if (NodeTraverser.isOnFeedTab(host.getRootNode())) return false
        delay(1_500)
        return !NodeTraverser.isOnFeedTab(host.getRootNode())
    }

    /**
     * Phục hồi về feed — 3 chiến lược theo thứ tự leo thang:
     *
     *   Tier 1: pressBack nhiều lần (config.maxBackAttempts)
     *   Tier 2: Click Home tab nếu nav bar đang hiển thị
     *   Tier 3: Kill + relaunch TikTok (nuclear option)
     */
    private suspend fun recoverToFeed(): Boolean {
        // Tier 1: pressBack
        repeat(config.maxBackAttempts) {
            host.pressBack()
            delay(1_200)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) return true
        }

        // Tier 2: Home tab
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(2_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) return true
        }

        // Tier 3: Kill + relaunch
        log("WARN: recoverToFeed: tier 3 — kill + relaunch TikTok")
        host.killTikTok()
        delay(2_000)
        host.launchTikTok()
        return waitFeedLoad()
    }

    /** Điều hướng về Home/Feed tab. Fallback relaunch nếu pressBack thoát TikTok. */
    private suspend fun navigateToFeedTab() {
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(2_000)
        } else {
            host.pressBack()
            delay(1_500)
            if (!NodeTraverser.hasNavBar(host.getRootNode())) {
                log("WARN: navigateToFeedTab: pressBack thoát TikTok — relaunch")
                host.launchTikTok()
                waitFeedLoad()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account detect + save
    // ─────────────────────────────────────────────────────────────────────────

    /** Click Profile tab → đọc @username → về feed. */
    private suspend fun detectCurrentAccount(): String? {
        val profileTab = NodeTraverser.findProfileTab(host.getRootNode()) ?: run {
            log("WARN: detectCurrentAccount: không tìm thấy profile tab")
            return null
        }
        host.clickNode(profileTab.node)
        delay(2_000)
        val id = NodeTraverser.getCurrentAccountId(host.getRootNode())
        navigateToFeedTab()
        if (id == null) log("WARN: detectCurrentAccount: không đọc được @username")
        return id
    }

    /** Sau switch: verify @username thực tế khớp với expected. */
    private suspend fun verifyCurrentAccount(expected: String) {
        val profileTab = NodeTraverser.findProfileTab(host.getRootNode()) ?: run {
            autoSaveAccounts(listOf(expected)); return
        }
        host.clickNode(profileTab.node)
        delay(2_000)

        val actual = NodeTraverser.getCurrentAccountId(host.getRootNode())
        when {
            actual == null ->
                log("WARN: verifyCurrentAccount: không đọc được @username — fallback save @$expected")
            !actual.equals(expected, ignoreCase = true) ->
                log("WARN: Account mismatch: expected=@$expected actual=@$actual")
            else ->
                log("OK: Đã xác nhận: @$actual")
        }
        autoSaveAccounts(listOf(actual ?: expected))
        navigateToFeedTab()
    }

    private suspend fun autoSaveAccounts(accounts: List<String>) {
        val existing = repo.getAccounts().map { it.username }.toSet()
        accounts.forEach { acc ->
            if (acc !in existing) {
                repo.addAccount(acc)
                log("SAVE: Auto-saved: @$acc")
                LanWebSocketServer.broadcast("accountDiscovered", mapOf("username" to acc))
            }
        }
        LanWebSocketServer.broadcast("accountsUpdated", mapOf("count" to accounts.size))
    }

    private suspend fun buildFarmList(
        mode:       FarmMode,
        inputList:  List<String>,
        discovered: List<String>,
    ): List<String> = when (mode) {
        FarmMode.ALL_LOCAL -> {
            if (discovered.isNotEmpty()) discovered
            else {
                log("WARN: ALL_LOCAL: discoveredList rỗng, fallback DB")
                repo.getAccounts()
                    .filter { it.status == "active" && !it.checkpoint }
                    .map { it.username }
            }
        }
        FarmMode.SELECTED_LIST -> inputList
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timing helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun randomWatchTimeMs(): Long {
        val minMs = (config.videoWatchTimeMin  * 1_000).toLong()
        val maxMs = (config.videoWatchTimeMax * 1_000).toLong()
        return if (maxMs > minMs) Random.nextLong(minMs, maxMs) else minMs
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Suspend cho đến khi resume() được gọi (hoặc ngay lập tức nếu không pause). */
    private suspend fun awaitResumed() {
        while (isPaused) delay(500)
    }

    private fun setStatus(msg: String) {
        _startupStatus.update { msg }
        OverlayFarmMonitor.setStartupStatus(msg)
    }

    private fun stopInternal(reason: String) {
        _liveFarmStats.update { LiveFarmStats() }
        isFarming = false
        isPaused  = false
        LanWebSocketServer.broadcast("farmStatus",
            mapOf("status" to "stopped", "reason" to reason))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
        host.scope.launch { repo.log(msg) }
    }

    fun onEvent(event: AccessibilityEvent) { /* reserved */ }
}
