package com.atpro.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.accessibility.ContentMatcher
import com.atpro.accessibility.CountParser
import com.atpro.accessibility.LikeCountGate
import com.atpro.accessibility.NodeTraverser
import com.atpro.accessibility.likeCountGate
import com.atpro.automation.popup.PopupHandler
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.data.TaskJobType
import com.atpro.tasks.TaskAccount
import com.atpro.tasks.TaskAccountMode
import com.atpro.tasks.TaskAccountSelector
import com.atpro.tasks.TaskJob
import com.atpro.tasks.TaskJobKind
import com.atpro.tasks.TaskProvider
import com.atpro.tasks.TaskResult
import com.atpro.network.LanWebSocketServer
import com.atpro.network.DiscordNotifier
import com.atpro.notification.AtProNotificationManager
import com.atpro.notification.NotifText
import com.atpro.notification.NotificationText
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

/** v1.2.1 — Chế độ dịch vụ: nuôi tài khoản hoặc làm nhiệm vụ kiếm tiền (v1.4.0: qua TaskProvider). */
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
    CLICK_FAILED,      // v1.9.0: thấy nút, bấm (cả ACTION_CLICK lẫn chạm cử chỉ) nhưng nút không đổi trạng thái
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
    // v1.9.0 — chế độ nhiệm vụ (Dashboard dùng thay cho video/còn lại)
    val jobsDone:      Int    = 0,
    val jobsTarget:    Int    = 0,
    val consecErrors:  Int    = 0,
    val reward:        Double = 0.0,
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

/**
 * v1.3.3 — Tính thời gian (giây) nuôi acc SAU KHI hoàn thành 1 job trong task mode.
 *
 * Pure function (không phụ thuộc Android/coroutine/host) — cố ý tách khỏi
 * taskOneAccount() để test trực tiếp bằng JUnit, không cần mock IFarmHost/
 * AccessibilityNodeInfo (theo skill `mat/tdd.md` — pure logic tách riêng khỏi
 * phần cần Android framework). Xem `TaskNurtureTest.kt`.
 *
 * Logic:
 *   - `jobsDone` là bội số dương của `everyNJobs` (và `everyNJobs > 0`) →
 *     nuôi LÂU — trả về đúng `longDurationSecs` (cố định, không random —
 *     phiên nuôi dài đã đủ đa dạng nhờ nội dung xem, không cần dao động thời lượng).
 *   - Ngược lại → nuôi NGẮN — random đều trong [shortMinSecs, shortMaxSecs].
 *
 * `everyNJobs <= 0` → tắt hẳn nuôi lâu định kỳ, luôn dùng nuôi ngắn.
 * `shortMaxSecs < shortMinSecs` (config lỗi/chưa đồng bộ) → coerce về shortMinSecs
 * cho cả hai đầu, tránh IllegalArgumentException từ Random.nextInt().
 *
 * `randomInt`: nguồn sinh số ngẫu nhiên trong [min, max] (đóng cả 2 đầu) — tham
 * số hoá để test kiểm soát được, không phụ thuộc kotlin.random.Random thật.
 */
internal fun taskPostJobNurtureSecs(
    jobsDone:         Int,
    everyNJobs:       Int,
    longDurationSecs: Int,
    shortMinSecs:     Int,
    shortMaxSecs:     Int,
    randomInt:        (IntRange) -> Int = { it.random() },
): Int {
    val isLongCycle = everyNJobs > 0 && jobsDone > 0 && jobsDone % everyNJobs == 0
    if (isLongCycle) return longDurationSecs

    val safeMin = shortMinSecs
    val safeMax = maxOf(shortMinSecs, shortMaxSecs)
    return randomInt(safeMin..safeMax)
}

/**
 * v1.11.0 — Chọn dòng cần bấm trong popup chuyển tài khoản. Trả chỉ số trong [names] hoặc `null` (không bấm).
 *
 *   • không có dòng trùng [target]                         → null (không tìm thấy)
 *   • [current] (acc đang đăng nhập) trùng [target]        → null (đã ở đúng acc, bấm chỉ đóng popup)
 *   • không biết [current] và dòng trùng là dòng ĐẦU, danh sách có ≥ 2 dòng
 *       → null: dòng đầu thường là acc đang login (hành vi cũ, tránh "chuyển" sang chính acc đang dùng)
 *   • còn lại                                              → chỉ số dòng đó
 *
 * Trường hợp từng gây lỗi: danh sách chỉ đọc được 1 dòng (chính là target) trong khi acc đang dùng là acc khác —
 * bản cũ luôn bỏ dòng đầu nên không bao giờ bấm được. Bản này bấm.
 */
internal fun pickSwitchEntry(names: List<String>, target: String, current: String?): Int? {
    val want = target.trim().removePrefix("@")
    val idx = names.indexOfFirst { it.trim().removePrefix("@").equals(want, ignoreCase = true) }
    if (idx < 0) return null
    val cur = current?.trim()?.removePrefix("@")
    if (cur != null && cur.equals(want, ignoreCase = true)) return null
    if (cur == null && idx == 0 && names.size >= 2) return null
    return idx
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

/**
 * v1.3.1 — Đọc root node + đánh giá (vd isOnFeedTab/detectLive...) dưới 1 timeout
 * THỰC SỰ có tác dụng huỷ được.
 *
 * `host.getRootNode()` tự nó đã dispatch sang Dispatchers.IO (xem IFarmHost),
 * nhưng bước DUYỆT CÂY sau đó (NodeTraverser.*) chạy trên root đã lấy được —
 * nếu duyệt cây cũng bị treo (app đích đứng hình khi đang truy vấn từng node
 * con qua IPC), nó cần nằm CÙNG 1 khối IO/timeout với bước fetch, không phải
 * chạy tiếp trên dispatcher gốc. Gộp cả hai vào 1 khối withContext(IO) để nếu
 * bất kỳ bước nào bên trong bị treo, timeoutMs vẫn trả quyền điều khiển lại
 * cho vòng lặp farm thay vì kẹt cứng.
 *
 * Dùng ở các điểm nóng hay bị "đứng hình" theo phản hồi thực tế: drift-check
 * trong lúc xem video, isLostWithRetry(), recoverFromStuck()/recoverToFeed().
 *
 * v1.3.1 (bổ sung): log khi timeout THỰC SỰ xảy ra (không phải block() trả về
 * null hợp lệ — với các cách dùng hiện tại T luôn là Boolean non-null nên null
 * ở đây đồng nghĩa timeout) — để verify được ngoài thực tế fix này có đang
 * "cứu" được các lần treo hay không, thay vì chỉ tin suông vào lý thuyết.
 * `logFn` optional: truyền `::log` từ nơi gọi để tin nhắn hiện lên CẢ overlay
 * popup (tag "WDG:" đã có sẵn màu/icon riêng), không chỉ logcat.
 */
private suspend fun <T> safeScreenRead(
    host:      IFarmHost,
    timeoutMs: Long = 4_000L,
    tag:       String = "",
    logFn:     ((String) -> Unit)? = null,
    block:     (AccessibilityNodeInfo?) -> T,
): T? = try {
    val result = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) { block(host.getRootNode()) }
    }
    if (result == null) {
        val ctx = if (tag.isNotEmpty()) " [$tag]" else ""
        Log.w(AutomationEngine.TAG, "IPC-TIMEOUT: getRootNode()/duyệt cây treo >${timeoutMs}ms$ctx — đã tự thoát")
        logFn?.invoke("WDG: IPC treo >${timeoutMs}ms$ctx — đã tự thoát, không kẹt cứng")
    }
    result
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(AutomationEngine.TAG, "safeScreenRead: ${e.javaClass.simpleName} — ${e.message}")
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
    private val taskProvider: TaskProvider? = null,
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

    /**
     * v1.11.0 — Tài khoản đang đăng nhập theo hiểu biết gần nhất (null = chưa rõ). `detectCurrentAccount()` luôn ghi đè;
     * chuyển acc thành công cập nhật. Dùng để chọn đúng dòng trong popup chuyển acc (xem [pickSwitchEntry]).
     */
    @Volatile private var activeAcc: String? = null

    /** v1.11.0 — Bộ khớp hashtag/từ khoá đã biên dịch, nhớ theo danh sách từ khoá (không biên dịch lại mỗi video). */
    private val matcherMemo = HashMap<List<String>, ContentMatcher>()
    private fun matcherFor(keywords: List<String>): ContentMatcher =
        matcherMemo.getOrPut(keywords) { ContentMatcher.of(keywords) }

    /**
     * v1.2.9: Rate-limit comment theo giờ (per doc accessibility-comment-like-logic.md).
     * Lưu timestamp các lần comment thành công trong phiên hiện tại — TikTok có cơ chế
     * phát hiện "spam behavior" nếu comment quá nhiều/giờ, dẫn tới shadow-ban.
     * Reset khi farm dừng (resetPauseTracking không xoá — chỉ xoá khi startFarm/startTask mới).
     */
    private val commentTimestamps = java.util.concurrent.CopyOnWriteArrayList<Long>()

    /** Kiểm tra còn được phép comment trong giờ hiện tại không (dựa trên config.maxCommentsPerHour). */
    private fun canCommentNow(): Boolean {
        if (config.maxCommentsPerHour <= 0) return true  // 0 = không giới hạn
        val oneHourAgo = System.currentTimeMillis() - 3_600_000L
        commentTimestamps.removeAll { it < oneHourAgo }
        return commentTimestamps.size < config.maxCommentsPerHour
    }

    private fun recordCommentSent() {
        commentTimestamps.add(System.currentTimeMillis())
    }

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
    @Volatile private var isNormalizing          = false
    /** v1.2.7: Acc đang active SAU normalize — dùng để không click lại acc hiện tại */
    @Volatile private var postNormalizeActiveAcc: String? = null

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
            try {
                // v1.12.5: nạp cấu hình + hiện popup nằm TRONG try — lỗi ở đây vẫn đi qua finally → stopInternal
                // (nhả cờ isFarming, về màn hình chính). Trước đây lỗi ở hai dòng này làm phiên chết mà không qua finally.
                config = repo.loadFarmConfig()
                host.showFarmOverlay("NUÔI TKT")
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
     * v1.2.1 Bắt đầu chế độ làm nhiệm vụ TikTok (v1.4.0: qua [TaskProvider]).
     *
     * Flow:
     *   1. Lấy danh sách TikTok acc đã liên kết từ nhà cung cấp.
     *   2. Mở TikTok → scan acc đang đăng nhập trên thiết bị.
     *   3. Match: acc thiết bị ∩ acc nhà cung cấp → danh sách cần làm việc.
     *   4. Với mỗi acc: nuôi → lấy job → làm → nuôi → ... → chuyển acc.
     *
     * `mode`: cách build danh sách (ALL_LOCAL hoặc SELECTED_LIST).
     * `inputList`: danh sách acc cụ thể (dùng khi mode = SELECTED_LIST).
     */
    fun startTask(
        mode:      FarmMode,
        inputList: List<String> = emptyList(),
    ) {
        if (isFarming) return
        isFarming = true
        isPaused  = false
        isResting = false
        resetPauseTracking()
        followDebugDumps = 0   // v1.9.0: mỗi lần chạy nhiệm vụ được dump chẩn đoán hồ sơ tối đa 3 lần

        farmJob = host.scope.launch {
            try {
                config = repo.loadFarmConfig()          // v1.12.5: trong try — xem startFarm()
                host.showFarmOverlay("NV TIKTOK")
                runTaskStateMachine(mode, inputList)
            } catch (e: CancellationException) {
                log("Task bị hủy bởi người dùng")
                throw e
            } catch (e: Exception) {
                log("ERR: Task lỗi không mong đợi: ${e.javaClass.simpleName} — ${e.message}")
            } finally {
                activeTaskProvider?.setTraceListener(null)
                activeTaskProvider = null
                AtProNotificationManager.clearRunStatus()
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
            try {
                config = repo.loadFarmConfig()          // v1.12.5: trong try — xem startFarm()
                host.showFarmOverlay("NUÔI TKFB")
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
            try { config = repo.loadFarmConfig(); host.showFarmOverlay("NUÔI TKX"); runXNurtureSession() }
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
            try { config = repo.loadFarmConfig(); host.showFarmOverlay("NUÔI TKIG"); runInstagramNurtureSession() }
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
            try { config = repo.loadFarmConfig(); host.showFarmOverlay("NUÔI TKTH"); runThreadsNurtureSession() }
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
            try { config = repo.loadFarmConfig(); host.showFarmOverlay("NUÔI TKSC"); runSnapchatNurtureSession() }
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
            log("ERR: Farm kết thúc sớm: ${phase.reason}")
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
        // v1.11.0: dòng "Đã xác nhận tài khoản…" của detectCurrentAccount() đã báo acc đang dùng — chỉ cảnh báo khi không đọc được.
        if (currentAcc == null) log("WARN: Chưa xác định được tài khoản đang dùng")

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
        AtProNotificationManager.startRun()
        AtProNotificationManager.notifyFarmStarted(farmList.size)
        notifyDiscord("🚀 **AT Pro** — Bắt đầu nuôi **${farmList.size}** tài khoản TikTok " +
            "(~${farmList.size * config.minutesPerAccount} phút dự kiến)")

        // Bước 4: Position acc đầu tiên (popup đang mở — dùng trực tiếp)
        // v1.2.7: Nếu có normalize thì dùng postNormalizeActiveAcc (stale currentAcc gây click lại acc hiện tại)
        val effectiveCurrentAcc = postNormalizeActiveAcc ?: currentAcc
        postNormalizeActiveAcc  = null   // reset sau khi dùng
        setStatus("SWITCH: Đang chuyển sang tài khoản đầu tiên...")
        val positioned = positionFirstAccount(farmList.first(), effectiveCurrentAcc)
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
            // v1.12.0: thông báo "đang chạy" hiện tiến độ nuôi (tài khoản n/N)
            AtProNotificationManager.setRunStatus("Nuôi TikTok", "@$account (${idx + 1}/${farmList.size})", idx, farmList.size)
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
                // v1.3.0: Ngoài cập nhật overlay mỗi giây, log định kỳ mỗi phút
                // (và ngay từ giây đầu) để người dùng thấy tiến trình đếm ngược
                // ngay trong màn hình Log, không chỉ trên overlay.
                // v1.10.0: một thanh đếm ngược trên popup thay cho cập nhật trạng thái MỖI GIÂY (tiết kiệm pin);
                // trạng thái chữ chỉ làm mới mỗi 10 giây.
                OverlayFarmMonitor.startCountdown("Nghỉ", restSecs * 1_000L)
                for (remaining in restSecs downTo 1L) {
                    awaitResumed()
                    val mm = remaining / 60L
                    val ss = remaining % 60L
                    val timeStr = "%02d:%02d".format(mm, ss)
                    if (remaining == restSecs || ss % 10L == 0L) setStatus("REST: Đang nghỉ $timeStr")
                    if (remaining == restSecs || ss == 0L) {
                        log("REST: Đang nghỉ... còn lại $timeStr")
                    }
                    delay(1_000L)
                }
                OverlayFarmMonitor.stopCountdown()

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
        notifyDiscord(
            "✅ **AT Pro** — Hoàn thành nuôi **${farmList.size}** tài khoản TikTok\n" +
            "▫️ Video xem: $sessionVideos  ▫️ Tim: $sessionLikes  ▫️ Follow: $sessionFollows"
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
     * v1.11.0: mở danh sách bằng [openSwitchList] (chống bấm nhầm Đăng xuất).
     * Trả về (success, discoveredList). Popup VẪN ĐỂ MỞ sau khi return.
     */
    private suspend fun openSwitchPopup(): Pair<Boolean, List<String>> {
        // v1.11.0: một đường duy nhất, có chống bấm nhầm "Đăng xuất" (xem openSwitchList).
        if (!openSwitchList()) return Pair(false, emptyList())

        // v1.2.5: Đọc danh sách kèm node — phân loại valid/invalid
        val entries  = NodeTraverser.parseAccountListWithNodes(host.getRootNode(), screenW, screenH)

        // ── Xử lý acc ĐẦU TIÊN (= acc đang đăng nhập hiện tại) ─────────────
        // v1.8.0 (FIX lỗi chuẩn hoá): acc ở vị trí 0 LÀ acc đang login. Click vào nó trong popup là no-op (TikTok chỉ
        // đóng popup) nên KHÔNG BAO GIỜ bấm chọn nó, và cũng không cần "switch tạm" sang acc khác rồi quay lại
        // (cách của v1.3.0 TH1: chậm, nhiều thao tác, dễ lệch). Cách đúng, một đường duy nhất:
        //   • acc đầu cần chuẩn hoá → đóng popup, về feed, MỞ HỒ SƠ đọc @username thật. Xong.
        //   • các acc KHÁC cần chuẩn hoá (restInvalid) → để khối "invalid" bên dưới xử lý như thường (switch thật).
        //     Acc đầu đã có @username từ Hồ sơ nên không phải quay lại nó.
        val firstEntry  = entries.firstOrNull()
        val restInvalid = entries.drop(1).filter { it.isNeedsNormalize }
        val discovered  = entries.filter { !it.isNeedsNormalize }.map { it.displayText }.toMutableList()

        if (config.normalizeEnabled && firstEntry != null && firstEntry.isNeedsNormalize) {
            // Đóng popup hiện tại — confirmCurrentAccountViaProfile() cần feed/tab Hồ sơ.
            host.pressBack(); delay(800); host.pressBack(); delay(800)

            log("FIX: Acc đang login '${firstEntry.displayText}' cần chuẩn hoá → chỉ mở Hồ sơ đọc @username " +
                "(không bấm vào acc đang đăng nhập)" +
                if (restInvalid.isNotEmpty()) "; ${restInvalid.size} acc khác sẽ chuẩn hoá sau" else "")
            confirmCurrentAccountViaProfile(firstEntry.displayText)?.let {
                discovered.add(0, it); postNormalizeActiveAcc = it
            }

            // Mở lại popup lần cuối để tiếp tục discover phần còn lại phía dưới.
            if (!openSwitchList()) {
                log("WARN: FIX: Không mở lại được popup sau chuẩn hoá acc đầu")
                if (discovered.isNotEmpty()) {
                    log("LIST: Discover ${discovered.size} acc từ switch popup")
                    setStatus("✓ Tìm thấy ${discovered.size} tài khoản trong popup")
                    autoSaveAccounts(discovered)
                }
                return Pair(true, discovered)
            }
        }

        val currentEntries = if (config.normalizeEnabled && firstEntry != null && firstEntry.isNeedsNormalize)
            NodeTraverser.parseAccountListWithNodes(host.getRootNode(), screenW, screenH)
        else entries

        // TH3 (hoặc phần còn lại sau TH1/TH2): chuẩn hoá các entry invalid còn
        // lại chưa xử lý — bỏ vị trí 0 vì đó luôn là acc đang login.
        val invalid = currentEntries.drop(1).filter { it.isNeedsNormalize }

        if (invalid.isNotEmpty() && config.normalizeEnabled) {
            log("FIX: Phát hiện ${invalid.size} entry không hợp lệ: " +
                invalid.joinToString { "'${it.displayText}'" })

            // Đóng popup hiện tại — normalizeInvalidAccountIds tự quản lý popup
            host.pressBack(); delay(800); host.pressBack(); delay(800)

            val fixedUsernames = normalizeInvalidAccountIds(invalid.map { it.displayText })
            // v1.2.7: acc CUỐI cùng chuẩn hoá = acc đang active sau normalize
            // → đưa lên đầu farmList để nuôi ngay (tận dụng context đã switch).
            // Phần còn lại của fixedUsernames ghép vào sau.
            if (fixedUsernames.isNotEmpty()) {
                val lastNormalized = fixedUsernames.last()
                val rest           = fixedUsernames.dropLast(1)
                // Cấu trúc: [lastNormalized] + [discovered có trước] + [rest]
                discovered.addAll(0, listOf(lastNormalized))
                discovered.addAll(rest)
            }
            // v1.2.7: lưu acc đang active sau normalize để phaseDiscover dùng thay currentAcc cũ
            if (fixedUsernames.isNotEmpty()) {
                postNormalizeActiveAcc = fixedUsernames.last()
                log("FIX: Chuẩn hoá xong — ${fixedUsernames.size} username, active hiện tại: @${postNormalizeActiveAcc}")
            } else {
                log("FIX: Chuẩn hoá xong — không có username mới")
            }

            // Mở lại popup lần cuối cho positionFirstAccount
            if (isFarming) openSwitchList()
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
     * v1.3.0 — TH2 của xử lý acc đầu tiên: acc đang login CHÍNH LÀ acc cần
     * chuẩn hoá, và không có acc nào khác cần chuẩn hoá để switch tạm qua.
     * Click vào chính entry đang active trong popup switch KHÔNG làm gì (TikTok
     * chỉ đóng popup) — nên thay vì switch, về thẳng feed rồi vào Hồ sơ để đọc
     * @username thật, coi như đã "chuẩn hoá" xong (không cần đổi acc nào cả).
     *
     * Giả định: popup switch đã được đóng (pressBack) trước khi gọi hàm này.
     */
    private suspend fun confirmCurrentAccountViaProfile(displayText: String): String? {
        // v1.8.0: hàm này còn được gọi TRONG normalizeInvalidAccountIds (cờ đã bật) → phải khôi phục giá trị cũ,
        // không được tắt hẳn cờ giữa chừng.
        val wasNormalizing = isNormalizing
        isNormalizing = true
        resetWatchdog()
        try {
            if (!waitFeedLoad()) {
                log("WARN: FIX: Feed chưa sẵn sàng trước khi xác nhận '$displayText' — thử recoverToFeed()")
                recoverToFeed()
            }
            setStatus("FIX: Xác nhận ID acc đang login '$displayText'...")
            val realUsername = detectCurrentAccount()
            return if (realUsername != null && NodeTraverser.isValidTikTokUsername(realUsername)) {
                log("FIX: ✓ '$displayText' (acc đang login) → '@$realUsername'")
                realUsername
            } else {
                log("WARN: FIX: Không đọc được @username của acc đang login (kết quả: $realUsername)")
                null
            }
        } finally {
            isNormalizing = wasNormalizing
            resetWatchdog()
        }
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
                if (!openSwitchList()) {
                    log("ERR: FIX: Không mở được danh sách tài khoản cho '$displayText' — bỏ qua")
                    return@forEachIndexed
                }

                // ── Bước 2: Re-parse popup fresh → tìm entry theo displayText ──
                // drop(1): entry đầu = acc đang login, KHÔNG click (chỉ đóng popup)
                val allFreshEntries = NodeTraverser
                    .parseAccountListWithNodes(host.getRootNode(), screenW, screenH)

                // v1.8.0: nếu target chính là entry đầu (= acc đang login) thì KHÔNG click và KHÔNG switch tạm sang acc
                // khác — chỉ cần đóng popup, về feed, mở Hồ sơ đọc @username thật.
                val firstFreshEntry = allFreshEntries.firstOrNull()
                if (firstFreshEntry != null &&
                    firstFreshEntry.displayText.trim().equals(displayText.trim(), ignoreCase = true)
                ) {
                    log("FIX: '$displayText' là acc đang login (vị trí 0) → chỉ mở Hồ sơ, không bấm chọn")
                    host.pressBack(); delay(800); host.pressBack(); delay(800)
                    confirmCurrentAccountViaProfile(displayText)?.let { recovered.add(it) }
                    delay(1_000)
                    return@forEachIndexed
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
                // v1.2.7: Sau khi click, TikTok tự điều hướng về feed (không cần pressBack)
                if (NodeTraverser.isLikelySystemLabel(target.displayText)) {
                    log("WARN: FIX: Fresh node '$displayText' nghi là nhãn hệ thống — bỏ qua")
                    host.pressBack(); delay(800); host.pressBack(); delay(800)
                    return@forEachIndexed
                }
                log("FIX: Click node '${target.displayText}' để switch...")
                host.clickNode(target.node)
                // v1.2.7: TikTok tự về feed sau khi switch thành công — chỉ cần delay + waitFeedLoad
                delay((config.delayAfterSwitchClick * 1_000).toLong().coerceAtLeast(2_000L))
                // [v1.3.1] Popup "cần cập nhật" có thể chặn ngay đây — dọn trước khi chờ feed,
                // tránh tốn hết 18s waitFeedLoad() rồi mới fallback kill+relaunch không cần thiết.
                popup.handleIfPresent()

                if (!isFarming) return@forEachIndexed

                // ── Bước 4: Chờ feed — không cần kill/relaunch vì TikTok tự redirect ──
                val feedLoaded = waitFeedLoad()
                if (!feedLoaded) {
                    // Fallback: nếu TikTok không tự về feed → kill+relaunch
                    log("WARN: FIX: Feed chưa hiện sau switch '$displayText' → fallback kill+relaunch")
                    host.killTikTok(); delay(2_500); host.launchTikTok()
                    if (!waitFeedLoad()) {
                        log("WARN: FIX: Feed không load sau fallback — bỏ qua")
                        return@forEachIndexed
                    }
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
     * Cuộn Settings cho đến khi thấy hàng "Chuyển đổi tài khoản". KHÔNG click — chỉ tìm.
     *
     * v1.12.5 (FIX vuốt thừa): trước đây tool vẫn vuốt thêm vài lần sau khi hàng đã hiện. Ba nguyên nhân, sửa cả ba:
     *  1. cú vuốt dài + nhanh → danh sách "trôi" (fling) qua mặt hàng rồi phải vuốt ngược lại → giờ vuốt NGẮN (34% màn
     *     hình) và CHẬM (≈600ms) nên không nhảy qua hàng, trôi rất ít;
     *  2. chỉ đọc lại sau 700ms cố định → giờ DÒ liên tục (150ms/lần) trong lúc danh sách còn trôi, thấy hàng là dừng ngay;
     *  3. kiểm tra thiếu một lượt sau cú vuốt cuối (vuốt xong mới thấy hàng nhưng vẫn báo "không tìm thấy").
     * Popup che trang Cài đặt (vuốt sẽ trúng popup) được dọn trước khi vuốt tiếp — không tính là một lần vuốt.
     */
    private suspend fun scrollUntilSwitchFound(maxScrolls: Int): Boolean {
        var swipes = 0
        var sweeps = 0
        while (isFarming) {
            if (NodeTraverser.findSwitchAccountRow(host.getRootNode(), screenH) != null) return true
            // Không thấy hàng: có thể một popup đang che trang → dọn rồi dò lại (không vuốt, không tính lượt vuốt).
            if (sweeps < 4 && popup.sweep("Cài đặt", maxRounds = 2) > 0) { sweeps++; delay(500); continue }
            if (swipes >= maxScrolls) return false
            swipes++
            setStatus("SCAN: Đang tìm nút chuyển đổi tài khoản...")
            host.swipeSuspend(
                screenW / 2, (screenH * 0.66).toInt(),
                screenW / 2, (screenH * 0.32).toInt(),
                Human.swipeDuration(600),
            )
            if (pollSwitchRow(900L)) return true   // thấy hàng khi danh sách còn trôi → dừng, không vuốt thêm
        }
        return false
    }

    /** Dò hàng "Chuyển đổi tài khoản" mỗi 150ms trong [windowMs]. true = đã thấy (không cần vuốt thêm). */
    private suspend fun pollSwitchRow(windowMs: Long): Boolean {
        var waited = 0L
        while (waited < windowMs) {
            delay(150); waited += 150
            if (NodeTraverser.findSwitchAccountRow(host.getRootNode(), screenH) != null) return true
        }
        return false
    }

    private enum class SwitchTapResult { OPENED, LOGOUT_PROMPT }

    /**
     * v1.11.0 — Từ feed: mở Cài đặt TikTok → cuộn tới hàng "Chuyển đổi tài khoản" → đợi hàng đứng yên → bấm →
     * kiểm tra danh sách tài khoản đã mở. Trả true khi popup đang MỞ (popup VẪN MỞ sau khi trả về).
     *
     * Thay cho 5 đoạn lặp lại ở các nơi (đọc danh sách, chuẩn hoá, chuyển acc…). Chống bấm nhầm "Đăng xuất":
     *  1. hàng được tìm bằng [NodeTraverser.findSwitchAccountRow] — chữ gần như đúng nhãn, không bao giờ là khung lớn;
     *  2. hàng phải nằm vùng giữa màn hình và ĐỨNG YÊN 2 lần đọc liên tiếp (tránh bấm lúc danh sách còn trôi);
     *  3. ưu tiên ACTION_CLICK lên hàng bấm được; bấm theo toạ độ thì chặn nếu điểm chạm trúng chữ "Đăng xuất";
     *  4. nếu lỡ hiện hộp xác nhận đăng xuất → bấm Hủy rồi thử lại (tối đa 3 lần).
     */
    private suspend fun openSwitchList(): Boolean {
        for (attempt in 0 until 3) {
            if (attempt == 0) log("CFG: Mở Settings để đọc danh sách acc...")
            host.openTikTokSettings()
            delay(if (attempt == 0) 2_000L else 2_200L)
            popup.sweep("Mở Cài đặt")   // v1.12.5: popup có thể hiện ngay khi vào Cài đặt

            if (!scrollUntilSwitchFound(maxScrolls = 8)) {
                log("ERR: Không tìm thấy nút chuyển đổi tài khoản")
                return false
            }
            val row = settleSwitchRow()
            if (row == null) {
                log("WARN: Nút chuyển đổi tài khoản chưa đứng yên — thử lại")
                continue
            }
            log("BTN: Đã tìm thấy nút chuyển đổi tài khoản")
            if (!tapSwitchRow(row)) continue
            delay(1_500)
            // v1.12.5: popup có thể hiện ngay sau khi mở danh sách (vd "Tài khoản cần cập nhật"). Bảng chuyển đổi tài khoản
            // có nút X "Đóng" nên chỉ nút đóng "mềm" (Để sau/Not now…) được bấm — xem PopupHandler.scannerDismiss.
            popup.sweep("Mở danh sách tài khoản", maxRounds = 2)

            when (afterSwitchTap()) {
                SwitchTapResult.OPENED -> return true
                SwitchTapResult.LOGOUT_PROMPT -> {
                    cancelLogoutPrompt()
                    log("WARN: Lỡ chạm Đăng xuất — đã huỷ, đang thử lại")
                }
            }
        }
        log("ERR: Không mở được danh sách tài khoản")
        return false
    }

    /**
     * Chờ hàng "Chuyển đổi tài khoản" ĐỨNG YÊN (2 lần đọc cách nhau 300ms trùng vị trí) rồi trả về.
     *
     * v1.12.5 (FIX vuốt thừa): hàng bấm được bằng ACTION_CLICK (không phụ thuộc toạ độ) thì KHÔNG cần căn giữa → không
     * vuốt thêm cú nào — chỉ chờ danh sách ngừng trôi. Trước đây luôn căn vào giữa màn hình bằng tối đa 3 cú vuốt.
     * Chỉ khi phải bấm theo toạ độ và hàng nằm sát mép mới nhích ĐÚNG MỘT lần (vuốt chậm); nhích xong vị trí không đổi
     * (đã hết cuộn được) thì bấm luôn nếu còn trong ngưỡng an toàn.
     */
    private suspend fun settleSwitchRow(): NodeTraverser.SwitchRow? {
        var nudged = false
        repeat(12) {
            if (!isFarming) return null
            val r1 = NodeTraverser.findSwitchAccountRow(host.getRootNode(), screenH)
            if (r1 == null) { delay(400); return@repeat }
            delay(300)
            val r2 = NodeTraverser.findSwitchAccountRow(host.getRootNode(), screenH) ?: return@repeat
            if (kotlin.math.abs(r2.bounds.top - r1.bounds.top) > 6) return@repeat   // còn trôi → chờ tiếp, KHÔNG vuốt

            // Bấm bằng ACTION_CLICK lên hàng đang hiện → xong, không cần cuộn thêm.
            if (r2.tapNode?.isVisibleToUser == true) return r2

            val b = r2.bounds
            val cy = b.centerY()
            val tooLow  = b.bottom > screenH * 0.92 || cy > screenH * 0.78
            val tooHigh = b.top < screenH * 0.12 || cy < screenH * 0.15
            if (!tooLow && !tooHigh) return r2                      // toạ độ ở giữa → bấm theo toạ độ an toàn
            if (!nudged) {
                nudged = true                                       // MỘT lần nhích duy nhất
                val dy = (cy - screenH / 2).coerceIn(-(screenH * 0.4).toInt(), (screenH * 0.4).toInt())
                val y0 = (screenH * 0.65).toInt()
                val y1 = (y0 - dy).coerceIn((screenH * 0.2).toInt(), (screenH * 0.85).toInt())
                host.swipeSuspend(screenW / 2, y0, screenW / 2, y1, 650L)
                delay(900)
                return@repeat
            }
            // Đã nhích mà vẫn sát mép (hết cuộn được): chỉ bấm khi chưa chạm mép màn hình.
            if (b.bottom <= screenH * 0.95 && b.top >= screenH * 0.06) return r2
            delay(500)
        }
        return null
    }

    /** Bấm hàng đã đứng yên. false = bỏ cú bấm (điểm chạm trúng "Đăng xuất" hoặc bấm không được). */
    private suspend fun tapSwitchRow(row: NodeTraverser.SwitchRow): Boolean {
        val tap = row.tapNode
        if (tap != null) return host.clickNode(tap)   // ACTION_CLICK lên hàng — không phụ thuộc toạ độ
        val x = row.bounds.centerX()
        val y = row.bounds.centerY()
        if (NodeTraverser.pointHitsLogout(host.getRootNode(), x, y)) {
            log("WARN: Điểm chạm trùng nút Đăng xuất — huỷ cú bấm, căn lại hàng")
            return false
        }
        return host.clickSuspend(x, y)
    }

    private suspend fun afterSwitchTap(): SwitchTapResult {
        val root = host.getRootNode()
        val accounts = NodeTraverser.parseAccountListWithNodes(root, screenW, screenH)
        return if (accounts.isEmpty() && NodeTraverser.detectLogoutPrompt(root)) SwitchTapResult.LOGOUT_PROMPT
        else SwitchTapResult.OPENED
    }

    /** Đóng hộp xác nhận đăng xuất bằng nút Hủy (không bao giờ bấm nút Đăng xuất). */
    private suspend fun cancelLogoutPrompt() {
        val cancel = NodeTraverser.findLogoutCancel(host.getRootNode())
        if (cancel != null) host.clickNode(cancel.node) else host.pressBack()
        delay(800)
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
            activeAcc = target
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
            val ok = clickInPopup(target, currentAcc) && relaunchAndVerify(target)
            if (ok) activeAcc = target
            ok
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
        if (!openSwitchList()) {
            log("ERR: Không mở được danh sách tài khoản khi chuyển @$target")
            return false
        }

        val ok = clickInPopup(target, activeAcc) && relaunchAndVerify(target)
        if (ok) activeAcc = target
        return ok
    }

    /**
     * Click vào username trong switch popup.
     * Retry 3 lần × delay 1.5s nếu chưa thấy node (popup chưa render xong).
     *
     * v1.11.0: không còn `drop(1)` mù quáng. Trước đây luôn bỏ dòng đầu vì tin rằng đó là acc đang đăng nhập; khi danh sách
     * chỉ đọc được 1 dòng (acc đang dùng hiện bằng tên hiển thị) thì chính acc đích bị bỏ → "Không tìm thấy @… trong
     * switch popup". Giờ chọn dòng theo acc đang dùng ([currentAcc]) — xem [pickSwitchEntry].
     */
    private suspend fun clickInPopup(target: String, currentAcc: String? = activeAcc): Boolean {
        // Đã biết chắc acc đang dùng chính là đích → không cần chờ/thử lại 3 lần, chỉ đóng popup.
        if (currentAcc != null && currentAcc.trim().removePrefix("@").equals(target.trim().removePrefix("@"), ignoreCase = true)) {
            log("OK: @$target thực ra đã là acc đang login — không click, chỉ đóng popup")
            host.pressBack(); delay(500)
            return true
        }
        val entry = retry(times = 3, delayMs = 1_500, tag = "clickPopup($target)") { attempt ->
            if (attempt > 0) popup.handleIfPresent()   // dọn popup lạ trước khi thử lại
            val all = NodeTraverser.parseAccountListWithNodes(host.getRootNode(), screenW, screenH)
            pickSwitchEntry(all.map { it.displayText }, target, currentAcc)?.let { all[it] }
        }

        if (entry != null) {
            host.clickNode(entry.node)
            delay((config.delayAfterSwitchClick * 1_000).toLong())
            // [v1.3.1] Popup "Tài khoản cần cập nhật" có thể đè lên NGAY sau click
            // switch — nếu không dọn ở đây, code coi như switch xong (return true)
            // trong khi thực ra dialog vẫn đang chặn, dẫn tới verify sai vòng sau.
            popup.handleIfPresent()
            return true
        }

        // Không có dòng để bấm — kiểm tra: target có THỰC RA đã là acc đang login không? Nếu đúng thì không phải lỗi.
        val wantNorm = target.trim().removePrefix("@")
        val curNorm  = currentAcc?.trim()?.removePrefix("@")
        val freshFirst = NodeTraverser.parseAccountListWithNodes(host.getRootNode(), screenW, screenH).firstOrNull()
        val isAlreadyCurrent = (curNorm != null && curNorm.equals(wantNorm, ignoreCase = true)) ||
            (curNorm == null && freshFirst != null &&
                freshFirst.displayText.trim().removePrefix("@").equals(wantNorm, ignoreCase = true))
        if (isAlreadyCurrent) {
            log("OK: @$target thực ra đã là acc đang login — không click, chỉ đóng popup")
            host.pressBack(); delay(500)
            return true
        }

        log("ERR: Không tìm thấy @$target trong switch popup")
        host.pressBack()
        delay(500)
        return false
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

        // v1.4.0 — Bộ điều tiết tỉ lệ hành động (xem FarmBehavior.kt): tỉ lệ THỰC TẾ
        // bám sát cấu hình, phương sai thấp, không có chuỗi hành động liên tiếp quá dài.
        // Mỗi acc/phiên một bộ riêng, mỗi loại hành động một gate.
        val likeGate        = RateGate()
        val followGate      = RateGate()
        val commentViewGate = RateGate()

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
        OverlayFarmMonitor.setCounters(likes = 0, follows = 0)   // v1.12.5: popup nổi hiện số tim / follow của acc đang nuôi
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
            // v1.4.0: kế hoạch xem có nhịp theo tiến độ phiên + mức quan tâm (interest)
            // dùng để gắn quyết định like/follow với thời gian xem (xem video lâu → dễ thích hơn).
            val sessionProgress = 1.0 - sessionSecsLeftNow().toDouble() /
                (sessionTotalMs / 1_000.0).coerceAtLeast(1.0)
            val watchPlan = FarmBehavior.watchPlan(
                config.videoWatchTimeMin, config.videoWatchTimeMax, sessionProgress,
            )
            val interestW = FarmBehavior.interestWeight(watchPlan.interest)
            val watchMs   = watchPlan.ms
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
                OverlayFarmMonitor.startCountdown(if (videos == 0) "Chuẩn bị" else "Xem video", watchSecs * 1_000L)
                for (w in watchSecs downTo 1L) {
                    // v1.10.0: chữ hành động cố định (đếm ngược do thanh trên popup đảm nhiệm) → update() bỏ qua
                    // các lần gọi thừa thay vì đăng lên luồng chính mỗi giây.
                    OverlayFarmMonitor.update(
                        accountIndex    = idx + 1,
                        accountTotal    = total,
                        accountId       = account,
                        sessionSecsLeft = sessionSecsLeftNow(),
                        totalSecsLeft   = totalSecsLeftFn(),
                        action          = if (videos == 0) "Chuẩn bị..." else "Xem video",
                    )
                    delay(1_000L)

                    // Kiểm tra drift mỗi 5 giây (không phải mỗi giây — tránh IPC quá nhiều)
                    if (w % FEED_CHECK_INTERVAL_SECS == 0L) {
                        // v1.3.1: dùng safeScreenRead (fetch + duyệt cây trong 1 khối
                        // IO có timeout riêng 4s) thay vì gọi thẳng — nếu bị treo ở
                        // đúng bước này, ta chủ động coi là "rời feed" và thoát sớm
                        // thay vì để cả vòng safeStep(watch_video) bên ngoài kẹt cứng
                        // (đây chính là nguyên nhân log đứng ở "Xem video (Ns)").
                        val onFeed = safeScreenRead(host, timeoutMs = 4_000L, tag = "watch_drift", logFn = ::log) { r ->
                            NodeTraverser.isOnFeedTab(r)
                        }
                        if (onFeed != true) {
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

            // [v1.2.0] Content-aware: khớp chú thích/hashtag. v1.11.0: đọc MỘT lần qua VideoScanner (theo vị trí trên
            // màn hình, không phân biệt hoa/thường/dấu) và kèm cổng số tim tối thiểu.
            val verdict = if (isLiveContent) LikeVerdict.NONE else judgeVideoForLike(
                freshRoot, config.minLikesToLike,
                config.likeByHashtag, config.hashtagKeywords, config.likeByCaption, config.captionKeywords,
            )
            val contentMatch = !isLiveContent && !isAdContent && verdict.contentMatch
            val countOk = verdict.gate == LikeCountGate.PASS
            if (!countOk && !isLiveContent) noteLikeGate(verdict, config.minLikesToLike)

            // contentMatch → bypass likeRate; không match → dùng likeRate bình thường.
            // v1.4.0: dùng RateGate (có hiệu chỉnh tỉ lệ thực tế + trọng số interest) và CHỈ
            // tiêu thụ 1 lượt quyết định khi video thực sự có thể like (không phải live/quảng
            // cáo bị chặn, không thiếu số tim) → tỉ lệ thực tế không bị méo bởi các video bị loại.
            val likeable = !isLiveContent && (!isAdContent || config.likeAdsEnabled) && countOk
            val passRateGate = countOk && (contentMatch ||
                if (likeable) likeGate.decide(config.likeRate, interestW)
                else Random.nextFloat() < config.likeRate)
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
            var followedThisVideo = false
            if (!isLiveContent && !isAdContent && followGate.decide(config.followRate, interestW)) {
                followedThisVideo = true
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

            // ── [10] Comment [v2.0, content-aware + rate-limit từ v1.2.9] ──
            // v1.2.9: bỏ qua live/ad (giống Like/Follow) + giới hạn số lần/giờ
            // theo khuyến nghị tài liệu accessibility-comment-like-logic.md —
            // comment liên tục là tín hiệu bot rõ nhất, dễ bị flag "spam behavior".
            // v1.4.0: không vừa follow vừa comment trên cùng 1 video (2 hành động "nặng").
            if (!isLiveContent && !isAdContent && !followedThisVideo
                && config.commentRate > 0f
                && config.commentTexts.isNotEmpty()
                && Random.nextFloat() < config.commentRate
            ) {
                if (canCommentNow()) {
                    OverlayFarmMonitor.update(idx + 1, total, account,
                        sessionSecsRemain, totalSecsRemain, "CMT: Bình luận")
                    // v1.2.3 [FIX]: timeout 25s — panel comment đôi khi không mở/đóng đúng.
                    val commentOk = safeStep("comment_action", timeoutMs = 25_000L) {
                        doComment(config.commentTexts)
                    }
                    if (commentOk == true) {
                        comments++
                        recordCommentSent()
                    }
                    if (commentOk == null && !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                        log("WARN: Comment timeout/lỗi — recoverToFeed()")
                        recoverToFeed()
                    }
                } else {
                    log("CMT SKIP: Đã đạt giới hạn ${config.maxCommentsPerHour} comment/giờ")
                }
            } else if (isLiveContent || isAdContent) {
                // im lặng bỏ qua — không log để tránh spam log với live/ad liên tục
            }

            // ── [10a] Xem bình luận (thụ động, v1.2.9) ────────────────────
            // Khác Comment ở trên: KHÔNG gõ/gửi gì — chỉ mở panel, cuộn xem vài
            // bình luận rồi đóng lại. Độc lập với commentRate — có thể xảy ra
            // dù không post comment nào trong lượt này.
            if (!isLiveContent && !isAdContent && commentViewGate.decide(config.commentViewRate)) {
                OverlayFarmMonitor.update(idx + 1, total, account,
                    sessionSecsRemain, totalSecsRemain, "CMT: Xem bình luận")
                val viewOk = safeStep("comment_view_action", timeoutMs = 20_000L) {
                    doViewComments()
                }
                if (viewOk == null && !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                    log("WARN: Xem bình luận timeout/lỗi — recoverToFeed()")
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
            OverlayFarmMonitor.setCounters(likes = likes, follows = follows)
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
    /** v1.11.0 — Kết luận "có nên tim video này không" theo số tim + nội dung. */
    private class LikeVerdict(val gate: LikeCountGate, val contentMatch: Boolean, val likes: Long?) {
        companion object { val NONE = LikeVerdict(LikeCountGate.PASS, false, null) }
    }

    /** Số video liên tiếp không đọc được số tim — để cảnh báo MỘT lần thay vì im lặng không tim gì. */
    private var likeUnknownStreak = 0

    /**
     * Đọc video đang hiện (chỉ khi cần: có ngưỡng số tim hoặc từ khoá) rồi quyết định.
     * Không cần gì → trả `NONE` ngay, không duyệt cây.
     */
    private fun judgeVideoForLike(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        minLikes: Int,
        byHashtag: Boolean, hashtags: List<String>,
        byCaption: Boolean, captions: List<String>,
    ): LikeVerdict {
        val tagOn = byHashtag && hashtags.isNotEmpty()
        val capOn = byCaption && captions.isNotEmpty()
        if (minLikes <= 0 && !tagOn && !capOn) return LikeVerdict.NONE
        val info = NodeTraverser.readVideoInfo(root, screenW, screenH)
        if (info.likes != null) likeUnknownStreak = 0
        val match = (capOn && matcherFor(captions).matchesCaption(info.caption)) ||
            (tagOn && matcherFor(hashtags).matchesHashtags(info.hashtags))
        return LikeVerdict(likeCountGate(minLikes, info.likes), match, info.likes)
    }

    /** Ghi lại vì sao KHÔNG tim video này (dòng chi tiết, ẩn ở nhật ký thường) + cảnh báo nếu liên tục không đọc được số tim. */
    private fun noteLikeGate(v: LikeVerdict, minLikes: Int) {
        if (v.gate == LikeCountGate.UNKNOWN) {
            log("FEED: Không đọc được số tim — bỏ qua tim video này")
            likeUnknownStreak++
            if (likeUnknownStreak == 3) {
                log("WARN: Không đọc được số tim của 3 video liên tiếp — kiểm tra lại ngưỡng số tim tối thiểu")
            }
        } else {
            likeUnknownStreak = 0
            log("FEED: Video ${CountParser.format(v.likes ?: 0L)} tim (cần ≥ ${CountParser.format(minLikes.toLong())}) — không tim")
        }
    }

    private suspend fun doLike() {
        isActionLocked = true
        try {
            val cx = screenW / 2 + Human.jitter(25)
            val cy = (screenH * 0.55).toInt() + Human.jitter(20)
            host.clickSuspend(cx, cy)
            Human.doubleTapGap()
            host.clickSuspend(cx + Human.jitter(8), cy + Human.jitter(8))
            Human.likeAnimDelay()
            log("TIM: Tim video")   // v1.2.7: hiển thị trong popup
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

        // Bước 2–5: tìm nút (chờ hồ sơ tải) → bấm → xác nhận (v1.9.0: dùng chung với task mode)
        val result = performProfileFollow("FOLLOW", 4_000L)

        when (result) {
            FollowResult.NO_BUTTON, FollowResult.BLOCKED,
            FollowResult.ALREADY_FOLLOWING, FollowResult.CLICK_FAILED -> {
                log("FOLLOW: Không bấm follow (${result.name}) — back về feed")
                host.pressBack()
                Human.delay(800, 1_200)
                return result
            }
            else -> Unit
        }

        waitWithBar("Sau follow", (config.delayAfterFollow * 1_000).toLong())

        // Bước 6: Back về feed
        log("OK: Về feed")
        host.pressBack()
        Human.delay(900, 1_400)

        // Đảm bảo về feed — thử thêm nếu cần
        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
            host.pressBack()
            Human.delay(700, 1_000)
        }

        return result
    }

    // ── v1.9.0: dò + bấm nút Follow trên hồ sơ (dùng chung nuôi acc & nhiệm vụ) ─────────────

    /** Số lần đã dump cây hồ sơ khi không thấy nút Follow trong phiên này (giới hạn để khỏi spam log). */
    private var followDebugDumps = 0

    private suspend fun scanProfileOnce(): NodeTraverser.ProfileScan? =
        safeScreenRead(host, 4_000L, "follow_scan", ::log) { NodeTraverser.scanProfile(it) }

    /**
     * Chờ hồ sơ tải xong và nút theo dõi xuất hiện (quét mỗi ~0,7s tới [maxWaitMs]).
     * Trước v1.9.0 chỉ đọc cây ĐÚNG MỘT LẦN → hồ sơ tải chậm là "không tìm thấy nút Follow".
     * Thoát sớm khi: thấy nút (bất kỳ trạng thái), hồ sơ bị khoá/riêng tư, hoặc dừng farm.
     */
    private suspend fun awaitProfileScan(prefix: String, maxWaitMs: Long): NodeTraverser.ProfileScan {
        val t0 = System.currentTimeMillis()
        var last = NodeTraverser.ProfileScan(false, NodeTraverser.FollowProbe.NOT_FOUND)
        var tries = 0
        while (true) {
            awaitResumed()
            tries++
            scanProfileOnce()?.let { last = it }
            if (last.blocked || last.probe.state != NodeTraverser.FollowState.NOT_FOUND) break
            if (!isFarming || System.currentTimeMillis() - t0 >= maxWaitMs) break
            if (tries == 1) log("$prefix: Chưa thấy nút Follow — chờ hồ sơ tải...")
            delay(700)
        }
        if (tries > 1 && last.probe.state != NodeTraverser.FollowState.NOT_FOUND) {
            log("$prefix: Nút Follow xuất hiện sau $tries lần quét")
        }
        return last
    }

    /** Sau khi bấm: chờ tới [windowMs] cho nút đổi sang "Đang follow"/"Bạn bè"/"Đã yêu cầu". */
    private suspend fun awaitFollowDone(windowMs: Long): NodeTraverser.FollowProbe {
        val t0 = System.currentTimeMillis()
        var last = NodeTraverser.FollowProbe.NOT_FOUND
        while (true) {
            delay(600)
            scanProfileOnce()?.let { last = it.probe }
            if (last.isDone || System.currentTimeMillis() - t0 >= windowMs) return last
        }
    }

    /** Khi không thấy nút: ghi nhãn + toạ độ các node nửa trên màn hình để chẩn đoán (tối đa 3 lần/phiên). */
    private suspend fun dumpFollowDebug(prefix: String) {
        if (followDebugDumps >= 3) return
        followDebugDumps++
        val lines = safeScreenRead(host, 4_000L, "follow_dump") { NodeTraverser.dumpProfileNodes(it) } ?: return
        lines.joinToString(" | ").chunked(170).take(4).forEachIndexed { i, part ->
            log("DEV: $prefix cây hồ sơ ${i + 1}: $part")
        }
    }

    /**
     * Quy trình follow DUY NHẤT trên trang hồ sơ đang mở:
     * chờ tải → quét (khoá? nút? trạng thái?) → bấm → chờ xác nhận → nếu ACTION_CLICK không ăn
     * thì chạm bằng cử chỉ thật đúng 1 lần (chỉ khi nút VẪN là "Follow" sau 3s — tránh bấm kép thành bỏ follow).
     */
    private suspend fun performProfileFollow(prefix: String, waitMs: Long): FollowResult {
        val scan = awaitProfileScan(prefix, waitMs)
        if (scan.blocked) {
            log("$prefix: Profile bị khoá/riêng tư — không follow được")
            return FollowResult.BLOCKED
        }
        val probe = scan.probe
        when (probe.state) {
            NodeTraverser.FollowState.NOT_FOUND -> {
                log("WARN: $prefix — không tìm thấy nút Follow sau ${waitMs / 1_000}s")
                dumpFollowDebug(prefix)
                return FollowResult.NO_BUTTON
            }
            NodeTraverser.FollowState.FOLLOWING,
            NodeTraverser.FollowState.REQUESTED,
            NodeTraverser.FollowState.FRIENDS -> {
                log("$prefix: Đã theo dõi từ trước (\"${probe.label}\")")
                return FollowResult.ALREADY_FOLLOWING
            }
            NodeTraverser.FollowState.CAN_FOLLOW -> Unit
        }
        val btn = probe.button ?: return FollowResult.NO_BUTTON
        log("$prefix: Thấy nút \"${probe.label.ifEmpty { "(không nhãn)" }}\" [${probe.how}] tại (${btn.centerX},${btn.centerY}) — bấm")

        Human.microPause()
        host.clickNode(btn.node)
        var after = awaitFollowDone(3_200L)

        if (!after.isDone && after.state == NodeTraverser.FollowState.CAN_FOLLOW) {
            log("$prefix: Nút chưa đổi trạng thái — chạm bằng cử chỉ")
            host.clickSuspend(btn.centerX + Human.jitter(6), btn.centerY + Human.jitter(6))
            after = awaitFollowDone(3_200L)
        }

        return when {
            after.isDone -> {
                log("$prefix: Follow thành công ✓ (\"${after.label}\")")
                FollowResult.SUCCESS
            }
            after.state == NodeTraverser.FollowState.CAN_FOLLOW -> {
                log("WARN: $prefix — bấm nhưng nút vẫn là \"Follow\"")
                FollowResult.CLICK_FAILED
            }
            else -> {
                log("$prefix: Không đọc được trạng thái sau khi bấm — tạm tính thành công")
                FollowResult.UNCONFIRMED
            }
        }
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
        // v1.9.0: chờ tối đa 8s cho hồ sơ tải (link mở từ ngoài vào thường chậm hơn feed).
        val result = performProfileFollow("TASK-FOLLOW", 8_000L)
        if (result == FollowResult.SUCCESS || result == FollowResult.UNCONFIRMED) {
            waitWithBar("Sau follow", (config.delayAfterFollow * 1_000).toLong())
        }
        return result
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
        // v1.2.9: lọc dòng "Experiment" (magic word mở khoá setting ẩn) — không
        // bao giờ được dùng làm nội dung comment thật, dù nó vẫn nằm trong list
        // hiển thị trên UI để người dùng biết tính năng đang mở.
        val realTexts = texts.filterNot { it.trim().equals("experiment", ignoreCase = true) }
        if (realTexts.isEmpty()) return false

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
     * v1.2.9: Xem bình luận thụ động — mở panel comment lên XEM (không gõ/gửi gì),
     * cuộn xuống xem vài bình luận rồi đóng lại. Mô phỏng hành vi tò mò tự nhiên,
     * khác hẳn doComment() (gõ + gửi text thật).
     *
     * Flow:
     *   1. Tìm nút comment → click mở panel (dùng chung finder với doComment)
     *   2. Chờ panel load xong
     *   3. Cuộn ngẫu nhiên [commentViewScrollMin, commentViewScrollMax] lần
     *   4. Đóng panel (back)
     *
     * Trả true nếu đã mở panel và xem được (kể cả khi số bình luận ít, không đủ
     * để cuộn hết số lần dự kiến — không phải điều kiện thất bại).
     */
    private suspend fun doViewComments(): Boolean {
        val root = host.getRootNode()

        val commentBtn = NodeTraverser.findByResourceId(root, "btn_comment")
            ?: NodeTraverser.findByResourceId(root, "comment_btn")
            ?: NodeTraverser.findByResourceId(root, "comment_layout")
            ?: NodeTraverser.findByText(root, "comment",   ignoreCase = true)
            ?: NodeTraverser.findByText(root, "bình luận", ignoreCase = true)
            ?: return false

        log("CMT: Mở xem bình luận...")
        host.clickNode(commentBtn.node)
        Human.delay(1_200, 1_800)

        val scrollTimes = Random.nextInt(
            config.commentViewScrollMin,
            config.commentViewScrollMax + 1,
        ).coerceAtLeast(1)

        repeat(scrollTimes) { _ ->
            // Panel comment TikTok chiếm nửa dưới màn hình — cuộn trong vùng đó
            // để tránh vô tình vuốt trúng video nền phía sau panel.
            host.swipeSuspend(
                screenW / 2, (screenH * 0.75).toInt(),
                screenW / 2, (screenH * 0.45).toInt(),
                Human.swipeDuration(400),
            )
            Human.delay(900, 1_600)
        }

        log("CMT: Đã xem $scrollTimes lượt bình luận")

        // Đóng panel — back về feed
        host.pressBack()
        delay(600)
        if (!NodeTraverser.isOnFeedTab(host.getRootNode())) {
            host.pressBack()
            delay(600)
        }

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
        OverlayFarmMonitor.startCountdown("Hộp thư", viewSecs * 1_000L)
        for (s in viewSecs downTo 1L) {
            OverlayFarmMonitor.update(
                accountIndex    = idx,
                accountTotal    = total,
                accountId       = account,
                sessionSecsLeft = secsFn(),
                totalSecsLeft   = totalFn(),
                action          = "Hộp thư",
            )
            delay(1_000L)
        }
        OverlayFarmMonitor.stopCountdown()

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
            // v1.3.0: đếm số lần PHÁT HIỆN LIÊN TIẾP mà cờ của lần trước vẫn CHƯA
            // được farmOneAccount() tiêu thụ (reset) — dấu hiệu main loop đã treo
            // hẳn (kẹt trong 1 lệnh suspend nào đó, không chạy tới bước [3b] kiểm
            // tra cờ), KHÁC với "chỉ đơn giản chưa có video mới".
            var consecutiveUnconsumed = 0

            while (isActive && isFarming) {
                delay(30_000L)
                // Bỏ qua khi đã dừng, đang pause, đang nghỉ giữa acc, đang normalize, hoặc
                // đang thực hiện action — các trạng thái này không có video mới là bình thường.
                if (!isFarming || isPaused || isResting || isNormalizing || isActionLocked) continue
                val stuckSecs = (System.currentTimeMillis() - watchdogLastTick) / 1_000
                if (stuckSecs > config.watchdogTimeoutSecs) {
                    if (watchdogStuckFlag) {
                        // Cờ lần trước CHƯA bị farmOneAccount() reset → nó không chạy tới
                        // được bước kiểm tra → rất có thể đã treo hẳn, không chỉ "đứng hình".
                        // Trước v1.3.0: watchdog chỉ set cờ rồi TỰ reset timer, lặp vô hạn mà
                        // không leo thang gì thêm → đồng hồ đếm ngược trên overlay vẫn chạy
                        // (tính theo wall-clock) trong khi automation đã đứng im thật sự.
                        consecutiveUnconsumed++
                        log("WDG: ⚠ Main loop chưa xử lý cảnh báo watchdog lần trước " +
                            "(${consecutiveUnconsumed} lần liên tiếp) — nghi ngờ đã treo hẳn")
                        if (consecutiveUnconsumed >= 2) {
                            log("WDG: 🛑 Main loop không phản hồi ~${stuckSecs * 2}s — " +
                                "watchdog tự can thiệp trực tiếp (kill + relaunch TikTok)")
                            notifyDiscord("⚠️ **AT Pro** — Phát hiện nghi ngờ tool bị treo " +
                                "(~${stuckSecs * 2}s không phản hồi), đang tự khôi phục...")
                            try {
                                // v1.3.1: dùng chung relaunchTikTokWithRetry() (có retry+backoff,
                                // tự bắn Discord khi relaunch xong/thất bại) thay vì kill+launch
                                // 1 lần duy nhất như trước — nếu launch fail đúng lần đó thì trước
                                // đây tool vẫn kẹt tới tận vòng watchdog kế tiếp (~30s sau).
                                val recovered = relaunchTikTokWithRetry("WDG")
                                log(
                                    if (recovered) "WDG: ✓ Đã tự relaunch TikTok và về được feed"
                                    else "WDG: ⚠ Đã relaunch nhưng chưa xác nhận về feed — nếu main " +
                                        "loop vẫn không phản hồi sau việc này, cần dừng tool thủ công và báo lỗi"
                                )
                            } catch (e: Exception) {
                                log("WDG: Lỗi khi tự can thiệp: ${e.message}")
                            }
                            consecutiveUnconsumed = 0
                        }
                    } else {
                        consecutiveUnconsumed = 0
                        log("WDG: Không có video mới trong ${stuckSecs}s — kích hoạt smart recovery")
                    }
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
                // v1.12.5: feed vừa tải xong là lúc popup hay hiện (có khi hiện trễ ~1s). Dọn bằng quét AN TOÀN
                // (chỉ bấm nút đóng trong whitelist — không Back, không đoán theo từ khoá) nên không lặp lại lỗi trên.
                if (popup.sweep("Feed vừa tải", lateRender = true) > 0 &&
                    !NodeTraverser.isOnFeedTab(host.getRootNode())) {
                    log("WARN: Sau khi dọn popup chưa thấy feed — chờ thêm")
                    return@repeat
                }
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
        // v1.3.1: safeScreenRead — nếu getRootNode()/duyệt cây bị treo, timeout
        // trả về null (`!= true`) → coi như "không ở feed", để logic recovery
        // xử lý tiếp thay vì cả hàm này bị kẹt cứng luôn.
        if (checkOnFeed()) return false
        delay(1_500)
        if (checkOnFeed()) return false
        delay(1_500)
        return !checkOnFeed()
    }

    /** v1.3.1: helper dùng chung — isOnFeedTab() với timeout thực sự huỷ được. */
    private suspend fun checkOnFeed(): Boolean =
        safeScreenRead(host, timeoutMs = 4_000L, tag = "checkOnFeed", logFn = ::log) { NodeTraverser.isOnFeedTab(it) } == true

    /**
     * v1.3.1 — TikTok còn ở foreground không?
     *
     * killTikTok() luôn bấm HOME trước khi kill (bắt buộc — HOME_ACTION rồi mới
     * killBackgroundProcesses() mới có tác dụng), nên MỌI lần recovery đều có
     * thể tạm thời đi qua màn hình Home. Nếu ta đã thực sự rời khỏi TikTok
     * (Home, hoặc bị văng sang app khác), pressBack/Home-tab (Tier 1/2 trong
     * recoverToFeed/recoverFromStuck) hoàn toàn vô nghĩa — chỉ tốn thời gian
     * (mỗi lần pressBack chờ >=1s) trước khi tới được Tier 3 relaunch, khiến
     * tool trông như treo lâu ở màn hình Home dù cuối cùng vẫn tự hồi phục.
     * Trả false khi không xác định được (timeout/null) → coi như "không chắc
     * đang ở TikTok", ưu tiên nhảy thẳng Tier 3 cho an toàn.
     */
    private suspend fun isForegroundTikTok(): Boolean =
        safeScreenRead(host, timeoutMs = 3_000L, tag = "isForegroundTikTok", logFn = ::log) { root ->
            val pkg = root?.packageName?.toString()
            pkg != null && AppConstants.tiktokPackages.any { it == pkg }
        } == true

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
        // v1.3.1: nếu đã văng khỏi TikTok hẳn (Home/app khác) thì pressBack vô nghĩa
        // — nhảy thẳng Tier 3 (relaunch) thay vì tốn ~maxBackAttempts giây vô ích.
        if (!isForegroundTikTok()) {
            log("WDG-RECOVER: Không còn ở TikTok (Home/app khác) — relaunch ngay, bỏ qua back")
            return relaunchTikTokWithRetry("WDG-RECOVER")
        }

        log("WDG-RECOVER: Back ×${config.maxBackAttempts} để thoát màn hình chặn")
        repeat(config.maxBackAttempts) { i ->
            host.pressBack()
            delay(1_000)
            if (checkOnFeed()) {
                log("WDG-RECOVER: Về feed sau ${i + 1} lần back ✓")
                return true
            }
        }

        // Kiểm tra popup chặn
        val popupResult = popup.handleIfPresent()
        if (popupResult.handled) {
            log("WDG-RECOVER: Đã xử lý popup chặn")
            delay(1_000)
            if (checkOnFeed()) {
                log("WDG-RECOVER: Về feed sau xử lý popup ✓")
                return true
            }
        }

        // Vẫn chưa về feed → reset hoàn toàn
        log("WDG-RECOVER: Vẫn chưa về feed — kill + relaunch TikTok")
        return relaunchTikTokWithRetry("WDG-RECOVER")
    }

    /**
     * v1.3.1 — Kill + relaunch TikTok với retry có backoff (tái dùng retry()
     * helper sẵn có ở phaseLaunch()). Trước đây các điểm relaunch trong
     * recovery chỉ gọi launchTikTok() đúng 1 lần, không retry — nếu launch
     * fail/chậm ngay lần đó, tool kẹt ở Home tới tận watchdog vòng sau.
     *
     * Bổ sung: bắn Discord khi phải relaunch (và khi relaunch thất bại hẳn) —
     * dùng chung cho MỌI điểm gọi Tier 3 (recoverFromStuck, recoverToFeed,
     * watchdog) để theo dõi từ xa xem tool có đang phải tự cứu liên tục hay
     * không, không cần cầm máy lên xem log.
     */
    private suspend fun relaunchTikTokWithRetry(tag: String): Boolean {
        notifyDiscord("🔄 **AT Pro** — [$tag] Đang kill + relaunch TikTok để tự khôi phục...")
        host.killTikTok()
        delay(2_000)
        val launched = retry(times = 3, delayMs = 2_000) {
            if (host.launchTikTok()) true else null
        }
        if (launched == null) {
            log("$tag: launchTikTok() thất bại sau 3 lần thử")
            notifyDiscord("🛑 **AT Pro** — [$tag] launchTikTok() thất bại sau 3 lần thử — cần kiểm tra thủ công")
            return false
        }
        val ok = waitFeedLoad()
        if (!ok) {
            notifyDiscord("⚠️ **AT Pro** — [$tag] Relaunch xong nhưng chưa xác nhận về được feed")
        }
        return ok
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
        // v1.3.1: Tier 0c — đã văng khỏi TikTok hẳn (Home/app khác) thì các Tier
        // 0a/0b/1/2 bên dưới (đều thao tác TRONG TikTok) vô nghĩa — nhảy thẳng
        // Tier 3. Đây chính là ca "kẹt ở màn hình Home, không log/không chạy
        // thời gian nuôi" — trước đây phải đi hết 4 tier vô ích mới tới relaunch.
        if (!isForegroundTikTok()) {
            log("RECOVER: Không còn ở TikTok (Home/app khác) — relaunch ngay")
            return relaunchTikTokWithRetry("RECOVER")
        }

        // Tier 0a: [v1.1.8] Daily screen time limit — nhập passcode + click "Quay lại TikTok"
        // pressBack không hoạt động; phải nhập passcode và click button.
        if (NodeTraverser.detectDailyLimitScreen(host.getRootNode())) {
            log("RECOVER: Daily limit screen — nhập mật mã và tiếp tục")
            popup.handleIfPresent()
            delay(2_000)
            if (checkOnFeed()) return true
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
            if (checkOnFeed()) return true
        }

        // Tier 1: pressBack
        repeat(config.maxBackAttempts) {
            host.pressBack()
            delay(1_200)
            if (checkOnFeed()) return true
        }

        // Tier 2: Home tab
        val homeTab = NodeTraverser.findHomeTab(host.getRootNode())
        if (homeTab != null) {
            host.clickNode(homeTab.node)
            delay(2_000)
            if (checkOnFeed()) return true
        }

        // Tier 3: Kill + relaunch
        log("WARN: recoverToFeed: tier 3 — kill + relaunch TikTok")
        return relaunchTikTokWithRetry("RECOVER")
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
        // v1.3.0: Popup có thể đang che nút Profile tab khiến findProfileTab()
        // không thấy gì — xử lý popup ưu tiên TRƯỚC khi kết luận thất bại thật sự.
        var profileTab = NodeTraverser.findProfileTab(host.getRootNode(), screenH)
        if (profileTab == null) {
            val cleared = popup.handleIfPresent()
            if (cleared.handled) {
                log("POPUP: Đã dọn popup (${cleared.type}) đang che Profile tab")
                delay(700)
                profileTab = NodeTraverser.findProfileTab(host.getRootNode(), screenH)
            }
        }
        if (profileTab == null) {
            log("WARN: detectCurrentAccount: không tìm thấy profile tab (đã thử dọn popup)")
            activeAcc = null
            return null
        }
        host.clickNode(profileTab.node)
        delay(2_000)

        var id = NodeTraverser.getCurrentAccountId(host.getRootNode())
        if (id == null) {
            // v1.3.0: popup có thể xuất hiện NGAY sau khi vào Hồ sơ (vd thông báo,
            // gợi ý) che mất @username — thử dọn rồi đọc lại 1 lần.
            val cleared = popup.handleIfPresent()
            if (cleared.handled) {
                log("POPUP: Đã dọn popup (${cleared.type}) trong màn Hồ sơ")
                delay(700)
                id = NodeTraverser.getCurrentAccountId(host.getRootNode())
            }
        }
        navigateToFeedTab()
        if (id == null) {
            log("WARN: detectCurrentAccount: không đọc được @username")
        } else {
            log("OK: Đã xác nhận tài khoản hiện tại là: @$id")
        }
        activeAcc = id
        return id
    }

    /** Sau switch: verify @username thực tế khớp với expected. */
    private suspend fun verifyCurrentAccount(expected: String) {
        var profileTab = NodeTraverser.findProfileTab(host.getRootNode(), screenH)
        if (profileTab == null && popup.handleIfPresent().handled) {
            delay(700)
            profileTab = NodeTraverser.findProfileTab(host.getRootNode(), screenH)
        }
        if (profileTab == null) {
            log("WARN: verifyCurrentAccount: không tìm thấy profile tab — fallback save @$expected")
            autoSaveAccounts(listOf(expected)); return
        }
        host.clickNode(profileTab.node)
        delay(2_000)

        var actual = NodeTraverser.getCurrentAccountId(host.getRootNode())
        if (actual == null && popup.handleIfPresent().handled) {
            delay(700)
            actual = NodeTraverser.getCurrentAccountId(host.getRootNode())
        }
        when {
            actual == null ->
                log("WARN: verifyCurrentAccount: không đọc được @username — fallback save @$expected")
            !actual.equals(expected, ignoreCase = true) ->
                log("WARN: Account mismatch: expected=@$expected actual=@$actual")
            else ->
                log("OK: Đã xác nhận tài khoản hiện tại là: @$actual")
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
        // v1.12.5: chỉ "đang chạy" mới cần về Home. stop() rồi finally của farmJob cùng gọi stopInternal → lần sau bỏ qua.
        val wasRunning = isFarming
        _liveFarmStats.update { LiveFarmStats() }
        isFarming = false
        isPaused  = false
        isResting = false
        if (wasRunning) goHomeBeforeStop(reason)
        AtProNotificationManager.clearRunStatus()   // v1.12.0: thông báo "đang chạy" về dòng mặc định
        LanWebSocketServer.broadcast("farmStatus",
            mapOf("status" to "stopped", "reason" to reason))

        // v1.3.0: thông báo Discord cho các trường hợp KHÔNG đi qua notifyFarmCompleted
        // riêng ở trên (TikTok chính đã tự gửi message chi tiết hơn kèm thống kê).
        when (reason) {
            "user_stopped" -> notifyDiscord("⏹️ **AT Pro** — Người dùng đã dừng phiên nuôi acc")
            "facebook_completed" -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên nuôi Facebook")
            "x_completed"         -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên nuôi X")
            "instagram_completed" -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên nuôi Instagram")
            "threads_completed"   -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên nuôi Threads")
            "snapchat_completed"  -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên nuôi Snapchat")
            "task_completed"      -> notifyDiscord("✅ **AT Pro** — Hoàn thành phiên Task mode")
            // "completed" (TikTok chính) đã có message riêng ở trên, không gửi trùng.
        }
    }

    /**
     * v1.12.5 — Về màn hình chính điện thoại TRƯỚC KHI tool tắt, ở MỌI đường dừng: người dùng bấm Dừng (Dashboard / popup /
     * thông báo), xong phiên, hết acc, lỗi không mong đợi, bị huỷ, service bị tắt. Máy không bị bỏ lại trong TikTok,
     * Cài đặt, danh sách tài khoản hay một popup.
     *
     * `performGlobalAction` không suspend nên chạy được cả khi coroutine đã bị huỷ. Nếu hệ thống từ chối lần đầu thì thử
     * lại đúng một lần sau 400ms (scope của service không bị huỷ theo farmJob).
     */
    private fun goHomeBeforeStop(reason: String) {
        val ok = runCatching { host.pressHome() }.getOrDefault(false)
        Log.i(TAG, "goHomeBeforeStop($reason): pressHome=$ok")
        if (!ok) {
            runCatching {
                host.scope.launch {
                    delay(400)
                    runCatching { host.pressHome() }
                }
            }
        }
    }

    // ── v1.9.0: đẩy trạng thái nhiệm vụ lên popup nổi (trước đây updateTask() không được gọi ở đâu cả
    //    → popup luôn hiện "@—", "✓ 0 / 0") ─────────────────────────────────────────────────────────────
    private var taskAccIndex = 0
    private var taskAccTotal = 0

    // v1.12.0 — tổng kết một lượt nhiệm vụ (cho thông báo tổng kết) + cờ phiên nhà cung cấp hết hạn
    private var taskRunStartMs = 0L
    private var taskRunAccounts = 0
    private var taskRunJobs = 0
    private var taskRunLikes = 0
    private var taskRunFollows = 0
    private var taskRunReward = 0.0
    @Volatile private var taskAuthFailed = false
    /** Nhà cung cấp đang chạy — để gỡ trace listener khi kết thúc. */
    private var activeTaskProvider: TaskProvider? = null

    private fun pushTaskOverlay(
        username:   String,
        jobsDone:   Int,
        consecFail: Int,
        likes:      Int,
        follows:    Int,
        reward:     Double,
        action:     String = "",
    ) {
        // v1.12.0: thông báo "đang chạy" hiện tiến độ (tài khoản n/N · job · xu); RunStatusHolder tự chống dồn 3s.
        AtProNotificationManager.setRunStatus(
            title = "Nhiệm vụ TikTok",
            line  = "@$username ($taskAccIndex/$taskAccTotal) · $jobsDone/${config.taskJobsPerAccount} job · +${NotificationText.coin(taskRunReward + reward)} xu",
            done  = taskAccIndex - 1,
            total = taskAccTotal,
        )
        _liveFarmStats.update {
            it.copy(
                account      = username,
                index        = taskAccIndex,
                total        = taskAccTotal,
                likes        = likes,
                follows      = follows,
                jobsDone     = jobsDone,
                jobsTarget   = config.taskJobsPerAccount,
                consecErrors = consecFail,
                reward       = reward,
            )
        }
        OverlayFarmMonitor.updateTask(
            accountId    = username,
            successCount = jobsDone,
            totalCount   = config.taskJobsPerAccount,
            consecErrors = consecFail,
            action       = action,
            maxErrors    = config.taskMaxConsecFailures,
            likes        = likes,
            follows      = follows,
            reward       = reward,
            accountIndex = taskAccIndex,
            accountTotal = taskAccTotal,
        )
    }

    /**
     * v1.10.0 — Chờ [ms] có thanh đếm ngược trên popup. Tôn trọng tạm dừng (thanh dừng theo) và thoát sớm khi dừng farm.
     * Chỉ thức dậy mỗi 500ms (trước đây nhiều chỗ đăng trạng thái lên giao diện MỖI GIÂY).
     */
    private suspend fun waitWithBar(label: String, ms: Long) {
        if (ms <= 0L) return
        OverlayFarmMonitor.startCountdown(label, ms)
        try {
            var left = ms
            while (left > 0L && isFarming) {
                awaitResumed()
                val step = minOf(left, 500L)
                delay(step)
                left -= step
            }
        } finally {
            OverlayFarmMonitor.stopCountdown()
        }
    }

    /** Đã từng thấy thanh điều hướng dưới cùng trong phiên này (máy có lộ nhãn tab) → "không thấy thanh" nghĩa là KHÔNG phải feed. */
    private var navLabelsSeen = false

    /**
     * v1.10.0 — Đang ở FEED THẬT? Xem [NodeTraverser.classifyFeed].
     * Trang không có thanh điều hướng: nếu máy này từng lộ nhãn tab → đó là trang job/hồ sơ (chưa về feed);
     * nếu chưa từng thấy → máy không lộ nhãn tab, dùng kiểm tra cũ (feed + không có nút Quay lại) để khỏi Back nhầm khỏi TikTok.
     */
    private suspend fun isRealFeedNow(): Boolean {
        val kind = safeScreenRead(host, 4_000L, "real_feed", ::log) { root ->
            val k = NodeTraverser.classifyFeed(root)
            if (k == NodeTraverser.FeedKind.NO_NAV) {
                // Dự phòng kiểu cũ — chỉ dùng khi máy chưa từng lộ thanh điều hướng.
                if (!navLabelsSeen) {
                    if (NodeTraverser.isOnFeedTab(root) && !NodeTraverser.hasBackButton(root)) NodeTraverser.FeedKind.REAL_FEED
                    else NodeTraverser.FeedKind.OTHER_PAGE
                } else NodeTraverser.FeedKind.OTHER_PAGE
            } else {
                navLabelsSeen = true
                k
            }
        }
        return kind == NodeTraverser.FeedKind.REAL_FEED
    }

    /**
     * v1.10.0 — Cơ chế BACK VỀ FEED: bấm Back tới khi tới feed thật (tối đa [maxBacks] lần), trước mỗi lần Back
     * kiểm tra TikTok còn ở foreground (Back trên feed sẽ thoát app → relaunch thay vì Back thêm).
     * Hết số lần Back mà vẫn chưa về → [recoverToFeed] (Home tab / mở lại TikTok).
     */
    private suspend fun ensureRealFeed(tag: String, maxBacks: Int = 6): Boolean {
        var backs = 0
        while (isFarming) {
            if (isRealFeedNow()) {
                if (backs > 0) log("$tag: Đã về feed (Back ×$backs)")
                return true
            }
            if (backs >= maxBacks) break
            if (!isForegroundTikTok()) {
                log("$tag: Không còn ở TikTok — mở lại")
                return relaunchTikTokWithRetry(tag)
            }
            // v1.12.5: popup đang che thì Back có thể trúng popup chứ không phải trang → dọn trước, không tính lần Back.
            if (popup.sweep("$tag: Về feed", maxRounds = 2) > 0) continue
            if (backs == 0) log("$tag: Chưa ở feed — Back về feed")
            host.pressBack()
            Human.delay(700, 1_100)
            backs++
        }
        if (!isFarming) return false
        log("WARN: $tag: Back $backs lần vẫn chưa về feed — hồi phục")
        return recoverToFeed()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LanWebSocketServer.broadcast("log", mapOf("message" to msg, "level" to "INFO"))
        host.scope.launch { repo.log(msg) }
        OverlayFarmMonitor.addLog(msg)   // [v1.1.0] hiển thị log lên overlay popup
    }

    /**
     * v1.3.0 — Gửi thông báo trạng thái nuôi acc qua Discord Webhook (nếu đã cấu
     * hình trong Cài đặt). Chạy fire-and-forget trên coroutine riêng — lỗi mạng/
     * webhook sai KHÔNG BAO GIỜ được phép làm gián đoạn luồng farm chính.
     */
    private fun notifyDiscord(message: String) {
        val url = config.discordWebhookUrl
        if (url.isBlank()) return
        host.scope.launch {
            try {
                DiscordNotifier.send(url, message)
            } catch (e: Exception) {
                Log.w(TAG, "notifyDiscord lỗi: ${e.message}")
            }
        }
    }

    private fun notifyDiscord(t: NotifText) = notifyDiscord("**${t.title}**\n${t.body}")

    /** v1.12.0: phiên nhà cung cấp hết hạn → dừng cả lượt (khỏi chuyển acc vô ích) + cảnh báo. */
    private fun notifyTaskAuthExpired(providerName: String) {
        taskAuthFailed = true
        log("ERR: Task — phiên $providerName đã hết hạn, cần đăng nhập lại")
        AtProNotificationManager.notifyF4fSessionExpired()
        notifyDiscord(NotificationText.f4fSessionExpired())
    }

    private fun notifyTaskAbort(reason: String) {
        AtProNotificationManager.notifyTaskAborted(reason)
        notifyDiscord(NotificationText.taskAborted(reason))
    }

    /** Tổng kết cả lượt nhiệm vụ (thông báo hệ thống + Discord). Số dư lấy từ phiên F4F (nhà cung cấp cập nhật sau mỗi job xong). */
    private fun notifyTaskFinished() {
        val minutes = ((System.currentTimeMillis() - taskRunStartMs) / 60_000L).toInt()
        val balance = runCatching {
            (com.atpro.tasks.f4f.F4fAuth.manager(com.atpro.AtProApplication.ctx).state.value
                as? com.atpro.tasks.f4f.F4fAuthState.LoggedIn)?.balance
        }.getOrNull()
        AtProNotificationManager.notifyTaskCompleted(taskRunAccounts, taskRunJobs, taskRunLikes, taskRunFollows, taskRunReward, minutes, balance)
        notifyDiscord(NotificationText.taskCompleted(taskRunAccounts, taskRunJobs, taskRunLikes, taskRunFollows, taskRunReward, minutes, balance))
    }

    @Suppress("UNUSED_PARAMETER")   // hook dành riêng — TikTokAccessibilityService gọi mỗi sự kiện; chữ ký phải giữ nguyên
    fun onEvent(event: AccessibilityEvent) { /* reserved */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Task mode — v1.2.1 (v1.4.0: tách khỏi Golike, đi qua TaskProvider)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * State machine cho task mode.
     *
     * Phase 0: Lấy acc đã liên kết từ TaskProvider
     * Phase 1: Launch TikTok (dùng lại phaseLaunch)
     * Phase 2: Discover & match acc thiết bị với acc của nhà cung cấp
     * Phase 3: Task loop — farm → job → farm → ...
     */
    private suspend fun runTaskStateMachine(
        mode:      FarmMode,
        inputList: List<String>,
    ) {
        // Phase 0: nhà cung cấp + danh sách acc đã liên kết
        val provider = taskProvider ?: run {
            log("WARN: Task — chưa cấu hình nơi làm nhiệm vụ (TaskProvider = null)")
            setStatus("Chưa cấu hình nơi làm nhiệm vụ")
            return
        }
        setStatus("TASK: Lấy danh sách acc từ ${provider.displayName}...")
        // v1.12.0: reset tổng kết + nối trace đổi acc/lấy job của nhà cung cấp vào nhật ký (dòng chi tiết, ẩn mặc định).
        taskRunStartMs = System.currentTimeMillis()
        taskRunAccounts = 0; taskRunJobs = 0; taskRunLikes = 0; taskRunFollows = 0; taskRunReward = 0.0
        taskAuthFailed = false
        AtProNotificationManager.startRun()
        activeTaskProvider = provider
        provider.setTraceListener { line -> log("F4F: $line") }

        val linkedAccounts: List<TaskAccount> = when (val r = provider.listAccounts()) {
            is TaskResult.Success -> r.data
            is TaskResult.Error   -> {
                log("ERR: Task — không lấy được acc từ ${provider.displayName}: ${r.message}")
                setStatus("Lỗi lấy acc nhiệm vụ")
                if (r.isAuthError) notifyTaskAuthExpired(provider.displayName) else notifyTaskAbort("Không lấy được tài khoản từ ${provider.displayName}: ${r.message}")
                return
            }
        }
        if (linkedAccounts.isEmpty()) {
            log("WARN: Task — ${provider.displayName} chưa có acc TikTok nào được liên kết")
            setStatus("Chưa có acc liên kết")
            return
        }

        // Phase 1: Launch
        setStatus(">> Đang mở TikTok...")
        val launchPhase = phaseLaunch()
        if (launchPhase is FarmPhase.Failed) {
            log("ERR: Task — TikTok không mở được: ${launchPhase.reason}")
            return
        }

        // Phase 2: Discover + match
        // v1.8.0 (FIX lỗi không bắt đầu được nhiệm vụ): PHẢI đọc acc đang active TRƯỚC khi mở switch popup — giống
        // phaseDiscover() của chế độ nuôi acc. Trước đây gọi sau openSwitchPopup(): lúc đó đang ở trang Cài đặt TikTok
        // (không có tab Hồ sơ) nên detectCurrentAccount() không tìm thấy tab → dọn nhầm popup chuyển tài khoản
        // → acc hiện tại = "@?" → positionFirstAccount() mất popup và thất bại.
        setStatus("SCAN: Đang đọc tài khoản hiện tại...")
        val currentAcc = detectCurrentAccount()
        // v1.11.0: dòng "Đã xác nhận tài khoản…" của detectCurrentAccount() đã báo acc đang dùng — chỉ cảnh báo khi không đọc được.
        if (currentAcc == null) log("WARN: Chưa xác định được tài khoản đang dùng")

        setStatus("SCAN: Quét tài khoản thiết bị...")
        val (popupOpened, discovered) = openSwitchPopup()
        if (!popupOpened) {
            log("ERR: Task — Không mở được switch popup")
            return
        }

        // v1.12.0: nhiệm vụ không còn dùng lựa chọn của Dashboard (mode/inputList) mà chọn theo cài đặt riêng
        // (Cài đặt → Nhiệm vụ → Tài khoản) → danh sách trên máy luôn là TOÀN BỘ acc đã quét.
        val farmList   = buildFarmList(FarmMode.ALL_LOCAL, emptyList(), discovered)
        if (farmList.isEmpty()) {
            log("WARN: Task — Không có tài khoản nào trên thiết bị")
            host.pressBack(); delay(500)
            host.pressBack(); delay(500)
            return
        }

        // v1.12.0: chọn tài khoản nhận nhiệm vụ = (acc trên máy) ∩ (acc đã liên kết) ∩ (acc được chọn khi "Chọn tay").
        // Luật nằm ở TaskAccountSelector (thuần Kotlin, có test); ở đây chỉ báo lý do từng acc bị bỏ qua cho dễ hiểu.
        val sel = TaskAccountSelector.select(
            deviceAccounts = farmList,
            linked         = linkedAccounts,
            mode           = TaskAccountMode.parse(config.taskAccountMode),
            selected       = config.taskSelectedAccounts,
        )
        val matchedList: List<Pair<String, TaskAccount>> = sel.matched
        if (sel.deviceNotLinked.isNotEmpty())
            log("TASK: Bỏ qua ${sel.deviceNotLinked.size} acc chưa liên kết ${provider.displayName}: ${NotificationText.accountList(sel.deviceNotLinked, 6)}")
        if (sel.notSelected.isNotEmpty())
            log("TASK: Bỏ qua ${sel.notSelected.size} acc không được chọn: ${NotificationText.accountList(sel.notSelected, 6)}")
        if (sel.selectedNotOnDevice.isNotEmpty())
            log("WARN: Task — ${sel.selectedNotOnDevice.size} acc đã chọn nhưng chưa có trên máy: ${NotificationText.accountList(sel.selectedNotOnDevice, 6)}")
        if (sel.selectedNotLinked.isNotEmpty())
            log("WARN: Task — ${sel.selectedNotLinked.size} acc đã chọn nhưng chưa liên kết ${provider.displayName}: ${NotificationText.accountList(sel.selectedNotLinked, 6)}")

        if (matchedList.isEmpty()) {
            val manual = TaskAccountMode.parse(config.taskAccountMode) == TaskAccountMode.SELECTED
            log(
                if (manual) "WARN: Task — Không có acc nào được chọn khớp với máy và ${provider.displayName}"
                else "WARN: Task — Không có acc nào trùng với ${provider.displayName}"
            )
            setStatus("Không có acc nào để làm nhiệm vụ")
            notifyTaskAbort(
                if (manual) "Không có tài khoản đã chọn nào khớp với máy và ${provider.displayName}. Kiểm tra Cài đặt → Nhiệm vụ → Tài khoản."
                else "Không có tài khoản nào trên máy trùng với ${provider.displayName}."
            )
            host.pressBack(); delay(500)
            host.pressBack(); delay(500)
            return
        }

        log("TASK: ${matchedList.size} acc sẽ làm nhiệm vụ: ${matchedList.map { it.first }}")
        taskRunAccounts = matchedList.size
        AtProNotificationManager.notifyTaskStarted(matchedList.map { it.first })
        notifyDiscord(NotificationText.taskStarted(matchedList.map { it.first }))

        // Position acc đầu
        // v1.8.0: nếu vừa chuẩn hoá thì acc active đã đổi → dùng postNormalizeActiveAcc (như chế độ nuôi acc),
        // tránh coi acc cũ là "đang login" rồi bỏ qua bước chuyển (hoặc click lại acc đang login).
        val effectiveCurrentAcc = postNormalizeActiveAcc ?: currentAcc
        postNormalizeActiveAcc  = null
        setStatus("SWITCH: Chuẩn bị acc đầu tiên...")
        val positioned = positionFirstAccount(matchedList.first().first, effectiveCurrentAcc)
        if (!positioned) {
            log("ERR: Task — Không position được acc đầu")
            return
        }

        // Phase 3: Task loop
        val farmedSet = mutableSetOf<String>()
        taskAccTotal = matchedList.size
        matchedList.forEachIndexed { idx, (username, linked) ->
            if (taskAuthFailed) return@forEachIndexed       // v1.12.0: phiên nhà cung cấp hết hạn → khỏi chuyển acc vô ích
            if (!isFarming) return@forEachIndexed
            awaitResumed()
            taskAccIndex = idx + 1
            pushTaskOverlay(username, 0, 0, 0, 0, 0.0)

            log("TASK: [${idx + 1}/${matchedList.size}] @$username")

            if (idx > 0) {
                val switched = switchToAccount(username, farmedSet)
                if (!switched) {
                    log("WARN: Task: Skip @$username — switch thất bại")
                    return@forEachIndexed
                }
            }
            farmedSet.add(username.lowercase())

            taskOneAccount(provider, username, linked)
        }

        log("TASK: Hoàn thành tất cả ${matchedList.size} tài khoản — đóng TikTok")
        if (!taskAuthFailed) notifyTaskFinished()
        delay(800)
        host.killTikTok()
    }

    /**
     * Làm nhiệm vụ cho 1 acc.
     *
     * Loop: [farm đệm] → [lấy job] → [làm job] → [báo cáo] → [nuôi sau job] → lặp lại
     * Thoát khi: đạt đủ taskJobsPerAccount hoặc thất bại liên tiếp >= taskMaxConsecFailures.
     */
    private suspend fun taskOneAccount(
        provider: TaskProvider,
        username: String,
        account:  TaskAccount,
    ) {
        // taskPostJobNurtureSecs() tách riêng ở top-level (cuối class) — pure
        // function, không phụ thuộc host/coroutine — để có thể unit test trực
        // tiếp bằng JUnit (xem TaskNurtureTest.kt).
        val sessionId   = repo.startSession(username)
        var jobsDone    = 0
        var consecFail  = 0
        var totalLikes  = 0
        var totalFollows = 0
        var totalVideos  = 0
        var totalReward  = 0.0

        // Chờ feed ổn định
        if (!waitFeedLoad()) recoverToFeed()
        isRealFeedNow()   // v1.10.0: ghi nhận máy có lộ thanh điều hướng hay không (đang ở feed)

        while (isFarming && jobsDone < config.taskJobsPerAccount &&
               consecFail < config.taskMaxConsecFailures) {
            awaitResumed()
            pushTaskOverlay(username, jobsDone, consecFail, totalLikes, totalFollows, totalReward)

            // ── [A] Farm đệm ──────────────────────────────────────
            if (jobsDone > 0 || consecFail > 0) ensureRealFeed("TASK")
            setStatus("TASK: [@$username] Nuôi acc trước job ${jobsDone + 1}")
            val farmVideos = doSimpleFarm(config.taskFarmBeforeJobSecs)
            totalVideos += farmVideos
            if (!isFarming) break

            // ── [B] Lấy job ───────────────────────────────────────
            // v1.3.3: delay ngẫu nhiên trước khi gọi API — mô phỏng người dùng
            // thật "quyết định" mở tab nhiệm vụ thay vì nhận job ngay tức khắc
            // sau khi vừa lướt xong.
            setStatus("TASK: Chuẩn bị nhận nhiệm vụ...")
            Human.delay(1_200, 3_000)

            setStatus("TASK: Lấy nhiệm vụ từ ${provider.displayName}...")
            // v1.6.0: chỉ hỏi nhà cung cấp loại job đã cấu hình (Follow4Follow lấy riêng từng loại).
            val wantedKinds: Set<TaskJobKind> = when (config.taskJobType) {
                TaskJobType.LIKE   -> setOf(TaskJobKind.LIKE)
                TaskJobType.FOLLOW -> setOf(TaskJobKind.FOLLOW)
                TaskJobType.BOTH   -> setOf(TaskJobKind.LIKE, TaskJobKind.FOLLOW)
            }
            val job: TaskJob? = when (val r = provider.nextJob(account, wantedKinds)) {
                is TaskResult.Success -> r.data
                is TaskResult.Error -> {
                    log("TASK: Lấy job thất bại: ${r.message}")
                    if (r.isAuthError) {
                        log("TASK: Thông tin xác thực ${provider.displayName} không hợp lệ — dừng task")
                        notifyTaskAuthExpired(provider.displayName)
                        break
                    }
                    if (r.cooldownSecs > 0) {
                        log("TASK: Server yêu cầu chờ ${r.cooldownSecs}s")
                        delay(r.cooldownSecs * 1_000L)
                    }
                    consecFail++
                    continue
                }
            }

            if (job == null) {
                val reason = provider.noJobReason(account)
                log(
                    "TASK: Không có job khả dụng cho @$username lúc này" +
                        (if (reason.isNotBlank()) " ($reason)" else "") + " — dừng làm việc"
                )
                // v1.12.0: hết lượt trong ngày → cảnh báo (khác với "tạm hết job" thông thường)
                if (reason.contains("giới hạn", ignoreCase = true) || reason.contains("hết lượt", ignoreCase = true)) {
                    AtProNotificationManager.notifyDailyLimit(username, reason)
                }
                break
            }

            // Lọc theo loại job được cấu hình. Không khớp → skip để giải phóng job.
            val typeMatches = when (config.taskJobType) {
                TaskJobType.LIKE   -> job.kind == TaskJobKind.LIKE
                TaskJobType.FOLLOW -> job.kind == TaskJobKind.FOLLOW
                TaskJobType.BOTH   -> true
            }
            if (!typeMatches) {
                log("TASK: Job #${job.id} loại ${job.kind} không khớp cấu hình — skip")
                provider.skip(account, job)
                consecFail++
                continue
            }

            log("TASK: Job #${job.id} ${job.kind} link=${job.link.take(60)}")

            // ── [C] Mở link nhiệm vụ ─────────────────────────────
            setStatus("TASK: Mở link nhiệm vụ...")
            // v1.6.0: báo nhà cung cấp trước khi mở link (Follow4Follow: mark-start cho job LIKE).
            provider.onJobOpening(account, job)
            host.openDeepLink(job.link)
            delay(1_500)  // chờ TikTok mở link

            // Chờ delay cấu hình cho video/profile load (v1.10.0: một thanh đếm ngược thay vì đăng trạng thái mỗi giây)
            setStatus("TASK: Chờ tải nội dung")
            waitWithBar("Chờ tải", config.taskJobDelaySecs * 1_000L)
            // v1.12.5: popup hay hiện ngay khi trang video/hồ sơ tải xong và che nút Thích/Theo dõi.
            popup.sweep("Trang nhiệm vụ", lateRender = true)

            // ── [D] Thực hiện nhiệm vụ ───────────────────────────
            val success = when (job.kind) {
                TaskJobKind.LIKE   -> doLikeTask()
                TaskJobKind.FOLLOW -> doFollowTask()
            }

            // ── [E] Back về feed ──────────────────────────────────
            // v1.10.0: kiểm tra "feed THẬT" (có thanh điều hướng, không có nút Quay lại). Trước đây dùng
            // isOnFeedTab() — trang video/hồ sơ mở từ link cũng có nút Thích/Bình luận nên bị coi là feed.
            setStatus("TASK: Về lại feed")
            ensureRealFeed("TASK")

            // ── [F] Báo cáo kết quả ──────────────────────────────
            if (!success) {
                setStatus("TASK: Không thực hiện được — báo skip...")
                log("TASK: Job #${job.id} không thực hiện được trên máy — gọi skip")
                when (val skipResult = provider.skip(account, job)) {
                    is TaskResult.Success -> log("TASK: Đã skip job #${job.id}")
                    is TaskResult.Error   -> log("TASK: Lỗi skip job #${job.id}: ${skipResult.message}")
                }
                consecFail++
                pushTaskOverlay(username, jobsDone, consecFail, totalLikes, totalFollows, totalReward)
                continue
            }

            setStatus("TASK: Báo cáo kết quả...")
            val firstResult = provider.complete(account, job)
            // v1.6.0: server giới hạn tốc độ (429) → chờ rồi báo lại ĐÚNG 1 lần, không bỏ mất job đã làm xong.
            val completeResult = if (firstResult is TaskResult.Error && !firstResult.isAuthError && firstResult.cooldownSecs > 0) {
                log("TASK: Báo job #${job.id} bị giới hạn tốc độ — chờ ${firstResult.cooldownSecs}s rồi báo lại")
                delay(firstResult.cooldownSecs * 1_000L)
                provider.complete(account, job)
            } else {
                firstResult
            }
            when (completeResult) {
                is TaskResult.Success -> {
                    log("TASK: Job #${job.id} hoàn thành ✓ (+${completeResult.data})")
                    jobsDone++
                    consecFail = 0
                    totalReward += completeResult.data
                    com.atpro.data.EarningsStore.record(job.kind.name, completeResult.data, username)
                    if (job.kind == TaskJobKind.LIKE) totalLikes++ else totalFollows++
                    pushTaskOverlay(username, jobsDone, consecFail, totalLikes, totalFollows, totalReward)

                    // ── [G] Nuôi acc SAU KHI hoàn thành job — v1.3.3 ──
                    // Nuôi ngắn mỗi job; định kỳ mỗi taskLongFarmEveryNJobs job thì
                    // nuôi LÂU (xem taskPostJobNurtureSecs() — pure function, có test).
                    if (isFarming) {
                        awaitResumed()
                        // v1.10.0: sau khi báo cáo xong (mất vài giây, TikTok có thể đang ở trang job) → ĐẢM BẢO về feed
                        // rồi mới nuôi acc. Trước đây vẫn chạy các bước nuôi ngay trên trang video/hồ sơ của job.
                        setStatus("TASK: Về lại feed")
                        ensureRealFeed("TASK")
                        val nurtureSecs = taskPostJobNurtureSecs(
                            jobsDone         = jobsDone,
                            everyNJobs       = config.taskLongFarmEveryNJobs,
                            longDurationSecs = config.taskLongFarmDurationSecs,
                            shortMinSecs     = config.taskFarmAfterJobMinSecs,
                            shortMaxSecs     = config.taskFarmAfterJobMaxSecs,
                        )
                        val isLongCycle = config.taskLongFarmEveryNJobs > 0 &&
                            jobsDone % config.taskLongFarmEveryNJobs == 0
                        setStatus(
                            if (isLongCycle) "TASK: [@$username] Nuôi acc lâu (${nurtureSecs}s)..."
                            else              "TASK: [@$username] Nuôi acc (${nurtureSecs}s)..."
                        )
                        log(
                            if (isLongCycle) "TASK: Mốc $jobsDone job — nuôi acc LÂU ${nurtureSecs}s"
                            else              "TASK: Nuôi acc ${nurtureSecs}s sau job #${job.id}"
                        )
                        totalVideos += doSimpleFarm(nurtureSecs)
                    }
                }
                is TaskResult.Error -> {
                    log("TASK: Lỗi báo cáo job #${job.id}: ${completeResult.message}")
                    if (completeResult.isAuthError) {
                        log("TASK: Thông tin xác thực ${provider.displayName} không hợp lệ — dừng task")
                        notifyTaskAuthExpired(provider.displayName)
                        break
                    }
                    if (completeResult.cooldownSecs > 0) {
                        log("TASK: Chờ cooldown ${completeResult.cooldownSecs}s")
                        delay(completeResult.cooldownSecs * 1_000L)
                    } else if (completeResult.jobGone) {
                        // v1.12.0 (f4f.py): "Không tìm thấy dữ liệu" = job đã bị xoá/hết hạn → báo lại hay skip đều vô ích, sang job kế.
                        log("TASK: Job #${job.id} đã hết hạn/bị xoá trên máy chủ — bỏ qua, sang job kế")
                    } else {
                        // v1.6.0: lỗi nghiệp vụ (vd "chưa làm job này") → skip để nhả job, khỏi bị giao lại.
                        when (val sk = provider.skip(account, job)) {
                            is TaskResult.Success -> log("TASK: Đã skip job #${job.id} sau lỗi báo cáo")
                            is TaskResult.Error   -> log("TASK: Lỗi skip job #${job.id}: ${sk.message}")
                        }
                    }
                    consecFail++
                    pushTaskOverlay(username, jobsDone, consecFail, totalLikes, totalFollows, totalReward)
                }
            }
        }

        pushTaskOverlay(username, jobsDone, consecFail, totalLikes, totalFollows, totalReward)
        repo.closeSession(sessionId, username, totalLikes, totalFollows, totalVideos, 0)
        log("TASK: @$username xong — jobs=$jobsDone likes=$totalLikes follows=$totalFollows videos=$totalVideos thưởng=+$totalReward")
        setStatus("")

        // v1.12.0: cộng vào tổng kết cả lượt + thông báo từng tài khoản
        taskRunJobs += jobsDone; taskRunLikes += totalLikes; taskRunFollows += totalFollows; taskRunReward += totalReward
        AtProNotificationManager.notifyTaskAccountDone(username, jobsDone, totalLikes, totalFollows, totalReward)
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
        // v1.4.0: cùng bộ điều tiết tỉ lệ như farmOneAccount (xem FarmBehavior.kt).
        val likeGate = RateGate()

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
                val progress  = 1.0 - secsLeftNow().toDouble() / (totalMs / 1_000.0).coerceAtLeast(1.0)
                // v1.11.0: thời gian xem + tỉ lệ tim lấy từ bộ thiết lập RIÊNG của chế độ kiếm tiền.
                val watchPlan = FarmBehavior.watchPlan(
                    config.taskFarmWatchMinSecs.toDouble(),
                    maxOf(config.taskFarmWatchMaxSecs, config.taskFarmWatchMinSecs + 1).toDouble(),
                    progress,
                )
                val watchMs   = watchPlan.ms
                val secsLeft  = maxOf(1L, secsLeftNow())
                val watchSecs = minOf(watchMs / 1_000L, secsLeft).coerceAtLeast(1L)
                log("FEED: Xem video #${videos + 1} — ${watchSecs}s (còn ${secsLeft}s)")
                OverlayFarmMonitor.startCountdown("Xem video #${videos + 1}", watchSecs * 1_000L)
                for (w in watchSecs downTo 1L) {
                    delay(1_000L)
                    if (!isFarming) break
                }
                OverlayFarmMonitor.stopCountdown()
                videos++

                Human.occasionalPause(config.occasionalPauseChance)

                // Like theo likeRate (không follow trong simplified farm)
                val freshRoot     = host.getRootNode()
                val isLiveContent = NodeTraverser.detectLive(freshRoot)
                val isAdContent   = !isLiveContent && NodeTraverser.detectAd(freshRoot)
                if (!isLiveContent && !isAdContent) {
                    val verdict = judgeVideoForLike(
                        freshRoot, config.taskFarmMinLikesToLike,
                        config.taskFarmLikeByHashtag, config.taskFarmHashtags,
                        config.taskFarmLikeByCaption, config.taskFarmCaptionKeywords,
                    )
                    if (verdict.gate != LikeCountGate.PASS) {
                        noteLikeGate(verdict, config.taskFarmMinLikesToLike)
                    } else if (verdict.contentMatch ||
                        likeGate.decide(config.taskFarmLikeRate, FarmBehavior.interestWeight(watchPlan.interest))) {
                        Human.delay(450, 1_200)
                        safeStep("simple_farm_like", timeoutMs = 15_000L) { doLike() }
                    }
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
     *
     * v1.3.3: nhà cung cấp nhiệm vụ xác minh server-side là số like thực sự tăng (job có thể
     * bị báo lỗi "chưa tăng like" nếu double-tap không được TikTok ghi nhận —
     * ví dụ rơi đúng lúc UI đang animate/scroll). Vì task mode cần độ tin cậy
     * cao hơn nuôi acc thường (job KHÔNG được thử lại tự do — mỗi lần fail
     * tính vào taskMaxConsecFailures), thêm 1 lượt double-tap dự phòng thứ 2
     * ở toạ độ jitter khác sau khi đã chờ xem animation tim lần 1.
     *
     * Giả định (CHƯA xác minh trên thiết bị thật — cần QA):
     * double-tap-để-tim của TikTok là "chỉ tim" (idempotent), KHÔNG toggle
     * unlike như tap đơn vào icon trái tim trên action rail. Nếu double-tap
     * lần 1 đã ăn, double-tap lần 2 chỉ trigger lại animation vô hại, không
     * bỏ tim. Nếu giả định này sai trên 1 biến thể TikTok nào đó, cần bỏ lượt
     * dự phòng và thay bằng xác minh qua node cây (tìm nút tim action rail +
     * so trạng thái trước/sau) — hiện chưa làm vì rủi ro lặp lại đúng lỗi
     * false-positive substring đã gặp ở NodeTraverser (xem v1.3.2).
     */
    private suspend fun doLikeTask(): Boolean {
        val cx = screenW / 2 + Human.jitter(25)
        val cy = (screenH * 0.55).toInt() + Human.jitter(20)
        host.clickSuspend(cx, cy)
        // v1.2.3 [FIX]: doubleTapGap() thay microPause() — xem doLike().
        Human.doubleTapGap()
        host.clickSuspend(cx + Human.jitter(8), cy + Human.jitter(8))
        Human.likeAnimDelay()

        // v1.3.3: double-tap dự phòng lần 2 — toạ độ jitter mới, gap tự nhiên
        // hơn giữa 2 lượt (không phải double-tap gap ~60-150ms, mà một
        // khoảng dừng "xem lại" như đang ngắm video).
        Human.delay(500, 1_100)
        val cx2 = screenW / 2 + Human.jitter(25)
        val cy2 = (screenH * 0.55).toInt() + Human.jitter(20)
        host.clickSuspend(cx2, cy2)
        Human.doubleTapGap()
        host.clickSuspend(cx2 + Human.jitter(8), cy2 + Human.jitter(8))
        Human.likeAnimDelay()

        delay((config.delayAfterLike * 1_000).toLong())
        log("TASK-LIKE: Double-tap x2 (dự phòng) ✓")
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
            FollowResult.BLOCKED, FollowResult.NO_BUTTON, FollowResult.CLICK_FAILED -> false
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
        log(">> [Facebook] Bắt đầu phiên nuôi acc Facebook")
        setStatus(">> Đang mở Facebook...")

        // v1.2.9: thử lần lượt các gói Facebook cho đến khi mở được
        val fbPkg = AppConstants.FACEBOOK_PACKAGES.firstOrNull { pkgName ->
            val launched = host.launchApp(pkgName)
            if (launched) log(">> [Facebook] Mở thành công: $pkgName")
            launched
        }
        if (fbPkg == null) {
            log("ERR: Không mở được Facebook — kiểm tra đã cài: ${AppConstants.FACEBOOK_PACKAGES.joinToString()}")
            setStatus(""); return
        }

        // v1.2.9: chờ FbMainTabActivity thực sự hiển thị (tối đa 10s)
        // Manifest: com.facebook.katana.activity.FbMainTabActivity là màn hình chính
        log("FB: Chờ Facebook tải...")
        var waited = 0
        while (waited < 20 && isFarming) {
            delay(500)
            waited++
            val root = host.getRootNode()
            if (root?.packageName == fbPkg) {
                log("FB: Facebook đã sẵn sàng (${waited * 500}ms)")
                break
            }
        }
        delay(1_500) // thêm chút chờ feed render

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

        log("FB: Lướt feed ${durationSecs}s (likeRate=${config.facebookLikeRate}, reelsRate=${config.facebookReelsViewRate})")

        while (secsLeftNow() > 0L && isFarming) {
            awaitResumed()
            val secsLeft = secsLeftNow()

            // v1.3.0: fallback chống văng khỏi Facebook (crash app khác nổi lên, dialog
            // hệ thống chiếm foreground, v.v.) — trước đây không có kiểm tra nào, cứ thế
            // tìm nút Like/Comment trên sai màn hình → toàn bộ hành động thất bại âm thầm.
            val curPkg = host.getRootNode()?.packageName
            if (curPkg != null && curPkg != fbPkg) {
                log("FB: ⚠ Không còn ở Facebook (đang ở '$curPkg') — thử khôi phục")
                if (!dismissFacebookPopupFallback("khôi phục về Facebook")) host.pressBack()
                delay(1_000)
                if (host.getRootNode()?.packageName != fbPkg) {
                    log("FB: Vẫn chưa về Facebook — mở lại app")
                    host.launchApp(fbPkg)
                    delay(2_000)
                }
            }

            // [v1.3.1] Kiểm tra chủ động menu/bottom sheet ngoài ý muốn (xem doc ở
            // dismissFacebookBottomSheetIfPresent) — trước đây chỉ phát hiện khi 1
            // hành động cụ thể (Like/Comment) fail, nên nếu vòng đó "may mắn" không
            // cần Like, menu cứ nằm ì che feed mà không ai back ra.
            dismissFacebookBottomSheetIfPresent("đầu vòng lặp")

            OverlayFarmMonitor.update(
                accountIndex    = 1, accountTotal = 1,
                accountId       = "Facebook",
                sessionSecsLeft = secsLeft, totalSecsLeft = secsLeft,
                action          = "FB: Đang đọc bài viết...",
            )

            val iterationOk = safeStep("facebook_iter", timeoutMs = 45_000L) {
                // v1.2.9: Khoảng "đọc bài" 8–25s (config.facebookReadTimeMin/MaxSecs)
                // trước khi quyết định like/lướt tiếp — thay cho delay cố định cũ,
                // mô phỏng hành vi đọc tự nhiên thay vì lướt liên tục không dừng.
                val readSecs = Random.nextLong(
                    config.facebookReadTimeMinSecs.toLong(),
                    (config.facebookReadTimeMaxSecs + 1).toLong(),
                )
                // v1.3.0: log hiển thị số giây đọc thực tế (xs) để người dùng thấy
                // tool đang "đọc" bao lâu, thay vì chỉ báo chung chung.
                log("FB: Đang đọc bài viết... (${readSecs}s)")
                delay(readSecs * 1_000L)

                // v1.3.0: cập nhật logic tìm nút Like theo accessibility-comment-like-logic.md
                // Tier 1: content-desc khớp CHÍNH XÁC (findByContentDescExact) — tránh
                // contains() bắt nhầm "Unlike"/"Bỏ thích" (đều chứa "thích"/"like" như substring).
                // Tier 2: fallback text khớp chính xác.
                val root = host.getRootNode()
                var likeBtn = NodeTraverser.findByContentDescExact(root, "thích", "like")
                    ?: NodeTraverser.findByText(root, "thích", exact = true)
                    ?: NodeTraverser.findByText(root, "like", exact = true)

                var didView = false
                val wantsToLike = Random.nextFloat() < config.facebookLikeRate
                if (wantsToLike && likeBtn == null) {
                    // v1.3.0: không tìm thấy nút Like — thử dọn popup lạ (nếu có) rồi
                    // tìm lại 1 lần, thay vì bỏ cuộc ngay (fallback chống popup che UI).
                    if (dismissFacebookPopupFallback("tìm nút Like")) {
                        val root2 = host.getRootNode()
                        likeBtn = NodeTraverser.findByContentDescExact(root2, "thích", "like")
                            ?: NodeTraverser.findByText(root2, "thích", exact = true)
                            ?: NodeTraverser.findByText(root2, "like", exact = true)
                    }
                    if (likeBtn == null) log("FB: Không tìm thấy nút Like ở bài này — lướt tiếp")
                }
                if (wantsToLike && likeBtn != null) {
                    val label = (
                        likeBtn.text?.toString()
                            ?: likeBtn.node.contentDescription?.toString()
                            ?: ""
                    ).lowercase()
                    // isChecked TRƯỚC (theo doc) — Facebook Like là toggle button 2 trạng
                    // thái; isChecked = true nghĩa là ĐÃ like rồi, click lại sẽ thành UNLIKE.
                    val alreadyLiked = likeBtn.isChecked || likeBtn.isSelected ||
                        "bỏ thích" in label || "unlike" in label || "đã thích" in label
                    if (!alreadyLiked) {
                        log("FB: Bài viết hay đấy, để lại tim nào...")
                        Human.microPause()
                        host.clickNode(likeBtn.node)
                        liked++
                        log("FB: Tim bài đăng #$liked ❤")
                        OverlayFarmMonitor.update(
                            accountIndex = 1, accountTotal = 1,
                            accountId = "Facebook",
                            sessionSecsLeft = secsLeftNow(), totalSecsLeft = secsLeftNow(),
                            action = "FB: Tim bài #$liked ❤",
                        )
                        Human.delay(800, 1_500)
                    } else {
                        log("FB: Bài này thích rồi, lướt tiếp thôi...")
                    }
                } else {
                    log("FB: Lướt tiếp xem có gì hay không...")
                }

                // v1.3.0: xem bình luận thụ động (không gõ) — tái sử dụng ý tưởng
                // commentViewRate của TikTok, áp riêng cho Facebook.
                if (Random.nextFloat() < config.facebookCommentViewRate) {
                    didView = doFacebookViewComments()
                }

                OverlayFarmMonitor.update(
                    accountIndex = 1, accountTotal = 1,
                    accountId = "Facebook",
                    sessionSecsLeft = secsLeftNow(), totalSecsLeft = secsLeftNow(),
                    action = "FB: Chuẩn bị lướt tiếp...",
                )

                // Lướt feed lên
                if (!didView) {
                    val x = screenW / 2 + Human.jitter(12)
                    host.swipeSuspend(
                        x, (screenH * 0.78).toInt(),
                        x, (screenH * 0.22).toInt(),
                        Human.swipeDuration(480),
                    )
                }
                scrolled++
                Human.delay(1_500, 3_000)
                Human.occasionalPause(config.occasionalPauseChance)

                // v1.3.0: thỉnh thoảng ghé qua tab Reels giữa lúc lướt feed.
                if (secsLeftNow() > 30L && Random.nextFloat() < config.facebookReelsViewRate) {
                    doFacebookReels()
                }
            }
            if (iterationOk == null) delay(1_000)
        }

        log("FB: Hoàn thành — lướt $scrolled lần, tim $liked bài")
        setStatus("FB: Đang đóng Facebook...")
        delay(500)
        host.killApp(fbPkg)
        setStatus("")
    }

    /**
     * v1.3.0 — Facebook chưa có PopupHandler xây riêng nhiều đời như TikTok (TikTok
     * có dữ liệu thực tế tích luỹ qua nhiều bản). Dùng bộ quét từ khoá GENERIC thay
     * thế — bắt các dialog phổ biến (xin quyền, cập nhật, đánh giá app, thông báo...)
     * đè lên Facebook khiến các bước tìm nút Like/Comment/Reels thất bại.
     *
     * CHỈ gọi khi 1 hành động quan trọng đã thất bại (không tìm thấy nút mong đợi) —
     * KHÔNG gọi chủ động mỗi vòng lặp, vì quét từ khoá rộng trên caption bài đăng thật
     * dễ false-positive (đúng bug đã gặp với TikTok — xem [FIX-WAITFEED-POPUP]).
     */
    /**
     * v1.3.1 — Phát hiện bottom sheet/context-menu Facebook xuất hiện ngoài ý
     * muốn (đôi khi do double-tap/long-press trúng nhầm nút "..." trên bài
     * đăng hoặc Reels) — vd "Lưu bài viết/Báo cáo ảnh/Lựa chọn khác" (menu bài
     * đăng) hoặc "Tốc độ phát lại/Cài đặt chất lượng" (menu Reels). Các menu
     * này che nút Like/Comment, khiến vòng lặp cứ tìm nút hụt mãi mà KHÔNG
     * back ra — nhìn như treo dù tool vẫn "đang chạy" bình thường.
     *
     * Khác với dismissFacebookPopupFallback(): hàm đó dùng từ khoá chung
     * chung ("hủy", "đóng", "để sau"...) nên CHỈ được gọi phản ứng khi 1 hành
     * động cụ thể fail (tránh false-positive với caption bài đăng thật). Hàm
     * này chỉ quét vài cụm từ ĐẶC TRƯNG riêng của các menu trên — an toàn để
     * gọi CHỦ ĐỘNG mỗi vòng lặp.
     */
    private suspend fun dismissFacebookBottomSheetIfPresent(context: String): Boolean {
        val root = host.getRootNode() ?: return false
        val menuSignals = listOf(
            "lưu bài viết", "báo cáo ảnh", "báo cáo bài viết", "lựa chọn khác",
            "tốc độ phát lại", "cài đặt chất lượng", "playback speed",
            "ẩn thước phim này", "hide reel", "gỡ theo dõi",
        )
        val hit = menuSignals.any { NodeTraverser.findByText(root, it, ignoreCase = true) != null }
        if (!hit) return false

        log("FB: Menu/bottom sheet ngoài ý muốn khi $context — back ra")
        host.pressBack()
        delay(700)
        return true
    }

    private suspend fun dismissFacebookPopupFallback(context: String): Boolean {
        val root = host.getRootNode() ?: return false
        val dismissTexts = listOf(
            "không cho phép", "don't allow", "để sau", "not now", "bỏ qua", "skip",
            "hủy", "cancel", "đóng", "close", "không, cảm ơn", "no thanks",
        )
        for (kw in dismissTexts) {
            val btn = NodeTraverser.findByText(root, kw, ignoreCase = true, exact = true)
            if (btn != null && btn.isClickable) {
                log("FB: Phát hiện popup lạ khi $context — bấm '$kw' để đóng")
                host.clickNode(btn.node)
                delay(700)
                return true
            }
        }
        return false
    }

    /**
     * v1.3.0 — Xem bình luận thụ động (không gõ) trên Facebook: mở panel bình luận,
     * cuộn xem vài lượt rồi đóng lại, không gõ/gửi gì. Mô phỏng hành vi đọc tự nhiên.
     */
    private suspend fun doFacebookViewComments(): Boolean {
        val root = host.getRootNode()
        val commentBtn = NodeTraverser.findByContentDescExact(root, "bình luận", "comment")
            ?: NodeTraverser.findByText(root, "bình luận", ignoreCase = true)
            ?: NodeTraverser.findByText(root, "comment", ignoreCase = true)
            ?: return false

        log("FB: Ghé xem vài bình luận...")
        host.clickNode(commentBtn.node)
        Human.delay(1_200, 2_000)

        val scrollTimes = Random.nextInt(1, 4)
        repeat(scrollTimes) {
            host.swipeSuspend(
                screenW / 2, (screenH * 0.75).toInt(),
                screenW / 2, (screenH * 0.40).toInt(),
                Human.swipeDuration(400),
            )
            Human.delay(900, 1_600)
        }
        log("FB: Đã xem $scrollTimes lượt bình luận")

        host.pressBack()
        delay(600)
        return true
    }

    /**
     * v1.3.0 — Tính năng xem Reels Facebook: chuyển qua tab Reels, lướt trong
     * khoảng [facebookReelsViewDurationMinSecs, facebookReelsViewDurationMaxSecs]
     * giây, thỉnh thoảng đúp màn hình để thích video, rồi quay lại tab Trang chủ.
     */
    private suspend fun doFacebookReels() {
        val rootBeforeNav = host.getRootNode()
        var reelsTab = NodeTraverser.findByContentDescExact(rootBeforeNav, "reels", "thước phim")
            ?: NodeTraverser.findByText(rootBeforeNav, "reels", ignoreCase = true)
            ?: NodeTraverser.findByText(rootBeforeNav, "thước phim", ignoreCase = true)
        if (reelsTab == null && dismissFacebookPopupFallback("tìm tab Reels")) {
            val rootRetry = host.getRootNode()
            reelsTab = NodeTraverser.findByContentDescExact(rootRetry, "reels", "thước phim")
                ?: NodeTraverser.findByText(rootRetry, "reels", ignoreCase = true)
                ?: NodeTraverser.findByText(rootRetry, "thước phim", ignoreCase = true)
        }
        if (reelsTab == null) {
            log("FB REELS: Không tìm thấy tab Reels — bỏ qua lượt này")
            return
        }

        log("FB REELS: Ghé qua xem Reels...")
        host.clickNode(reelsTab.node)
        Human.delay(1_500, 2_500)

        val durationSecs = Random.nextLong(
            config.facebookReelsViewDurationMinSecs.toLong(),
            config.facebookReelsViewDurationMaxSecs.toLong() + 1,
        )
        log("FB REELS: Xem Reels trong ${durationSecs}s (likeRate=${config.facebookReelsLikeRate})")

        val startWall = System.currentTimeMillis()
        var reelsWatched = 0
        var reelsLiked    = 0
        while (isFarming && (System.currentTimeMillis() - startWall) < durationSecs * 1_000L) {
            awaitResumed()
            // [v1.3.1] Menu "..." của Reels (Tốc độ phát lại/Cài đặt chất lượng...) đôi
            // khi tự nổi lên — nếu không dọn, double-tap/swipe bên dưới đều "hụt" vào
            // menu thay vì video, và vòng lặp cứ lặp lại mà không back ra được.
            dismissFacebookBottomSheetIfPresent("Reels")
            val watchMs = Random.nextLong(3_000L, 9_000L)
            OverlayFarmMonitor.update(
                accountIndex = 1, accountTotal = 1, accountId = "Facebook",
                sessionSecsLeft = durationSecs, totalSecsLeft = durationSecs,
                action = "FB REELS: Đang xem Reel #${reelsWatched + 1}...",
            )
            delay(watchMs)
            reelsWatched++
            log("FB REELS: Đã xem Reel #$reelsWatched (${watchMs / 1000}s)")

            // v1.3.0: Reels không có nút Like rõ ràng khi lướt nhanh — dùng đúp
            // (double-tap) vào giữa màn hình, giống hành vi người dùng thật.
            if (Random.nextFloat() < config.facebookReelsLikeRate) {
                log("FB REELS: Video này hay, đúp màn hình để thích...")
                host.doubleTapSuspend(screenW / 2, screenH / 2)
                reelsLiked++
                log("FB REELS: Đã thích Reel #$reelsLiked ❤")
                OverlayFarmMonitor.update(
                    accountIndex = 1, accountTotal = 1, accountId = "Facebook",
                    sessionSecsLeft = durationSecs, totalSecsLeft = durationSecs,
                    action = "FB REELS: Thích Reel #$reelsLiked ❤",
                )
                Human.delay(500, 1_000)
            }

            val x = screenW / 2 + Human.jitter(10)
            host.swipeSuspend(x, (screenH * 0.80).toInt(), x, (screenH * 0.20).toInt(), Human.swipeDuration(350))
            Human.delay(300, 800)
        }
        log("FB REELS: Hoàn thành — xem $reelsWatched reels, thích $reelsLiked")

        // Quay lại tab Trang chủ (không dùng pressBack vì Reels là tab, không phải
        // màn hình con trong back-stack — pressBack có thể thoát hẳn app).
        val rootAfter = host.getRootNode()
        val homeTab = NodeTraverser.findByContentDescExact(rootAfter, "trang chủ", "home")
            ?: NodeTraverser.findByText(rootAfter, "trang chủ", ignoreCase = true)
            ?: NodeTraverser.findByText(rootAfter, "home", ignoreCase = true)
        if (homeTab != null) {
            host.clickNode(homeTab.node)
        } else {
            host.pressBack()
        }
        delay(800)
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

        var scrolled = 0; var liked = 0; var reposted = 0; var repliesViewed = 0
        log("X: Lướt timeline ${config.xNurtureDurationSecs}s (like=${config.xLikeRate}, repost=${config.xRetweetRate}, replyView=${config.xReplyViewRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "X", secsLeft(), secsLeft(), "X: Đang xem tweet...")
            safeStep("x_iter", 30_000L) {
                val root = host.getRootNode()
                // v1.3.0: content-desc khớp CHÍNH XÁC trước — tránh contains() bắt
                // nhầm "Liked"/"Unlike" (X dùng content-desc "Like"/"Liked" cho nút tim).
                val likeBtn = NodeTraverser.findByContentDescExact(root, "like", "thích")
                    ?: NodeTraverser.findByText(root, "thích", exact = true)
                    ?: NodeTraverser.findByText(root, "like", exact = true)
                if (Random.nextFloat() < config.xLikeRate && likeBtn != null) {
                    val label = (likeBtn.text ?: likeBtn.node.contentDescription?.toString() ?: "").lowercase()
                    val alreadyLiked = likeBtn.isChecked || likeBtn.isSelected ||
                        "liked" in label || "unlike" in label || "đã thích" in label
                    if (!alreadyLiked) {
                        Human.microPause(); host.clickNode(likeBtn.node); liked++
                        log("X: Đã like tweet (#$liked)"); Human.delay(600, 1_200)
                        OverlayFarmMonitor.update(1, 1, "X", secsLeft(), secsLeft(), "X: Like tweet #$liked ❤")
                    }
                }
                if (Random.nextFloat() < config.xRetweetRate) {
                    reposted++; log("X: Repost tweet (#$reposted) (demo)")
                }
                // v1.3.0: thỉnh thoảng mở xem reply thụ động (không gõ) rồi đóng lại.
                if (Random.nextFloat() < config.xReplyViewRate) {
                    if (doXViewReplies()) repliesViewed++
                }
                val x = screenW / 2 + Human.jitter(12)
                host.swipeSuspend(x, (screenH * 0.75).toInt(), x, (screenH * 0.25).toInt(), Human.swipeDuration(420))
                scrolled++
                log("X: Lướt tiếp timeline (#$scrolled)")
                Human.delay(1_000, 2_200)
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("X: Hoàn thành — lướt $scrolled lần, like $liked, repost $reposted, xem reply $repliesViewed lần")
        setStatus("X: Đang đóng X..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** v1.3.0 — Xem reply thụ động (không gõ) dưới 1 tweet trên X, rồi đóng lại. */
    private suspend fun doXViewReplies(): Boolean {
        val root = host.getRootNode()
        val replyBtn = NodeTraverser.findByContentDescExact(root, "reply", "trả lời")
            ?: NodeTraverser.findByText(root, "trả lời", ignoreCase = true)
            ?: NodeTraverser.findByText(root, "reply", ignoreCase = true)
            ?: return false

        log("X: Xem thử vài reply...")
        host.clickNode(replyBtn.node)
        Human.delay(1_200, 2_000)
        val scrollTimes = Random.nextInt(1, 4)
        repeat(scrollTimes) {
            host.swipeSuspend(screenW / 2, (screenH * 0.75).toInt(), screenW / 2, (screenH * 0.40).toInt(), Human.swipeDuration(400))
            Human.delay(900, 1_600)
        }
        log("X: Đã xem $scrollTimes lượt reply")
        host.pressBack()
        delay(600)
        return true
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

        var scrolled = 0; var liked = 0; var followed = 0; var commentsViewed = 0
        log("IG: Lướt Reels ${config.instagramNurtureDurationSecs}s (like=${config.instagramLikeRate}, follow=${config.instagramFollowRate}, commentView=${config.instagramCommentViewRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Instagram", secsLeft(), secsLeft(), "IG: Đang xem Reel/bài...")
            safeStep("ig_iter", 30_000L) {
                val root = host.getRootNode()
                // v1.3.0: content-desc khớp CHÍNH XÁC trước — Instagram dùng
                // content-desc "Like"/"Unlike" cho nút tim, contains() sẽ nhầm lẫn.
                val likeBtn = NodeTraverser.findByContentDescExact(root, "like", "thích")
                    ?: NodeTraverser.findByText(root, "thích", exact = true)
                    ?: NodeTraverser.findByText(root, "like", exact = true)
                if (Random.nextFloat() < config.instagramLikeRate && likeBtn != null) {
                    val label = (likeBtn.text ?: likeBtn.node.contentDescription?.toString() ?: "").lowercase()
                    val alreadyLiked = likeBtn.isChecked || likeBtn.isSelected ||
                        "unlike" in label || "đã thích" in label
                    if (!alreadyLiked) {
                        Human.microPause(); host.clickNode(likeBtn.node); liked++
                        log("IG: Đã like (#$liked)"); Human.delay(500, 1_000)
                        OverlayFarmMonitor.update(1, 1, "Instagram", secsLeft(), secsLeft(), "IG: Like #$liked ❤")
                    }
                }
                if (Random.nextFloat() < config.instagramFollowRate) {
                    followed++; log("IG: Follow (demo) (#$followed)")
                }
                // v1.3.0: thỉnh thoảng xem bình luận thụ động rồi đóng lại.
                if (Random.nextFloat() < config.instagramCommentViewRate) {
                    if (doInstagramViewComments()) commentsViewed++
                }
                val x = screenW / 2 + Human.jitter(10)
                host.swipeSuspend(x, (screenH * 0.80).toInt(), x, (screenH * 0.20).toInt(), Human.swipeDuration(400))
                scrolled++
                log("IG: Lướt tiếp (#$scrolled)")
                Human.delay(2_000, 4_000)  // Reels xem lâu hơn
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("IG: Hoàn thành — lướt $scrolled lần, like $liked, follow $followed, xem bình luận $commentsViewed lần")
        setStatus("IG: Đang đóng Instagram..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** v1.3.0 — Xem bình luận thụ động (không gõ) trên Instagram, rồi đóng lại. */
    private suspend fun doInstagramViewComments(): Boolean {
        val root = host.getRootNode()
        val commentBtn = NodeTraverser.findByContentDescExact(root, "comment", "bình luận")
            ?: NodeTraverser.findByText(root, "bình luận", ignoreCase = true)
            ?: NodeTraverser.findByText(root, "comment", ignoreCase = true)
            ?: return false

        log("IG: Xem thử vài bình luận...")
        host.clickNode(commentBtn.node)
        Human.delay(1_200, 2_000)
        val scrollTimes = Random.nextInt(1, 4)
        repeat(scrollTimes) {
            host.swipeSuspend(screenW / 2, (screenH * 0.75).toInt(), screenW / 2, (screenH * 0.40).toInt(), Human.swipeDuration(400))
            Human.delay(900, 1_600)
        }
        log("IG: Đã xem $scrollTimes lượt bình luận")
        host.pressBack()
        delay(600)
        return true
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

        var scrolled = 0; var liked = 0; var repliesViewed = 0
        log("Threads: Lướt feed ${config.threadsNurtureDurationSecs}s (like=${config.threadsLikeRate}, replyView=${config.threadsReplyViewRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Threads", secsLeft(), secsLeft(), "Threads: Đang xem bài viết...")
            safeStep("threads_iter", 30_000L) {
                val root = host.getRootNode()
                // v1.3.0: content-desc khớp CHÍNH XÁC trước, tránh contains() bắt
                // nhầm "Unlike" (Threads dùng chung engine content-desc với Instagram).
                val likeBtn = NodeTraverser.findByContentDescExact(root, "like", "thích")
                    ?: NodeTraverser.findByText(root, "thích", exact = true)
                    ?: NodeTraverser.findByText(root, "like", exact = true)
                if (Random.nextFloat() < config.threadsLikeRate && likeBtn != null) {
                    val label = (likeBtn.text ?: likeBtn.node.contentDescription?.toString() ?: "").lowercase()
                    val alreadyLiked = likeBtn.isChecked || likeBtn.isSelected || "unlike" in label
                    if (!alreadyLiked) {
                        Human.microPause(); host.clickNode(likeBtn.node); liked++
                        log("Threads: Đã like (#$liked)"); Human.delay(600, 1_100)
                        OverlayFarmMonitor.update(1, 1, "Threads", secsLeft(), secsLeft(), "Threads: Like #$liked ❤")
                    }
                }
                // v1.3.0: thỉnh thoảng xem reply thụ động rồi đóng lại.
                if (Random.nextFloat() < config.threadsReplyViewRate) {
                    if (doThreadsViewReplies()) repliesViewed++
                }
                val x = screenW / 2 + Human.jitter(8)
                host.swipeSuspend(x, (screenH * 0.76).toInt(), x, (screenH * 0.26).toInt(), Human.swipeDuration(430))
                scrolled++
                log("Threads: Lướt tiếp (#$scrolled)")
                Human.delay(1_200, 2_600)
                Human.occasionalPause(config.occasionalPauseChance)
            }
        }
        log("Threads: Hoàn thành — lướt $scrolled lần, like $liked, xem reply $repliesViewed lần")
        setStatus("Threads: Đang đóng..."); delay(500); host.killApp(pkg); setStatus("")
    }

    /** v1.3.0 — Xem reply thụ động (không gõ) dưới 1 bài trên Threads, rồi đóng lại. */
    private suspend fun doThreadsViewReplies(): Boolean {
        val root = host.getRootNode()
        val replyBtn = NodeTraverser.findByContentDescExact(root, "reply", "trả lời")
            ?: NodeTraverser.findByText(root, "trả lời", ignoreCase = true)
            ?: NodeTraverser.findByText(root, "reply", ignoreCase = true)
            ?: return false

        log("Threads: Xem thử vài reply...")
        host.clickNode(replyBtn.node)
        Human.delay(1_200, 2_000)
        val scrollTimes = Random.nextInt(1, 4)
        repeat(scrollTimes) {
            host.swipeSuspend(screenW / 2, (screenH * 0.75).toInt(), screenW / 2, (screenH * 0.40).toInt(), Human.swipeDuration(400))
            Human.delay(900, 1_600)
        }
        log("Threads: Đã xem $scrollTimes lượt reply")
        host.pressBack()
        delay(600)
        return true
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

        var storiesViewed = 0; var liked = 0
        val viewMs = config.snapchatStoryViewSecs * 1_000L
        log("Snapchat: Xem Spotlight ${config.snapchatNurtureDurationSecs}s (${config.snapchatStoryViewSecs}s/story, like=${config.snapchatLikeRate})")

        while (secsLeft() > 0L && isFarming) {
            awaitResumed()
            OverlayFarmMonitor.update(1, 1, "Snapchat", secsLeft(), secsLeft(), "Snapchat: Đang xem story #${storiesViewed + 1}")
            safeStep("snap_iter", 40_000L) {
                delay(viewMs)  // xem story N giây
                storiesViewed++
                log("Snapchat: Đã xem story #$storiesViewed (${config.snapchatStoryViewSecs}s)")

                // v1.3.0: thêm hành động Like — trước đây Snapchat chỉ xem/swipe,
                // chưa có tương tác nào. Content-desc khớp CHÍNH XÁC, tránh contains()
                // bắt nhầm nút đã ở trạng thái "Unlike"/"Liked".
                if (Random.nextFloat() < config.snapchatLikeRate) {
                    val root = host.getRootNode()
                    val likeBtn = NodeTraverser.findByContentDescExact(root, "like", "thích")
                        ?: NodeTraverser.findByText(root, "thích", exact = true)
                        ?: NodeTraverser.findByText(root, "like", exact = true)
                    if (likeBtn != null) {
                        val label = (likeBtn.text ?: likeBtn.node.contentDescription?.toString() ?: "").lowercase()
                        val alreadyLiked = likeBtn.isChecked || likeBtn.isSelected || "unlike" in label
                        if (!alreadyLiked) {
                            Human.microPause(); host.clickNode(likeBtn.node); liked++
                            log("Snapchat: Đã thích story #$liked ❤"); Human.delay(500, 1_000)
                            OverlayFarmMonitor.update(1, 1, "Snapchat", secsLeft(), secsLeft(), "Snapchat: Thích #$liked ❤")
                        }
                    }
                }

                // Swipe sang story tiếp theo (trái sang phải = next story)
                val y = screenH / 2
                host.swipeSuspend((screenW * 0.85).toInt(), y, (screenW * 0.15).toInt(), y, 250)
                log("Snapchat: Chuyển sang story tiếp theo...")
                Human.delay(800, 1_500)
            }
        }
        log("Snapchat: Hoàn thành — đã xem $storiesViewed stories, thích $liked")
        setStatus("Snapchat: Đang đóng..."); delay(500); host.killApp(pkg); setStatus("")
    }
}

