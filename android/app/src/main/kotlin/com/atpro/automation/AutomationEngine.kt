package com.atpro.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.atpro.accessibility.NodeTraverser
import com.atpro.automation.popup.PopupHandler
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.data.TaskJobType
import com.atpro.golike.GolikeRepository
import com.atpro.golike.TikTokAccountDto
import com.atpro.golike.TikTokJobDto
import com.atpro.golike.GolikeResult
import com.atpro.network.LanWebSocketServer
import com.atpro.notification.AtProNotificationManager
import com.atpro.security.AppConstants
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

/** v1.2.1 — Chế độ dịch vụ: nuôi tài khoản hoặc làm nhiệm vụ Golike. */
/** v1.2.3: thêm FACEBOOK_NURTURE — demo nuôi acc Facebook (mở app → lướt feed → like → đóng). */
/** v1.2.4: thêm X_NURTURE, INSTAGRAM_NURTURE, THREADS_NURTURE, SNAPCHAT_NURTURE — demo nuôi acc. */
enum class ServiceMode { FARM, TASK, FACEBOOK_NURTURE, X_NURTURE, INSTAGRAM_NURTURE, THREADS_NURTURE, SNAPCHAT_NURTURE }

/** v1.2.1 — Kết quả thao tác follow từ hồ sơ. */
internal enum class FollowResult {
    SUCCESS,           // Follow thành công + xác nhận
    NO_BUTTON,         // Không có nút Follow (đã follow hoặc không có)
    BLOCKED,           // Profile bị khoá / riêng tư
    ALREADY_FOLLOWING, // Đã theo dõi rồi
    UNCONFIRMED,       // Click rồi nhưng không xác nhận được kết quả
}

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
     * v1.2.3 [FIX] — Khoảng nghỉ giữa 2 tap của double-tap LIKE.
     *
     * Trước v1.2.3, doLike()/doLikeTask() dùng microPause() cho khoảng nghỉ
     * này — đôi khi rơi vào nhánh "đọc caption" (800–2500ms) hoặc gần
     * ngưỡng 300ms, vượt quá double-tap timeout của Android/TikTok
     * (~300ms). Khi đó 2 tap bị nhận diện là 2 SINGLE-TAP riêng lẻ:
     * tap 1 → pause video, tap 2 → resume → video bị "dừng" (giật khung)
     * và KHÔNG có animation tim/like.
     *
     * doubleTapGap() luôn nằm trong 60–150ms — đủ tự nhiên (không phải
     * 0ms tuyệt đối) nhưng chắc chắn dưới ngưỡng double-tap.
     */
    suspend fun doubleTapGap() = kotlinx.coroutines.delay(Random.nextLong(60, 150))

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
// safeStep — v1.2.3
// ─────────────────────────────────────────────────────────────────────────────

/**
 * v1.2.3 — Bọc 1 "bước" (action) trong farm loop với timeout + try/catch.
 *
 * Lý do: trước v1.2.3, một exception (vd: AccessibilityNodeInfo đã stale/
 * "view is detached from window") ném ra từ giữa 1 action (LIKE, FOLLOW,
 * COMMENT, diversion, swipe...) sẽ:
 *   - propagate lên tới startFarm()'s catch(Exception) → log "Lỗi không
 *     mong đợi" → finally → stopInternal() → TOÀN BỘ farm dừng, dù mới
 *     chỉ lỗi ở 1 hành động của 1 video.
 *   - hoặc nếu 1 gesture/IPC bị treo (không bao giờ resume callback),
 *     coroutine bị "đứng" vô hạn ở bước đó — log cuối cùng người dùng thấy
 *     là hành động đang treo (vd "LIKE: Thích") và farm không tiến tiếp.
 *
 * safeStep() đảm bảo MỌI bước:
 *   - Có timeout [timeoutMs] (mặc định 45s) — quá hạn → log cảnh báo, trả null.
 *   - Exception (trừ CancellationException) được bắt + log, KHÔNG propagate.
 *
 * → Farm luôn tiến tới bước tiếp theo (vd: swipeNext() sau LIKE) bất kể
 *   bước hiện tại thành công, lỗi, hay timeout.
 */
private suspend fun <T> safeStep(
    stepName:  String,
    timeoutMs: Long = 45_000L,
    block:     suspend () -> T,
): T? = try {
    val result = withTimeoutOrNull(timeoutMs) { block() }
    if (result == null) {
        Log.w(AutomationEngine.TAG, "safeStep[$stepName]: timeout ${timeoutMs}ms — bỏ qua, tiếp tục")
    }
    result
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(AutomationEngine.TAG, "safeStep[$stepName]: ${e.javaClass.simpleName} — ${e.message}")
    null
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
    private val host:       IFarmHost,
    private val repo:       IFarmRepository,
    private val golikeRepo: GolikeRepository? = null,
) {
    companion object {
        const val TAG = "AutomationEngine"
        /** Số lần skip (ad/live/diary) liên tiếp tối đa trước khi force-swipe mạnh hơn. */
        private const val MAX_CONSECUTIVE_SKIPS = 8
        /**
         * v1.2.1 — Tần suất kiểm tra isOnFeedTab() TRONG khi xem video (đơn vị: giây).
         * Mỗi N giây watch loop gọi 1 getRootNode() để phát hiện drift sớm
         * (live auto-play, popup che phủ, v.v.) thay vì đợi hết watchSecs.
         * 5s = ~1 IPC call/5s — đủ nhanh và không gây overhead đáng kể.
         */
        private const val FEED_CHECK_INTERVAL_SECS = 5L
    }

    var isFarming = false; private set
    var isPaused  = false; private set
    /** True khi engine đang trong giai đoạn nghỉ giữa 2 acc — watchdog bỏ qua để không false-alarm.
     *  @Volatile: watchdog chạy trên coroutine riêng — cần visibility đảm bảo. */
    @Volatile private var isResting = false

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

    /**
     * v1.2.6 — Flag báo hiệu đang trong quá trình chuẩn hoá acc (normalize).
     * Khi true:
     *   - watchdog KHÔNG kích hoạt stuck recovery
     *   - isLostWithRetry() luôn trả về false (tắt hoàn toàn)
     *   - recoverToFeed() KHÔNG được gọi tự động
     * Flag này đảm bảo normalize chạy độc lập, không bị can thiệp bởi
     * các hàm nhận dạng lạc đang chạy song song.
     */
    @Volatile private var isNormalizing = false

    /**
     * v1.2.6 — Flag tắt lost/feed detection trong mọi giai đoạn ĐANG THỰC HIỆN hành động:
     *   • Chuẩn bị nuôi acc (waitFeedLoad, kill/relaunch, switch acc)
     *   • doLike(), doFollow(), doComment(), doNotification()
     *   • swipeNext(), forceSwipeNext()
     *   • recoverToFeed() tự thân (tránh re-entry)
     * Khi true: isLostWithRetry() trả false ngay, watchdog không kích hoạt.
     * Đảm bảo các hàm không chặn lẫn nhau.
     */
    @Volatile private var isActionLocked = false

    // ─────────────────────────────────────────────────────────────────────────
    // [v1.2.3] Pause-aware time tracking
    //
    // Trước v1.2.3: countdown (sessionSecsLeft/totalSecsLeft) được tính từ
    // deadline tĩnh (now + duration) → khi engine bị "kẹt" ở một bước nào đó
    // (vd: hành động sau LIKE bị treo), thời gian thực vẫn trôi nhưng UI vẫn
    // hiển thị số đếm cũ vì không có code nào update — người dùng thấy
    // countdown "đứng yên". Ngược lại, khi pause() bị gọi, deadline KHÔNG bị
    // dịch lại → thời gian pause vẫn bị trừ vào countdown (sai theo hướng khác).
    //
    // v1.2.3: Track tổng thời gian đã pause (pauseAccumMs) + thời điểm pause
    // hiện tại (pauseStartedAt). currentPauseMs() trả tổng pause tới hiện tại.
    // Mọi countdown được tính = (sessionTotalMs - (wallElapsed - pauseElapsed)) —
    // luôn giảm đều theo thời gian thực, CHỈ đứng yên khi đang pause.
    // ─────────────────────────────────────────────────────────────────────────
    @Volatile private var pauseAccumMs   = 0L
    @Volatile private var pauseStartedAt = 0L  // 0 = hiện không pause

    /** Tổng thời gian đã pause tính đến hiện tại (kể cả pause đang diễn ra). */
    private fun currentPauseMs(): Long {
        val ongoing = if (pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
        return pauseAccumMs + ongoing
    }

    /** Reset bộ đếm pause — gọi khi bắt đầu một phiên farm/task/nurture mới. */
    private fun resetPauseTracking() {
        pauseAccumMs   = 0L
        pauseStartedAt = 0L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun startFarm(mode: FarmMode, inputList: List<String> = emptyList()) {
        if (isFarming) return
        isFarming = true
        isPaused  = false
        isResting = false
        resetPauseTracking()

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

    /**
     * v1.2.1 Bắt đầu chế độ làm nhiệm vụ Golike TikTok.
     *
     * Flow:
     *   1. Mở TikTok → scan acc đang đăng nhập trên thiết bị.
     *   2. Lấy danh sách TikTok acc từ Golike server.
     *   3. Match: acc thiết bị ∩ acc Golike → danh sách cần làm việc.
     *   4. Với mỗi acc: nuôi → lấy job → làm → nuôi → ... → chuyển acc.
     *
     * `mode`: cách build danh sách (ALL_LOCAL hoặc SELECTED_LIST).
     * `inputList`: danh sách acc cụ thể (dùng khi mode = SELECTED_LIST).
     * `golikeAccounts`: danh sách TikTok acc từ Golike (đã fetch sẵn ở ViewModel).
     */
    fun startTask(
        mode:           FarmMode,
        inputList:      List<String>         = emptyList(),
        golikeAccounts: List<TikTokAccountDto> = emptyList(),
    ) {
        if (isFarming) return
        isFarming = true
        isPaused  = false
        isResting = false
        resetPauseTracking()

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            try {
                runTaskStateMachine(mode, inputList, golikeAccounts)
            } catch (e: CancellationException) {
                log("Task bị hủy bởi người dùng")
                throw e
            } catch (e: Exception) {
                log("ERR: Task lỗi không mong đợi: ${e.javaClass.simpleName} — ${e.message}")
            } finally {
                watchdogJob?.cancel()
                host.hideFarmOverlay()
                setStatus("")
                stopInternal("task_completed")
            }
        }
    }

    /**
     * v1.2.3 — Demo nuôi acc Facebook (gói: com.facebook.katana).
     *
     * Flow đơn giản:
     *   1. Mở Facebook
     *   2. Lướt feed (swipe lên) trong `config.facebookNurtureDurationSecs` giây
     *   3. Ngẫu nhiên thích bài đăng theo `config.facebookLikeRate`
     *   4. Đóng app → kết thúc phiên nuôi
     *
     * Không dùng FarmPhase state machine (chỉ 1 phase đơn giản) — chạy trực
     * tiếp trong farmJob coroutine, tái sử dụng isFarming/isPaused/pause-aware
     * timing + safeStep() như farm TikTok.
     */
    fun startFacebookNurture() {
        if (isFarming) return
        isFarming = true
        isPaused  = false
        isResting = false
        resetPauseTracking()

        farmJob = host.scope.launch {
            config = repo.loadFarmConfig()
            host.showFarmOverlay()

            try {
                runFacebookNurtureSession()
            } catch (e: CancellationException) {
                log("Nuôi Facebook bị hủy bởi người dùng")
                throw e
            } catch (e: Exception) {
                log("ERR: Nuôi Facebook lỗi không mong đợi: ${e.javaClass.simpleName} — ${e.message}")
            } finally {
                host.hideFarmOverlay()
                setStatus("")
                stopInternal("facebook_completed")
            }
        }
    }

    /** v1.2.4 — Demo nuôi tài khoản X (Twitter). */
    fun startXNurture() {
        if (isFarming) return
        isFarming = true; isPaused = false; isResting = false
        resetPauseTracking()
        farmJob = host.scope.launch {
            config = repo.loadFarmConfig(); host.showFarmOverlay()
            try { runXNurtureSession() }
            catch (e: CancellationException) { log("Nuôi X bị hủy bởi người dùng"); throw e }
            catch (e: Exception) { log("ERR: Nuôi X lỗi: ${e.javaClass.simpleName} — ${e.message}") }
            finally { host.hideFarmOverlay(); setStatus(""); stopInternal("x_completed") }
        }
    }

    /** v1.2.4 — Demo nuôi tài khoản Instagram. */
    fun startInstagramNurture() {
        if (isFarming) return
        isFarming = true; isPaused = false; isResting = false
        resetPauseTracking()
        farmJob = host.scope.launch {
            config = repo.loadFarmConfig(); host.showFarmOverlay()
            try { runInstagramNurtureSession() }
            catch (e: CancellationException) { log("Nuôi Instagram bị hủy bởi người dùng"); throw e }
            catch (e: Exception) { log("ERR: Nuôi Instagram lỗi: ${e.javaClass.simpleName} — ${e.message}") }
            finally { host.hideFarmOverlay(); setStatus(""); stopInternal("instagram_completed") }
        }
    }

    /** v1.2.4 — Demo nuôi tài khoản Threads. */
    fun startThreadsNurture() {
        if (isFarming) return
        isFarming = true; isPaused = false; isResting = false
        resetPauseTracking()
        farmJob = host.scope.launch {
            config = repo.loadFarmConfig(); host.showFarmOverlay()
            try { runThreadsNurtureSession() }
            catch (e: CancellationException) { log("Nuôi Threads bị hủy bởi người dùng"); throw e }
            catch (e: Exception) { log("ERR: Nuôi Threads lỗi: ${e.javaClass.simpleName} — ${e.message}") }
            finally { host.hideFarmOverlay(); setStatus(""); stopInternal("threads_completed") }
        }
    }

    /** v1.2.4 — Demo nuôi tài khoản Snapchat. */
    fun startSnapchatNurture() {
        if (isFarming) return
        isFarming = true; isPaused = false; isResting = false
        resetPauseTracking()
        farmJob = host.scope.launch {
            config = repo.loadFarmConfig(); host.showFarmOverlay()
            try { runSnapchatNurtureSession() }
            catch (e: CancellationException) { log("Nuôi Snapchat bị hủy bởi người dùng"); throw e }
            catch (e: Exception) { log("ERR: Nuôi Snapchat lỗi: ${e.javaClass.simpleName} — ${e.message}") }
            finally { host.hideFarmOverlay(); setStatus(""); stopInternal("snapchat_completed") }
        }
    }

    /**
     * v1.2.3 — pause/resume: track [pauseAccumMs] để countdown luôn chính xác.
     * Guard `if (!isPaused)` / `if (isPaused)` tránh double-counting nếu
     * pause()/resume() bị gọi nhiều lần liên tiếp (vd: từ UI + Accessibility
     * onInterrupt() cùng lúc).
     */
    fun pause() {
        if (!isPaused) {
            isPaused       = true
            pauseStartedAt = System.currentTimeMillis()
            OverlayFarmMonitor.syncPausedState(true)
            LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to true))
        }
    }

    fun resume() {
        if (isPaused) {
            isPaused = false
            if (pauseStartedAt > 0L) {
                pauseAccumMs  += System.currentTimeMillis() - pauseStartedAt
                pauseStartedAt = 0L
            }
            OverlayFarmMonitor.syncPausedState(false)
            LanWebSocketServer.broadcast("pauseStatus", mapOf("paused" to false))
        }
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
        // v1.2.3: Tổng thời gian ước tính của TOÀN BỘ phiên farm, bao gồm cả
        // thời gian nghỉ giữa các acc (yêu cầu: "tính thời gian tổng thì
        // cộng thêm thời gian nghỉ"). Trước đây totalSecs chỉ tính
        // farmList.size * minutesPerAccount, không cộng restDurationMinutes
        // → totalSecsLeft hiển thị sai (chạy hết acc cuối nhưng vẫn còn lệch
        // so với thực tế nếu có nghỉ giữa acc).
        val restSecsEach = if (config.enableRestBetweenAccounts) config.restDurationMinutes * 60L else 0L
        val restCount    = (farmList.size - 1).coerceAtLeast(0)
        val totalSecs    = farmList.size.toLong() * config.minutesPerAccount * 60L + restCount * restSecsEach

        // v1.2.3: Mốc thời gian bắt đầu TOÀN phiên farm (wall-clock) + pause đã
        // tích lũy tại mốc đó. totalSecsLeftFn() = totalSecs trừ đi thời gian
        // thực đã trôi (loại trừ thời gian pause) — luôn giảm đều theo thời
        // gian thực kể cả trong lúc nghỉ giữa acc, CHỈ đứng yên khi pause.
        val farmStartWall    = System.currentTimeMillis()
        val farmStartPauseMs = currentPauseMs()
        val totalSecsLeftFn: () -> Long = {
            val elapsedMs = (System.currentTimeMillis() - farmStartWall) -
                (currentPauseMs() - farmStartPauseMs)
            maxOf(0L, totalSecs - elapsedMs / 1_000L)
        }

        val farmedSet = mutableSetOf<String>()

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

            // v1.2.3 [FIX] Một exception trong farmOneAccount() (lỗi node stale,
            // IPC fail...) không còn làm sập toàn bộ farm — log lỗi, tính acc
            // này là "completed" rỗng, và TIẾP TỤC sang acc kế / nghỉ giữa acc.
            val result = try {
                farmOneAccount(account, idx, farmList.size, totalSecsLeftFn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("ERR: farmOneAccount(@$account) lỗi: ${e.javaClass.simpleName} — ${e.message} → bỏ qua, sang acc tiếp")
                AccountResult(account, reason = "error")
            }

            sessionLikes   += result.likes
            sessionFollows += result.follows
            sessionVideos  += result.videos

            AtProNotificationManager.notifySessionDone(
                account, result.likes, result.follows, result.videos)

            // Nghỉ giữa acc (không nghỉ sau acc cuối)
            if (config.enableRestBetweenAccounts && idx < farmList.size - 1) {
                val restSecs = restSecsEach
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

        // v1.2.5: Đọc danh sách kèm node — phân loại valid/invalid
        val entries  = NodeTraverser.parseAccountListWithNodes(host.getRootNode(), screenW, screenH)
        val valid    = entries.filter { !it.isNeedsNormalize }

        // ── Xử lý acc ĐẦU TIÊN bị invalid (= acc đang đăng nhập) ──────────
        // Không thể click vào entry[0] để switch vì TikTok chỉ đóng popup.
        // Fix: nếu entry[0] invalid → switch tạm sang 1 acc hợp lệ khác,
        //      sau đó entry[0] sẽ rời vị trí 0 → có thể chuẩn hoá bình thường.
        val firstEntry = entries.firstOrNull()
        var currentEntries = entries

        if (firstEntry != null && firstEntry.isNeedsNormalize && config.normalizeEnabled) {
            val tempTarget = entries.drop(1).firstOrNull { !it.isNeedsNormalize }
            if (tempTarget != null) {
                log("FIX: Acc đang login '${firstEntry.displayText}' invalid → " +
                    "switch tạm sang '${tempTarget.displayText}' để đưa ra khỏi vị trí đầu")
                setStatus("FIX: Chuyển tạm sang '${tempTarget.displayText}'...")
                host.clickNode(tempTarget.node)
                delay((config.delayAfterSwitchClick * 1_000).toLong().coerceAtLeast(1_800L))
                host.killTikTok(); delay(2_500); host.launchTikTok()
                waitFeedLoad()
                // Mở lại popup → re-parse (entry đầu invalid giờ ở vị trí != 0)
                host.openTikTokSettings(); delay(2_200)
                if (scrollUntilSwitchFound(maxScrolls = 8)) {
                    delay(800)
                    findSwitchBtnNode()?.let { b ->
                        host.clickNode(b.node); delay(1_500)
                        currentEntries = NodeTraverser
                            .parseAccountListWithNodes(host.getRootNode(), screenW, screenH)
                    }
                }
            } else {
                log("WARN: FIX: Acc đầu invalid nhưng không tìm được acc hợp lệ để switch tạm — bỏ qua")
            }
        }

        val invalid = currentEntries.drop(1).filter { it.isNeedsNormalize }
        val discovered = currentEntries.filter { !it.isNeedsNormalize }.map { it.displayText }.toMutableList()

        if (invalid.isNotEmpty() && config.normalizeEnabled) {
            log("FIX: Phát hiện ${invalid.size} entry không hợp lệ: " +
                invalid.joinToString { "'${it.displayText}'" })

            // Đóng popup hiện tại — normalizeInvalidAccountIds tự quản lý popup
            host.pressBack(); delay(800); host.pressBack(); delay(800)

            val fixedUsernames = normalizeInvalidAccountIds(invalid.map { it.displayText })
            discovered.addAll(fixedUsernames)
            log("FIX: Chuẩn hoá xong — thêm ${fixedUsernames.size} username: $fixedUsernames")

            // Mở lại popup lần cuối cho positionFirstAccount
            if (isFarming) {
                host.openTikTokSettings(); delay(2_200)
                if (scrollUntilSwitchFound(maxScrolls = 8)) {
                    delay(800)
                    findSwitchBtnNode()?.let { b ->
                        host.clickNode(b.node); delay(1_500)
                    }
                }
            }
        }

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
     * v1.2.6 — Chuẩn hoá các entry không hợp lệ trong switch popup.
     *
     * Flow mỗi entry (theo đúng spec người dùng):
     *   1. Mở switch popup → RE-PARSE fresh (không dùng node cũ từ lần trước)
     *   2. Tìm entry cần chuẩn hoá theo displayText (bỏ acc đầu tiên = acc đang login)
     *   3. Click entry đó → TikTok switch sang acc
     *   4. Kill + Relaunch → chờ feed load
     *   5. Vào profile → xác nhận @username thực
     *   6. Về feed → lặp lại cho entry tiếp theo
     *
     * Quan trọng:
     *   - [isNormalizing = true] suốt quá trình → tắt HOÀN TOÀN watchdog +
     *     isLostWithRetry() + recoverToFeed() tự động — tránh can thiệp nhầm.
     *   - Mỗi vòng lặp re-parse fresh → không dùng node stale sau kill/relaunch.
     *   - Bỏ acc đầu tiên trong popup (= acc đang đăng nhập hiện tại).
     *
     * @param invalidDisplayTexts Danh sách displayText của các entry cần chuẩn hoá.
     * @return Danh sách @username hợp lệ đã phục hồi được.
     */
    private suspend fun normalizeInvalidAccountIds(
        invalidDisplayTexts: List<String>,
    ): List<String> {
        val recovered   = mutableListOf<String>()
        isNormalizing   = true          // TẮT watchdog + lost detection hoàn toàn
        resetWatchdog()                 // Xoá trạng thái stuck cũ (nếu có)

        try {
            invalidDisplayTexts.forEachIndexed { idx, displayText ->
                if (!isFarming) return@forEachIndexed

                if (NodeTraverser.isLikelySystemLabel(displayText)) {
                    log("WARN: FIX: Bỏ qua '$displayText' — nghi là nhãn hệ thống")
                    return@forEachIndexed
                }

                val typeLabel = when {
                    displayText.all { it.isDigit() } -> "ID thuần số"
                    else                              -> "display name"
                }
                log("FIX: [${idx + 1}/${invalidDisplayTexts.size}] Chuẩn hoá ($typeLabel): '$displayText'")
                setStatus("FIX: [${idx + 1}/${invalidDisplayTexts.size}] Chuẩn hoá '$displayText'...")

                // ── Bước 1: Mở switch popup + RE-PARSE FRESH ───────────────
                // KHÔNG dùng node cũ — node bị stale sau kill/relaunch lần trước.
                host.openTikTokSettings()
                delay(2_200)
                if (!scrollUntilSwitchFound(maxScrolls = 8)) {
                    log("ERR: FIX: Không tìm thấy nút switch cho '$displayText' — bỏ qua")
                    return@forEachIndexed
                }
                delay(800)
                val switchBtn = findSwitchBtnNode() ?: run {
                    log("ERR: FIX: Switch button biến mất — bỏ qua '$displayText'")
                    return@forEachIndexed
                }
                host.clickNode(switchBtn.node)
                delay(1_500)

                // ── Bước 2: Re-parse popup fresh → tìm entry theo displayText ──
                // drop(1): entry đầu = acc đang login, KHÔNG click (chỉ đóng popup)
                val allFreshEntries = NodeTraverser
                    .parseAccountListWithNodes(host.getRootNode(), screenW, screenH)

                // Kiểm tra entry đầu — nếu chính là target thì phải switch tạm trước
                val firstFreshEntry = allFreshEntries.firstOrNull()
                if (firstFreshEntry != null &&
                    firstFreshEntry.displayText.trim().equals(displayText.trim(), ignoreCase = true)
                ) {
                    // Target đang ở vị trí 0 (acc đang login) → switch sang acc khác tạm
                    val tempTarget = allFreshEntries.drop(1).firstOrNull { !it.isNeedsNormalize }
                    if (tempTarget == null) {
                        log("WARN: FIX: '$displayText' ở vị trí 0, không tìm được acc tạm — bỏ qua")
                        host.pressBack(); delay(800); host.pressBack(); delay(800)
                        return@forEachIndexed
                    }
                    log("FIX: '$displayText' ở vị trí 0 → switch tạm sang '${tempTarget.displayText}'")
                    host.clickNode(tempTarget.node)
                    delay((config.delayAfterSwitchClick * 1_000).toLong().coerceAtLeast(1_800L))
                    host.killTikTok(); delay(2_500); host.launchTikTok()
                    waitFeedLoad()
                    // Mở lại popup → target giờ không còn ở vị trí 0
                    host.openTikTokSettings(); delay(2_200)
                    if (!scrollUntilSwitchFound(maxScrolls = 8)) {
                        log("ERR: FIX: Không tìm thấy switch button sau switch tạm — bỏ qua '$displayText'")
                        return@forEachIndexed
                    }
                    delay(800)
                    val switchBtn2 = findSwitchBtnNode() ?: run {
                        log("ERR: FIX: Switch button biến mất — bỏ qua '$displayText'")
                        return@forEachIndexed
                    }
                    host.clickNode(switchBtn2.node); delay(1_500)
                }

                val freshEntries = NodeTraverser
                    .parseAccountListWithNodes(host.getRootNode(), screenW, screenH)
                    .drop(1)
                val target = freshEntries.firstOrNull { entry ->
                    entry.displayText.trim().equals(displayText.trim(), ignoreCase = true)
                }
                if (target == null) {
                    log("WARN: FIX: Không tìm thấy '$displayText' trong popup (đã chuẩn hoá lần trước?) — bỏ qua")
                    // Đóng popup trước khi sang entry tiếp
                    host.pressBack()
                    delay(800)
                    host.pressBack()
                    delay(800)
                    return@forEachIndexed
                }

                // ── Bước 3: Click entry → switch sang acc ──────────────────
                if (NodeTraverser.isLikelySystemLabel(target.displayText)) {
                    log("WARN: FIX: Fresh node '$displayText' nghi là nhãn hệ thống — bỏ qua")
                    host.pressBack(); delay(800); host.pressBack(); delay(800)
                    return@forEachIndexed
                }
                log("FIX: Click node '${target.displayText}' để switch...")
                host.clickNode(target.node)
                delay((config.delayAfterSwitchClick * 1_000).toLong().coerceAtLeast(1_800L))

                if (!isFarming) return@forEachIndexed

                // ── Bước 4: Kill + Relaunch → chờ feed ─────────────────────
                host.killTikTok()
                delay(2_500)
                host.launchTikTok()

                val feedLoaded = waitFeedLoad()
                if (!feedLoaded) {
                    log("WARN: FIX: Feed không load sau switch '$displayText' — bỏ qua")
                    return@forEachIndexed
                }

                // ── Bước 5: Vào profile → xác nhận @username ───────────────
                setStatus("FIX: Xác nhận tài khoản sau chuẩn hoá '$displayText'...")
                val realUsername = detectCurrentAccount()
                if (realUsername != null && NodeTraverser.isValidTikTokUsername(realUsername)) {
                    log("FIX: ✓ '$displayText' → '@$realUsername'")
                    recovered.add(realUsername)
                } else {
                    log("WARN: FIX: Không đọc được @username sau switch (kết quả: $realUsername)")
                }

                // ── Bước 6: Về feed (detectCurrentAccount() đã về feed) ─────
                // waitFeedLoad() ở đây để ổn định trước vòng tiếp theo
                delay(1_000)
                log("FIX: [${idx + 1}/${invalidDisplayTexts.size}] Hoàn tất — chuẩn bị entry tiếp theo")
            }
        } finally {
            // Đảm bảo luôn reset flag dù có exception
            isNormalizing = false
            resetWatchdog()
        }

        return recovered
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
        account:         String,
        idx:             Int,
        total:           Int,
        totalSecsLeftFn: () -> Long,
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

        // v1.2.3: sessionSecsLeftNow() pause-aware — xem mô tả ở khu vực
        // "Pause-aware time tracking" phía trên. Countdown của acc này luôn
        // giảm đều theo thời gian thực, chỉ đứng yên khi isPaused = true.
        val sessionTotalMs      = config.minutesPerAccount * 60_000L
        val accountStartWall    = System.currentTimeMillis()
        val accountStartPauseMs = currentPauseMs()
        fun sessionSecsLeftNow(): Long {
            val elapsedMs = (System.currentTimeMillis() - accountStartWall) -
                (currentPauseMs() - accountStartPauseMs)
            return maxOf(0L, (sessionTotalMs - elapsedMs) / 1_000L)
        }

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
            totalSecsLeft   = totalSecsLeftFn(),
            action          = "Khởi động...",
        )
        LanWebSocketServer.broadcast("currentAccount", mapOf(
            "account" to account,
            "index"   to idx + 1,
            "total"   to total,
        ))

        while (sessionSecsLeftNow() > 0L && isFarming) {
            awaitResumed()
            val secsLeft = sessionSecsLeftNow()

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

            // ── [2d] Live card toàn màn hình trong feed ───────────────────
            // Thẻ Live preview full-screen (chưa vào live room) — không có
            // like/share buttons → engine không thể farm, cần swipe qua.
            // Phải xử lý TRƯỚC isLostWithRetry() để không bị tính là "lạc".
            // [v1.2.7 FIX] Thêm consecutiveSkipCount: nếu swipeNext() không thoát
            // được live card liên tiếp → forceSwipeNext() + delay dài, tránh stuck loop.
            if (NodeTraverser.isLiveCardInFeed(root)) {
                consecutiveSkipCount++
                if (consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS) {
                    log("LIVE-FEED: Skip liên tiếp ${consecutiveSkipCount}× (live-card) → forceSwipeNext")
                    forceSwipeNext(); delay(2_000); consecutiveSkipCount = 0
                } else {
                    log("LIVE-FEED: Thẻ Live full-screen trong feed → swipe qua")
                    swipeNext()
                    delay(1_200)
                }
                lostStreak = 0
                resetWatchdog()   // tránh watchdog fire sau swipe
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
            val totalSecsRemain   = totalSecsLeftFn()
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
            val watchMs   = randomWatchTimeMs()
            val watchSecs = (watchMs / 1_000L).coerceAtLeast(1L)

            // [v1.2.1] Drift detection — kiểm tra isOnFeedTab() mỗi FEED_CHECK_INTERVAL giây
            // trong khi xem video.  Phát hiện khi TikTok tự chuyển sang live room, popup
            // che phủ, hoặc người dùng vô tình chạm màn hình → kịp dừng sớm thay vì
            // đợi hết watchSecs rồi mới phát hiện ở isLostWithRetry() vòng sau.
            // IPC cost: ~1 getRootNode() / 5s — chấp nhận được, rẻ hơn recovery loop.
            //
            // v1.2.3 [FIX]: Bọc bằng safeStep() — nếu getRootNode()/IPC trong vòng
            // lặp xem video bị treo (nguyên nhân khiến log "đứng" ở "Xem video (Ns)"
            // không tiến tiếp), timeout sau watchSecs*1.5s + 10s sẽ tự thoát thay vì
            // treo vô hạn. sessionSecsLeftNow()/totalSecsLeftFn() pause-aware → countdown
            // hiển thị luôn giảm đều theo thời gian thực.
            val driftedDuringWatch = safeStep(
                "watch_video",
                timeoutMs = (watchSecs * 1_500L) + 10_000L,
            ) {
                var drifted = false
                for (w in watchSecs downTo 1L) {
                    OverlayFarmMonitor.update(
                        accountIndex    = idx + 1,
                        accountTotal    = total,
                        accountId       = account,
                        sessionSecsLeft = sessionSecsLeftNow(),
                        totalSecsLeft   = totalSecsLeftFn(),
                        action          = if (videos == 0) "Chuẩn bị..." else "Xem video (${w}s)",
                    )
                    delay(1_000L)

                    // Kiểm tra drift mỗi 5 giây (không phải mỗi giây — tránh IPC quá nhiều)
                    if (w % FEED_CHECK_INTERVAL_SECS == 0L) {
                        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
                            log("WATCH: Rời feed khi xem (${w}s còn lại) — dừng sớm, chờ recovery")
                            drifted = true
                            break
                        }
                    }
                }
                drifted
            } ?: true  // timeout/lỗi → coi như drift, để isLostWithRetry() xử lý vòng sau
            // Nếu drift, bỏ qua toàn bộ action bước [8]-[11],
            // quay đầu vòng lặp → isLostWithRetry() sẽ xử lý.
            if (driftedDuringWatch) { lostStreak++; continue }

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
                    // v1.2.3 [FIX]: Bọc safeStep() — nếu doLike() bị treo (gesture
                    // callback không resume), timeout 15s sẽ tự bỏ qua thay vì
                    // làm log "đứng" ở LIKE và không vuốt sang video tiếp theo.
                    val likeOk = safeStep("like_action", timeoutMs = 15_000L) { doLike() }
                    if (likeOk != null) likes++

                    // v1.2.6 FIX: Kiểm tra ngay sau like — quảng cáo có nút CTA đôi khi bị
                    // tap nhầm khi double-tap, khiến TikTok mở trang sản phẩm / trình duyệt.
                    // Nếu không còn ở feed: recover rồi skip toàn bộ [9]-[11], bắt đầu vòng mới.
                    val onFeedAfterLike = NodeTraverser.isOnFeedTab(host.getRootNode())
                    if (!onFeedAfterLike) {
                        log("WARN: Rời feed sau like (CTA bị tap nhầm?) — recoverToFeed")
                        OverlayFarmMonitor.update(idx + 1, total, account,
                            sessionSecsRemain, totalSecsRemain, "RECOVER: quay lại feed")
                        recoverToFeed()
                        lostStreak++
                        continue
                    }
                } else if (isLiveContent) {
                    log("LIKE SKIP: Không like live")
                } else if (isAdContent) {
                    log("LIKE SKIP: Không like quảng cáo (likeAds=off)")
                }
            }

            // ── [9] Follow ────────────────────────────────────────────────
            // [v1.2.1] Cơ chế mới: swipe phải sang trái → vào hồ sơ → follow → back về feed.
            // Không dùng freshRoot nữa (freshRoot là feed root, profile cần fetch riêng).
            if (!isLiveContent && !isAdContent && Random.nextFloat() < config.followRate) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "FOLLOW: Theo dõi")
                // v1.2.3 [FIX]: timeout 30s — nếu treo giữa lúc swipe vào profile /
                // bấm Follow, tự thoát + recoverToFeed() để không kẹt ở "FOLLOW".
                val fr = safeStep("follow_action", timeoutMs = 30_000L) { doFollowFromProfile() }
                if (fr == FollowResult.SUCCESS || fr == FollowResult.UNCONFIRMED) follows++
                if (fr == null && !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                    log("WARN: Follow timeout/lỗi — recoverToFeed()")
                    recoverToFeed()
                }
                // doFollowFromProfile() luôn back về feed trước khi trả kết quả
            }

            // ── [10] Comment [v2.0] ───────────────────────────────────────
            if (config.commentRate > 0f
                && config.commentTexts.isNotEmpty()
                && Random.nextFloat() < config.commentRate
            ) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "CMT: Bình luận")
                // v1.2.3 [FIX]: timeout 25s — panel comment đôi khi không mở/đóng đúng.
                val commentOk = safeStep("comment_action", timeoutMs = 25_000L) {
                    doComment(config.commentTexts)
                }
                if (commentOk == true) comments++
                if (commentOk == null && !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                    log("WARN: Comment timeout/lỗi — recoverToFeed()")
                    recoverToFeed()
                }
            }

            // ── [10b] Diversion: Hộp thư / Cửa hàng [v1.1.9+] ────────────
            // Thỉnh thoảng ghé thăm tab phụ trước khi lướt — hành vi tự nhiên hơn.
            // Chỉ 1 tab mỗi lần (ưu tiên inbox). Các hàm doView* tự về feed.
            // v1.2.3 [FIX]: timeout 90s — các phiên inbox/shop/search có nhiều
            // bước con; nếu 1 bước treo, không để toàn bộ farm bị kẹt ở đó.
            val diversionResult = safeStep("diversion_step", timeoutMs = 90_000L) {
                run diversion@{
                    val secsFn  = { sessionSecsLeftNow() }
                    val totalFn = totalSecsLeftFn
                    if (config.inboxViewRate > 0f && Random.nextFloat() < config.inboxViewRate) {
                        doViewInbox(idx + 1, total, account, secsFn, totalFn)
                        return@diversion
                    }
                    if (config.shopViewRate > 0f && Random.nextFloat() < config.shopViewRate) {
                        doViewShop(idx + 1, total, account, secsFn, totalFn)
                        return@diversion
                    }
                    // [v1.2.0] Phiên tìm kiếm từ khoá — độc lập với inbox/shop
                    if (config.searchEnabled
                        && config.searchKeywords.isNotEmpty()
                        && Random.nextFloat() < config.searchRate
                    ) {
                        doSearchSession(idx + 1, total, account, secsFn, totalFn)
                    }
                }
            }
            if (diversionResult == null && !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                log("WARN: Diversion (Hộp thư/Cửa hàng/Tìm kiếm) timeout/lỗi — recoverToFeed()")
                recoverToFeed()
            }

            // ── [11] Swipe next ───────────────────────────────────────────
            // swipeNext() dùng swipeStartFactor(68–82%) + swipeEndFactor(18–30%) ngẫu nhiên
            // → tự nhiên tránh thanh dot •••• của image carousel mà không cần detect.
            // v1.2.3 [FIX]: timeout 10s — đây là bước CUỐI mỗi vòng lặp; nếu gesture
            // bị treo ở đây, log sẽ "đứng" mãi ở action trước đó (LIKE/FOLLOW/CMT)
            // mà không chuyển video tiếp theo. Timeout đảm bảo loop luôn quay lại.
            safeStep("swipe_next", timeoutMs = 10_000L) { swipeNext() }

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
        isActionLocked = true
        try {
            val cx = screenW / 2 + Human.jitter(25)
            val cy = (screenH * 0.55).toInt() + Human.jitter(20)
            host.clickSuspend(cx, cy)
            Human.doubleTapGap()
            host.clickSuspend(cx + Human.jitter(8), cy + Human.jitter(8))
            Human.likeAnimDelay()
            delay((config.delayAfterLike * 1_000).toLong())
        } finally {
            isActionLocked = false
        }
    }

    /**
     * Follow tác giả video hiện tại.
     *
     * Kiểm tra text nút trước khi click — bỏ qua nếu đã follow.
     * Trả true nếu đã thực sự click (chưa follow).
     */
    private suspend fun doFollow(): Boolean {
        isActionLocked = true
        return try { doFollowWithRoot(host.getRootNode()) }
        finally { isActionLocked = false }
    }

    private suspend fun doFollowWithRoot(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        val btn = NodeTraverser.findByText(root, "follow",     ignoreCase = true)
            ?: NodeTraverser.findByText(root, "theo dõi",     ignoreCase = true)
            ?: NodeTraverser.findByText(root, "đăng ký",      ignoreCase = true)
            ?: return false
        val t = btn.text?.lowercase() ?: ""
        if ("following" in t || "đang theo dõi" in t || "đã theo dõi" in t) return false
        host.clickNode(btn.node)
        delay((config.delayAfterFollow * 1_000).toLong())
        return true
    }

    /**
     * v1.2.1: Follow cơ chế mới — kéo từ phải sang trái vào hồ sơ người đăng video,
     * tìm nút Follow, bấm follow, xác nhận mất nút Follow, rồi back về feed.
     *
     * Trả FollowResult:
     *   SUCCESS         — follow thành công + xác nhận nút đổi sang "Following"
     *   NO_BUTTON       — không có nút Follow (đã follow hoặc bị private)
     *   BLOCKED         — profile bị khoá / riêng tư
     *   ALREADY_FOLLOWING — đã follow trước khi thao tác
     *   UNCONFIRMED     — click rồi nhưng nút chưa đổi kịp (vẫn tính thành công)
     *
     * Sau hàm này, engine đang ở feed (đã back về).
     */
    private suspend fun doFollowFromProfile(): FollowResult {
        // Bước 1: Swipe từ phải sang trái → vào hồ sơ tác giả video
        val swipeY = (screenH * 0.50).toInt()
        host.swipeSuspend(
            (screenW * 0.90).toInt(), swipeY,
            (screenW * 0.10).toInt(), swipeY,
            Human.swipeDuration(350),
        )
        Human.delay(1_400, 2_200)  // chờ profile load

        val root = host.getRootNode()

        // Bước 2: Kiểm tra profile bị khoá / riêng tư
        if (NodeTraverser.detectBlockedProfile(root)) {
            log("FOLLOW: Profile bị khoá/riêng tư — back về feed")
            host.pressBack()
            Human.delay(800, 1_200)
            return FollowResult.BLOCKED
        }

        // Bước 3: Tìm nút Follow
        val followBtn = NodeTraverser.findFollowButtonOnProfile(root)
        if (followBtn == null) {
            log("FOLLOW: Không tìm thấy nút Follow trên profile — back về feed")
            host.pressBack()
            Human.delay(800, 1_200)
            return FollowResult.NO_BUTTON
        }

        // Kiểm tra đã follow chưa
        val btnLabel = (followBtn.text ?: followBtn.node.contentDescription?.toString())?.lowercase() ?: ""
        if ("following" in btnLabel || "đang theo dõi" in btnLabel || "đã theo dõi" in btnLabel) {
            log("FOLLOW: Đã theo dõi — back về feed")
            host.pressBack()
            Human.delay(800, 1_200)
            return FollowResult.ALREADY_FOLLOWING
        }

        // Bước 4: Bấm Follow
        Human.microPause()
        host.clickNode(followBtn.node)
        Human.delay(900, 1_600)

        // Bước 5: Xác nhận nút Follow đã mất / đổi sang "Following"
        val freshRoot = host.getRootNode()
        val confirmed = NodeTraverser.isFollowConfirmed(freshRoot) ||
            NodeTraverser.findFollowButtonOnProfile(freshRoot) == null

        log("FOLLOW: Profile follow ${if (confirmed) "thành công ✓" else "chưa xác nhận"}")
        delay((config.delayAfterFollow * 1_000).toLong())

        // Bước 6: Back về feed
        host.pressBack()
        Human.delay(900, 1_400)

        // Đảm bảo về feed — thử thêm nếu cần
        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
            host.pressBack()
            Human.delay(700, 1_000)
        }

        return if (confirmed) FollowResult.SUCCESS else FollowResult.UNCONFIRMED
    }

    /**
     * v1.2.1: Follow từ trang hồ sơ đang mở sẵn (dùng trong task mode).
     *
     * Khác doFollowFromProfile(): không cần swipe — trang hồ sơ đã được
     * mở sẵn bởi host.openDeepLink(job.link).
     *
     * Sau hàm này, engine VẪN đang ở trang hồ sơ (chưa back).
     * Caller tự back về feed sau khi nhận kết quả.
     */
    private suspend fun doFollowOnOpenProfile(): FollowResult {
        val root = host.getRootNode()

        // Kiểm tra profile bị khoá / riêng tư
        if (NodeTraverser.detectBlockedProfile(root)) {
            log("TASK-FOLLOW: Profile bị khoá — báo lỗi server")
            return FollowResult.BLOCKED
        }

        // Tìm nút Follow
        val followBtn = NodeTraverser.findFollowButtonOnProfile(root)
        if (followBtn == null) {
            log("TASK-FOLLOW: Không tìm thấy nút Follow")
            return FollowResult.NO_BUTTON
        }

        // Kiểm tra đã follow chưa
        val btnLabel = (followBtn.text ?: followBtn.node.contentDescription?.toString())?.lowercase() ?: ""
        if ("following" in btnLabel || "đang theo dõi" in btnLabel) {
            log("TASK-FOLLOW: Đã follow rồi")
            return FollowResult.ALREADY_FOLLOWING
        }

        // Bấm Follow
        Human.microPause()
        host.clickNode(followBtn.node)
        Human.delay(900, 1_600)

        // Xác nhận
        val freshRoot = host.getRootNode()
        val confirmed = NodeTraverser.isFollowConfirmed(freshRoot) ||
            NodeTraverser.findFollowButtonOnProfile(freshRoot) == null

        log("TASK-FOLLOW: ${if (confirmed) "thành công ✓" else "chưa xác nhận"}")
        delay((config.delayAfterFollow * 1_000).toLong())

        return if (confirmed) FollowResult.SUCCESS else FollowResult.UNCONFIRMED
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
        idx:     Int,
        total:   Int,
        account: String,
        secsFn:  () -> Long,
        totalFn: () -> Long,
    ) {
        // [v1.2.1] Nếu không tìm thấy tab Hộp thư ngay → có thể không ở feed.
        // Thử back về feed rồi tìm lại 1 lần. Shop KHÔNG áp dụng quy tắc này vì
        // nhiều acc không có tab Cửa hàng (thay bằng Bạn bè / Khám phá / v.v.).
        var inboxTab = NodeTraverser.findInboxTab(host.getRootNode())
        if (inboxTab == null) {
            log("INBOX: Không tìm thấy tab Hộp thư — thử back về feed rồi tìm lại")
            host.pressBack()
            Human.delay(900, 1_400)
            if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
                // Vẫn chưa về feed — gọi recoverToFeed()
                log("INBOX: Back chưa về feed → recoverToFeed()")
                recoverToFeed()
                Human.delay(600, 1_000)
            }
            // Thử lại tìm tab sau khi đã về feed
            inboxTab = NodeTraverser.findInboxTab(host.getRootNode())
            if (inboxTab == null) {
                log("INBOX: Vẫn không tìm thấy tab Hộp thư sau recovery → bỏ qua")
                return
            }
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
                sessionSecsLeft = secsFn(),
                totalSecsLeft   = totalFn(),
                action          = "Hộp thư (${s}s)",
            )
            delay(1_000L)
        }

        // Về feed — ưu tiên tab Trang chủ, fallback back loop
        val backOk = returnToFeedFromTab()
        if (!backOk) {
            log("INBOX: returnToFeedFromTab thất bại → recoverToFeed()")
            recoverToFeed()
        } else {
            log("INBOX: Xong → về feed")
        }
    }

    /**
     * Ghé qua tab Cửa hàng — cuộn `config.shopScrollCount` lần rồi về feed.
     * Gọi từ step [10b] trong farmOneAccount(), sau khi đã xem + tương tác xong.
     *
     * v1.2.1: CHỦ Ý không thêm back-to-feed recovery khi không tìm thấy tab:
     * Nhiều tài khoản TikTok không có tab Cửa hàng (thay bằng Bạn bè, Khám phá,
     * Nhận thưởng, v.v.) — null là kết quả hợp lệ, không phải drift indicator.
     * Khác với Hộp thư (tab Inbox có trên mọi acc) → bỏ qua yên lặng là đúng.
     */
    private suspend fun doViewShop(
        idx:     Int,
        total:   Int,
        account: String,
        secsFn:  () -> Long,
        totalFn: () -> Long,
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
                sessionSecsLeft = secsFn(),
                totalSecsLeft   = totalFn(),
                action          = "Cửa hàng (${i + 1}/${scrollCount})",
            )
            host.swipeSuspend(
                screenW / 2, (screenH * 0.70).toInt(),
                screenW / 2, (screenH * 0.30).toInt(),
                Human.swipeDuration(400),
            )
            Human.delay(1_500, 2_500)
        }

        // Về feed — ưu tiên tab Trang chủ, fallback back loop
        val backOk = returnToFeedFromTab()
        if (!backOk) {
            log("SHOP: returnToFeedFromTab thất bại → recoverToFeed()")
            recoverToFeed()
        } else {
            log("SHOP: Xong → về feed")
        }
    }

    /**
     * v1.2.2 — Trả về feed từ tab inbox/shop.
     *
     * Ưu tiên click tab Trang chủ nếu tìm thấy.
     * Nếu không: back 1–3 lần, mỗi lần kiểm tra feed + tab Trang chủ.
     *
     * @return true nếu đã về feed thành công.
     */
    private suspend fun returnToFeedFromTab(): Boolean {
        // Thử tab Trang chủ trước
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(1_000)
            if (NodeTraverser.isOnFeedTab(host.getRootNode())) return true
        }
        // Home tab không thấy hoặc click chưa về — back loop 1–3 lần
        repeat(3) { attempt ->
            host.pressBack()
            delay(900)
            val root = host.getRootNode()
            if (NodeTraverser.isOnFeedTab(root)) {
                log("  returnToFeed: về feed sau ${attempt + 1} lần back ✓")
                return true
            }
            val h = NodeTraverser.findHomeTab(root)
            if (h != null) {
                host.clickNode(h.node)
                delay(1_000)
                if (NodeTraverser.isOnFeedTab(host.getRootNode())) {
                    log("  returnToFeed: tab Trang chủ tìm thấy sau back ${attempt + 1} ✓")
                    return true
                }
            }
        }
        return false
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
        idx:     Int,
        total:   Int,
        account: String,
        secsFn:  () -> Long,
        totalFn: () -> Long,
    ) {
        val keyword = config.searchKeywords.randomOrNull() ?: return
        log("SEARCH: Bắt đầu tìm kiếm \"$keyword\"")

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

        // 3. Submit search — tìm nút Search/Tìm kiếm trên màn hình, fallback tap phím Enter
        //    Không dùng ACTION_IME_ENTER vì constant chỉ có API 30 và không resolve ổn định
        val rootAfterType  = host.getRootNode()
        val searchSubmitBtn = NodeTraverser.findByText(rootAfterType, "tìm kiếm", ignoreCase = true)
            ?: NodeTraverser.findByText(rootAfterType, "search", ignoreCase = true)
            ?: NodeTraverser.findByText(rootAfterType, "done", ignoreCase = true)
        if (searchSubmitBtn != null) {
            host.clickNode(searchSubmitBtn.node)
        } else {
            // Fallback: tap vùng phím Search/Done góc phải-dưới bàn phím
            host.clickSuspend(screenW - 90, (screenH * 0.88).toInt())
            Human.delay(200, 400)
        }
        Human.delay(1_600, 2_600)

        // 4. Click video đầu tiên trong kết quả
        //    TikTok search result: grid 2 cột — click cột trái, hàng đầu (~35–42% chiều cao)
        OverlayFarmMonitor.update(idx, total, account, secsFn(), totalFn(), "SEARCH: \"$keyword\"")
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
                sessionSecsLeft = secsFn(),
                totalSecsLeft   = totalFn(),
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
            log("SEARCH: Xong \"$keyword\" → về feed (back ${backCount}x)")
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
        isActionLocked = true
        try {
            val xOff = Human.jitter(12)
            val x    = screenW / 2 + xOff
            host.swipeSuspend(
                x, (screenH * Human.swipeStartFactor()).toInt(),
                x, (screenH * Human.swipeEndFactor()).toInt(),
                Human.swipeDuration(350),
            )
            Human.delay(700, 1_400)
        } finally {
            isActionLocked = false
        }
    }

    private suspend fun forceSwipeNext() {
        isActionLocked = true
        try {
            repeat(2) {
                host.swipeSuspend(
                    screenW / 2, (screenH * 0.82).toInt(),
                    screenW / 2, (screenH * 0.08).toInt(),
                    200,
                )
                delay(500)
            }
        } finally {
            isActionLocked = false
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
                // Bỏ qua khi đã dừng, đang pause, đang nghỉ giữa acc, đang normalize, hoặc
                // đang thực hiện action — các trạng thái này không có video mới là bình thường.
                if (!isFarming || isPaused || isResting || isNormalizing || isActionLocked) continue
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
        // v1.2.6: Không coi là "lạc" khi đang normalize hoặc thực hiện action
        if (isNormalizing || isActionLocked) return false
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

    // ─────────────────────────────────────────────────────────────────────────
    // Task mode — v1.2.1
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * State machine cho task mode.
     *
     * Phase 1: Launch TikTok (dùng lại phaseLaunch)
     * Phase 2: Discover & match acc thiết bị với Golike
     * Phase 3: Task loop — farm → job → farm → ...
     */
    private suspend fun runTaskStateMachine(
        mode:           FarmMode,
        inputList:      List<String>,
        golikeAccounts: List<TikTokAccountDto>,
    ) {
        // Phase 1: Launch
        setStatus(">> Đang mở TikTok...")
        val launchPhase = phaseLaunch()
        if (launchPhase is FarmPhase.Failed) {
            log("ERR: Task — TikTok không mở được: ${launchPhase.reason}")
            return
        }

        // Phase 2: Discover + match
        setStatus("SCAN: Quét tài khoản thiết bị...")
        val (popupOpened, discovered) = openSwitchPopup()
        if (!popupOpened) {
            log("ERR: Task — Không mở được switch popup")
            return
        }

        val currentAcc = detectCurrentAccount()
        val farmList   = buildFarmList(mode, inputList, discovered)
        if (farmList.isEmpty()) {
            log("WARN: Task — Không có tài khoản nào trên thiết bị")
            host.pressBack(); delay(500)
            host.pressBack(); delay(500)
            return
        }

        // v1.2.6 FIX — Matching thiết bị ↔ Golike theo đúng spec golike.py:
        //   1. unique_username là key chính (trường unique trên Golike).
        //   2. nickname chỉ dùng làm fallback nếu không tìm thấy trùng unique_username
        //      VÀ nickname đó không bị trùng với unique_username của acc khác (tránh nhầm).
        //
        // Trước đây chỉ map theo unique_username → bỏ sót các acc có unique_username
        // lạ nhưng nickname khớp với @username TikTok thực tế trên thiết bị.
        val uniqueMap  = golikeAccounts.associateBy { it.uniqueUsername.lowercase().removePrefix("@") }
        val nicknameMap = golikeAccounts
            .filter { acc ->
                // Chỉ cho phép nickname làm key nếu nickname KHÔNG trùng với
                // unique_username của bất kỳ acc nào khác (tránh ambiguity).
                val nick = acc.nickname.lowercase().removePrefix("@")
                nick.isNotEmpty() && !uniqueMap.containsKey(nick)
            }
            .associateBy { it.nickname.lowercase().removePrefix("@") }

        val matchedList: List<Pair<String, TikTokAccountDto>> = farmList.mapNotNull { username ->
            val clean = username.lowercase().removePrefix("@")
            // Ưu tiên match unique_username → fallback nickname
            val golikeAcc = uniqueMap[clean] ?: nicknameMap[clean]
            golikeAcc?.let { username to it }
        }

        if (matchedList.isEmpty()) {
            log("WARN: Task — Không có acc nào trùng với hệ thống Golike")
            setStatus("Không có acc Golike trên thiết bị này")
            host.pressBack(); delay(500)
            host.pressBack(); delay(500)
            return
        }

        log("TASK: ${matchedList.size} acc sẽ làm nhiệm vụ: ${matchedList.map { it.first }}")
        AtProNotificationManager.notifyFarmStarted(matchedList.size)

        // Position acc đầu
        setStatus("SWITCH: Chuẩn bị acc đầu tiên...")
        val positioned = positionFirstAccount(matchedList.first().first, currentAcc)
        if (!positioned) {
            log("ERR: Task — Không position được acc đầu")
            return
        }

        // Phase 3: Task loop
        val farmedSet = mutableSetOf<String>()
        matchedList.forEachIndexed { idx, (username, golikeAcc) ->
            if (!isFarming) return@forEachIndexed
            awaitResumed()

            log("TASK: [${idx + 1}/${matchedList.size}] @$username (Golike: ${golikeAcc.uniqueUsername})")

            if (idx > 0) {
                val switched = switchToAccount(username, farmedSet)
                if (!switched) {
                    log("WARN: Task: Skip @$username — switch thất bại")
                    return@forEachIndexed
                }
            }
            farmedSet.add(username.lowercase())

            taskOneAccount(username, golikeAcc)
        }

        log("TASK: Hoàn thành tất cả ${matchedList.size} tài khoản — đóng TikTok")
        delay(800)
        host.killTikTok()
    }

    /**
     * Làm nhiệm vụ cho 1 acc.
     *
     * Loop: [farm đệm] → [lấy job] → [làm job] → lặp lại
     * Thoát khi: đạt đủ taskJobsPerAccount hoặc thất bại liên tiếp >= taskMaxConsecFailures.
     */
    private suspend fun taskOneAccount(
        username:   String,
        golikeAcc:  TikTokAccountDto,
    ) {
        val gRepo = golikeRepo ?: run {
            log("WARN: Task: GolikeRepository null — bỏ qua @$username")
            return
        }

        val sessionId   = repo.startSession(username)
        var jobsDone    = 0
        var consecFail  = 0
        var totalLikes  = 0
        var totalFollows = 0
        var totalVideos  = 0

        // Chờ feed ổn định
        if (!waitFeedLoad()) recoverToFeed()

        while (isFarming && jobsDone < config.taskJobsPerAccount &&
               consecFail < config.taskMaxConsecFailures) {
            awaitResumed()

            // ── [A] Farm đệm ──────────────────────────────────────
            setStatus("TASK: [@$username] Nuôi acc trước job ${jobsDone + 1}...")
            val farmVideos = doSimpleFarm(config.taskFarmBeforeJobSecs)
            totalVideos += farmVideos
            if (!isFarming) break

            // ── [B] Lấy job từ Golike ─────────────────────────────
            setStatus("TASK: Lấy nhiệm vụ từ server...")
            val jobsResult = gRepo.getTikTokJobs(golikeAcc.id)
            val (job, lock) = when (jobsResult) {
                is GolikeResult.Success -> Pair(jobsResult.data.data, jobsResult.data.lock)
                is GolikeResult.Error -> {
                    log("TASK: Lấy job thất bại: ${jobsResult.message}")
                    if (jobsResult.isAuthError) {
                        log("TASK: Token Golike hết hạn/không hợp lệ — dừng task, cần đăng nhập lại")
                        break
                    }
                    if (jobsResult.cooldown > 0) {
                        log("TASK: Server yêu cầu chờ ${jobsResult.cooldown}s")
                        delay(jobsResult.cooldown * 1_000L)
                    }
                    consecFail++
                    Pair(null, null)
                }
            }

            if (job == null) {
                log("TASK: Không có job khả dụng cho @$username lúc này — dừng làm việc")
                break
            }

            // object_id: ưu tiên lock (golike.py: lock.get("object_id") or job.get("object_id", ""))
            val objectId = lock?.objectId?.takeIf { it.isNotBlank() } ?: job.objectId

            // Lọc theo loại job được cấu hình — server tự chọn job, không phải client.
            // Nếu không khớp config: skip job này để giải phóng lock, lấy job khác ở vòng sau.
            val typeMatches = when (config.taskJobType) {
                TaskJobType.LIKE   -> job.type == "like"
                TaskJobType.FOLLOW -> job.type == "follow"
                TaskJobType.BOTH   -> job.type == "like" || job.type == "follow"
            }
            if (!typeMatches) {
                log("TASK: Job #${job.jobId} type=${job.type} không khớp cấu hình — skip")
                gRepo.skipTikTokJob(golikeAcc.id, job.jobId, objectId)
                consecFail++
                continue
            }

            log("TASK: Job #${job.jobId} type=${job.type} link=${job.link.take(60)}")

            // ── [C] Mở link nhiệm vụ ─────────────────────────────
            setStatus("TASK: Mở link nhiệm vụ...")
            host.openDeepLink(job.link)
            delay(1_500)  // chờ TikTok mở link

            // Chờ delay cấu hình cho video/profile load
            for (s in config.taskJobDelaySecs downTo 1) {
                awaitResumed()
                setStatus("TASK: Chờ tải nội dung... (${s}s)")
                delay(1_000L)
            }

            // ── [D] Thực hiện nhiệm vụ ───────────────────────────
            val success = when (job.type) {
                "like"   -> doLikeTask()
                "follow" -> doFollowTask()
                else     -> {
                    log("TASK: Loại job không hỗ trợ: ${job.type}")
                    false
                }
            }

            // ── [E] Back về feed ──────────────────────────────────
            setStatus("TASK: Về lại feed...")
            var backCount = 0
            while (!NodeTraverser.isOnFeedTab(host.getRootNode()) && backCount < 8) {
                host.pressBack()
                Human.delay(700, 1_100)
                backCount++
            }
            if (!NodeTraverser.isOnFeedTab(host.getRootNode())) recoverToFeed()

            // ── [F] Báo cáo kết quả lên Golike ───────────────────
            // v1.2.5 — Theo golike.py: complete-jobs CHỈ dùng khi đã làm xong
            // (không có cờ success). Nếu không làm được trên máy → skip-jobs riêng.
            if (!success) {
                setStatus("TASK: Không thực hiện được — báo skip...")
                log("TASK: Job #${job.jobId} không thực hiện được trên máy — gọi skip")
                when (val skipResult = gRepo.skipTikTokJob(golikeAcc.id, job.jobId, objectId)) {
                    is GolikeResult.Success -> log("TASK: Đã skip job #${job.jobId}")
                    is GolikeResult.Error   -> log("TASK: Lỗi skip job #${job.jobId}: ${skipResult.message}")
                }
                consecFail++
                continue
            }

            setStatus("TASK: Báo cáo kết quả...")
            val completeResult = gRepo.completeTikTokJob(
                accountId = golikeAcc.id,
                adsId     = job.jobId,
                objectId  = objectId,
                type      = job.type,
                link      = job.link,
            )

            when (completeResult) {
                is GolikeResult.Success -> {
                    if (completeResult.data.success) {
                        val earned = completeResult.data.data?.prices ?: job.fixCoin
                        log("TASK: Job #${job.jobId} hoàn thành ✓ (+$earned coin)")
                        jobsDone++
                        consecFail = 0
                        if (job.type == "like") totalLikes++ else totalFollows++
                    } else {
                        log("TASK: Server từ chối job #${job.jobId}: ${completeResult.data.message}")
                        if (completeResult.data.cooldown > 0) {
                            log("TASK: Chờ cooldown ${completeResult.data.cooldown}s")
                            delay(completeResult.data.cooldown * 1_000L)
                        }
                        consecFail++
                    }
                }
                is GolikeResult.Error -> {
                    log("TASK: Lỗi báo cáo job #${job.jobId}: ${completeResult.message}")
                    if (completeResult.isAuthError) {
                        log("TASK: Token Golike hết hạn/không hợp lệ — dừng task")
                        break
                    }
                    if (completeResult.cooldown > 0) {
                        log("TASK: Chờ cooldown ${completeResult.cooldown}s")
                        delay(completeResult.cooldown * 1_000L)
                    }
                    consecFail++
                }
            }
        }

        repo.closeSession(sessionId, username, totalLikes, totalFollows, totalVideos, 0)
        log("TASK: @$username xong — jobs=$jobsDone likes=$totalLikes follows=$totalFollows videos=$totalVideos")
        setStatus("")
    }

    /**
     * v1.2.1: Farm đơn giản trong task mode — chỉ scroll feed + like.
     * Không comment, không inbox/shop/search, không follow (tránh conflict với task flow).
     * Chạy trong `durationSecs` giây rồi trả về.
     *
     * Trả số video đã xem.
     */
    private suspend fun doSimpleFarm(durationSecs: Int): Int {
        // v1.2.3: pause-aware deadline — đồng bộ cách tính với farmOneAccount().
        val totalMs      = durationSecs * 1_000L
        val startWall    = System.currentTimeMillis()
        val startPauseMs = currentPauseMs()
        fun secsLeftNow(): Long {
            val elapsedMs = (System.currentTimeMillis() - startWall) - (currentPauseMs() - startPauseMs)
            return maxOf(0L, (totalMs - elapsedMs) / 1_000L)
        }

        var videos = 0

        while (secsLeftNow() > 0L && isFarming) {
            awaitResumed()

            // v1.2.3 [FIX]: bọc toàn bộ 1 vòng lặp trong safeStep — exception/treo
            // ở 1 bước (vd doLike) không làm dừng cả doSimpleFarm/task.
            val iterationOk = safeStep("simple_farm_iter", timeoutMs = 60_000L) {
                val root = host.getRootNode()

                // Popup + wellbeing + daily limit
                val popupResult = popup.handleIfPresent()
                if (popupResult.handled) { delay(500); return@safeStep }

                val wellbeingBtn = NodeTraverser.findReturnFromWellbeingButton(root)
                if (wellbeingBtn != null) {
                    host.clickNode(wellbeingBtn.node); delay(1_500); return@safeStep
                }
                if (NodeTraverser.detectDailyLimitScreen(root)) { delay(2_000); return@safeStep }

                // Skip live / ad / diary
                if (config.skipAds && NodeTraverser.detectAd(root))   { swipeNext(); return@safeStep }
                if (config.skipLive && NodeTraverser.detectLive(root)) { swipeNext(); return@safeStep }
                if (NodeTraverser.detectDiary(root))                   { swipeNext(); return@safeStep }

                // Lost detection
                if (isLostWithRetry()) { recoverToFeed(); return@safeStep }

                // Xem video
                val watchMs   = randomWatchTimeMs()
                val secsLeft  = maxOf(1L, secsLeftNow())
                val watchSecs = minOf(watchMs / 1_000L, secsLeft).coerceAtLeast(1L)
                for (w in watchSecs downTo 1L) {
                    delay(1_000L)
                    if (!isFarming) break
                }
                videos++

                Human.occasionalPause(config.occasionalPauseChance)

                // Like theo likeRate (không follow trong simplified farm)
                val freshRoot     = host.getRootNode()
                val isLiveContent = NodeTraverser.detectLive(freshRoot)
                val isAdContent   = !isLiveContent && NodeTraverser.detectAd(freshRoot)
                if (!isLiveContent && !isAdContent && Random.nextFloat() < config.likeRate) {
                    Human.delay(450, 1_200)
                    safeStep("simple_farm_like", timeoutMs = 15_000L) { doLike() }
                }

                safeStep("simple_farm_swipe", timeoutMs = 10_000L) { swipeNext() }
            }
            if (iterationOk == null) {
                // Timeout/lỗi toàn vòng — đảm bảo vẫn đang ở feed trước khi lặp lại.
                if (!NodeTraverser.isOnFeedTab(host.getRootNode())) recoverToFeed()
            }
        }
        return videos
    }

    /**
     * v1.2.1: Thực hiện like trong task mode — double-tap vào vùng video.
     * Trả true khi double-tap thành công.
     */
    private suspend fun doLikeTask(): Boolean {
        val cx = screenW / 2 + Human.jitter(25)
        val cy = (screenH * 0.55).toInt() + Human.jitter(20)
        host.clickSuspend(cx, cy)
        // v1.2.3 [FIX]: doubleTapGap() thay microPause() — xem doLike().
        Human.doubleTapGap()
        host.clickSuspend(cx + Human.jitter(8), cy + Human.jitter(8))
        Human.likeAnimDelay()
        delay((config.delayAfterLike * 1_000).toLong())
        log("TASK-LIKE: Double-tap ✓")
        return true
    }

    /**
     * v1.2.1: Thực hiện follow trong task mode — trang hồ sơ đang mở sẵn từ job link.
     * Trả true nếu follow thành công hoặc unconfirmed (tạm tính thành công).
     */
    private suspend fun doFollowTask(): Boolean {
        val result = doFollowOnOpenProfile()
        return when (result) {
            FollowResult.SUCCESS, FollowResult.UNCONFIRMED -> true
            FollowResult.ALREADY_FOLLOWING -> {
                log("TASK-FOLLOW: Đã follow rồi — tính là thành công")
                true
            }
            FollowResult.BLOCKED, FollowResult.NO_BUTTON -> false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Demo nuôi acc Facebook — v1.2.3
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * v1.2.3 — Phiên nuôi acc Facebook đơn giản.
     *
     * Flow: mở Facebook → lướt feed → ngẫu nhiên thích bài đăng → đóng app.
     *
     * Mỗi vòng lặp được bọc safeStep() (giống farmOneAccount) — exception/treo
     * ở 1 bước (vd Like) không làm dừng cả phiên, luôn tiến tới swipe feed tiếp.
     */
    private suspend fun runFacebookNurtureSession() {
        val pkg = AppConstants.FACEBOOK_PKG

        log(">> [Facebook] Bắt đầu phiên nuôi acc Facebook")
        setStatus(">> Đang mở Facebook...")
        if (!host.launchApp(pkg)) {
            log("ERR: Không mở được Facebook (gói: $pkg) — kiểm tra đã cài đặt trên thiết bị")
            setStatus("")
            return
        }
        delay(4_000)  // chờ app khởi động + feed load

        val durationSecs = config.facebookNurtureDurationSecs.toLong()
        val totalMs      = durationSecs * 1_000L
        val startWall    = System.currentTimeMillis()
        val startPauseMs = currentPauseMs()
        fun secsLeftNow(): Long {
            val elapsedMs = (System.currentTimeMillis() - startWall) - (currentPauseMs() - startPauseMs)
            return maxOf(0L, (totalMs - elapsedMs) / 1_000L)
        }

        var scrolled = 0
        var liked    = 0

        log("FB: Lướt feed Facebook trong ${durationSecs}s (likeRate=${config.facebookLikeRate})")

        while (secsLeftNow() > 0L && isFarming) {
            awaitResumed()
            val secsLeft = secsLeftNow()

            OverlayFarmMonitor.update(
                accountIndex    = 1,
                accountTotal    = 1,
                accountId       = "Facebook",
                sessionSecsLeft = secsLeft,
                totalSecsLeft   = secsLeft,
                action          = "FB: Lướt feed (${secsLeft}s)",
            )

            val iterationOk = safeStep("facebook_iter", timeoutMs = 30_000L) {
                // Ngẫu nhiên thích bài đăng — tìm node "Thích"/"Like" trên màn hình.
                if (Random.nextFloat() < config.facebookLikeRate) {
                    val root = host.getRootNode()
                    val likeBtn = NodeTraverser.findByText(root, "thích", ignoreCase = true)
                        ?: NodeTraverser.findByText(root, "like", ignoreCase = true)
                    if (likeBtn != null) {
                        val label = likeBtn.text?.lowercase() ?: ""
                        // Bỏ qua nếu đã "Bỏ thích" / "Unlike" (đã like rồi)
                        if ("bỏ thích" !in label && "unlike" !in label) {
                            Human.microPause()
                            host.clickNode(likeBtn.node)
                            liked++
                            log("FB: Đã thích bài đăng (#$liked)")
                            OverlayFarmMonitor.update(
                                accountIndex    = 1,
                                accountTotal    = 1,
                                accountId       = "Facebook",
                                sessionSecsLeft = secsLeftNow(),
                                totalSecsLeft   = secsLeftNow(),
                                action          = "FB: Đã thích bài #$liked",
                            )
                            Human.delay(800, 1_500)
                        }
                    }
                }

                // Lướt feed lên — random duration giống TikTok swipeNext().
                val xOff = Human.jitter(12)
                val x    = screenW / 2 + xOff
                host.swipeSuspend(
                    x, (screenH * 0.78).toInt(),
                    x, (screenH * 0.25).toInt(),
                    Human.swipeDuration(450),
                )
                scrolled++
                Human.delay(1_200, 2_500)
                Human.occasionalPause(config.occasionalPauseChance)
            }
            if (iterationOk == null) {
                // Timeout/lỗi — vẫn tiếp tục, đợi 1 nhịp rồi thử lại.
                delay(1_000)
            }
        }

        log("FB: Hoàn thành — lướt $scrolled lần, thích $liked bài đăng")
        setStatus("FB: Đang đóng Facebook...")
        delay(500)
        host.killApp(pkg)
        setStatus("")
    }

    // ── v1.2.4: Demo session runners — X, Instagram, Threads, Snapchat ────────

    /** Demo nuôi X: mở X → lướt timeline → like/repost → đóng. */
    private suspend fun runXNurtureSession() {
        val pkg = AppConstants.X_PKG
        log(">> [X] Bắt đầu phiên nuôi acc X (Twitter)")
        setStatus(">> Đang mở X...")
        if (!host.launchApp(pkg)) {
            log("ERR: Không mở được X (gói: $pkg) — kiểm tra đã cài đặt"); setStatus(""); return
        }
        delay(4_000)

        val totalMs   = config.xNurtureDurationSecs * 1_000L
        val startWall = System.currentTimeMillis()
        val startPMs  = currentPauseMs()
        fun secsLeft() = maxOf(0L, (totalMs - ((System.currentTimeMillis() - startWall) - (currentPauseMs() - startPMs))) / 1_000L)

        var scrolled = 0; var liked = 0; var reposted = 0
        log("X: Lướt timeline ${config.xNurtureDurationSecs}s (like=${config.xLikeRate}, repost=${config.xRetweetRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "X", secsLeft(), secsLeft(), "X: Lướt timeline (${secsLeft()}s)")
            safeStep("x_iter", 30_000L) {
                if (Random.nextFloat() < config.xLikeRate) {
                    val root = host.getRootNode()
                    val btn = NodeTraverser.findByText(root, "thích", ignoreCase = true)
                        ?: NodeTraverser.findByText(root, "like", ignoreCase = true)
                    if (btn != null) {
                        Human.microPause(); host.clickNode(btn.node); liked++
                        log("X: Đã like tweet (#$liked)"); Human.delay(600, 1_200)
                    }
                }
                if (Random.nextFloat() < config.xRetweetRate) {
                    reposted++; log("X: Repost tweet (#$reposted) (demo)")
                }
                val x = screenW / 2 + Human.jitter(12)
                host.swipeSuspend(x, (screenH * 0.75).toInt(), x, (screenH * 0.25).toInt(), Human.swipeDuration(420))
                scrolled++
                Human.delay(1_000, 2_200)
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("X: Hoàn thành — lướt $scrolled lần, like $liked, repost $reposted")
        setStatus("X: Đang đóng X..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** Demo nuôi Instagram: mở Instagram → lướt Reels → like → follow → đóng. */
    private suspend fun runInstagramNurtureSession() {
        val pkg = AppConstants.INSTAGRAM_PKG
        log(">> [Instagram] Bắt đầu phiên nuôi acc Instagram")
        setStatus(">> Đang mở Instagram...")
        if (!host.launchApp(pkg)) {
            log("ERR: Không mở được Instagram (gói: $pkg) — kiểm tra đã cài đặt"); setStatus(""); return
        }
        delay(4_500)

        val totalMs   = config.instagramNurtureDurationSecs * 1_000L
        val startWall = System.currentTimeMillis()
        val startPMs  = currentPauseMs()
        fun secsLeft() = maxOf(0L, (totalMs - ((System.currentTimeMillis() - startWall) - (currentPauseMs() - startPMs))) / 1_000L)

        var scrolled = 0; var liked = 0; var followed = 0
        log("IG: Lướt Reels ${config.instagramNurtureDurationSecs}s (like=${config.instagramLikeRate}, follow=${config.instagramFollowRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Instagram", secsLeft(), secsLeft(), "IG: Lướt Reels (${secsLeft()}s)")
            safeStep("ig_iter", 30_000L) {
                if (Random.nextFloat() < config.instagramLikeRate) {
                    val root = host.getRootNode()
                    val btn = NodeTraverser.findByText(root, "thích", ignoreCase = true)
                        ?: NodeTraverser.findByText(root, "like", ignoreCase = true)
                    if (btn != null) {
                        Human.microPause(); host.clickNode(btn.node); liked++
                        log("IG: Đã like (#$liked)"); Human.delay(500, 1_000)
                    }
                }
                if (Random.nextFloat() < config.instagramFollowRate) {
                    followed++; log("IG: Follow (demo) (#$followed)")
                }
                val x = screenW / 2 + Human.jitter(10)
                host.swipeSuspend(x, (screenH * 0.80).toInt(), x, (screenH * 0.20).toInt(), Human.swipeDuration(400))
                scrolled++
                Human.delay(2_000, 4_000)  // Reels xem lâu hơn
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("IG: Hoàn thành — lướt $scrolled lần, like $liked, follow $followed")
        setStatus("IG: Đang đóng Instagram..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** Demo nuôi Threads: mở Threads → lướt feed → like → đóng. */
    private suspend fun runThreadsNurtureSession() {
        val pkg = AppConstants.THREADS_PKG
        log(">> [Threads] Bắt đầu phiên nuôi acc Threads")
        setStatus(">> Đang mở Threads...")
        if (!host.launchApp(pkg)) {
            log("ERR: Không mở được Threads (gói: $pkg) — kiểm tra đã cài đặt"); setStatus(""); return
        }
        delay(4_000)

        val totalMs   = config.threadsNurtureDurationSecs * 1_000L
        val startWall = System.currentTimeMillis()
        val startPMs  = currentPauseMs()
        fun secsLeft() = maxOf(0L, (totalMs - ((System.currentTimeMillis() - startWall) - (currentPauseMs() - startPMs))) / 1_000L)

        var scrolled = 0; var liked = 0
        log("Threads: Lướt feed ${config.threadsNurtureDurationSecs}s (like=${config.threadsLikeRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Threads", secsLeft(), secsLeft(), "Threads: Lướt feed (${secsLeft()}s)")
            safeStep("threads_iter", 30_000L) {
                if (Random.nextFloat() < config.threadsLikeRate) {
                    val root = host.getRootNode()
                    val btn = NodeTraverser.findByText(root, "thích", ignoreCase = true)
                        ?: NodeTraverser.findByText(root, "like", ignoreCase = true)
                    if (btn != null) {
                        Human.microPause(); host.clickNode(btn.node); liked++
                        log("Threads: Đã like (#$liked)"); Human.delay(600, 1_100)
                    }
                }
                val x = screenW / 2 + Human.jitter(8)
                host.swipeSuspend(x, (screenH * 0.76).toInt(), x, (screenH * 0.26).toInt(), Human.swipeDuration(430))
                scrolled++
                Human.delay(1_200, 2_600)
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("Threads: Hoàn thành — lướt $scrolled lần, like $liked")
        setStatus("Threads: Đang đóng..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** Demo nuôi Snapchat: mở Snapchat → xem Spotlight / Stories → swipe → đóng. */
    private suspend fun runSnapchatNurtureSession() {
        val pkg = AppConstants.SNAPCHAT_PKG
        log(">> [Snapchat] Bắt đầu phiên nuôi acc Snapchat")
        setStatus(">> Đang mở Snapchat...")
        if (!host.launchApp(pkg)) {
            log("ERR: Không mở được Snapchat (gói: $pkg) — kiểm tra đã cài đặt"); setStatus(""); return
        }
        delay(5_000)  // Snapchat khởi động chậm hơn

        val totalMs   = config.snapchatNurtureDurationSecs * 1_000L
        val startWall = System.currentTimeMillis()
        val startPMs  = currentPauseMs()
        fun secsLeft() = maxOf(0L, (totalMs - ((System.currentTimeMillis() - startWall) - (currentPauseMs() - startPMs))) / 1_000L)

        var storiesViewed = 0
        val viewMs = config.snapchatStoryViewSecs * 1_000L
        log("Snapchat: Xem Spotlight ${config.snapchatNurtureDurationSecs}s (${config.snapchatStoryViewSecs}s/story)")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Snapchat", secsLeft(), secsLeft(), "Snapchat: Xem story #${storiesViewed + 1}")
            safeStep("snap_iter", 40_000L) {
                delay(viewMs)  // xem story N giây
                storiesViewed++
                log("Snapchat: Đã xem story #$storiesViewed")
                // Swipe sang story tiếp theo (trái sang phải = next story)
                val y = screenH / 2
                host.swipeSuspend((screenW * 0.85).toInt(), y, (screenW * 0.15).toInt(), y, 250)
                Human.delay(800, 1_500)
            }
        }
        log("Snapchat: Hoàn thành — đã xem $storiesViewed stories")
        setStatus("Snapchat: Đang đóng..."); delay(500); host.killApp(pkg); setStatus("")
    }
}

