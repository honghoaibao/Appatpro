package com.atpro.data

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.widget.ImageButton
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
 * v1.10.0 — gọn hơn (rộng 216dp, cao ~½ bản trước) và có **thanh đếm ngược cho hành động đang chờ**
 * ([startCountdown]/[stopCountdown]: xem video, chờ tải job, nghỉ, nuôi acc...). Nhật ký còn 3 dòng, chạm để mở rộng.
 * Tiết kiệm pin: thanh đếm ngược là MỘT animator thuộc tính (không đăng chữ mỗi giây); khi thu nhỏ hoặc tạm dừng
 * thì dừng animator; khi thu nhỏ không dựng View cho nhật ký (chỉ giữ dữ liệu, dựng lại khi mở).
 *
 * v1.9.0: nhật ký trực tiếp (mọi log của engine, gộp dòng trùng ×N), khối tóm tắt theo chế độ
 * (nhiệm vụ: acc + ✓ n/N + thanh tiến độ; nuôi acc: acc + phiên/tổng), dòng "Đang làm", nút
 * Tạm dừng / Tiếp tục · Dừng.
 *
 * Popup luôn tối (nổi trên TikTok, cần đọc rõ trên mọi nền) — KHÔNG đổi theo chế độ Sáng/Tối của app.
 * Mọi thao tác View chạy trên main thread (qua [handler]); API công khai gọi được từ bất kỳ luồng nào.
 */
object OverlayFarmMonitor {
    const val TAG = "OverlayMonitor"

    // ── Palette: Sky (đen ngả xanh · xanh dương · xanh da trời · trắng) ──
    private const val C_BG     = 0xF506090F.toInt()
    private const val C_BG2    = 0xF50D1420.toInt()
    private const val C_BORDER = 0xFF1F2B44.toInt()
    private const val C_ACCENT = 0xFF5B93FF.toInt()
    private const val C_BLUE   = 0xFF7FB0FF.toInt()
    private const val C_SKY    = 0xFF4FC3FF.toInt()
    private const val C_GREEN  = 0xFF34D399.toInt()
    private const val C_AMBER  = 0xFFFBBF24.toInt()
    private const val C_RED    = 0xFFF87171.toInt()
    private const val C_PINK   = 0xFFFB7185.toInt()
    private const val C_TEXT   = 0xFFF5F9FF.toInt()
    private const val C_MUTED  = 0xFFA9B8CE.toInt()
    private const val C_DIM    = 0xFF7285A3.toInt()

    private const val PANEL_WIDTH_DP = 216
    private const val LOG_ROWS_SMALL = 3
    private const val LOG_ROWS_LARGE = 8
    private const val LOG_ROW_DP     = 15
    private const val MAX_LOG_ROWS   = 40

    // ─────────────────────────────────────────────────────────
    //  Internal state
    // ─────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var overlayView:   View?          = null
    private val handler = Handler(Looper.getMainLooper())

    // Header
    private var statusDotBg:     GradientDrawable? = null
    private var statusDotGlowBg: GradientDrawable? = null
    private var tvServiceLabel:  TextView? = null
    private var tvRunTime:       TextView? = null

    // Tóm tắt — chế độ NUÔI ACC
    private var farmRows:      View?     = null
    private var tvAccount:     TextView? = null
    private var tvSessionTime: TextView? = null
    private var tvTotalTime:   TextView? = null

    // Tóm tắt — chế độ NHIỆM VỤ
    private var taskRows:       View?         = null
    private var tvTaskAccount:  TextView?     = null
    private var tvTaskAccIdx:   TextView?     = null
    private var tvTaskProgress: TextView?     = null
    private var tvTaskReward:   TextView?     = null
    private var progressTrack:  LinearLayout? = null   // child(0) = phần đã xong, child(1) = phần còn lại
    private var tvConsecErrors: TextView?     = null
    private var tvTaskLikes:    TextView?     = null
    private var tvTaskFollows:  TextView?     = null
    private var isTaskMode:     Boolean       = false

    // Hành động + thanh đếm ngược
    private var tvAction:   TextView? = null
    private var cdGroup:    View?     = null
    private var tvCdLabel:  TextView? = null
    private var tvCdTime:   TextView? = null
    private var cdFill:     View?     = null
    private var cdActive    = false
    private var cdTotalMs   = 0L
    private var cdRemaining = 0L        // còn lại tại thời điểm cdSyncWall
    private var cdSyncWall  = 0L        // mốc đồng hồ khi animator bắt đầu chạy đoạn hiện tại
    private var cdAnimator: ObjectAnimator? = null

    // Nhật ký trực tiếp
    private class LogEntry(val icon: String, val text: String, val color: Int, val pulse: Boolean = false)
    private class LogRow(var time: String, val entry: LogEntry, var count: Int = 1) {
        var msgView:  TextView? = null
        var timeView: TextView? = null
        val line: String get() = "${entry.icon} ${entry.text}"
    }
    private val logData = ArrayDeque<LogRow>()           // luôn giữ dữ liệu (kể cả khi thu nhỏ)
    private var logList:   LinearLayout? = null
    private var logBox:    View?         = null
    private var logLarge:  Boolean       = false
    private var tvLogHint: TextView?     = null

    // Nút điều khiển + thu nhỏ
    private var btnPauseResume: TextView?  = null
    private var fullPanel:      View?      = null
    private var circleView:     View?      = null
    private var bubbleRingBg:   GradientDrawable? = null
    private var tvBubbleLog:    TextView?  = null
    private var isMinimized:    Boolean    = false
    private var isPaused:       Boolean    = false
    private var panelWidthPx:   Int = 0
    private var circleSizePx:   Int = 0
    private var bubbleWidthPx:  Int = 0
    private var bubbleHeightPx: Int = 0

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
        isTaskMode = (serviceLabel == "NV TIKTOK")
        handler.post {
            try {
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
                // v1.11.0: popup hiện dần + phóng nhẹ thay vì bật ra đột ngột
                overlayView?.let { v ->
                    v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
                }
                tvServiceLabel?.text = serviceLabel.removePrefix("NV ")
                tvServiceLabel?.visibility = if (serviceLabel.isNotEmpty()) View.VISIBLE else View.GONE
                farmRows?.visibility = if (isTaskMode) View.GONE else View.VISIBLE
                taskRows?.visibility = if (isTaskMode) View.VISIBLE else View.GONE
                tickRunSecs = 0L
                renderLogRows()
                startTicker()
                Log.i(TAG, "Overlay shown [label=$serviceLabel taskMode=$isTaskMode]")
            } catch (e: Exception) {
                Log.e(TAG, "show: ${e.message}")
            }
        }
    }

    fun hide() {
        stopTicker()
        handler.post {
            cdAnimator?.cancel(); cdAnimator = null; cdActive = false
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            overlayView = null; windowManager = null
            statusDotBg = null; statusDotGlowBg = null
            tvServiceLabel = null; tvRunTime = null
            farmRows = null; tvAccount = null; tvSessionTime = null; tvTotalTime = null
            taskRows = null; tvTaskAccount = null; tvTaskAccIdx = null; tvTaskProgress = null
            tvTaskReward = null; progressTrack = null; tvConsecErrors = null
            tvTaskLikes = null; tvTaskFollows = null; isTaskMode = false
            tvAction = null; cdGroup = null; tvCdLabel = null; tvCdTime = null; cdFill = null
            logList = null; tvLogHint = null; logBox = null; logLarge = false; logData.clear()
            btnPauseResume = null
            fullPanel = null; circleView = null; bubbleRingBg = null; tvBubbleLog = null
            isMinimized = false; isPaused = false
            lastAccountText = ""; lastActionText = ""; lastShownAction = ""
            tickSessionSecs = 0L; tickTotalSecs = 0L; tickRunSecs = 0L
            stopDotsAnimation()
        }
    }

    fun syncPausedState(paused: Boolean) {
        handler.post {
            isPaused = paused
            refreshPauseButton()
            if (paused) cdFreeze() else cdAnimate()
        }
    }

    /**
     * Chế độ NHIỆM VỤ — cập nhật khối tóm tắt: acc đang làm, tiến độ ✓ n/N, lỗi liên tiếp, tim/follow, thưởng.
     * [action] (nếu có) hiện ở dòng "Đang làm".
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
            tvTaskAccount?.text = "@${accountId.removePrefix("@")}"
            tvTaskAccIdx?.text  = if (accountTotal > 0) "$accountIndex/$accountTotal" else ""
            tvTaskProgress?.text = "✓ $successCount/$totalCount"
            tvTaskReward?.text   = if (reward > 0.0) "+${formatReward(reward)}" else ""

            progressTrack?.let { track ->
                val ratio = if (totalCount > 0)
                    (successCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
                else 0f
                (track.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.weight = ratio; track.getChildAt(0).layoutParams = it
                }
                (track.getChildAt(1)?.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.weight = 1f - ratio; track.getChildAt(1).layoutParams = it
                }
                track.requestLayout()
            }

            tvConsecErrors?.let { tv ->
                tv.text = if (maxErrors > 0) "⚠ $consecErrors/$maxErrors" else "⚠ $consecErrors"
                tv.setTextColor(when {
                    consecErrors <= 0                          -> C_DIM
                    maxErrors > 0 && consecErrors >= maxErrors -> C_RED
                    else                                       -> C_AMBER
                })
            }
            tvTaskLikes?.text   = "❤ $likes"
            tvTaskFollows?.text = "＋ $follows"

            if (action.isNotEmpty()) showAction(parseAction(action), dots = false)
        }
    }

    /** Dòng "Đang làm" từ các bước khởi động / trạng thái của engine (`setStatus`). Không đưa vào danh sách log. */
    fun setStartupStatus(msg: String) {
        if (msg.isBlank()) return
        val e = parseAction(msg)
        handler.post { showAction(e, dots = e.color != C_RED && e.color != C_GREEN) }
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
            tvAccount?.text = accountText

            val actionClean = action.trim()
            if (actionClean.isNotEmpty() && actionClean != lastShownAction) {
                lastShownAction = actionClean
                showAction(parseAction(actionClean), dots = false)
            }

            if (sessionDiff) {
                tickSessionSecs = sessionSecsLeft
                tvSessionTime?.text = formatTime(tickSessionSecs)
            }
            if (totalDiff) {
                tickTotalSecs = totalSecsLeft
                tvTotalTime?.text = formatTime(tickTotalSecs)
            }
        }
    }

    /**
     * Thêm MỘT dòng vào "Nhật ký trực tiếp". v1.11.0: câu đã được [LogPolish] viết lại cho dễ đọc; dòng chỉ để gỡ lỗi
     * (`detail`) không lên popup (vẫn có đủ ở màn Nhật ký, bật "Chi tiết").
     */
    fun addLog(msg: String) {
        val m = msg.trim()
        if (m.isEmpty()) return
        val polished = LogPolish.polish(m)
        if (polished.detail) return
        val entry = parseLog(m, polished)
        val time  = nowText()
        handler.post { appendLogRow(time, entry) }
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
            cdTotalMs   = totalMs
            cdRemaining = totalMs
            tvCdLabel?.text = label
            cdGroup?.visibility = if (overlayView != null) View.VISIBLE else View.GONE
            cdFill?.scaleX = 1f
            tvCdTime?.text = formatSecs(totalMs)
            cdAnimate()
        }
    }

    fun stopCountdown() {
        handler.post {
            cdAnimator?.cancel(); cdAnimator = null
            cdActive = false
            cdGroup?.visibility = View.GONE
        }
    }

    // ── Thanh đếm ngược: quản lý animator (chỉ chạy khi hiện + không tạm dừng) ──

    private fun cdLeftNow(): Long {
        val running = cdAnimator?.isRunning == true
        val left = if (running) cdRemaining - (System.currentTimeMillis() - cdSyncWall) else cdRemaining
        return left.coerceIn(0L, cdTotalMs)
    }

    private fun cdFreeze() {
        if (!cdActive) return
        if (cdAnimator?.isRunning == true) cdRemaining = cdLeftNow()
        cdAnimator?.cancel(); cdAnimator = null
        tvCdTime?.text = formatSecs(cdRemaining)
    }

    private fun cdAnimate() {
        if (!cdActive) return
        val fill = cdFill ?: return
        cdAnimator?.cancel(); cdAnimator = null
        val fraction = if (cdTotalMs > 0L) (cdRemaining.toFloat() / cdTotalMs.toFloat()).coerceIn(0f, 1f) else 0f
        fill.scaleX = fraction
        tvCdTime?.text = formatSecs(cdRemaining)
        if (isPaused || isMinimized || cdRemaining <= 0L) return
        cdSyncWall = System.currentTimeMillis()
        cdAnimator = ObjectAnimator.ofFloat(fill, View.SCALE_X, fraction, 0f).apply {
            duration     = cdRemaining
            interpolator = LinearInterpolator()
            start()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Phân tích log → biểu tượng + màu
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
            return LogEntry("🚀", clean(p.body), C_BLUE)
        if (msg.startsWith("✓"))
            return LogEntry("✓", clean(p.body), C_GREEN, pulse = true)
        if (msg.startsWith("Chờ feed"))
            return LogEntry("⏳", "Đang tải feed", C_SKY)

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
        return LogEntry(style?.first ?: "•", clean(p.body), color, pulse)
    }

    private fun clean(s: String): String = s.trim().take(140)

    // ─────────────────────────────────────────────────────────
    //  Hiển thị: dòng "Đang làm" + danh sách log
    // ─────────────────────────────────────────────────────────

    private fun showAction(e: LogEntry, dots: Boolean) {
        stopDotsAnimation()
        val line = "${e.icon} ${e.text}"
        tvBubbleLog?.let { b -> b.text = line; b.visibility = View.VISIBLE }
        if (isMinimized) return               // thu nhỏ: chỉ cập nhật chú thích bong bóng, không chạy hiệu ứng
        tvAction?.setTextColor(e.color)
        if (dots) {
            startDotsAnimation(line.trimEnd('.', '…', ' '))
        } else {
            tvAction?.text = line
            if (e.pulse) pulseView(tvAction)
        }
    }

    /** Thêm vào dữ liệu nhật ký (luôn) và dựng View (chỉ khi popup đang mở rộng). */
    private fun appendLogRow(time: String, e: LogEntry) {
        val last = logData.peekLast()
        if (last != null && last.line == "${e.icon} ${e.text}") {
            last.count++
            last.time = time
            last.msgView?.text = "${last.line}  ×${last.count}"
            last.timeView?.text = time
            tvBubbleLog?.let { b -> b.text = last.line; b.visibility = View.VISIBLE }
            return
        }
        val row = LogRow(time, e)
        logData.addLast(row)
        while (logData.size > MAX_LOG_ROWS) logData.removeFirst()
        tvBubbleLog?.let { b -> b.text = row.line; b.visibility = View.VISIBLE }
        if (isMinimized) return

        val list = logList ?: return
        addRowView(list, row, animate = true)
        while (list.childCount > MAX_LOG_ROWS) list.removeViewAt(0)
        if (e.pulse) pulseView(row.msgView)
        scrollLogToEnd()
    }

    private fun addRowView(list: LinearLayout, row: LogRow, animate: Boolean = false) {
        val ctx = list.context
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTime = TextView(ctx).apply {
            text = row.time; textSize = 7.5f; setTextColor(C_DIM)
            typeface = Typeface.MONOSPACE; includeFontPadding = false
        }
        val tvMsg = TextView(ctx).apply {
            text = if (row.count > 1) "${row.line}  ×${row.count}" else row.line
            textSize = 9f; setTextColor(row.entry.color)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        line.addView(tvTime, LinearLayout.LayoutParams(dp(ctx, 38), LinearLayout.LayoutParams.WRAP_CONTENT))
        line.addView(tvMsg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(line, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, LOG_ROW_DP)))
        row.msgView = tvMsg; row.timeView = tvTime
        // v1.11.0: dòng mới hiện dần + trượt nhẹ từ dưới lên (chỉ dòng mới; dựng lại hàng loạt thì không)
        if (animate) {
            line.alpha = 0f
            line.translationY = dp(ctx, 5).toFloat()
            line.animate().alpha(1f).translationY(0f).setDuration(200L).start()
        }
    }

    /** Dựng lại toàn bộ View nhật ký từ dữ liệu (khi mở rộng popup / lúc show). */
    private fun renderLogRows() {
        val list = logList ?: return
        list.removeAllViews()
        for (row in logData) { addRowView(list, row) }
        scrollLogToEnd()
    }

    private fun scrollLogToEnd() {
        val box = logBox as? android.widget.ScrollView ?: return
        box.post { box.scrollTo(0, logList?.height ?: 0) }
    }

    private fun toggleLogSize() {
        logLarge = !logLarge
        val box = logBox ?: return
        val lp = box.layoutParams
        lp.height = dp(box.context, (if (logLarge) LOG_ROWS_LARGE else LOG_ROWS_SMALL) * LOG_ROW_DP + 8)
        box.layoutParams = lp
        tvLogHint?.text = if (logLarge) "Chạm để thu gọn ▴" else "Chạm để mở rộng ▾"
        scrollLogToEnd()
    }

    // ─────────────────────────────────────────────────────────
    //  Đồng hồ thời gian thực
    // ─────────────────────────────────────────────────────────

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                // Tạm dừng / thu nhỏ: không có gì để cập nhật → thức dậy thưa hơn (tiết kiệm pin).
                val idle = isPaused || isMinimized
                if (!idle) {
                    tickRunSecs += 1L
                    if (tickSessionSecs > 0) tickSessionSecs = maxOf(0, tickSessionSecs - 1L)
                    if (tickTotalSecs > 0)   tickTotalSecs   = maxOf(0, tickTotalSecs - 1L)
                    tvRunTime?.text     = formatTime(tickRunSecs)
                    tvSessionTime?.text = formatTime(tickSessionSecs)
                    tvTotalTime?.text   = formatTime(tickTotalSecs)
                    if (cdActive) tvCdTime?.text = formatSecs(cdLeftNow())
                } else if (isMinimized && !isPaused) {
                    tickRunSecs += 3L
                    if (tickSessionSecs > 0) tickSessionSecs = maxOf(0, tickSessionSecs - 3L)
                    if (tickTotalSecs > 0)   tickTotalSecs   = maxOf(0, tickTotalSecs - 3L)
                }
                handler.postDelayed(this, if (idle) 3_000L else 1_000L)
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

    /** v1.11.0: nút co nhẹ khi nhấn, bật lại khi thả (không chặn sự kiện click). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
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
    //  View builder
    // ─────────────────────────────────────────────────────────

    private fun dp(ctx: Context, n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun buildView(context: Context): View {
        val d = { n: Int -> dp(context, n) }

        val screenW = context.resources.displayMetrics.widthPixels
        panelWidthPx   = minOf(d(PANEL_WIDTH_DP), (screenW * 0.88f).toInt())
        circleSizePx   = d(44)
        bubbleWidthPx  = d(150)
        bubbleHeightPx = circleSizePx + d(56)

        val wrapper = FrameLayout(context)

        // ── Panel đầy đủ ────────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(10), d(8), d(10), d(8))
            background = GradientDrawable().apply {
                setColor(C_BG)
                cornerRadius = d(14).toFloat()
                setStroke(d(1), C_BORDER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = d(8).toFloat()
        }
        fullPanel = root
        wrapper.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Bong bóng thu nhỏ (vòng tròn logo + chú thích log mới nhất) ──
        val bubbleWrapper = FrameLayout(context).apply { visibility = View.GONE }

        val bubbleText = TextView(context).apply {
            textSize = 8f
            setTextColor(C_TEXT)
            gravity  = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(C_BG2); cornerRadius = d(10).toFloat(); setStroke(d(1), C_BORDER)
            }
            setPadding(d(6), d(3), d(6), d(3))
            visibility = View.GONE
        }
        bubbleWrapper.addView(bubbleText, FrameLayout.LayoutParams(
            d(144), FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = circleSizePx + d(4) })
        tvBubbleLog = bubbleText

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C_BG)
            setStroke(d(2), C_GREEN)
        }
        bubbleRingBg = ring
        val circle = FrameLayout(context).apply {
            background = ring
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        try {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            circle.addView(ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(d(5), d(5), d(5), d(5))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } catch (_: Exception) {
            circle.addView(TextView(context).apply {
                text = "AT"; textSize = 10f; setTextColor(C_ACCENT)
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Chạm vào vòng tròn được xử lý ở touch listener của wrapper (ACTION_UP + !hasDragged).
        bubbleWrapper.addView(circle, FrameLayout.LayoutParams(circleSizePx, circleSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })
        circleView = bubbleWrapper
        wrapper.addView(bubbleWrapper, FrameLayout.LayoutParams(d(150), circleSizePx + d(48)))

        fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView =
            TextView(context).apply {
                text = t; textSize = sizeSp; setTextColor(color)
                typeface = when {
                    bold -> Typeface.DEFAULT_BOLD
                    mono -> Typeface.MONOSPACE
                    else -> Typeface.DEFAULT
                }
                includeFontPadding = false
            }

        // ── Header: [logo] AT PRO ● NHÃN ........ 00:00 [─] ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        ContextCompat.getDrawable(context, R.drawable.icon_app)?.let { logoDrawable ->
            val logo = ImageView(context).apply {
                setImageDrawable(logoDrawable)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, d(5).toFloat())
                    }
                }
            }
            header.addView(logo, LinearLayout.LayoutParams(d(16), d(16)).apply { setMargins(0, 0, d(5), 0) })
        }
        header.addView(text("AT PRO", 9.5f, C_TEXT, bold = true).apply { letterSpacing = 0.06f })

        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(C_GREEN and 0x00FFFFFF or 0x33000000)
        }.also { statusDotGlowBg = it }
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(C_GREEN)
        }.also { statusDotBg = it }
        val dotStack = FrameLayout(context).apply {
            addView(View(context).apply { background = glow },
                FrameLayout.LayoutParams(d(9), d(9)).apply { gravity = Gravity.CENTER })
            addView(View(context).apply { background = dot },
                FrameLayout.LayoutParams(d(6), d(6)).apply { gravity = Gravity.CENTER })
        }
        header.addView(dotStack, LinearLayout.LayoutParams(d(9), d(9)).apply {
            gravity = Gravity.CENTER_VERTICAL; setMargins(d(5), 0, 0, 0)
        })
        tvServiceLabel = text("", 8f, C_DIM).apply { visibility = View.GONE }
        header.addView(tvServiceLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(d(6), 0, 0, 0) })
        header.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        tvRunTime = text("00:00", 8.5f, C_MUTED, mono = true)
        header.addView(tvRunTime, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, d(4), 0) })

        val minBtn = ImageButton(context).apply {
            ContextCompat.getDrawable(context, R.drawable.ic_ov_min)?.mutate()?.let {
                DrawableCompat.setTint(it, C_MUTED); setImageDrawable(it)
            }
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(d(2), 0, 0, 0)
            setOnClickListener { onMinimizeClick() }
        }
        header.addView(minBtn, LinearLayout.LayoutParams(d(18), d(18)).apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(header)

        // ── Tóm tắt: hai khối, mỗi chế độ chỉ hiện một ──
        // NUÔI ACC: acc · i/n + Phiên / Tổng
        val farmGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(7), 0, 0)
        }
        tvAccount = text("@—  ·  —/—", 11f, C_TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        farmGroup.addView(tvAccount)
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, d(3), 0, 0)
        }
        tvSessionTime = text("--:--", 9f, C_GREEN, bold = true)
        tvTotalTime   = text("--:--", 9f, C_TEXT)
        timeRow.addView(text("Phiên ", 8f, C_DIM)); timeRow.addView(tvSessionTime)
        timeRow.addView(text("   Tổng ", 8f, C_DIM)); timeRow.addView(tvTotalTime)
        farmGroup.addView(timeRow)
        root.addView(farmGroup)
        farmRows = farmGroup

        // NHIỆM VỤ: acc + i/N · ✓ n/N + thưởng · thanh tiến độ · ⚠ ❤ ＋
        val taskGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(7), 0, 0)
            visibility  = View.GONE
        }
        val taskTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvTaskAccount = text("@—", 11.5f, C_TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        tvTaskAccIdx = text("", 8f, C_DIM)
        taskTop.addView(tvTaskAccount, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        taskTop.addView(tvTaskAccIdx)
        taskGroup.addView(taskTop)

        val taskMid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(3), 0, 0)
        }
        tvTaskProgress = text("✓ 0/0", 10.5f, C_GREEN, bold = true)
        tvTaskReward   = text("", 8.5f, C_AMBER)
        taskMid.addView(tvTaskProgress, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        taskMid.addView(tvTaskReward)
        taskGroup.addView(taskMid)

        val fill = View(context).apply {
            background = GradientDrawable().apply { setColor(C_GREEN); cornerRadius = d(2).toFloat() }
        }
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(C_BORDER and 0x00FFFFFF or 0x33000000); cornerRadius = d(2).toFloat()
            }
            addView(fill, LinearLayout.LayoutParams(0, d(3), 0f))
            addView(View(context), LinearLayout.LayoutParams(0, d(3), 1f))
        }
        progressTrack = track
        taskGroup.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(3))
            .apply { setMargins(0, d(4), 0, 0) })

        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, d(4), 0, 0)
        }
        tvConsecErrors = text("⚠ 0", 8.5f, C_DIM)
        tvTaskLikes    = text("❤ 0", 8.5f, C_PINK)
        tvTaskFollows  = text("＋ 0", 8.5f, C_SKY)
        chips.addView(tvTaskLikes)
        chips.addView(tvTaskFollows, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(d(8), 0, 0, 0) })
        chips.addView(tvConsecErrors, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(d(8), 0, 0, 0) })
        taskGroup.addView(chips)
        root.addView(taskGroup)
        taskRows = taskGroup

        // ── Dòng "Đang làm" ──
        tvAction = TextView(context).apply {
            textSize = 10f
            setTextColor(C_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, d(7), 0, 0)
        }
        root.addView(tvAction)

        // ── Thanh đếm ngược: [nhãn ........ 8s] + thanh mỏng ──
        val cd = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(4), 0, 0)
            visibility = View.GONE
        }
        val cdTop = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tvCdLabel = text("", 8f, C_DIM)
        tvCdTime  = text("", 8f, C_SKY, mono = true)
        cdTop.addView(tvCdLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cdTop.addView(tvCdTime)
        cd.addView(cdTop)
        val cdTrack = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(C_BORDER and 0x00FFFFFF or 0x66000000); cornerRadius = d(2).toFloat()
            }
        }
        val cdBar = View(context).apply {
            background = GradientDrawable().apply { setColor(C_SKY); cornerRadius = d(2).toFloat() }
            pivotX = 0f
        }
        cdFill = cdBar
        cdTrack.addView(cdBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, d(3)))
        cd.addView(cdTrack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(3))
            .apply { setMargins(0, d(2), 0, 0) })
        root.addView(cd)
        cdGroup = cd

        // ── Nhật ký trực tiếp: 3 dòng, chạm để mở rộng 8 dòng ──
        // v1.11.0: có tiêu đề + gợi ý để biết chạm vào được
        val logHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(2), d(8), d(2), 0)
        }
        logHeader.addView(text("Nhật ký", 8f, C_DIM, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvLogHint = text("Chạm để mở rộng ▾", 7.5f, C_DIM)
        logHeader.addView(tvLogHint)
        logHeader.setOnClickListener { toggleLogSize() }
        root.addView(logHeader)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        logList = list
        val sv = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                setColor(C_BG2); cornerRadius = d(8).toFloat(); setStroke(d(1), C_BORDER)
            }
            setPadding(d(6), d(3), d(6), d(3))
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { toggleLogSize() }
        }
        logBox = sv
        root.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, d(LOG_ROWS_SMALL * LOG_ROW_DP + 8),
        ).apply { setMargins(0, d(3), 0, 0) })

        // ── Nút điều khiển: Tạm dừng / Tiếp tục · Dừng ──
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, d(7), 0, 0)
        }

        fun makeBtn(label: String, color: Int, iconRes: Int): TextView =
            TextView(context).apply {
                text = label; textSize = 9f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.CENTER
                setPadding(d(6), d(5), d(6), d(5))
                ContextCompat.getDrawable(context, iconRes)?.mutate()?.also { dr ->
                    DrawableCompat.setTint(dr, color)
                    val sz = d(11)
                    dr.setBounds(0, 0, sz, sz)
                    setCompoundDrawables(dr, null, null, null)
                    compoundDrawablePadding = d(3)
                }
                background = GradientDrawable().apply {
                    setColor(color and 0x00FFFFFF or 0x22000000)
                    cornerRadius = d(8).toFloat()
                    setStroke(d(1), color and 0x00FFFFFF or 0x55000000)
                }
            }

        btnPauseResume = makeBtn("Tạm dừng", C_AMBER, R.drawable.ic_ov_pause).also { btn ->
            pressEffect(btn)
            btn.setOnClickListener { onPauseResumeClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, d(5), 0) })
        }
        makeBtn("Dừng", C_RED, R.drawable.ic_ov_stop).also { btn ->
            pressEffect(btn)
            btn.setOnClickListener { onStopClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(btnRow)

        // ── Kéo để di chuyển (ngưỡng 8px phân biệt kéo / chạm) ──
        var dragInitX = 0; var dragInitY = 0
        var dragTouchX = 0f; var dragTouchY = 0f
        var hasDragged = false

        wrapper.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams)
                        ?: return@setOnTouchListener false
                    dragInitX = lp.x; dragInitY = lp.y
                    dragTouchX = event.rawX; dragTouchY = event.rawY
                    hasDragged = false; true
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
                    if (isMinimized && !hasDragged) onMinimizeClick()
                    hasDragged = false; false
                }
                else -> false
            }
        }

        return wrapper
    }

    // ─────────────────────────────────────────────────────────
    //  Nút bấm
    // ─────────────────────────────────────────────────────────

    private fun onMinimizeClick() {
        handler.post {
            isMinimized = !isMinimized
            val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@post
            if (isMinimized) {
                cdFreeze()                       // thu nhỏ: dừng animator, giữ nguyên số giây còn lại
                stopDotsAnimation()
                fullPanel?.visibility  = View.GONE
                circleView?.visibility = View.VISIBLE
                lp.width  = bubbleWidthPx
                lp.height = bubbleHeightPx
            } else {
                circleView?.visibility = View.GONE
                fullPanel?.visibility  = View.VISIBLE
                lp.width  = panelWidthPx
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                tvRunTime?.text     = formatTime(tickRunSecs)
                tvSessionTime?.text = formatTime(tickSessionSecs)
                tvTotalTime?.text   = formatTime(tickTotalSecs)
                renderLogRows()                  // dựng lại nhật ký từ dữ liệu đã giữ khi thu nhỏ
                cdAnimate()
            }
            try { windowManager?.updateViewLayout(overlayView, lp) } catch (_: Exception) {}
        }
    }

    private fun onPauseResumeClick() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        if (isPaused) { engine.resume(); isPaused = false }
        else          { engine.pause();  isPaused = true  }
        refreshPauseButton()
        if (isPaused) cdFreeze() else cdAnimate()
    }

    private fun onStopClick() {
        TikTokAccessibilityService.instance?.engine?.stop()
    }

    private fun updateBtnStyle(btn: TextView, label: String, color: Int, iconRes: Int) {
        btn.text = label
        btn.setTextColor(color)
        val context = btn.context
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.also { dr ->
            DrawableCompat.setTint(dr, color)
            val sz = dp(context, 11)
            dr.setBounds(0, 0, sz, sz)
            btn.setCompoundDrawables(dr, null, null, null)
        }
        val strokeW = dp(context, 1).coerceAtLeast(1)
        (btn.background as? GradientDrawable)?.apply {
            setColor(color and 0x00FFFFFF or 0x22000000)
            setStroke(strokeW, color and 0x00FFFFFF or 0x55000000)
        }
    }

    private fun refreshPauseButton() {
        val btn = btnPauseResume ?: return
        val ringW = dp(btn.context, 2)
        if (isPaused) {
            updateBtnStyle(btn, "Tiếp tục", C_GREEN, R.drawable.ic_ov_play)
            statusDotBg?.setColor(C_AMBER)
            statusDotGlowBg?.setColor(C_AMBER and 0x00FFFFFF or 0x33000000)
            bubbleRingBg?.setStroke(ringW, C_AMBER)
        } else {
            updateBtnStyle(btn, "Tạm dừng", C_AMBER, R.drawable.ic_ov_pause)
            statusDotBg?.setColor(C_GREEN)
            statusDotGlowBg?.setColor(C_GREEN and 0x00FFFFFF or 0x33000000)
            bubbleRingBg?.setStroke(ringW, C_GREEN)
        }
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
