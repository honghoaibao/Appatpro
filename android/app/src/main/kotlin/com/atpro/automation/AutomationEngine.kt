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
 *
 * v1.1.9: Tăng cường random:
 *   - swipeStartFactor / swipeEndFactor: vị trí bắt đầu/kết thúc swipe random ±8% height.
 *   - swipeDuration: phân phối phi đều — 35% slow, 65% fast.
 *   - microPause: 5% cơ hội dừng dài 800–2500ms (đang đọc caption).
 *   - likeAnimDelay: delay riêng sau double-tap, nhìn animation tim.
 */
private object Human {
    /** Delay ngẫu nhiên trong [minMs, maxMs]. */
    suspend fun delay(minMs: Long, maxMs: Long = minMs) =
        kotlinx.coroutines.delay(
            if (maxMs > minMs) Random.nextLong(minMs, maxMs) else minMs
        )

    /**
     * Micro-pause sau click/swipe — người thật không thao tác tức thì.
     * v1.1.9: 5% cơ hội "đọc caption" → dừng dài hơn.
     */
    suspend fun microPause() {
        if (Random.nextFloat() < 0.05f)
            kotlinx.coroutines.delay(Random.nextLong(800, 2_500))
        else
            kotlinx.coroutines.delay(Random.nextLong(50, 300))
    }

    /**
     * Thỉnh thoảng dừng lại lâu — mô phỏng người dùng bị phân tâm.
     * Mặc định 6% cơ hội, dừng 3–9s.
     */
    suspend fun occasionalPause(chance: Float = 0.06f) {
        if (Random.nextFloat() < chance)
            kotlinx.coroutines.delay(Random.nextLong(3_000, 9_000))
    }

    /**
     * Thời gian swipe ngẫu nhiên xung quanh baseMs.
     * v1.1.9: Phân phối phi đều: 35% chậm hơn (slow reader), 65% nhanh.
     */
    fun swipeDuration(baseMs: Long = 350): Long {
        val bias = if (Random.nextFloat() < 0.35f) Random.nextLong(60, 160)
                   else Random.nextLong(-60, 80)
        return (baseMs + bias).coerceAtLeast(140)
    }

    /** Jitter tọa độ click — tránh click cùng pixel mỗi lần. */
    fun jitter(range: Int = 25): Int = Random.nextInt(-range, range)

    /**
     * v1.1.9: Vị trí bắt đầu swipe ngẫu nhiên — dao động 68%–82% screen height.
     * Người thật không luôn bắt đầu swipe từ cùng một điểm.
     */
    fun swipeStartFactor(): Float = 0.68f + Random.nextFloat() * 0.14f

    /**
     * v1.1.9: Vị trí kết thúc swipe ngẫu nhiên — dao động 18%–30% screen height.
     */
    fun swipeEndFactor(): Float = 0.18f + Random.nextFloat() * 0.12f

    /**
     * v1.1.9: Delay sau double-tap like — người thật đôi khi nhìn animation tim.
     */
    suspend fun likeAnimDelay() = kotlinx.coroutines.delay(Random.nextLong(350, 900))
}


// ─────────────────────────────────────────────────────────────────────────────
// retry — helper chạy lại với exponential backoff
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Retry block tối đa times lần.
 * Trả null nếu tất cả lần đều fail.
 * Delay giữa các lần tăng dần: delayMs * backoff ^ attempt.
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
    companion object {
        const val TAG = "AutomationEngine"
        /** Số lần skip (ad/live/diary) liên tiếp tối đa trước khi force-swipe mạnh hơn. */
        private const val MAX_CONSECUTIVE_SKIPS = 8
    }

    var isFarming = false; private set
    var isPaused  = false; private set
    /** True khi engine đang trong giai đoạn nghỉ giữa 2 acc — watchdog bỏ qua để không false-alarm. */
    private var isResting = false

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
    // [v1.1.9+] Set true bởi watchdog khi phát hiện không có video mới quá lâu.
    //     farmOneAccount() sẽ đọc flag này và thực hiện smart recovery.
    @Volatile private var watchdogStuckFlag  = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun startFarm(mode: FarmMode, inputList: List<String> = emptyList()) {
        if (isFarming) return
        isFarming = true
        isPaused  = false
        isResting = false

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            try {
                runStateMachine(mode, inputList)
            } catch (e: CancellationException) {
                log("Farm bị hủy bởi người dùng")
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

        setStatus("Chờ feed tải...")
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

        setStatus("${farmList.size} tài khoản sẽ được nuôi")

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

        farmList.forEachIndexed { idx, account ->
            if (!isFarming) return@forEachIndexed
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
                    return@forEachIndexed
                }
            }

            farmedSet.add(account.lowercase())
            resetWatchdog()

            val secsLeft = maxOf(0L, totalSecs - elapsed)
            val result   = farmOneAccount(account, idx, farmList.size, secsLeft)

            sessionLikes   += result.likes
            sessionFollows += result.follows
            sessionVideos  += result.videos
            elapsed        += config.minutesPerAccount * 60L

            AtProNotificationManager.notifySessionDone(
                account, result.likes, result.follows, result.videos)

            // Nghỉ giữa acc (không nghỉ sau acc cuối)
            if (config.enableRestBetweenAccounts && idx < farmList.size - 1) {
                val restSecs = config.restDurationMinutes * 60L
                log("REST: Nghỉ ${config.restDurationMinutes}m trước acc tiếp...")

                // [v1.1.8] Báo watchdog: đang nghỉ — không phải stuck, không cần recovery.
                isResting = true

                // [v1.1.9+] Thoát TikTok hoàn toàn trước khi nghỉ (Restart the app)
                log("REST: Đóng TikTok trước khi nghỉ")
                host.killTikTok()
                setStatus("REST: Đã đóng TikTok — bắt đầu nghỉ...")
                delay(1_000)

                // [v1.1.9] Đếm ngược từng giây thay vì delay tĩnh
                for (remaining in restSecs downTo 1L) {
                    awaitResumed()
                    val mm = remaining / 60L
                    val ss = remaining % 60L
                    setStatus("REST: Đang nghỉ %02d:%02d...".format(mm, ss))
                    delay(1_000L)
                }

                // [v1.1.9+] Mở lại TikTok sau khi nghỉ xong, chờ feed ổn định
                log("REST: Nghỉ xong — mở lại TikTok để tiếp tục nuôi")
                setStatus("REST: Đang mở lại TikTok...")
                host.launchTikTok()
                if (!waitFeedLoad()) {
                    log("WARN: REST: Feed chưa load sau relaunch — thử recoverToFeed()")
                    recoverToFeed()
                }

                // Reset watchdog TRƯỚC khi bỏ cờ isResting —
                // tránh watchdog đếm thời gian nghỉ vào stuckSecs của video đầu tiên.
                resetWatchdog()
                isResting = false
                setStatus("")
            }
        }

        watchdogJob?.cancel()

        AtProNotificationManager.notifyFarmCompleted(
            farmList.size, sessionLikes, sessionFollows, sessionVideos,
            farmList.size * config.minutesPerAccount,
        )

        // [v1.1.9+] Thoát TikTok sau khi nuôi xong — không để app chạy nền
        log("DONE: Farm hoàn thành tất cả ${farmList.size} tài khoản — đóng TikTok")
        delay(800)
        host.killTikTok()

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
            setStatus("✓ Tìm thấy ${discovered.size} tài khoản trong popup")
            autoSaveAccounts(discovered)
        } else {
            log("WARN: parseAccountList rỗng — TikTok dùng display name thay @username?")
            setStatus("WARN: Không đọc được danh sách tài khoản")
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
            log("OK: @$target đang active — đóng popup, về feed")
            host.pressBack()  // Đóng switch popup
            delay(800)
            host.pressBack()  // Đóng Settings
            // BUG-FEED-001: delay 800ms không đủ cho TikTok Settings đóng hoàn toàn.
            // Single isOnFeedTab() check gây false-negative → recoverToFeed() loop.
            // Fix: dùng waitFeedLoad() (retry 18s) thay vì one-shot check.
            if (!waitFeedLoad()) {
                log("WARN: Feed chưa hiện sau pressBack×2 → recoverToFeed")
                recoverToFeed()
            } else true
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

        // [FIX-2] Thay delay(2_000) cứng bằng waitFeedLoad() để đảm bảo feed
        // thực sự sẵn sàng trước khi vào loop — tránh isLostWithRetry() kích hoạt
        // ngay lập tức và khiến app vòng lặp lost→recover vô hạn mà không tới step [5].
        setStatus("Chờ feed ổn định (@$account)...")
        if (!waitFeedLoad()) {
            log("WARN: farmOneAccount: feed chưa load sau waitFeedLoad() → recoverToFeed()")
            if (!recoverToFeed()) {
                log("ERR: farmOneAccount: recoverToFeed() thất bại — skip @$account")
                repo.closeSession(sessionId, account, 0, 0, 0, 0)
                return AccountResult(account, reason = "feed_timeout")
            }
        }
        setStatus("")

        val deadline = System.currentTimeMillis() + config.minutesPerAccount * 60_000L

        var videos   = 0; var likes = 0; var follows = 0; var comments = 0
        var lostStreak         = 0  // Số lần isLostWithRetry() liên tiếp
        // [v1.1.9+] Đếm số lần skip (ad/live/diary) liên tiếp mà không xem được video nào.
        // Nếu >= MAX_CONSECUTIVE_SKIPS: có thể TikTok đang không phản hồi swipe
        // → dùng forceSwipeNext() + delay dài hơn để thoát khỏi stuck state.
        var consecutiveSkipCount = 0

        // [FIX-1] Pre-loop update — đẩy thông tin acc/session/time lên overlay + Dashboard
        // NGAY TRƯỚC khi vào while loop, tránh hiển thị "--" khi bước [1]-[4] dùng continue/break.
        val initSessionSecs = config.minutesPerAccount * 60L
        _liveFarmStats.update {
            LiveFarmStats(
                account       = account,
                index         = idx + 1,
                total         = total,
                videos        = 0,
                likes         = 0,
                follows       = 0,
                comments      = 0,
                remainingSecs = initSessionSecs.toInt(),
            )
        }
        OverlayFarmMonitor.update(
            accountIndex    = idx + 1,
            accountTotal    = total,
            accountId       = account,
            sessionSecsLeft = initSessionSecs,
            totalSecsLeft   = totalSecsLeft,
            action          = "Khởi động...",
        )
        LanWebSocketServer.broadcast("currentAccount", mapOf(
            "account" to account,
            "index"   to idx + 1,
            "total"   to total,
        ))

        while (System.currentTimeMillis() < deadline && isFarming) {
            awaitResumed()
            val secsLeft = maxOf(0L, (deadline - System.currentTimeMillis()) / 1_000L)

            // [v1.1.8] getRootNode() là IPC call qua accessibility service.
            // Cache 1 lần mỗi iteration để tái sử dụng ở steps 1–6.
            // Ngoại lệ: popup.handleIfPresent() tự lấy root riêng (nó cần root fresh sau khi click).
            val root = host.getRootNode()

            // ── [1] Checkpoint ────────────────────────────────────────────
            if (NodeTraverser.detectCheckpoint(root)) {
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

            // ── [2b] Wellbeing / Swipe-to-return screen ─────────────────
            // [v1.1.4] Màn hình nghỉ ngơi TikTok — click "Quay lại ngay bây giờ".
            // [v1.1.9+] Swipe-to-return screen — click "Tạm thời quay lại" (hãy kéo).
            // Cả 2 loại đều được xử lý bởi findReturnFromWellbeingButton().
            val wellbeingBtn = NodeTraverser.findReturnFromWellbeingButton(root)
            if (wellbeingBtn != null) {
                val btnText = wellbeingBtn.text?.take(30) ?: "?"
                log("WELLBEING: Màn hình wellbeing TikTok — click '$btnText'")
                host.clickNode(wellbeingBtn.node)
                delay(1_500)
                lostStreak = 0
                continue
            }

            // ── [2c] Daily screen time limit ─────────────────────────────
            // [v1.1.8] TikTok hiển thị màn hình giới hạn thời gian sử dụng hằng ngày.
            // Nhập mật mã (mặc định: 1234) và click "Quay lại TikTok".
            // PHẢI xử lý TRƯỚC isLostWithRetry(): màn hình này không phải feed
            // → isLostWithRetry() = true → recoverToFeed() vô ích (pressBack không hoạt động).
            if (NodeTraverser.detectDailyLimitScreen(root)) {
                log("LIMIT: Daily screen time limit — xử lý tự động")
                popup.handleIfPresent()
                delay(2_000)
                lostStreak = 0
                continue
            }

            // ── [3] Lost detection ────────────────────────────────────────
            if (isLostWithRetry()) {
                val dbgTexts = NodeTraverser.dumpScreenTexts(root)
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

            // ── [3b] Watchdog stuck recovery ─────────────────────────────
            // [v1.1.9+] Khi watchdog phát hiện không có video mới quá lâu,
            // thực hiện smart recovery thay vì đứng yên:
            //   1. Back × maxBackAttempts
            //   2. Kiểm tra & xử lý popup chặn
            //   3. Kiểm tra về feed chưa
            //   4. Nếu chưa → kill + relaunch TikTok
            if (watchdogStuckFlag) {
                watchdogStuckFlag = false
                log("WDG: Thực hiện smart recovery (không có video mới quá lâu)")
                val recovered = recoverFromStuck()
                if (!recovered) {
                    log("ERR: recoverFromStuck() thất bại — kết thúc sớm @$account")
                    break
                }
                resetWatchdog()
                continue
            }

            // ── [4] Update overlay ────────────────────────────────────────
            val sessionSecsRemain = secsLeft
            val totalSecsRemain   = maxOf(0L,
                totalSecsLeft - (config.minutesPerAccount * 60L - sessionSecsRemain))
            OverlayFarmMonitor.update(
                accountIndex    = idx + 1,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecsRemain,
                totalSecsLeft   = totalSecsRemain,
                action          = if (videos == 0) "Chuẩn bị..." else "Xem video",
            )

            // ── [5a] Skip ad ──────────────────────────────────────────────
            // [v1.1.3] Phát hiện quảng cáo TikTok trong feed → lướt video tiếp theo.
            // [v1.1.9] Tôn trọng config.skipAds — chỉ skip khi người dùng bật.
            if (config.skipAds && NodeTraverser.detectAd(root)) {
                log("SKIP: Bỏ qua quảng cáo")
                consecutiveSkipCount++
                if (consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS) {
                    log("WARN: Skip liên tiếp ${consecutiveSkipCount}× (ad) → forceSwipeNext")
                    forceSwipeNext(); delay(2_000); consecutiveSkipCount = 0
                } else {
                    swipeNext()
                }
                continue
            }

            // ── [6] Skip live ─────────────────────────────────────────────
            if (config.skipLive && NodeTraverser.detectLive(root)) {
                log("SKIP: Bỏ qua live")
                consecutiveSkipCount++
                if (consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS) {
                    log("WARN: Skip liên tiếp ${consecutiveSkipCount}× (live) → forceSwipeNext")
                    forceSwipeNext(); delay(2_000); consecutiveSkipCount = 0
                } else {
                    swipeNext()
                }
                continue
            }

            // ── [6b] Skip diary overlay ───────────────────────────────────
            // [v1.1.9] TikTok hiển thị nút "Xem Nhật ký" chồng lên video
            // (kèm "Nhật ký khác trong 'Hộp thư'") — swipe qua để tiếp tục farm.
            if (NodeTraverser.detectDiary(root)) {
                log("SKIP: Bỏ qua Xem Nhật ký")
                consecutiveSkipCount++
                if (consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS) {
                    log("WARN: Skip liên tiếp ${consecutiveSkipCount}× (diary) → forceSwipeNext")
                    forceSwipeNext(); delay(2_000); consecutiveSkipCount = 0
                } else {
                    swipeNext()
                }
                continue
            }

            // ── [7] Watch video ───────────────────────────────────────────
            // [v1.1.9] swipeStartFactor() random 68–82% xử lý image post carousel tự nhiên:
            // vị trí bắt đầu thay đổi mỗi lần → tránh nhất quán chạm vào thanh dot ••••.
            val watchMs = randomWatchTimeMs()
            val watchSecs = (watchMs / 1_000L).coerceAtLeast(1L)
            for (w in watchSecs downTo 1L) {
                OverlayFarmMonitor.update(
                    accountIndex    = idx + 1,
                    accountTotal    = total,
                    accountId       = account,
                    sessionSecsLeft = maxOf(0L, (deadline - System.currentTimeMillis()) / 1_000L),
                    totalSecsLeft   = maxOf(0L, totalSecsLeft - (config.minutesPerAccount * 60L - maxOf(0L, (deadline - System.currentTimeMillis()) / 1_000L))),
                    action          = if (videos == 0) "Chuẩn bị..." else "Xem video (${w}s)",
                )
                delay(1_000L)
            }
            val remainder = watchMs % 1_000L
            if (remainder > 0L) delay(remainder)
            videos++
            consecutiveSkipCount = 0  // [v1.1.9+] reset sau khi xem được 1 video thành công
            watchdogVideoCount = videos
            watchdogLastTick   = System.currentTimeMillis()

            Human.occasionalPause(config.occasionalPauseChance)

            // ── [8] Like — content-aware gate ────────────────────────────
            // Phát hiện loại nội dung TRƯỚC khi quyết định like:
            //   • Live → không bao giờ like (dù skipLive = false)
            //   • Ad   → chỉ like nếu config.likeAdsEnabled = true
            //   • [v1.2.0] captionKeywords / hashtagKeywords → luôn like nếu khớp
            //   • Còn lại → like theo likeRate
            // [FIX] Lấy freshRoot mới SAU khi watch xong — `root` đã stale (3–8s).
            // [v1.1.9] Cache freshRoot — tái sử dụng cho Follow, giảm số lần gọi IPC.
            val freshRoot = host.getRootNode()
            val isLiveContent = NodeTraverser.detectLive(freshRoot)
            val isAdContent   = !isLiveContent && NodeTraverser.detectAd(freshRoot)

            // [v1.2.0] Content-aware: kiểm tra caption/hashtag keywords khi tính năng bật.
            // Chỉ chạy scan khi feature enabled — tránh overhead khi tắt.
            val contentMatch = !isLiveContent && !isAdContent && run {
                val captionHit = config.likeByCaption && config.captionKeywords.isNotEmpty() &&
                    config.captionKeywords.any { kw ->
                        kw.lowercase() in NodeTraverser.extractVideoCaption(freshRoot).lowercase()
                    }
                if (captionHit) return@run true
                config.likeByHashtag && config.hashtagKeywords.isNotEmpty() &&
                    config.hashtagKeywords.any { kw ->
                        kw.lowercase() in NodeTraverser.extractVideoHashtags(freshRoot)
                    }
            }

            // contentMatch → bypass likeRate; không match → dùng likeRate bình thường
            val passRateGate = contentMatch || Random.nextFloat() < config.likeRate
            if (passRateGate) {
                val shouldLike = when {
                    isLiveContent -> false
                    isAdContent   -> config.likeAdsEnabled
                    else          -> true
                }
                if (shouldLike) {
                    val action = if (contentMatch) "LIKE: Khớp từ khoá" else "LIKE: Thích"
                    OverlayFarmMonitor.update(idx + 1, total, account,
                        sessionSecsRemain, totalSecsRemain, action)
                    // [v1.2.0] Micro-pause trước like — người thật dừng ngắn khi thích video
                    Human.delay(450, 1_200)
                    doLike()
                    likes++
                } else if (isLiveContent) {
                    log("LIKE SKIP: Không like live")
                } else if (isAdContent) {
                    log("LIKE SKIP: Không like quảng cáo (likeAds=off)")
                }
            }

            // ── [9] Follow ────────────────────────────────────────────────
            // [v1.1.9] Tái sử dụng freshRoot từ bước Like — tránh gọi getRootNode() lần nữa.
            if (Random.nextFloat() < config.followRate) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "FOLLOW: Theo dõi")
                if (doFollowWithRoot(freshRoot)) follows++
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

            // ── [10b] Diversion: Hộp thư / Cửa hàng [v1.1.9+] ────────────
            // Thỉnh thoảng ghé thăm tab phụ trước khi lướt — hành vi tự nhiên hơn.
            // Chỉ 1 tab mỗi lần (ưu tiên inbox). Các hàm doView* tự về feed.
            run diversion@{
                val curSecs  = maxOf(0L, (deadline - System.currentTimeMillis()) / 1_000L)
                val curTotal = maxOf(0L, totalSecsLeft -
                    (config.minutesPerAccount * 60L - curSecs))
                if (config.inboxViewRate > 0f && Random.nextFloat() < config.inboxViewRate) {
                    doViewInbox(idx + 1, total, account, curSecs, curTotal)
                    return@diversion
                }
                if (config.shopViewRate > 0f && Random.nextFloat() < config.shopViewRate) {
                    doViewShop(idx + 1, total, account, curSecs, curTotal)
                    return@diversion
                }
                // [v1.2.0] Phiên tìm kiếm từ khoá — độc lập với inbox/shop
                if (config.searchEnabled
                    && config.searchKeywords.isNotEmpty()
                    && Random.nextFloat() < config.searchRate
                ) {
                    doSearchSession(idx + 1, total, account, curSecs, curTotal)
                }
            }

            // ── [11] Swipe next ───────────────────────────────────────────
            // swipeNext() dùng swipeStartFactor(68–82%) + swipeEndFactor(18–30%) ngẫu nhiên
            // → tự nhiên tránh thanh dot •••• của image carousel mà không cần detect.
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
     * v1.1.9: likeAnimDelay() sau double-tap — người thật thường nhìn animation tim.
     */
    private suspend fun doLike() {
        val cx = screenW / 2 + Human.jitter(25)
        val cy = (screenH * 0.55).toInt() + Human.jitter(20)
        host.clickSuspend(cx, cy)
        Human.microPause()
        host.clickSuspend(cx + Human.jitter(8), cy + Human.jitter(8))
        Human.likeAnimDelay()
        delay((config.delayAfterLike * 1_000).toLong())
    }

    /**
     * Follow tác giả video hiện tại.
     *
     * Kiểm tra text nút trước khi click — bỏ qua nếu đã follow.
     * Trả true nếu đã thực sự click (chưa follow).
     */
    private suspend fun doFollow(): Boolean = doFollowWithRoot(host.getRootNode())

    /**
     * v1.1.9: Overload nhận root đã fetch — tái sử dụng từ bước Like,
     * giảm số lần gọi getRootNode() (IPC qua accessibility service).
     */
    private suspend fun doFollowWithRoot(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Tab diversion: Hộp thư / Cửa hàng  [v1.1.9+]
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ghé qua tab Hộp thư — xem `config.inboxViewDurationSecs` giây rồi về feed.
     * Gọi từ step [10b] trong farmOneAccount(), sau khi đã xem + tương tác xong.
     */
    private suspend fun doViewInbox(
        idx:         Int,
        total:       Int,
        account:     String,
        sessionSecs: Long,
        totalSecs:   Long,
    ) {
        val inboxTab = NodeTraverser.findInboxTab(host.getRootNode()) ?: run {
            log("WARN: Không tìm thấy tab Hộp thư — bỏ qua")
            return
        }
        log("INBOX: Ghé qua Hộp thư (${config.inboxViewDurationSecs}s)")
        host.clickNode(inboxTab.node)
        delay(1_500)

        val viewSecs = config.inboxViewDurationSecs.toLong().coerceAtLeast(5L)
        for (s in viewSecs downTo 1L) {
            OverlayFarmMonitor.update(
                accountIndex    = idx,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = maxOf(0L, sessionSecs - (viewSecs - s)),
                totalSecsLeft   = maxOf(0L, totalSecs   - (viewSecs - s)),
                action          = "Hộp thư (${s}s)",
            )
            delay(1_000L)
        }

        // Về feed
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
        } else {
            host.pressBack()
        }
        delay(1_200)
        log("INBOX: Xong → về feed")
    }

    /**
     * Ghé qua tab Cửa hàng — cuộn `config.shopScrollCount` lần rồi về feed.
     * Gọi từ step [10b] trong farmOneAccount(), sau khi đã xem + tương tác xong.
     */
    private suspend fun doViewShop(
        idx:         Int,
        total:       Int,
        account:     String,
        sessionSecs: Long,
        totalSecs:   Long,
    ) {
        val shopTab = NodeTraverser.findShopTab(host.getRootNode()) ?: run {
            log("WARN: Không tìm thấy tab Cửa hàng — bỏ qua")
            return
        }
        val scrollCount = config.shopScrollCount.coerceIn(1, 10)
        log("SHOP: Ghé qua Cửa hàng (${scrollCount} lần cuộn)")
        host.clickNode(shopTab.node)
        delay(2_000)

        repeat(scrollCount) { i ->
            OverlayFarmMonitor.update(
                accountIndex    = idx,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecs,
                totalSecsLeft   = totalSecs,
                action          = "Cửa hàng (${i + 1}/${scrollCount})",
            )
            host.swipeSuspend(
                screenW / 2, (screenH * 0.70).toInt(),
                screenW / 2, (screenH * 0.30).toInt(),
                Human.swipeDuration(400),
            )
            Human.delay(1_500, 2_500)
        }

        // Về feed
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(1_500)
        } else {
            host.pressBack()
            delay(1_500)
        }
        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
            log("SHOP: Chưa về feed → recoverToFeed()")
            recoverToFeed()
        } else {
            log("SHOP: Xong → về feed")
        }
    }


    /**
     * Phiên tìm kiếm theo từ khoá — v1.2.0.
     *
     * Flow:
     * 1. Click icon tìm kiếm (kính lúp) trên feed.
     * 2. Tìm ô input → gõ từng ký tự keyword (random delay 80–220ms/ký tự).
     * 3. Nhấn ACTION_IME_ENTER → đợi kết quả.
     * 4. Click vào video đầu tiên trong kết quả (vùng 35–45% chiều cao màn hình).
     * 5. Xem [config.searchVideosPerSession] video (swipeNext giữa các video).
     * 6. Back từng bước, kiểm tra isOnFeedTab() sau mỗi back đến khi về feed.
     */
    private suspend fun doSearchSession(
        idx:         Int,
        total:       Int,
        account:     String,
        sessionSecs: Long,
        totalSecs:   Long,
    ) {
        val keyword = config.searchKeywords.randomOrNull() ?: return
        log("SEARCH: Bắt đầu tìm kiếm "$keyword"")

        // 1. Click tab tìm kiếm
        val searchTab = NodeTraverser.findSearchTab(host.getRootNode()) ?: run {
            log("SEARCH: WARN — Không tìm thấy nút tìm kiếm, bỏ qua")
            return
        }
        host.clickNode(searchTab.node)
        Human.delay(1_200, 2_000)

        // 2. Điền từ khoá vào ô input
        val freshRoot  = host.getRootNode()
        val inputField = NodeTraverser.findSearchInputField(freshRoot) ?: run {
            log("SEARCH: WARN — Không tìm thấy ô nhập, quay về")
            host.pressBack()
            return
        }
        host.clickNode(inputField.node)
        Human.delay(350, 600)
        // Gõ từng ký tự — delay ngẫu nhiên mô phỏng người gõ thực
        host.typeText(inputField.node, keyword)
        Human.delay(600, 1_000)

        // 3. Submit search bằng IME Enter
        inputField.node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_IME_ENTER)
        Human.delay(1_600, 2_600)

        // 4. Click video đầu tiên trong kết quả
        //    TikTok search result: grid 2 cột — click cột trái, hàng đầu (~35–42% chiều cao)
        OverlayFarmMonitor.update(idx, total, account, sessionSecs, totalSecs, "SEARCH: "$keyword"")
        host.clickSuspend(
            screenW / 4 + Human.jitter(20),
            (screenH * (0.36 + Random.nextDouble() * 0.06)).toInt(),
        )
        Human.delay(1_000, 1_800)

        // 5. Xem N video
        val videoCount = config.searchVideosPerSession.coerceIn(1, 10)
        repeat(videoCount) { i ->
            OverlayFarmMonitor.update(
                accountIndex    = idx,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = sessionSecs,
                totalSecsLeft   = totalSecs,
                action          = "SEARCH: Video ${i + 1}/$videoCount",
            )
            val watchMs = randomWatchTimeMs()
            delay(watchMs)
            if (i < videoCount - 1) swipeNext()
        }

        // 6. Back từng bước về feed — kiểm tra sau mỗi lần back
        var backCount = 0
        while (!NodeTraverser.isOnFeedTab(host.getRootNode()) && backCount < 10) {
            host.pressBack()
            Human.delay(700, 1_100)
            backCount++
        }
        if (NodeTraverser.isOnFeedTab(host.getRootNode())) {
            log("SEARCH: Xong "$keyword" → về feed (back ${backCount}x)")
        } else {
            log("SEARCH: Chưa về feed → recoverToFeed()")
            recoverToFeed()
        }
    }

    /**
     * Swipe lên xem video tiếp theo.
     *
     * `v2.0` Jitter ngang nhỏ — người thật không swipe thẳng đứng hoàn toàn.
     * v1.1.9: Dùng swipeStartFactor/EndFactor ngẫu nhiên — vị trí swipe thay đổi mỗi lần.
     */
    private suspend fun swipeNext() {
        val xOff = Human.jitter(12)
        val x    = screenW / 2 + xOff
        host.swipeSuspend(
            x, (screenH * Human.swipeStartFactor()).toInt(),
            x, (screenH * Human.swipeEndFactor()).toInt(),
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
                // Bỏ qua khi đã dừng, đang pause, hoặc đang nghỉ giữa acc —
                // không có video mới trong REST là đúng, không phải stuck.
                if (!isFarming || isPaused || isResting) continue
                val stuckSecs = (System.currentTimeMillis() - watchdogLastTick) / 1_000
                if (stuckSecs > config.watchdogTimeoutSecs) {
                    log("WDG: Không có video mới trong ${stuckSecs}s — kích hoạt smart recovery")
                    watchdogStuckFlag = true
                    // Reset timer để không kích hoạt lại ngay lập tức
                    watchdogLastTick = System.currentTimeMillis()
                }
            }
        }
    }

    private fun resetWatchdog() {
        watchdogVideoCount = 0
        watchdogLastTick   = System.currentTimeMillis()
        watchdogStuckFlag  = false
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
                // [FIX-WAITFEED-POPUP] Đã bỏ repeat(3) { popup.handleIfPresent() } ở đây.
                // scanKeywords() false positive (caption video) → pressBack() ngay sau confirm feed
                // → navigate khỏi feed dù waitFeedLoad() đã trả true → Lost loop.
                // Popup thật sẽ được xử lý tự nhiên trong vòng lặp farm chính (farmOneAccount step [2]).
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
     * Smart recovery khi watchdog phát hiện không có video mới quá lâu.
     *
     * Quy trình leo thang:
     *   1. pressBack × config.maxBackAttempts — mỗi lần kiểm tra về feed ngay
     *   2. Kiểm tra & xử lý popup đang chặn (nếu có)
     *   3. Kiểm tra feed lần cuối
     *   4. Kill + relaunch TikTok nếu vẫn chưa về feed
     */
    private suspend fun recoverFromStuck(): Boolean {
        log("WDG-RECOVER: Back ×${config.maxBackAttempts} để thoát màn hình chặn")
        repeat(config.maxBackAttempts) { i ->
            host.pressBack()
            delay(1_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) {
                log("WDG-RECOVER: Về feed sau ${i + 1} lần back ✓")
                return true
            }
        }

        // Kiểm tra popup chặn
        val popupResult = popup.handleIfPresent()
        if (popupResult.handled) {
            log("WDG-RECOVER: Đã xử lý popup chặn")
            delay(1_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) {
                log("WDG-RECOVER: Về feed sau xử lý popup ✓")
                return true
            }
        }

        // Vẫn chưa về feed → reset hoàn toàn
        log("WDG-RECOVER: Vẫn chưa về feed — kill + relaunch TikTok")
        host.killTikTok()
        delay(2_000)
        host.launchTikTok()
        return waitFeedLoad()
    }

    /**
     * Phục hồi về feed — 5 chiến lược theo thứ tự leo thang:
     *
     *   Tier 0a: (v1.1.8) Nhập passcode nếu đang ở Daily Screen Time Limit
     *   Tier 0b: (v1.1.4) Click "Quay lại ngay bây giờ" nếu đang ở Wellbeing screen
     *   Tier 1: pressBack nhiều lần (config.maxBackAttempts)
     *   Tier 2: Click Home tab nếu nav bar đang hiển thị
     *   Tier 3: Kill + relaunch TikTok (nuclear option)
     */
    private suspend fun recoverToFeed(): Boolean {
        // Tier 0a: [v1.1.8] Daily screen time limit — nhập passcode + click "Quay lại TikTok"
        // pressBack không hoạt động; phải nhập passcode và click button.
        if (NodeTraverser.detectDailyLimitScreen(host.getRootNode())) {
            log("RECOVER: Daily limit screen — nhập mật mã và tiếp tục")
            popup.handleIfPresent()
            delay(2_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) return true
        }

        // Tier 0b: [v1.1.4+] Wellbeing / Swipe-to-return screen
        // pressBack KHÔNG hoạt động trên 2 loại màn hình này; phải click button.
        //   - "Quay lại ngay bây giờ" (nghỉ ngơi/hít thở)
        //   - "Tạm thời quay lại" (hãy kéo — swipe-to-return, v1.1.9+)
        val wellbeingBtn = NodeTraverser.findReturnFromWellbeingButton(host.getRootNode())
        if (wellbeingBtn != null) {
            val btnText = wellbeingBtn.text?.take(30) ?: "?"
            log("RECOVER: Wellbeing/swipe screen — click '$btnText'")
            host.clickNode(wellbeingBtn.node)
            delay(2_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) return true
        }

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
        isResting = false
        LanWebSocketServer.broadcast("farmStatus",
            mapOf("status" to "stopped", "reason" to reason))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
        host.scope.launch { repo.log(msg) }
        OverlayFarmMonitor.addLog(msg)   // [v1.1.0] hiển thị log lên overlay popup
    }

    fun onEvent(event: AccessibilityEvent) { /* reserved */ }
}
