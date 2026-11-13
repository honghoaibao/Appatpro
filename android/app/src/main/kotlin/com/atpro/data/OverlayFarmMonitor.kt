package com.atpro.data

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.atpro.R
import com.atpro.accessibility.TikTokAccessibilityService
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * OverlayFarmMonitor — popup nổi hiển thị phiên chạy theo THỜI GIAN THỰC.
 *
 * v1.12.5 — thiết kế lại theo mẫu, 3 trạng thái (cùng một cửa sổ nổi, đổi giữa các trạng thái bằng [setMode]):
 *  • FULL — popup đầy đủ: tài khoản + tiến trình, 3 ô số (Lượt thích · Đã thêm · Cảnh báo), trạng thái đang làm (kèm thanh
 *    đếm ngược), Nhật ký hoạt động (mốc thời gian, nhãn INFO/SYSTEM/OK/WARN/ERROR), nút Tạm dừng/Tiếp tục · Dừng.
 *    Nút ─ → MINI; nút ✕ → CHIP.
 *  • MINI — "Mini Popup": tên + trạng thái + đồng hồ, thanh tiến trình, Hoàn thành n/N · tim · cảnh báo. Chạm / ⌃ → FULL; nhấn giữ → CHIP.
 *  • CHIP — "Floating Chip": logo + trạng thái + đồng hồ. Chạm / › → FULL.
 *  Cả ba kéo thả được.
 *
 * v1.10.0 — thanh đếm ngược cho hành động đang chờ ([startCountdown]/[stopCountdown]: xem video, chờ tải job, nghỉ, nuôi acc...).
 * Tiết kiệm pin: thanh đếm ngược là MỘT animator thuộc tính (không đăng chữ mỗi giây); vòng quay "đang tải" chỉ chạy khi FULL và
 * không tạm dừng; MINI/CHIP không dựng View cho nhật ký (chỉ giữ dữ liệu, dựng lại khi mở lại FULL).
 *
 * Popup luôn tối (nổi trên TikTok, cần đọc rõ trên mọi nền) — KHÔNG đổi theo chế độ Sáng/Tối của app.
 * Mọi thao tác View chạy trên main thread (qua [handler]); API công khai gọi được từ bất kỳ luồng nào.
 */
object OverlayFarmMonitor {
    const val TAG = "OverlayMonitor"

    // ── Palette: navy đậm · xanh dương · xanh da trời · trắng (+ tím cho nút chính) ──
    private const val C_BG      = 0xF2080E1E.toInt()
    private const val C_CARD    = 0xFF0F1830.toInt()
    private const val C_CARD2   = 0xFF0B1326.toInt()
    private const val C_BORDER  = 0xFF1F3158.toInt()
    private const val C_ACCENT  = 0xFF3B8BFF.toInt()
    private const val C_ACCENT2 = 0xFF6A5CFF.toInt()
    private const val C_VIOLET  = 0xFF8B7CFF.toInt()
    private const val C_BLUE    = 0xFF7FB0FF.toInt()
    private const val C_SKY     = 0xFF4FC3FF.toInt()
    private const val C_GREEN   = 0xFF2EE6A6.toInt()
    private const val C_AMBER   = 0xFFFBBF24.toInt()
    private const val C_RED     = 0xFFFF5C73.toInt()
    private const val C_PINK    = 0xFFFF4D7A.toInt()
    private const val C_TEXT    = 0xFFF5F9FF.toInt()
    private const val C_MUTED   = 0xFFA9B8CE.toInt()
    private const val C_DIM     = 0xFF7285A3.toInt()

    private const val PANEL_WIDTH_DP = 340
    private const val MINI_WIDTH_DP  = 300
    private const val LOG_ROWS_SMALL = 3
    private const val LOG_ROWS_LARGE = 8
    private const val LOG_ROW_DP     = 24
    private const val MAX_LOG_ROWS   = 40

    /** Ba trạng thái hiển thị của cùng một cửa sổ nổi. */
    private enum class Mode { FULL, MINI, CHIP }

    // ─────────────────────────────────────────────────────────
    //  Internal state
    // ─────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var overlayView:   View?          = null
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.FULL
    private var fullPanel: View? = null
    private var miniPanel: View? = null
    private var chipPanel: View? = null
    private var panelWidthPx = 0
    private var miniWidthPx  = 0

    // Nền tảng + chế độ (suy ra từ nhãn dịch vụ khi show())
    private var platformName = "TikTok"
    private var platformLogo = R.drawable.ic_logo_tiktok
    private var isTaskMode   = false

    // Trạng thái chạy/tạm dừng — vòng lặp cập nhật cả 3 trạng thái cùng lúc
    private val pillTexts = ArrayList<TextView>(3)
    private val pillDots  = ArrayList<GradientDrawable>(3)
    private val timeTexts = ArrayList<TextView>(3)
    private var btnPauseResume: TextView? = null

    // Tài khoản + tiến trình
    private var tvUser:    TextView? = null
    private var tvModeChip: TextView? = null
    private var tvReward:  TextView? = null
    private var tvProgress: TextView? = null
    private var tvPercent:  TextView? = null
    private var progressRing: ProgressRing? = null
    private var progressFillFull: View? = null
    private var progressFillMini: View? = null
    private var tvMiniDone: TextView? = null
    private var tvMiniLikes: TextView? = null
    private var tvMiniWarn:  TextView? = null

    // Ô số
    private var tvLikes: TextView? = null
    private var tvAdded: TextView? = null
    private var tvWarn:  TextView? = null
    private var warnCardBg: GradientDrawable? = null

    // Số liệu hiện tại
    private var accName   = ""
    private var accIndex  = 0
    private var accTotal  = 0
    private var doneCount = 0
    private var totalCount = 0
    private var progressFraction = 0f
    private var sessionTotalSecs = 0L
    private var likeCount   = 0
    private var followCount = 0
    private var warnCount   = 0
    private var rewardValue = 0.0
    private var consecErrors = 0
    private var maxErrors    = 0

    // Hành động + thanh đếm ngược
    private var tvAction:    TextView? = null
    private var tvActionSub: TextView? = null
    private var spinner:     ArcSpinner? = null
    private var signalBars:  SignalBars? = null
    private var cdGroup:     View?     = null
    private var cdFill:      View?     = null
    private var cdLabel      = ""
    private var cdActive     = false
    private var cdTotalMs    = 0L
    private var cdRemaining  = 0L        // còn lại tại thời điểm cdSyncWall
    private var cdSyncWall   = 0L        // mốc đồng hồ khi animator bắt đầu chạy đoạn hiện tại
    private var cdAnimator: ObjectAnimator? = null

    // Nhật ký trực tiếp
    private class LogEntry(
        val icon: String, val text: String, val color: Int, val pulse: Boolean = false,
        val badge: String = "INFO", val badgeColor: Int = C_ACCENT,
    )
    private class LogRow(var time: String, val entry: LogEntry, var count: Int = 1) {
        var msgView:  TextView? = null
        var timeView: TextView? = null
        val line: String get() = "${entry.icon} ${entry.text}"
    }
    private val logData = ArrayDeque<LogRow>()           // luôn giữ dữ liệu (kể cả khi thu nhỏ)
    private var logList:  LinearLayout? = null
    private var logBox:   View?         = null
    private var logLarge: Boolean       = false
    private var tvLogHint: TextView?    = null

    private var isPaused: Boolean = false

    // Hiệu ứng chấm khi đang chờ
    private var dotsRunnable: Runnable? = null
    private var dotsPhase:    Int       = 0
    private var dotsBase:     String    = ""

    // Đồng hồ thời gian thực
    private var tickerRunnable: Runnable? = null
    private var tickSessionSecs = 0L
    private var tickTotalSecs   = 0L
    private var tickRunSecs     = 0L

    // Giá trị gửi lần trước — bỏ qua cập nhật thừa
    private var lastAccountText = ""
    private var lastActionText  = ""
    private var lastShownAction = ""
    private var curAction: LogEntry? = null      // dòng trạng thái hiện tại — vẽ lại khi mở lại FULL từ Mini/Chip
    private var curActionDots = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun nowText(): String = synchronized(timeFmt) { timeFmt.format(Date()) }

    // ─────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────

    fun show(context: Context, serviceLabel: String = "") {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission"); return
        }
        handler.post {
            try {
                isTaskMode = (serviceLabel == "NV TIKTOK")
                resolvePlatform(serviceLabel)
                resetNumbers()
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                overlayView   = buildView(context)

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE

                val params = WindowManager.LayoutParams(
                    panelWidthPx,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 12; y = 110
                }

                windowManager!!.addView(overlayView, params)
                // popup hiện dần + phóng nhẹ thay vì bật ra đột ngột
                overlayView?.let { v ->
                    v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
                }
                tickRunSecs = 0L
                renderAll()
                renderLogRows()
                spinner?.start()
                startTicker()
                Log.i(TAG, "Overlay shown [label=$serviceLabel taskMode=$isTaskMode platform=$platformName]")
            } catch (e: Exception) {
                Log.e(TAG, "show: ${e.message}")
            }
        }
    }

    fun hide() {
        stopTicker()
        handler.post {
            cdAnimator?.cancel(); cdAnimator = null; cdActive = false
            spinner?.stop()
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            overlayView = null; windowManager = null
            fullPanel = null; miniPanel = null; chipPanel = null
            pillTexts.clear(); pillDots.clear(); timeTexts.clear(); btnPauseResume = null
            tvUser = null; tvModeChip = null; tvReward = null; tvProgress = null; tvPercent = null
            progressRing = null; progressFillFull = null; progressFillMini = null
            tvMiniDone = null; tvMiniLikes = null; tvMiniWarn = null
            tvLikes = null; tvAdded = null; tvWarn = null; warnCardBg = null
            tvAction = null; tvActionSub = null; spinner = null; signalBars = null; cdGroup = null; cdFill = null
            logList = null; tvLogHint = null; logBox = null; logLarge = false; logData.clear()
            mode = Mode.FULL; isPaused = false; isTaskMode = false
            lastAccountText = ""; lastActionText = ""; lastShownAction = ""; curAction = null; curActionDots = false
            tickSessionSecs = 0L; tickTotalSecs = 0L; tickRunSecs = 0L
            resetNumbers()
            stopDotsAnimation()
        }
    }

    fun syncPausedState(paused: Boolean) {
        handler.post {
            isPaused = paused
            refreshPauseButton()
            if (paused) cdFreeze() else cdAnimate()
            if (paused) spinner?.stop() else if (mode == Mode.FULL) spinner?.start()
        }
    }

    /**
     * Chế độ NHIỆM VỤ — cập nhật: acc đang làm, tiến độ job n/N, lỗi liên tiếp, tim/follow, thưởng.
     * [action] (nếu có) hiện ở dòng trạng thái đang làm.
     */
    fun updateTask(
        accountId:    String,
        successCount: Int,
        totalCount:   Int,
        consecErrors: Int,
        action:       String = "",
        maxErrors:    Int    = 0,
        likes:        Int    = 0,
        follows:      Int    = 0,
        reward:       Double = 0.0,
        accountIndex: Int    = 0,
        accountTotal: Int    = 0,
    ) {
        handler.post {
            accName     = accountId.removePrefix("@")
            accIndex    = accountIndex
            accTotal    = accountTotal
            doneCount   = successCount
            this.totalCount = totalCount
            progressFraction = if (totalCount > 0) (successCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
            this.consecErrors = consecErrors
            this.maxErrors    = maxErrors
            likeCount   = likes
            followCount = follows
            rewardValue = reward
            renderAccount()
            renderProgress()
            renderCounters()
            if (action.isNotEmpty()) showAction(parseAction(action), dots = false)
        }
    }

    /** Dòng trạng thái từ các bước khởi động / trạng thái của engine (`setStatus`). Không đưa vào danh sách log. */
    fun setStartupStatus(msg: String) {
        if (msg.isBlank()) return
        val e = parseAction(msg)
        handler.post { showAction(e, dots = e.color != C_RED && e.color != C_GREEN) }
    }

    /** Chế độ NUÔI ACC — số tim / follow của acc đang nuôi (hiện ở ô "Lượt thích" / "Đã thêm"). */
    fun setCounters(likes: Int, follows: Int) {
        handler.post {
            likeCount = likes
            followCount = follows
            renderCounters()
        }
    }

    /** Chế độ NUÔI ACC — acc hiện tại, đồng hồ phiên/tổng, hành động đang làm. */
    fun update(
        accountIndex:    Int,
        accountTotal:    Int,
        accountId:       String,
        sessionSecsLeft: Long,
        totalSecsLeft:   Long,
        action:          String,
    ) {
        val accountText = "@$accountId  ·  $accountIndex/$accountTotal"
        val sessionDiff = kotlin.math.abs(tickSessionSecs - sessionSecsLeft) > 2L
        val totalDiff   = kotlin.math.abs(tickTotalSecs   - totalSecsLeft)   > 2L
        if (accountText == lastAccountText && action == lastActionText && !sessionDiff && !totalDiff) return

        lastAccountText = accountText
        lastActionText  = action

        handler.post {
            val cleanId = accountId.removePrefix("@")
            val newAccount = accIndex != accountIndex || accName != cleanId
            accName  = cleanId
            accIndex = accountIndex
            accTotal = accountTotal
            // Lần đọc đầu tiên của mỗi acc = tổng thời lượng phiên của acc đó (để tính % tiến trình)
            if (newAccount || sessionTotalSecs <= 0L) sessionTotalSecs = sessionSecsLeft
            if (sessionSecsLeft > sessionTotalSecs) sessionTotalSecs = sessionSecsLeft

            val actionClean = action.trim()
            if (actionClean.isNotEmpty() && actionClean != lastShownAction) {
                lastShownAction = actionClean
                showAction(parseAction(actionClean), dots = false)
            }

            if (sessionDiff) tickSessionSecs = sessionSecsLeft
            if (totalDiff)   tickTotalSecs   = totalSecsLeft
            farmProgressFromClock()
            renderAccount()
            renderProgress()
        }
    }

    /**
     * Thêm MỘT dòng vào "Nhật ký hoạt động". Câu đã được [LogPolish] viết lại cho dễ đọc; dòng chỉ để gỡ lỗi
     * (`detail`) không lên popup (vẫn có đủ ở màn Nhật ký, bật "Chi tiết").
     */
    fun addLog(msg: String) {
        val m = msg.trim()
        if (m.isEmpty()) return
        val polished = LogPolish.polish(m)
        if (polished.detail) return
        val entry = parseLog(m, polished)
        val time  = nowText()
        val isWarning = (polished.level == LogLevel.WARNING || polished.level == LogLevel.ERROR) &&
            polished.rawTag !in NON_WARNING_TAGS
        handler.post {
            if (isWarning) { warnCount++; renderCounters() }
            appendLogRow(time, entry)
        }
    }

    /**
     * Bắt đầu thanh đếm ngược cho hành động đang chờ [totalMs] mili giây, nhãn [label] ("Xem video", "Nghỉ"...).
     * Gọi lại = thay thanh cũ. Tạm dừng → thanh dừng theo; thu nhỏ popup → animator dừng (không tốn pin).
     */
    fun startCountdown(label: String, totalMs: Long) {
        if (totalMs <= 0L) return
        handler.post {
            cdAnimator?.cancel(); cdAnimator = null
            cdActive    = true
            cdLabel     = label
            cdTotalMs   = totalMs
            cdRemaining = totalMs
            cdGroup?.visibility = if (overlayView != null && mode == Mode.FULL) View.VISIBLE else View.GONE
            cdFill?.scaleX = 1f
            renderCdText(totalMs)
            cdAnimate()
        }
    }

    fun stopCountdown() {
        handler.post {
            cdAnimator?.cancel(); cdAnimator = null
            cdActive = false
            cdGroup?.visibility = View.INVISIBLE
            tvActionSub?.text = if (isPaused) "Đang tạm dừng" else ""
        }
    }

    // ── Thanh đếm ngược: quản lý animator (chỉ chạy khi hiện + không tạm dừng) ──

    private fun cdLeftNow(): Long {
        val running = cdAnimator?.isRunning == true
        val left = if (running) cdRemaining - (System.currentTimeMillis() - cdSyncWall) else cdRemaining
        return left.coerceIn(0L, cdTotalMs)
    }

    private fun renderCdText(ms: Long) {
        tvActionSub?.text = if (cdLabel.isEmpty()) formatSecs(ms) else "$cdLabel · còn ${formatSecs(ms)}"
    }

    private fun cdFreeze() {
        if (!cdActive) return
        if (cdAnimator?.isRunning == true) cdRemaining = cdLeftNow()
        cdAnimator?.cancel(); cdAnimator = null
        renderCdText(cdRemaining)
    }

    private fun cdAnimate() {
        if (!cdActive) return
        val fill = cdFill ?: return
        cdAnimator?.cancel(); cdAnimator = null
        val fraction = if (cdTotalMs > 0L) (cdRemaining.toFloat() / cdTotalMs.toFloat()).coerceIn(0f, 1f) else 0f
        fill.scaleX = fraction
        renderCdText(cdRemaining)
        if (isPaused || mode != Mode.FULL || cdRemaining <= 0L) return
        cdSyncWall = System.currentTimeMillis()
        cdAnimator = ObjectAnimator.ofFloat(fill, View.SCALE_X, fraction, 0f).apply {
            duration     = cdRemaining
            interpolator = LinearInterpolator()
            start()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Phân tích log → biểu tượng + màu + nhãn
    // ─────────────────────────────────────────────────────────

    private val TAG_STYLES: Map<String, Pair<String, Int>> = mapOf(
        "OPEN" to ("🚀" to C_BLUE),   "SCAN" to ("👁" to C_SKY),     "CFG" to ("⚙" to C_AMBER),
        "BTN" to ("🔍" to C_DIM),     "LIST" to ("📋" to C_SKY),     "FOUND" to ("✦" to C_GREEN),
        "FIX" to ("🔧" to C_AMBER),   "NORM" to ("⚠" to C_AMBER),    "RELOAD" to ("↩" to C_BLUE),
        "SWITCH" to ("↩" to C_BLUE),  "OK" to ("✓" to C_GREEN),      "ACC" to ("▸" to C_BLUE),
        "ACCS" to ("👤" to C_BLUE),   "AUTH" to ("🔐" to C_AMBER),   "TIM" to ("❤" to C_PINK),
        "FOLLOW" to ("＋" to C_GREEN), "CMT" to ("💬" to C_SKY),      "WATCH" to ("👁" to C_AMBER),
        "WDG" to ("🐕" to C_AMBER),   "WDG-RECOVER" to ("🛠" to C_BLUE), "RECOVER" to ("🛠" to C_BLUE),
        "POPUP" to ("💬" to C_AMBER), "NOTIFY" to ("🔔" to C_AMBER), "WELLBEING" to ("🌙" to C_AMBER),
        "LIMIT" to ("⏱" to C_AMBER),  "SKIP" to ("⏭" to C_DIM),     "CMT SKIP" to ("⏭" to C_DIM),
        "LIKE SKIP" to ("⏭" to C_DIM), "DONE" to ("🏁" to C_GREEN),  "FB REELS" to ("🎬" to C_SKY),
        "FB" to ("📘" to C_SKY),      "IG" to ("📷" to C_SKY),       "X" to ("🐦" to C_SKY),
        "LIVE-FEED" to ("🔴" to C_AMBER), "TASK-FOLLOW" to ("＋" to C_GREEN), "TASK-LIKE" to ("❤" to C_PINK),
        "TASK" to ("📌" to C_BLUE),   "REST" to ("☕" to C_DIM),     "SEARCH" to ("🔎" to C_SKY),
        "SHOP" to ("🛍" to C_SKY),    "INBOX" to ("✉" to C_SKY),     "SAVE" to ("🔖" to C_GREEN),
        "FEED" to ("▶" to C_SKY),     "DEV" to ("🧪" to C_DIM),      "WARN" to ("⚠" to C_AMBER),
        "ERR" to ("✕" to C_RED),
    )

    /**
     * Dòng "Đang làm" (trạng thái/hành động hiện tại do engine báo qua `setStatus` / `update`) — KHÔNG phải dòng của danh sách
     * nhật ký, nên luôn được hiển thị kể cả khi [LogPolish] đánh dấu `detail` (cờ đó chỉ để ẩn khỏi danh sách log).
     * Câu chữ và màu theo cùng một bộ luật với danh sách log để hai nơi nhất quán.
     */
    private fun parseAction(raw: String): LogEntry = parseLog(raw, LogPolish.polish(raw.trim()))

    private fun parseLog(raw: String, p: PolishedLog): LogEntry {
        val msg = raw.trim()
        if (msg.startsWith(">>"))
            return LogEntry("🚀", clean(p.body), C_BLUE, badge = "SYSTEM", badgeColor = C_VIOLET)
        if (msg.startsWith("✓"))
            return LogEntry("✓", clean(p.body), C_GREEN, pulse = true, badge = "OK", badgeColor = C_GREEN)
        if (msg.startsWith("Chờ feed"))
            return LogEntry("⏳", "Đang tải feed", C_SKY, badge = "INFO", badgeColor = C_ACCENT)

        val style = TAG_STYLES[p.rawTag]
        var color = style?.second ?: C_MUTED
        var pulse = false
        // Màu theo mức độ hiệu lực của dòng (LogPolish đã xét cả nội dung: job xong → xanh, thất bại → vàng)
        when (p.level) {
            LogLevel.ERROR   -> color = C_RED
            LogLevel.WARNING -> color = C_AMBER
            LogLevel.SUCCESS -> { color = C_GREEN; pulse = true }
            LogLevel.INFO    -> {}
        }
        val (badge, badgeColor) = when {
            p.level == LogLevel.ERROR   -> "ERROR" to C_RED
            p.level == LogLevel.WARNING -> "WARN"  to C_AMBER
            p.level == LogLevel.SUCCESS -> "OK"    to C_GREEN
            p.rawTag in SYSTEM_TAGS     -> "SYSTEM" to C_VIOLET
            else                        -> "INFO"  to C_ACCENT
        }
        return LogEntry(style?.first ?: "•", clean(p.body), color, pulse, badge, badgeColor)
    }

    /** Dòng log thuộc nhóm "hệ thống" (mở app, cấu hình, tự sửa lỗi, chuyển acc…) → nhãn SYSTEM; còn lại INFO. */
    private val SYSTEM_TAGS = setOf(
        "CFG", "OPEN", "BTN", "FOUND", "DEV", "WDG", "WDG-RECOVER", "RECOVER", "RELOAD", "SWITCH", "FIX", "NORM", "REST", "FEED",
    )

    /** Cảnh báo đếm vào ô "Cảnh báo": WARN/ERR thật — không tính các dòng "Bỏ qua" (quảng cáo/live/video không hợp) vì đó là hành vi bình thường. */
    private val NON_WARNING_TAGS = setOf("SKIP", "LIKE SKIP", "CMT SKIP", "LIVE-FEED")

    private fun clean(s: String): String = s.trim().take(140)

    // ─────────────────────────────────────────────────────────
    //  Hiển thị: dòng trạng thái + danh sách log
    // ─────────────────────────────────────────────────────────

    private fun showAction(e: LogEntry, dots: Boolean) {
        stopDotsAnimation()
        curAction = e; curActionDots = dots
        val line = "${e.icon} ${e.text}"
        if (mode != Mode.FULL) return          // thu nhỏ: không có dòng trạng thái để cập nhật, không chạy hiệu ứng
        tvAction?.setTextColor(if (e.color == C_MUTED || e.color == C_DIM) C_TEXT else e.color)
        if (dots) {
            startDotsAnimation(line.trimEnd('.', '…', ' '))
        } else {
            tvAction?.text = line
            if (e.pulse) pulseView(tvAction)
        }
    }

    /** Thêm vào dữ liệu nhật ký (luôn) và dựng View (chỉ khi popup đang ở FULL). */
    private fun appendLogRow(time: String, e: LogEntry) {
        val last = logData.peekLast()
        if (last != null && last.line == "${e.icon} ${e.text}") {
            last.count++
            last.time = time
            last.msgView?.text = "${last.line}  ×${last.count}"
            last.timeView?.text = time
            return
        }
        val row = LogRow(time, e)
        logData.addLast(row)
        while (logData.size > MAX_LOG_ROWS) logData.removeFirst()
        if (mode != Mode.FULL) return

        val list = logList ?: return
        addRowView(list, row, animate = true)
        while (list.childCount > MAX_LOG_ROWS) list.removeViewAt(0)
        if (e.pulse) pulseView(row.msgView)
        scrollLogToEnd()
    }

    private fun addRowView(list: LinearLayout, row: LogRow, animate: Boolean = false) {
        val ctx = list.context
        val d = { n: Int -> dp(ctx, n) }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // chấm mốc thời gian (đường nối dọc do khung nhật ký vẽ chung)
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(row.entry.badgeColor) }
        }
        line.addView(dot, LinearLayout.LayoutParams(d(8), d(8)).apply { setMargins(d(2), 0, d(8), 0) })
        val tvTime = TextView(ctx).apply {
            text = row.time; textSize = 10f; setTextColor(C_DIM)
            typeface = Typeface.MONOSPACE; includeFontPadding = false
        }
        line.addView(tvTime, LinearLayout.LayoutParams(d(54), LinearLayout.LayoutParams.WRAP_CONTENT))
        val badge = TextView(ctx).apply {
            text = row.entry.badge; textSize = 8.5f; setTextColor(row.entry.badgeColor)
            typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false; gravity = Gravity.CENTER
            setPadding(d(6), d(2), d(6), d(2))
            background = GradientDrawable().apply {
                setColor(row.entry.badgeColor and 0x00FFFFFF or 0x2E000000); cornerRadius = d(6).toFloat()
            }
        }
        line.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, d(7), 0) })
        val tvMsg = TextView(ctx).apply {
            text = if (row.count > 1) "${row.line}  ×${row.count}" else row.line
            textSize = 10.5f; setTextColor(row.entry.color)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        line.addView(tvMsg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(LOG_ROW_DP)))
        row.msgView = tvMsg; row.timeView = tvTime
        // dòng mới hiện dần + trượt nhẹ từ dưới lên (chỉ dòng mới; dựng lại hàng loạt thì không)
        if (animate) {
            line.alpha = 0f
            line.translationY = d(5).toFloat()
            line.animate().alpha(1f).translationY(0f).setDuration(200L).start()
        }
    }

    /** Dựng lại toàn bộ View nhật ký từ dữ liệu (khi mở lại FULL / lúc show). */
    private fun renderLogRows() {
        val list = logList ?: return
        list.removeAllViews()
        for (row in logData) { addRowView(list, row) }
        scrollLogToEnd()
    }

    private fun scrollLogToEnd() {
        val box = logBox as? android.widget.ScrollView ?: return
        box.post { box.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toggleLogSize() {
        logLarge = !logLarge
        val box = logBox ?: return
        val lp = box.layoutParams
        lp.height = dp(box.context, (if (logLarge) LOG_ROWS_LARGE else LOG_ROWS_SMALL) * LOG_ROW_DP + 4)
        box.layoutParams = lp
        tvLogHint?.text = if (logLarge) "Thu gọn  ‹" else "Xem tất cả  ›"
        scrollLogToEnd()
    }

    // ─────────────────────────────────────────────────────────
    //  Vẽ lại các khối từ số liệu hiện tại (chạy trên main thread)
    // ─────────────────────────────────────────────────────────

    private fun resolvePlatform(label: String) {
        val l = label.uppercase(Locale.ROOT)
        when {
            l.contains("TKFB") -> { platformName = "Facebook";  platformLogo = R.drawable.ic_logo_facebook }
            l.contains("TKIG") -> { platformName = "Instagram"; platformLogo = R.drawable.ic_logo_instagram }
            l.contains("TKSC") -> { platformName = "Snapchat";  platformLogo = R.drawable.ic_logo_snapchat }
            l.contains("TKTH") -> { platformName = "Threads";   platformLogo = R.drawable.ic_logo_x }
            l.contains("TKX")  -> { platformName = "X";         platformLogo = R.drawable.ic_logo_x }
            else               -> { platformName = "TikTok";    platformLogo = R.drawable.ic_logo_tiktok }
        }
    }

    private fun resetNumbers() {
        accName = ""; accIndex = 0; accTotal = 0
        doneCount = 0; totalCount = 0; progressFraction = 0f; sessionTotalSecs = 0L
        likeCount = 0; followCount = 0; warnCount = 0
        rewardValue = 0.0; consecErrors = 0; maxErrors = 0
        cdLabel = ""
    }

    private fun renderAll() {
        renderAccount(); renderProgress(); renderCounters(); refreshPauseButton()
        renderTimes()
    }

    private fun renderAccount() {
        tvUser?.text = if (accName.isEmpty()) "@—" else "@$accName"
        val kind = if (isTaskMode) "NHIỆM VỤ" else "NUÔI ACC"
        val idx  = if (accTotal > 0) "  ·  $accIndex/$accTotal" else ""
        // Nền tảng đã hiện ở avatar → nhãn chỉ ghi chế độ + acc n/N (ngắn để không bị cắt ở máy 360dp)
        tvModeChip?.text = "$kind$idx"
        tvReward?.text = if (rewardValue > 0.0) "+${formatReward(rewardValue)} xu" else ""
    }

    /** Nuôi acc: % = (acc đã xong + phần đã trôi của phiên acc hiện tại) / tổng acc. */
    private fun farmProgressFromClock() {
        if (isTaskMode) return
        doneCount  = (accIndex - 1).coerceAtLeast(0)
        totalCount = accTotal
        val session = if (sessionTotalSecs > 0L)
            (1f - tickSessionSecs.toFloat() / sessionTotalSecs.toFloat()).coerceIn(0f, 1f) else 0f
        progressFraction = if (accTotal > 0) ((doneCount + session) / accTotal.toFloat()).coerceIn(0f, 1f) else 0f
    }

    private fun renderProgress() {
        tvProgress?.text = "$doneCount / $totalCount"
        tvPercent?.text  = "${(progressFraction * 100f).toInt()}%"
        tvMiniDone?.text = "Hoàn thành: $doneCount/$totalCount"
        progressRing?.setProgress(progressFraction)
        progressFillFull?.animate()?.scaleX(progressFraction)?.setDuration(250L)?.start()
        progressFillMini?.animate()?.scaleX(progressFraction)?.setDuration(250L)?.start()
    }

    private fun renderCounters() {
        tvLikes?.text = likeCount.toString()
        tvAdded?.text = followCount.toString()
        tvWarn?.text  = warnCount.toString()
        tvMiniLikes?.text = likeCount.toString()
        tvMiniWarn?.text  = warnCount.toString()
        // Ô Cảnh báo đổi màu theo lỗi liên tiếp của nhiệm vụ: vàng khi có lỗi, đỏ khi chạm ngưỡng dừng.
        val c = when {
            maxErrors > 0 && consecErrors >= maxErrors -> C_RED
            consecErrors > 0                           -> C_AMBER
            warnCount > 0                              -> C_AMBER
            else                                       -> C_DIM
        }
        warnCardBg?.setStroke(dp(overlayView?.context ?: return, 1), c and 0x00FFFFFF or 0x66000000)
    }

    private fun renderTimes() {
        val t = formatTime(tickRunSecs)
        for (tv in timeTexts) tv.text = t
    }

    // ─────────────────────────────────────────────────────────
    //  Đồng hồ thời gian thực
    // ─────────────────────────────────────────────────────────

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                if (!isPaused) {
                    tickRunSecs += 1L
                    if (tickSessionSecs > 0) tickSessionSecs = maxOf(0, tickSessionSecs - 1L)
                    if (tickTotalSecs > 0)   tickTotalSecs   = maxOf(0, tickTotalSecs - 1L)
                    renderTimes()
                    if (mode == Mode.FULL && cdActive) {
                        // thanh đếm ngược do animator lo; chỉ cập nhật chữ
                        renderCdText(cdLeftNow())
                    }
                    // % của nuôi acc trôi theo thời gian phiên — cập nhật thưa (5 giây/lần) cho đỡ tốn
                    if (!isTaskMode && mode != Mode.CHIP && tickRunSecs % 5L == 0L) {
                        farmProgressFromClock(); renderProgress()
                    }
                }
                // Tạm dừng: không có gì để cập nhật → thức dậy thưa hơn (tiết kiệm pin).
                handler.postDelayed(this, if (isPaused) 3_000L else 1_000L)
            }
        }
        tickerRunnable = r
        handler.postDelayed(r, 1_000)
    }

    private fun stopTicker() {
        tickerRunnable?.let { handler.removeCallbacks(it) }
        tickerRunnable = null
    }

    // ─────────────────────────────────────────────────────────
    //  Hiệu ứng
    // ─────────────────────────────────────────────────────────

    private fun startDotsAnimation(baseText: String) {
        stopDotsAnimation()
        dotsPhase = 0
        dotsBase  = baseText
        val dots = arrayOf("", ".", "..", "...")
        val r = object : Runnable {
            override fun run() {
                tvAction?.text = "$dotsBase${dots[dotsPhase % 4]}"
                dotsPhase++
                dotsRunnable = this
                // 700ms: đủ để thấy "đang chạy", thưa hơn bản cũ (500ms) → ít đánh thức hơn.
                handler.postDelayed(this, 700L)
            }
        }
        dotsRunnable = r
        handler.post(r)
    }

    private fun stopDotsAnimation() {
        dotsRunnable?.let { handler.removeCallbacks(it) }
        dotsRunnable = null
        dotsPhase = 0
    }

    /** Nút co nhẹ khi nhấn, bật lại khi thả (không chặn sự kiện click). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private fun pulseView(v: View?) {
        v ?: return
        v.alpha = 0.3f
        handler.postDelayed({ v.alpha = 1f }, 320L)
    }

    // ─────────────────────────────────────────────────────────
    //  View phụ: vòng quay · vòng tiến trình · cột sóng
    // ─────────────────────────────────────────────────────────

    /** Vòng cung quay — chỉ quay khi [start] (FULL + đang chạy). Một ObjectAnimator xoay View, không vẽ lại từng khung hình. */
    private class ArcSpinner(context: Context, private val arc: Int, private val track: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()
        private var anim: ObjectAnimator? = null

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val sw = minOf(w, h) * 0.12f
            paint.strokeWidth = sw
            oval.set(sw, sw, w - sw, h - sw)
            paint.color = track; canvas.drawArc(oval, 0f, 360f, false, paint)
            paint.color = arc;   canvas.drawArc(oval, -90f, 110f, false, paint)
        }

        fun start() {
            if (anim?.isRunning == true) return
            anim = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1_100L; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
        }

        fun stop() { anim?.cancel(); anim = null }
    }

    /** Vòng tiến trình có dấu ✓ ở giữa. */
    private class ProgressRing(context: Context, private val arc: Int, private val track: Int, private val check: Drawable?) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()
        private var fraction = 0f

        fun setProgress(f: Float) { fraction = f.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val sw = minOf(w, h) * 0.10f
            paint.strokeWidth = sw
            oval.set(sw, sw, w - sw, h - sw)
            paint.color = track; canvas.drawArc(oval, 0f, 360f, false, paint)
            if (fraction > 0f) { paint.color = arc; canvas.drawArc(oval, -90f, 360f * fraction, false, paint) }
            check?.let {
                val pad = (minOf(w, h) * 0.30f).toInt()
                it.setBounds(pad, pad, width - pad, height - pad)
                it.draw(canvas)
            }
        }
    }

    /** Bốn cột sóng bên phải ô trạng thái (tĩnh — không tốn pin). */
    private class SignalBars(context: Context, private var color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        fun setBarColor(c: Int) { color = c; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val gap = w * 0.12f
            val bw = (w - gap * 3) / 4f
            val heights = floatArrayOf(0.35f, 0.55f, 0.78f, 1f)
            paint.color = color
            for (i in 0 until 4) {
                val left = i * (bw + gap)
                rect.set(left, h * (1f - heights[i]), left + bw, h)
                canvas.drawRoundRect(rect, bw * 0.25f, bw * 0.25f, paint)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  View builder
    // ─────────────────────────────────────────────────────────

    private fun dp(ctx: Context, n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun roundBg(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1).coerceAtLeast(1), strokeColor)
        }

    private fun tinted(ctx: Context, res: Int, color: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.also { DrawableCompat.setTint(it, color) }

    private fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(tinted(ctx, res, color))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    private fun vDivider(ctx: Context, heightDp: Int = 16): View =
        View(ctx).apply {
            setBackgroundColor(C_BORDER)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 1), dp(ctx, heightDp)).apply { setMargins(dp(ctx, 7), 0, dp(ctx, 7), 0) }
        }

    private fun text(ctx: Context, t: String, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t; textSize = sizeSp; setTextColor(color)
            typeface = when {
                bold -> Typeface.DEFAULT_BOLD
                mono -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
            includeFontPadding = false
        }

    /** Nút tròn nhỏ có icon (─, ✕, ⌃, ›…). */
    private fun roundIconButton(ctx: Context, res: Int, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(tinted(ctx, res, C_MUTED))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(ctx, 5), dp(ctx, 5), dp(ctx, 5), dp(ctx, 5))
            background = roundBg(C_CARD, 16, ctx, C_BORDER)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)).apply { setMargins(dp(ctx, 4), 0, 0, 0) }
            pressEffect(this)
            setOnClickListener { onClick() }
        }

    /** Logo AT PRO bo góc (icon_app); lỗi tải thì rơi về chữ "AT". */
    private fun atLogo(ctx: Context, sizeDp: Int): View {
        val dr = ContextCompat.getDrawable(ctx, R.drawable.icon_app)
        if (dr == null) return text(ctx, "AT", 11f, C_ACCENT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        return ImageView(ctx).apply {
            setImageDrawable(dr)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(ctx, sizeDp / 4).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }
    }

    /** Chấm trạng thái (xanh = chạy, vàng = tạm dừng) có quầng sáng. */
    private fun statusDot(ctx: Context): View {
        val glow = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_GREEN and 0x00FFFFFF or 0x33000000) }
        val dot  = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_GREEN) }
        pillDots.add(dot); pillDots.add(glow)
        return FrameLayout(ctx).apply {
            addView(View(ctx).apply { background = glow }, FrameLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12)).apply { gravity = Gravity.CENTER })
            addView(View(ctx).apply { background = dot }, FrameLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { gravity = Gravity.CENTER })
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12)).apply { setMargins(0, 0, dp(ctx, 5), 0) }
        }
    }

    /** Viên thuốc trạng thái "● Đang chạy" — chạm để Tạm dừng / Tiếp tục. */
    private fun statusPill(ctx: Context, withChevron: Boolean): View {
        val tv = text(ctx, "Đang chạy", 11f, C_GREEN, bold = true)
        pillTexts.add(tv)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
            background = roundBg(C_GREEN and 0x00FFFFFF or 0x1F000000, 14, ctx, C_GREEN and 0x00FFFFFF or 0x55000000)
            addView(statusDot(ctx))
            addView(tv)
            if (withChevron) addView(icon(ctx, R.drawable.ic_ov_chev_down, C_GREEN, 12).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(ctx, 2), 0, 0, 0)
            })
            pressEffect(this)
            setOnClickListener { onPauseResumeClick() }
        }
    }

    private fun progressBar(ctx: Context, heightDp: Int, fillRef: (View) -> Unit): View {
        val track = FrameLayout(ctx).apply { background = roundBg(C_BORDER and 0x00FFFFFF or 0x99000000.toInt(), 4, ctx) }
        val fill = View(ctx).apply {
            pivotX = 0f; scaleX = 0f
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_ACCENT, C_ACCENT2))
                .apply { cornerRadius = dp(ctx, 4).toFloat() }
        }
        fillRef(fill)
        track.addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)))
        track.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))
        return track
    }

    private fun buildView(context: Context): View {
        val d = { n: Int -> dp(context, n) }
        pillTexts.clear(); pillDots.clear(); timeTexts.clear()

        val screenW = context.resources.displayMetrics.widthPixels
        panelWidthPx = minOf(d(PANEL_WIDTH_DP), (screenW * 0.94f).toInt())
        miniWidthPx  = minOf(d(MINI_WIDTH_DP),  (screenW * 0.90f).toInt())

        val wrapper = FrameLayout(context)

        // ═════ FULL ═════════════════════════════════════════════════════════
        val full = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(10), d(12), d(12))
            background = roundBg(C_BG, 20, context, C_BORDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = d(10).toFloat()
        }
        fullPanel = full
        wrapper.addView(full, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // Header: [logo] AT PRO | ● Đang chạy ⌄ ........ 00:00 [─] [✕]   (nền tảng hiện ở avatar bên dưới)
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(atLogo(context, 28).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, d(7), 0) })
        header.addView(text(context, "AT PRO", 13f, C_TEXT, bold = true).apply { letterSpacing = 0.04f })
        header.addView(vDivider(context))
        header.addView(statusPill(context, withChevron = true))
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val tFull = text(context, "00:00", 12f, C_TEXT, bold = true, mono = true); timeTexts.add(tFull)
        header.addView(tFull, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(d(6), 0, d(4), 0) })
        header.addView(roundIconButton(context, R.drawable.ic_ov_min) { setMode(Mode.MINI) })
        header.addView(roundIconButton(context, R.drawable.ic_ov_close) { setMode(Mode.CHIP) })
        full.addView(header)

        // Thẻ tài khoản + tiến trình
        val profile = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(10), d(12), d(10))
            background = roundBg(C_CARD, 16, context, C_BORDER)
        }
        val avatar = ImageView(context).apply {
            setImageResource(platformLogo)
            contentDescription = platformName
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(d(10), d(10), d(10), d(10))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(d(2), C_ACCENT) }
        }
        profile.addView(avatar, LinearLayout.LayoutParams(d(48), d(48)).apply { setMargins(0, 0, d(10), 0) })
        val who = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tvUser = text(context, "@—", 15f, C_TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        who.addView(tvUser)
        tvModeChip = text(context, "", 9.5f, C_BLUE, bold = true).apply {
            setPadding(d(8), d(3), d(8), d(3))
            background = roundBg(C_ACCENT and 0x00FFFFFF or 0x26000000, 8, context, C_ACCENT and 0x00FFFFFF or 0x59000000)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        who.addView(tvModeChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(5), 0, 0) })
        tvReward = text(context, "", 10f, C_AMBER, bold = true)
        who.addView(tvReward, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(3), 0, 0) })
        profile.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        profile.addView(View(context).apply { setBackgroundColor(C_BORDER) },
            LinearLayout.LayoutParams(d(1), d(46)).apply { setMargins(d(8), 0, d(10), 0) })
        val prog = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val progTop = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progressRing = ProgressRing(context, C_ACCENT, C_BORDER, tinted(context, R.drawable.ic_ov_check, C_ACCENT))
        progTop.addView(progressRing, LinearLayout.LayoutParams(d(32), d(32)).apply { setMargins(0, 0, d(7), 0) })
        val progTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tvProgress = text(context, "0 / 0", 16f, C_TEXT, bold = true).apply { maxLines = 1 }
        progTexts.addView(tvProgress)
        progTexts.addView(text(context, "Tiến trình", 10.5f, C_MUTED))
        progTop.addView(progTexts)
        prog.addView(progTop)
        val progBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progBottom.addView(progressBar(context, 5) { progressFillFull = it }, LinearLayout.LayoutParams(0, d(5), 1f))
        tvPercent = text(context, "0%", 10.5f, C_TEXT, bold = true)
        progBottom.addView(tvPercent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(d(6), 0, 0, 0) })
        prog.addView(progBottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(6), 0, 0) })
        profile.addView(prog, LinearLayout.LayoutParams(d(112), LinearLayout.LayoutParams.WRAP_CONTENT))
        full.addView(profile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(10), 0, 0) })

        // 3 ô số
        fun statCard(res: Int, color: Int, label: String, setValue: (TextView) -> Unit, setBg: ((GradientDrawable) -> Unit)? = null): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(d(8), d(8), d(8), d(8))
            }
            val bg = roundBg(color and 0x00FFFFFF or 0x14000000, 14, context, color and 0x00FFFFFF or 0x59000000)
            card.background = bg
            setBg?.invoke(bg)
            val badge = FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color and 0x00FFFFFF or 0x33000000) }
                addView(icon(context, res, color, 15), FrameLayout.LayoutParams(d(15), d(15)).apply { gravity = Gravity.CENTER })
            }
            card.addView(badge, LinearLayout.LayoutParams(d(28), d(28)).apply { setMargins(0, 0, d(7), 0) })
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val v = text(context, "0", 16f, C_TEXT, bold = true); setValue(v)
            col.addView(v)
            col.addView(text(context, label, 10f, C_MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return card
        }
        val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statCard(R.drawable.ic_ov_heart, C_PINK, "Lượt thích", { tvLikes = it }),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statCard(R.drawable.ic_ov_plus, C_ACCENT, "Đã thêm", { tvAdded = it }),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(d(6), 0, d(6), 0) })
        stats.addView(statCard(R.drawable.ic_ov_warn, C_AMBER, "Cảnh báo", { tvWarn = it }, { warnCardBg = it }),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        full.addView(stats, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(8), 0, 0) })

        // Trạng thái đang làm (+ thanh đếm ngược ở đáy)
        val statusCard = FrameLayout(context).apply { background = roundBg(C_ACCENT and 0x00FFFFFF or 0x1A000000, 16, context, C_ACCENT and 0x00FFFFFF or 0x66000000) }
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(10), d(12), d(12))
        }
        spinner = ArcSpinner(context, C_ACCENT, C_BORDER)
        statusRow.addView(spinner, LinearLayout.LayoutParams(d(30), d(30)).apply { setMargins(0, 0, d(10), 0) })
        val statusTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tvAction = text(context, "Đang khởi động…", 13f, C_TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        tvActionSub = text(context, "", 10.5f, C_MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusTexts.addView(tvAction)
        statusTexts.addView(tvActionSub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(3), 0, 0) })
        statusRow.addView(statusTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        signalBars = SignalBars(context, C_ACCENT)
        statusRow.addView(signalBars, LinearLayout.LayoutParams(d(26), d(22)).apply { setMargins(d(8), 0, 0, 0) })
        statusCard.addView(statusRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val cdTrack = FrameLayout(context).apply { visibility = View.INVISIBLE; background = roundBg(C_BORDER and 0x00FFFFFF or 0x99000000.toInt(), 2, context) }
        val cdBar = View(context).apply { background = roundBg(C_SKY, 2, context); pivotX = 0f }
        cdFill = cdBar
        cdTrack.addView(cdBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, d(3)))
        cdGroup = cdTrack
        statusCard.addView(cdTrack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, d(3))
            .apply { gravity = Gravity.BOTTOM; setMargins(d(12), 0, d(12), d(5)) })
        full.addView(statusCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(8), 0, 0) })

        // Nhật ký hoạt động
        val logCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(9), d(12), d(8))
            background = roundBg(C_CARD2, 16, context, C_BORDER)
        }
        val logHeader = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(icon(context, R.drawable.ic_ov_clock, C_ACCENT, 16), LinearLayout.LayoutParams(d(16), d(16)).apply { setMargins(0, 0, d(7), 0) })
        logHeader.addView(text(context, "Nhật ký hoạt động", 12.5f, C_TEXT, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLogHint = text(context, "Xem tất cả  ›", 10.5f, C_BLUE)
        logHeader.addView(tvLogHint)
        logHeader.setOnClickListener { toggleLogSize() }
        logCard.addView(logHeader)
        logCard.addView(View(context).apply { setBackgroundColor(C_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(1)).apply { setMargins(0, d(7), 0, d(3)) })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        logList = list
        // đường nối dọc giữa các chấm mốc (vẽ một lần cho cả danh sách; chấm nằm ở cột trái mỗi dòng)
        val line = View(context).apply { setBackgroundColor(C_ACCENT and 0x00FFFFFF or 0x66000000) }
        val content = FrameLayout(context).apply {
            addView(line, FrameLayout.LayoutParams(d(2), FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { setMargins(d(5), d(LOG_ROW_DP / 2), 0, d(LOG_ROW_DP / 2)) })
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val sv = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { toggleLogSize() }
        }
        logBox = sv
        logCard.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(LOG_ROWS_SMALL * LOG_ROW_DP + 4)))
        full.addView(logCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(8), 0, 0) })

        // Nút điều khiển: Tạm dừng / Tiếp tục (nút chính, nền chuyển màu) · Dừng
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val primary = TextView(context).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false
            setPadding(d(10), d(12), d(10), d(12))
            compoundDrawablePadding = d(7)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1E90FF.toInt(), C_ACCENT2))
                .apply { cornerRadius = d(24).toFloat() }
            pressEffect(this)
            setOnClickListener { onPauseResumeClick() }
        }
        btnPauseResume = primary
        btnRow.addView(primary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f).apply { setMargins(0, 0, d(8), 0) })
        val stop = TextView(context).apply {
            text = "Dừng"; textSize = 14f; setTextColor(C_RED); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false
            setPadding(d(10), d(12), d(10), d(12))
            compoundDrawablePadding = d(7)
            tinted(context, R.drawable.ic_ov_stop, C_RED)?.let { dr ->
                dr.setBounds(0, 0, d(14), d(14)); setCompoundDrawables(dr, null, null, null)
            }
            background = roundBg(C_CARD, 24, context, C_BORDER)
            pressEffect(this)
            setOnClickListener { onStopClick() }
        }
        btnRow.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        full.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, d(10), 0, 0) })

        // ═════ MINI ═════════════════════════════════════════════════════════
        val mini = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(10), d(12), d(10))
            background = roundBg(C_BG, 18, context, C_BORDER)
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = d(8).toFloat()
        }
        miniPanel = mini
        wrapper.addView(mini, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val miniTop = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        miniTop.addView(atLogo(context, 26).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, d(7), 0) })
        miniTop.addView(text(context, "AT PRO", 13f, C_TEXT, bold = true).apply { letterSpacing = 0.04f })
        miniTop.addView(View(context), LinearLayout.LayoutParams(d(8), 1))
        miniTop.addView(statusPill(context, withChevron = false))
        miniTop.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val tMini = text(context, "00:00", 12f, C_TEXT, bold = true, mono = true); timeTexts.add(tMini)
        miniTop.addView(tMini, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(d(6), 0, d(4), 0) })
        miniTop.addView(roundIconButton(context, R.drawable.ic_ov_chev_up) { setMode(Mode.FULL) })
        mini.addView(miniTop)
        mini.addView(progressBar(context, 5) { progressFillMini = it },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(5)).apply { setMargins(0, d(9), 0, 0) })
        val miniBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvMiniDone = text(context, "Hoàn thành: 0/0", 11.5f, C_TEXT)
        miniBottom.addView(tvMiniDone, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        miniBottom.addView(vDivider(context, 14))
        miniBottom.addView(icon(context, R.drawable.ic_ov_heart, C_PINK, 14))
        tvMiniLikes = text(context, "0", 12f, C_TEXT, bold = true)
        miniBottom.addView(tvMiniLikes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(d(5), 0, 0, 0) })
        miniBottom.addView(vDivider(context, 14))
        miniBottom.addView(icon(context, R.drawable.ic_ov_warn, C_AMBER, 14))
        tvMiniWarn = text(context, "0", 12f, C_TEXT, bold = true)
        miniBottom.addView(tvMiniWarn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(d(5), 0, 0, 0) })
        mini.addView(miniBottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, d(9), 0, 0) })

        // ═════ CHIP ═════════════════════════════════════════════════════════
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(6), d(6), d(6), d(6))
            background = roundBg(C_BG, 28, context, C_BORDER)
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = d(8).toFloat()
        }
        chipPanel = chip
        wrapper.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        chip.addView(atLogo(context, 30).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, d(8), 0) })
        val chipDot = statusDot(context)
        chip.addView(chipDot)
        val chipTv = text(context, "Đang chạy", 12f, C_TEXT, bold = true); pillTexts.add(chipTv)
        chip.addView(chipTv)
        chip.addView(vDivider(context))
        val tChip = text(context, "00:00", 12.5f, C_TEXT, bold = true, mono = true); timeTexts.add(tChip)
        chip.addView(tChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, d(6), 0) })
        chip.addView(roundIconButton(context, R.drawable.ic_ov_chev_right) { setMode(Mode.FULL) })

        // ── Kéo để di chuyển (ngưỡng 8px phân biệt kéo / chạm); chạm MINI/CHIP = mở lại FULL, nhấn giữ MINI = CHIP ──
        var dragInitX = 0; var dragInitY = 0
        var dragTouchX = 0f; var dragTouchY = 0f
        var hasDragged = false
        var downAt = 0L

        wrapper.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams)
                        ?: return@setOnTouchListener false
                    dragInitX = lp.x; dragInitY = lp.y
                    dragTouchX = event.rawX; dragTouchY = event.rawY
                    hasDragged = false; downAt = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragTouchX).toInt()
                    val dy = (event.rawY - dragTouchY).toInt()
                    if (!hasDragged && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) hasDragged = true
                    if (hasDragged) {
                        val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams)
                            ?: return@setOnTouchListener false
                        // Gravity.END: tăng lp.x = di chuyển sang TRÁI → phủ định delta X
                        lp.x = dragInitX - dx
                        lp.y = dragInitY + dy
                        windowManager?.updateViewLayout(overlayView, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasDragged) {
                        val held = System.currentTimeMillis() - downAt
                        when (mode) {
                            Mode.CHIP -> setMode(Mode.FULL)
                            Mode.MINI -> setMode(if (held >= 500L) Mode.CHIP else Mode.FULL)
                            Mode.FULL -> Unit
                        }
                    }
                    hasDragged = false; false
                }
                else -> false
            }
        }

        return wrapper
    }

    // ─────────────────────────────────────────────────────────
    //  Đổi trạng thái FULL / MINI / CHIP
    // ─────────────────────────────────────────────────────────

    private fun setMode(newMode: Mode) {
        handler.post {
            if (mode == newMode) return@post
            val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@post
            mode = newMode
            fullPanel?.visibility = if (newMode == Mode.FULL) View.VISIBLE else View.GONE
            miniPanel?.visibility = if (newMode == Mode.MINI) View.VISIBLE else View.GONE
            chipPanel?.visibility = if (newMode == Mode.CHIP) View.VISIBLE else View.GONE
            when (newMode) {
                Mode.FULL -> {
                    lp.width = panelWidthPx
                    cdGroup?.visibility = if (cdActive) View.VISIBLE else View.INVISIBLE
                    renderLogRows()                  // dựng lại nhật ký từ dữ liệu đã giữ khi thu nhỏ
                    curAction?.let { showAction(it, curActionDots) }
                    cdAnimate()
                    if (!isPaused) spinner?.start()
                }
                Mode.MINI -> {
                    lp.width = miniWidthPx
                    cdFreeze(); stopDotsAnimation(); spinner?.stop()   // thu nhỏ: dừng animator, giữ nguyên số giây còn lại
                }
                Mode.CHIP -> {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    cdFreeze(); stopDotsAnimation(); spinner?.stop()
                }
            }
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            renderAll()
            try { windowManager?.updateViewLayout(overlayView, lp) } catch (_: Exception) {}
            overlayView?.let { v -> v.alpha = 0.4f; v.animate().alpha(1f).setDuration(160L).start() }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Nút bấm
    // ─────────────────────────────────────────────────────────

    private fun onPauseResumeClick() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        if (isPaused) { engine.resume(); isPaused = false }
        else          { engine.pause();  isPaused = true  }
        refreshPauseButton()
        if (isPaused) { cdFreeze(); spinner?.stop() } else { cdAnimate(); if (mode == Mode.FULL) spinner?.start() }
    }

    private fun onStopClick() {
        TikTokAccessibilityService.instance?.engine?.stop()
    }

    /** Cập nhật mọi nơi hiển thị trạng thái chạy/tạm dừng: viên thuốc ở cả 3 trạng thái, nút chính, cột sóng. */
    private fun refreshPauseButton() {
        val color = if (isPaused) C_AMBER else C_GREEN
        val label = if (isPaused) "Tạm dừng" else "Đang chạy"
        for (tv in pillTexts) {
            tv.text = label
            // chữ của viên thuốc ở CHIP là màu trắng; hai viên còn lại theo màu trạng thái
            if (tv.currentTextColor != C_TEXT) tv.setTextColor(color)
        }
        // pillDots chứa [chấm, quầng] theo cặp
        var i = 0
        while (i + 1 < pillDots.size) {
            pillDots[i].setColor(color)
            pillDots[i + 1].setColor(color and 0x00FFFFFF or 0x33000000)
            i += 2
        }
        signalBars?.setBarColor(if (isPaused) C_DIM else C_ACCENT)
        btnPauseResume?.let { btn ->
            val ctx = btn.context
            btn.text = if (isPaused) "Tiếp tục" else "Tạm dừng"
            tinted(ctx, if (isPaused) R.drawable.ic_ov_play else R.drawable.ic_ov_pause, Color.WHITE)?.let { dr ->
                val sz = dp(ctx, 16); dr.setBounds(0, 0, sz, sz); btn.setCompoundDrawables(dr, null, null, null)
            }
        }
        if (!isPaused && !cdActive) tvActionSub?.text = ""
        else if (isPaused && !cdActive) tvActionSub?.text = "Đang tạm dừng"
    }

    // ─────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────

    private fun formatTime(secs: Long): String {
        val s = maxOf(0L, secs)
        return if (s >= 3600)
            "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else
            "%02d:%02d".format(s / 60, s % 60)
    }

    /** 8400ms → "9s"; ≥ 60s → "1:05". */
    private fun formatSecs(ms: Long): String {
        val s = (maxOf(0L, ms) + 999L) / 1000L
        return if (s >= 60L) "%d:%02d".format(s / 60, s % 60) else "${s}s"
    }

    private fun formatReward(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
