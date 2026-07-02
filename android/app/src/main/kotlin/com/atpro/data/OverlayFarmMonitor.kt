package com.atpro.data

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.atpro.R
import com.atpro.accessibility.TikTokAccessibilityService

/**
 * v1.2.8 OverlayFarmMonitor — floating popup hiển thị thông tin phiên farm.
 *
 * Thay đổi v1.2.8:
 *   - Log màu: mỗi loại log (OPEN, SCAN, CFG, LIST, FOUND, SWITCH, ERR, …)
 *     được tô màu riêng và có hiệu ứng tương ứng (loading dots / pulse).
 *   - setStartupStatus() hiển thị các bước khởi động lên popup (trước đây bị
 *     bỏ qua hoàn toàn sau khi xoá tvAction ở v1.2.7).
 *   - addLog() whitelist mở rộng: thêm OPEN, SCAN, CFG, BTN, LIST, FOUND,
 *     NORM, RELOAD (→ SWITCH), OK (→ HOME), FIX, WARN, ERR.
 *   - Drag khi minimize: wrapper touch listener dùng threshold 8px để phân
 *     biệt drag / tap. Tap trên bubble → restore panel (thay setOnClickListener
 *     cũ bị chặn bởi wrapper consuming ACTION_DOWN).
 *   - startDotsAnimation() cập nhật cả tvUserLog lẫn tvBubbleLog.
 *   - pulseView(): hiệu ứng alpha flash cho các sự kiện thành công.
 */
object OverlayFarmMonitor {
    const val TAG = "OverlayMonitor"

    // ── Palette (design tokens) ──────────────────────────────
    private const val C_BG       = 0xF50D0D18.toInt()
    private const val C_BG2      = 0xF0141428.toInt()
    private const val C_BORDER   = 0x556C63FF.toInt()
    private const val C_PURPLE   = 0xFF7C73FF.toInt()
    private const val C_GREEN    = 0xFF10B981.toInt()
    private const val C_AMBER    = 0xFFF59E0B.toInt()
    private const val C_RED      = 0xFFEF4444.toInt()
    private const val C_TEXT     = 0xFFEEEEF5.toInt()
    private const val C_MUTED    = 0xFF8B8FA8.toInt()
    private const val C_DIM      = 0xFF4B5563.toInt()

    // ── Log-entry colours (v1.2.8) ───────────────────────────
    private const val C_LOG_PURPLE = 0xFF9D92F5.toInt()   // OPEN, SWITCH, ACC, RELOAD
    private const val C_LOG_CYAN   = 0xFF22D3EE.toInt()   // SCAN, LIST, CMT, FEED
    private const val C_LOG_AMBER  = 0xFFF59E0B.toInt()   // CFG, NORM, WARN, FIX
    private const val C_LOG_GREEN  = 0xFF10B981.toInt()   // FOUND, HOME, FOLLOW, OK
    private const val C_LOG_PINK   = 0xFFF472B6.toInt()   // TIM (like ❤)
    private const val C_LOG_RED    = 0xFFEF4444.toInt()   // ERR
    private const val C_LOG_MUTED  = 0xFF6B7280.toInt()   // default / BTN

    // ─────────────────────────────────────────────────────────
    //  Internal state
    // ─────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var overlayView:   View?           = null
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private var tvHeader:       View?     = null
    private var statusDotBg:    GradientDrawable? = null
    private var tvServiceLabel: TextView? = null   // v1.2.9: label đối diện AT PRO
    private var tvAccount:      TextView? = null
    private var tvSessionTime:  TextView? = null
    private var tvTotalTime:    TextView? = null
    // v1.2.9: Golike task mode rows
    private var tvTaskAccount:  TextView? = null
    private var tvTaskProgress: TextView? = null
    private var tvConsecErrors: TextView? = null
    private var farmRows:       View?     = null   // timeRow chỉ dùng trong FARM mode
    private var isTaskMode:     Boolean   = false
    private var btnPauseResume: TextView? = null
    private var btnStop:        TextView? = null
    private var btnMinimize:    View?     = null
    private var contentArea:    View?     = null
    private var isMinimized:    Boolean   = false
    private var fullPanel:      View?     = null
    private var circleView:     View?     = null
    private var panelWidthPx:   Int = 0
    private var circleSizePx:   Int = 0
    private var bubbleWidthPx:  Int = 0
    private var bubbleHeightPx: Int = 0

    // Log area
    private var tvUserLog:   TextView? = null
    private var tvBubbleLog: TextView? = null
    private var lastUserLog: String    = ""

    // Loading dots animation
    private var dotsRunnable: Runnable? = null
    private var dotsPhase:    Int       = 0

    private var isPaused: Boolean = false

    // Real-time countdown
    private var tickerRunnable: Runnable? = null
    private var tickSessionSecs = 0L
    private var tickTotalSecs   = 0L

    // Last-posted values — skip redundant updates
    private var lastAccountText = ""
    private var lastActionText  = ""
    private var lastShownAction = ""

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
                    x = 16; y = 120
                }

                windowManager!!.addView(overlayView, params)
                // v1.2.9: apply service label + mode after view is built
                tvServiceLabel?.text = serviceLabel
                tvServiceLabel?.visibility = if (serviceLabel.isNotEmpty()) View.VISIBLE else View.GONE
                farmRows?.visibility   = if (isTaskMode) View.GONE else View.VISIBLE
                tvTaskAccount?.visibility  = if (isTaskMode) View.VISIBLE else View.GONE
                tvTaskProgress?.visibility = if (isTaskMode) View.VISIBLE else View.GONE
                tvConsecErrors?.visibility = if (isTaskMode) View.VISIBLE else View.GONE
                Log.i(TAG, "Overlay shown [label=$serviceLabel taskMode=$isTaskMode]")
            } catch (e: Exception) {
                Log.e(TAG, "show: ${e.message}")
            }
        }
    }

    fun hide() {
        stopTicker()
        handler.post {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            overlayView    = null; windowManager  = null
            tvHeader       = null; tvAccount      = null; statusDotBg    = null
            tvServiceLabel = null
            tvSessionTime  = null; tvTotalTime    = null
            tvTaskAccount  = null; tvTaskProgress = null; tvConsecErrors = null
            farmRows       = null; isTaskMode     = false
            btnPauseResume = null
            btnStop        = null; btnMinimize    = null
            contentArea    = null; isMinimized    = false
            fullPanel      = null; circleView     = null
            tvUserLog = null; tvBubbleLog = null; lastUserLog = ""
            stopDotsAnimation()
            isPaused       = false
        }
    }

    fun syncPausedState(paused: Boolean) {
        handler.post {
            isPaused = paused
            refreshPauseButton()
        }
    }

    /**
     * v1.2.9: Cập nhật trạng thái popup khi làm nhiệm vụ Golike (isTaskMode = true).
     * Hiển thị: tên acc, số nhiệm vụ thành công / tổng, lỗi liên tiếp, và log action.
     */
    fun updateTask(
        accountId:     String,
        successCount:  Int,
        totalCount:    Int,
        consecErrors:  Int,
        action:        String = "",
    ) {
        handler.post {
            tvTaskAccount?.text  = "@$accountId"
            tvTaskProgress?.text = "✓ $successCount nhiệm vụ   |   Tổng: $totalCount"
            tvConsecErrors?.let { tv ->
                tv.text      = "Lỗi liên tiếp: $consecErrors"
                tv.setTextColor(if (consecErrors > 0) C_LOG_RED else C_LOG_GREEN)
            }
            if (action.isNotEmpty()) {
                val entry = parseLogMsg(action) ?: LogEntry(action.take(42), C_LOG_MUTED)
                stopDotsAnimation()
                tvUserLog?.setTextColor(entry.color)
                if (entry.dots) startDotsAnimation(entry.text) else {
                    tvUserLog?.text = entry.text
                    if (entry.pulse) pulseView(tvUserLog)
                }
            }
        }
    }

    fun setStartupStatus(msg: String) {
        val entry = parseStatusMsg(msg) ?: return
        showLogEntry(entry)
    }

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
                // Hành động trong farming loop → màu muted, dừng dots
                stopDotsAnimation()
                tvUserLog?.setTextColor(C_LOG_MUTED)
                tvUserLog?.text = actionClean
                tvBubbleLog?.let { b ->
                    b.text = actionClean
                    b.visibility = View.VISIBLE
                }
            }

            if (sessionDiff) {
                tickSessionSecs = sessionSecsLeft
                tvSessionTime?.text = formatTime(tickSessionSecs)
            }
            if (totalDiff) {
                tickTotalSecs = totalSecsLeft
                tvTotalTime?.text = formatTime(tickTotalSecs)
            }

            if (tickerRunnable == null) startTicker()
        }
    }

    /**
     * v1.2.8: addLog() whitelist mở rộng — thêm các prefix mới của
     * startup/switch phase. Mỗi loại có màu và hiệu ứng riêng.
     */
    fun addLog(msg: String) {
        val entry = parseLogMsg(msg) ?: return
        showLogEntry(entry)
    }

    // ─────────────────────────────────────────────────────────
    //  Log entry parsing (v1.2.8)
    // ─────────────────────────────────────────────────────────

    /** Dữ liệu một log entry: text hiển thị, màu, có dots animation, có pulse. */
    private data class LogEntry(
        val text:     String,
        val color:    Int,
        val dots:     Boolean = false,
        val pulse:    Boolean = false,
    )

    /**
     * Chuyển đổi STATUS message (từ AutomationEngine.setStatus → setStartupStatus).
     * Trả về null nếu không có gì để hiện.
     */
    private fun parseStatusMsg(msg: String): LogEntry? = when {
        msg.isBlank() -> null

        // Mở app — v1.2.9 FIX: trước đây hardcode "Đang mở TikTok" cho MỌI dịch vụ
        // (Facebook/X/Instagram/Threads/Snapchat đều show nhầm chữ TikTok vì cùng
        // dùng prefix ">>"). Giờ lấy đúng nội dung message thực tế.
        msg.startsWith(">>") ->
            LogEntry("🚀 " + msg.removePrefix(">>").trim().take(42), C_LOG_PURPLE, dots = true)

        // Chờ feed
        msg.startsWith("Chờ feed") ->
            LogEntry("⏳ Đang tải feed", C_LOG_CYAN, dots = true)

        // SCAN: đọc acc hiện tại
        msg.startsWith("SCAN:") ->
            LogEntry("👁 " + msg.removePrefix("SCAN:").trim().take(42), C_LOG_CYAN, dots = true)

        // CFG: mở danh sách acc
        msg.startsWith("CFG:") ->
            LogEntry("⚙ " + msg.removePrefix("CFG:").trim().take(42), C_LOG_AMBER, dots = true)

        // LIST: phân tích danh sách
        msg.startsWith("LIST:") ->
            LogEntry("📋 " + msg.removePrefix("LIST:").trim().take(42), C_LOG_CYAN, dots = true)

        // SWITCH: chuyển sang acc đầu
        msg.startsWith("SWITCH:") ->
            LogEntry("↩ " + msg.removePrefix("SWITCH:").trim().take(42), C_LOG_PURPLE, dots = true)

        // FIX: chuyển tạm
        msg.startsWith("FIX:") ->
            LogEntry("🔧 " + msg.removePrefix("FIX:").trim().take(42), C_LOG_AMBER)

        // WARN: cảnh báo
        msg.startsWith("WARN:") ->
            LogEntry("⚠ " + msg.removePrefix("WARN:").trim().take(42), C_LOG_AMBER)

        // ✓ Tìm thấy X tài khoản
        msg.startsWith("✓") ->
            LogEntry(msg.take(48), C_LOG_GREEN, pulse = true)

        // X tài khoản sẽ được nuôi
        msg.contains("tài khoản sẽ được nuôi") -> {
            val n = Regex("\\d+").find(msg)?.value ?: "?"
            LogEntry("✦ $n tài khoản sẽ được nuôi", C_LOG_GREEN, pulse = true)
        }

        else -> null
    }

    /**
     * Chuyển đổi LOG message (từ AutomationEngine.log → addLog).
     * Trả về null nếu không thuộc whitelist.
     */
    private fun parseLogMsg(msg: String): LogEntry? = when {

        // ── Startup / switch phase ────────────────────────────

        // OPEN: mở TikTok
        msg.startsWith("OPEN:") ->
            LogEntry("🚀 " + msg.removePrefix("OPEN:").trim().take(42), C_LOG_PURPLE, dots = true)

        // SCAN: đọc acc hiện tại
        msg.startsWith("SCAN:") ->
            LogEntry("👁 " + msg.removePrefix("SCAN:").trim().take(42), C_LOG_CYAN, dots = true)

        // CFG: mở settings
        msg.startsWith("CFG:") ->
            LogEntry("⚙ " + msg.removePrefix("CFG:").trim().take(42), C_LOG_AMBER, dots = true)

        // BTN: tìm nút chuyển đổi tài khoản
        msg.startsWith("BTN:") ->
            LogEntry("🔍 " + msg.removePrefix("BTN:").trim().take(42), C_LOG_MUTED)

        // LIST: danh sách tài khoản — trích số lượng nếu có
        msg.startsWith("LIST:") -> {
            val body = msg.removePrefix("LIST:").trim()
            val n    = Regex("(\\d+)").find(body)?.value
            if (n != null)
                LogEntry("📋 Tìm thấy $n tài khoản", C_LOG_CYAN, pulse = true)
            else
                LogEntry("📋 " + body.take(42), C_LOG_CYAN, dots = true)
        }

        // FOUND: phát hiện x tài khoản
        msg.startsWith("FOUND:") -> {
            val body = msg.removePrefix("FOUND:").trim()
            val n    = Regex("(\\d+)").find(body)?.value ?: "?"
            LogEntry("✦ Phát hiện $n tài khoản", C_LOG_GREEN, pulse = true)
        }

        // FIX: phát hiện entry không hợp lệ → X acc cần chuẩn hoá
        msg.startsWith("FIX:") && msg.contains("entry không hợp lệ") -> {
            val n = Regex("(\\d+)").find(msg)?.value ?: "?"
            LogEntry("⚠ $n tài khoản cần chuẩn hoá", C_LOG_AMBER)
        }

        // FIX: chuẩn hoá xong
        msg.startsWith("FIX:") && msg.contains("Chuẩn hoá xong") ->
            LogEntry("✓ Chuẩn hoá hoàn tất", C_LOG_GREEN, pulse = true)

        // FIX: chung
        msg.startsWith("FIX:") ->
            LogEntry("🔧 " + msg.removePrefix("FIX:").trim().take(42), C_LOG_AMBER)

        // NORM: x tài khoản cần chuẩn hoá (nếu dùng prefix NORM trong tương lai)
        msg.startsWith("NORM:") -> {
            val body = msg.removePrefix("NORM:").trim()
            LogEntry("⚠ $body".take(48), C_LOG_AMBER)
        }

        // RELOAD: chuyển tài khoản (từ switchToAccount)
        msg.startsWith("RELOAD:") -> {
            val acc = msg.removePrefix("RELOAD:")
                .removePrefix("Chuyển acc →").trim().take(24)
            LogEntry("↩ Chuyển sang $acc", C_LOG_PURPLE, dots = true)
        }

        // OK: về trang chủ
        msg.startsWith("OK:") && ("về feed" in msg || "home" in msg.lowercase()) ->
            LogEntry("🏠 Về trang chủ", C_LOG_GREEN, pulse = true)

        // SWITCH: chuyển acc (prefix mới nếu dùng trong tương lai)
        msg.startsWith("SWITCH:") ->
            LogEntry("↩ " + msg.removePrefix("SWITCH:").trim().take(42), C_LOG_PURPLE, dots = true)

        // ── Farming actions ───────────────────────────────────

        // ACC: [X/Y] @username
        msg.startsWith("ACC: ") -> {
            val body = msg.removePrefix("ACC: ").trim()
            val m    = Regex("\\[(\\d+)/(\\d+)\\]\\s*@?(\\S+)").find(body)
            if (m != null)
                LogEntry(
                    "▸ @${m.groupValues[3]} (${m.groupValues[1]}/${m.groupValues[2]})".take(48),
                    C_LOG_PURPLE,
                )
            else null
        }

        // TIM: like video
        msg.startsWith("TIM:") ->
            LogEntry("❤ Tim video", C_LOG_PINK, pulse = true)

        // FOLLOW: đã theo dõi
        msg.startsWith("FOLLOW:") && ("theo dõi" in msg.lowercase() || "follow" in msg.lowercase()) ->
            LogEntry("✓ Đã theo dõi", C_LOG_GREEN, pulse = true)

        // CMT: bình luận
        msg.startsWith("CMT:") -> {
            val body = msg.removePrefix("CMT:").removePrefix("Comment:")
                .trim().removeSurrounding("\"").take(36)
            LogEntry("💬 $body", C_LOG_CYAN)
        }

        // WARN: cảnh báo chung
        msg.startsWith("WARN:") ->
            LogEntry("⚠ " + msg.removePrefix("WARN:").trim().take(42), C_LOG_AMBER)

        // ERR: lỗi
        msg.startsWith("ERR:") ->
            LogEntry("✕ " + msg.removePrefix("ERR:").trim().take(42), C_LOG_RED)

        else -> null
    }

    /**
     * Hiển thị một LogEntry lên tvUserLog + tvBubbleLog.
     * Dedup theo lastUserLog. Không post lên handler nếu không thay đổi.
     */
    private fun showLogEntry(entry: LogEntry) {
        if (entry.text == lastUserLog) return
        lastUserLog = entry.text

        handler.post {
            stopDotsAnimation()
            tvUserLog?.setTextColor(entry.color)
            if (entry.dots) {
                startDotsAnimation(entry.text)
            } else {
                tvUserLog?.text = entry.text
                if (entry.pulse) pulseView(tvUserLog)
            }
            tvBubbleLog?.let { bubble ->
                bubble.text       = entry.text
                bubble.visibility = View.VISIBLE
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Real-time countdown ticker
    // ─────────────────────────────────────────────────────────

    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                val intervalMs = if (isMinimized) 2_000L else 1_000L
                if (!isPaused) {
                    val dec = if (isMinimized) 2L else 1L
                    if (tickSessionSecs > 0) tickSessionSecs = maxOf(0, tickSessionSecs - dec)
                    if (tickTotalSecs > 0)   tickTotalSecs   = maxOf(0, tickTotalSecs - dec)
                    if (!isMinimized) {
                        tvSessionTime?.text = formatTime(tickSessionSecs)
                        tvTotalTime?.text   = formatTime(tickTotalSecs)
                    }
                }
                handler.postDelayed(this, intervalMs)
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
    //  Loading dots animation (v1.2.8: cập nhật cả tvUserLog + tvBubbleLog)
    // ─────────────────────────────────────────────────────────

    private fun startDotsAnimation(baseText: String) {
        stopDotsAnimation()
        dotsPhase = 0
        val dots = arrayOf("", ".", "..", "...")
        val r = object : Runnable {
            override fun run() {
                val s = "$baseText${dots[dotsPhase % 4]}"
                tvUserLog?.text = s
                tvBubbleLog?.let { if (it.visibility == View.VISIBLE) it.text = s }
                dotsPhase++
                dotsRunnable = this
                handler.postDelayed(this, 500L)
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

    // ─────────────────────────────────────────────────────────
    //  Pulse animation — alpha flash cho sự kiện thành công
    // ─────────────────────────────────────────────────────────

    private fun pulseView(v: View?) {
        v ?: return
        v.alpha = 0.3f
        handler.postDelayed({ v.alpha = 1f }, 320L)
    }

    // ─────────────────────────────────────────────────────────
    //  View builder
    // ─────────────────────────────────────────────────────────

    private fun buildView(context: Context): View {
        val dp = { n: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, n.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        panelWidthPx   = dp(220)
        circleSizePx   = dp(52)
        bubbleWidthPx  = dp(170)
        bubbleHeightPx = circleSizePx + dp(80)

        val wrapper = FrameLayout(context)

        // ── Full panel ──────────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = GradientDrawable().apply {
                colors = intArrayOf(C_BG, C_BG2)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                gradientType = GradientDrawable.LINEAR_GRADIENT
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), C_BORDER)
            }
        }
        fullPanel = root
        wrapper.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Minimize bubble (circle + speech bubble) ────────
        val bubbleWrapper = FrameLayout(context).apply { visibility = View.GONE }

        val tvBubbleLogView = TextView(context).apply {
            text     = ""
            textSize = 8f
            setTextColor(C_TEXT)
            gravity  = Gravity.CENTER
            maxLines = 2
            background = GradientDrawable().apply {
                setColor(C_BG2)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), C_BORDER)
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
        }
        bubbleWrapper.addView(tvBubbleLogView, FrameLayout.LayoutParams(
            dp(160), FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity      = Gravity.BOTTOM or Gravity.START
            bottomMargin = circleSizePx + dp(4)
        })
        this.tvBubbleLog = tvBubbleLogView

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_BG)
                setStroke(dp(2), C_PURPLE)
            }
            // v1.2.9: clip content (icon) to oval shape to avoid corner bleed
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        try {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val imgView = android.widget.ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            circle.addView(imgView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        } catch (_: Exception) {
            val fallback = TextView(context).apply {
                text = "AT"; textSize = 10f
                setTextColor(C_PURPLE); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            circle.addView(fallback, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER })
        }
        // v1.2.8: click circle xử lý qua wrapper touch listener (ACTION_UP + !hasDragged)
        // Không dùng setOnClickListener vì wrapper ACTION_DOWN=true chặn child click.
        bubbleWrapper.addView(circle, FrameLayout.LayoutParams(circleSizePx, circleSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })

        circleView = bubbleWrapper
        wrapper.addView(bubbleWrapper, FrameLayout.LayoutParams(
            dp(170), circleSizePx + dp(60)
        ))

        fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                text      = t
                textSize  = sizeSp
                setTextColor(color)
                typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            }

        // ── Header: "AT PRO  ●  [spacer]  [LABEL]  [─]" ────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        val tvLogo = text("AT PRO", 10f, C_PURPLE, bold = true).apply {
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        }
        val dotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(C_GREEN)
        }.also { statusDotBg = it }
        val dotView = View(context).apply { background = dotBg }
        header.addView(tvLogo)
        header.addView(dotView, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            gravity = Gravity.CENTER_VERTICAL; setMargins(dp(5), 0, 0, 0)
        })
        tvHeader = dotView

        val spacerH = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        header.addView(spacerH)

        // v1.2.9: service label đối diện AT PRO
        val tvLabel = text("", 8.5f, C_MUTED, bold = false).apply {
            visibility = View.GONE
            letterSpacing = 0.06f
        }
        tvServiceLabel = tvLabel
        header.addView(tvLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_VERTICAL; setMargins(0, 0, dp(4), 0) })

        val minBtn = ImageButton(context).apply {
            val d = ContextCompat.getDrawable(context, R.drawable.ic_ov_min)?.mutate()
            if (d != null) { DrawableCompat.setTint(d, C_MUTED); setImageDrawable(d) }
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), 0, 0, 0)
            setOnClickListener { onMinimizeClick() }
        }
        btnMinimize = minBtn
        header.addView(minBtn, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        root.addView(header)

        // ── Content body ────────────────────────────────────
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentArea = body

        fun divider() = View(context).apply {
            setBackgroundColor(C_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        body.addView(divider())

        // ── FARM mode: account row + time row ───────────────
        tvAccount = text("@—  ·  —/—", 10.5f, C_TEXT).also { body.addView(it) }

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        val lblSession = text("Phiên  ", 8.5f, C_DIM)
        tvSessionTime  = text("--:--", 9f, C_GREEN, bold = true)
        val lblSep     = text("   Tổng  ", 8.5f, C_DIM)
        tvTotalTime    = text("--:--", 9f, C_TEXT)
        timeRow.addView(lblSession); timeRow.addView(tvSessionTime)
        timeRow.addView(lblSep);     timeRow.addView(tvTotalTime)
        body.addView(timeRow)
        farmRows = timeRow  // reference để ẩn/hiện theo mode

        // ── TASK mode: account + progress + errors ───────────
        tvTaskAccount = text("@—", 10.5f, C_TEXT).apply {
            visibility = View.GONE
        }.also { body.addView(it) }

        tvTaskProgress = text("✓ 0 nhiệm vụ   |   Tổng: 0", 9f, C_GREEN).apply {
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }.also { body.addView(it) }

        tvConsecErrors = text("Lỗi liên tiếp: 0", 8.5f, C_LOG_GREEN).apply {
            setPadding(0, dp(1), 0, 0)
            visibility = View.GONE
        }.also { body.addView(it) }

        // ── Log area (v1.2.8: hỗ trợ màu động) ─────────────
        tvUserLog = TextView(context).apply {
            text     = ""
            textSize = 8.5f
            setTextColor(C_LOG_MUTED)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.ITALIC,
            )
            setPadding(0, dp(2), 0, dp(1))
        }.also { body.addView(it) }

        body.addView(divider())

        // ── Control buttons row ──────────────────────────────
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, color: Int, iconRes: Int): TextView =
            TextView(context).apply {
                text     = label
                textSize = 9.5f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                ContextCompat.getDrawable(context, iconRes)?.mutate()?.also { d ->
                    DrawableCompat.setTint(d, color)
                    val sz = dp(12)
                    d.setBounds(0, 0, sz, sz)
                    setCompoundDrawables(d, null, null, null)
                    compoundDrawablePadding = dp(3)
                }
                background = GradientDrawable().apply {
                    setColor(color and 0x00FFFFFF or 0x1A000000)
                    cornerRadius = dp(6).toFloat()
                    setStroke(1, color and 0x00FFFFFF or 0x40000000)
                }
            }

        btnPauseResume = makeBtn("Dừng", C_AMBER, R.drawable.ic_ov_pause).also { btn ->
            btn.setOnClickListener { onPauseResumeClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(5), 0)
            })
        }

        btnStop = makeBtn("Tắt", C_RED, R.drawable.ic_ov_stop).also { btn ->
            btn.setOnClickListener { onStopClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        body.addView(btnRow)
        root.addView(body)

        // ─────────────────────────────────────────────────────
        //  Drag to move — v1.2.8
        //  Dùng hasDragged threshold (8px) để phân biệt drag / tap.
        //  Tap trên bubble khi minimize → restore (ACTION_UP + !hasDragged).
        //  Khi full panel: drag hoạt động bình thường.
        //  FIX: trước đây circle.setOnClickListener bị chặn vì wrapper
        //  trả về true cho ACTION_DOWN, nên click không bao giờ tới circle.
        // ─────────────────────────────────────────────────────
        var dragInitX = 0; var dragInitY = 0
        var dragTouchX = 0f; var dragTouchY = 0f
        var hasDragged = false

        wrapper.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams)
                        ?: return@setOnTouchListener false
                    dragInitX  = lp.x; dragInitY  = lp.y
                    dragTouchX = event.rawX; dragTouchY = event.rawY
                    hasDragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragTouchX).toInt()
                    val dy = (event.rawY - dragTouchY).toInt()
                    if (!hasDragged && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        hasDragged = true
                    }
                    if (hasDragged) {
                        val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams)
                            ?: return@setOnTouchListener false
                        // Gravity.END: tăng lp.x = di chuyển sang TRÁI → phủ nhận delta X
                        lp.x = dragInitX - dx
                        lp.y = dragInitY + dy
                        windowManager?.updateViewLayout(overlayView, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Tap trên bubble khi minimize → restore panel
                    if (isMinimized && !hasDragged) onMinimizeClick()
                    hasDragged = false; false
                }
                else -> false
            }
        }

        return wrapper
    }

    // ─────────────────────────────────────────────────────────
    //  Button handlers
    // ─────────────────────────────────────────────────────────

    private fun onMinimizeClick() {
        handler.post {
            isMinimized = !isMinimized
            val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@post
            if (isMinimized) {
                fullPanel?.visibility  = View.GONE
                circleView?.visibility = View.VISIBLE
                lp.width  = bubbleWidthPx
                lp.height = bubbleHeightPx
            } else {
                circleView?.visibility = View.GONE
                fullPanel?.visibility  = View.VISIBLE
                lp.width  = panelWidthPx
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                tvSessionTime?.text = formatTime(tickSessionSecs)
                tvTotalTime?.text   = formatTime(tickTotalSecs)
            }
            try { windowManager?.updateViewLayout(overlayView, lp) } catch (_: Exception) {}
        }
    }

    private fun onPauseResumeClick() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        if (isPaused) { engine.resume(); isPaused = false }
        else          { engine.pause();  isPaused = true  }
        refreshPauseButton()
    }

    private fun onStopClick() {
        TikTokAccessibilityService.instance?.engine?.stop()
    }

    private fun updateBtnStyle(btn: TextView, label: String, color: Int, iconRes: Int) {
        btn.text = label
        btn.setTextColor(color)
        val context = btn.context
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.also { d ->
            DrawableCompat.setTint(d, color)
            val sz = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics
            ).toInt()
            d.setBounds(0, 0, sz, sz)
            btn.setCompoundDrawables(d, null, null, null)
        }
        (btn.background as? GradientDrawable)?.apply {
            setColor(color and 0x00FFFFFF or 0x1A000000)
            setStroke(1, color and 0x00FFFFFF or 0x40000000)
        }
    }

    private fun refreshPauseButton() {
        val btn = btnPauseResume ?: return
        if (isPaused) {
            updateBtnStyle(btn, "Tiếp tục", C_GREEN, R.drawable.ic_ov_play)
            statusDotBg?.setColor(C_AMBER)
        } else {
            updateBtnStyle(btn, "Dừng", C_AMBER, R.drawable.ic_ov_pause)
            statusDotBg?.setColor(C_GREEN)
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
}
