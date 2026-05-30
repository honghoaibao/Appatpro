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
import android.widget.LinearLayout
import android.widget.TextView
import com.atpro.accessibility.TikTokAccessibilityService

/**
 * v1.1.0 OverlayFarmMonitor — floating popup hiển thị thông tin phiên farm.
 *
 * Thay đổi v1.1.0:
 *   - Countdown thời gian thực: ticker 1s tự động giảm sessionTime/totalTime
 *     mà không cần chờ mỗi vòng lặp video (~30-60s) mới cập nhật.
 *   - Thêm khu vực log 3 dòng gần nhất bên dưới action text:
 *     hiển thị phát hiện live, bỏ qua live, tim video, lỗi, v.v.
 */
object OverlayFarmMonitor {
    const val TAG = "OverlayMonitor"

    // ── Colors (đồng bộ design tokens với DashboardScreen) ──
    private const val C_BG       = 0xF01A1A2E.toInt()
    private const val C_BORDER   = 0x446C63FF.toInt()
    private const val C_PURPLE   = 0xFF6C63FF.toInt()
    private const val C_GREEN    = 0xFF10B981.toInt()
    private const val C_AMBER    = 0xFFF59E0B.toInt()
    private const val C_RED      = 0xFFEF4444.toInt()
    private const val C_TEXT     = 0xFFE5E7EB.toInt()
    private const val C_MUTED    = 0xFF9CA3AF.toInt()

    private var windowManager: WindowManager? = null
    private var overlayView: View?            = null
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private var tvHeader:      TextView? = null
    private var tvAccount:     TextView? = null
    private var tvSessionTime: TextView? = null
    private var tvTotalTime:   TextView? = null
    private var tvAction:      TextView? = null
    private var btnPauseResume: TextView? = null
    private var btnStop:       TextView? = null

    // [v1.1.0] Log area — 3 dòng gần nhất
    private var tvLog1: TextView? = null
    private var tvLog2: TextView? = null
    private var tvLog3: TextView? = null
    private val recentLogLines = ArrayDeque<String>()

    // Track trạng thái pause nội bộ
    private var isPaused: Boolean = false

    // [v1.1.0] Real-time countdown state
    private var tickerRunnable: Runnable? = null
    private var tickSessionSecs = 0L
    private var tickTotalSecs   = 0L

    // ── Public API ────────────────────────────────────────────

    fun show(context: Context) {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission"); return
        }
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
                    WindowManager.LayoutParams.WRAP_CONTENT,
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
                Log.i(TAG, "Overlay shown")
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
            tvHeader       = null; tvAccount      = null
            tvSessionTime  = null; tvTotalTime    = null
            tvAction       = null; btnPauseResume = null
            btnStop        = null
            tvLog1         = null; tvLog2         = null; tvLog3 = null
            recentLogLines.clear()
            isPaused       = false
        }
    }

    fun syncPausedState(paused: Boolean) {
        handler.post {
            isPaused = paused
            refreshPauseButton()
        }
    }

    fun setStartupStatus(msg: String) {
        handler.post {
            tvAction?.text      = if (msg.isEmpty()) "▶ Chờ..." else msg
            tvAction?.setTextColor(if (msg.isEmpty()) C_MUTED else C_AMBER)
        }
    }

    fun update(
        accountIndex:    Int,
        accountTotal:    Int,
        accountId:       String,
        sessionSecsLeft: Long,
        totalSecsLeft:   Long,
        action:          String,
    ) {
        handler.post {
            tvAccount?.text = "@$accountId  ·  $accountIndex/$accountTotal"
            tvAction?.text  = action
            tvAction?.setTextColor(C_MUTED)

            // [v1.1.0] Sync ticker state với giá trị thực từ engine
            // Chỉ reset nếu lệch >2s để tránh giật khi update liên tục
            if (kotlin.math.abs(tickSessionSecs - sessionSecsLeft) > 2L) {
                tickSessionSecs = sessionSecsLeft
                tvSessionTime?.text = formatTime(tickSessionSecs)
            }
            if (kotlin.math.abs(tickTotalSecs - totalSecsLeft) > 2L) {
                tickTotalSecs = totalSecsLeft
                tvTotalTime?.text = formatTime(tickTotalSecs)
            }

            // Khởi động ticker nếu chưa chạy
            if (tickerRunnable == null) startTicker()
        }
    }

    /**
     * v1.1.0: Thêm một dòng log vào khu vực log của overlay.
     * Giữ tối đa 3 dòng gần nhất (FIFO). Lọc bỏ log quá dài/quá noise.
     */
    fun addLog(msg: String) {
        // Bỏ qua các log noise không cần thiết trong overlay
        val skip = msg.startsWith("LIST:") || msg.startsWith("DEV:") ||
                   msg.startsWith("SAVE:") || msg.startsWith("SCAN: Lost")
        if (skip) return

        handler.post {
            // Rút gọn message cho vừa overlay nhỏ
            val short = msg
                .removePrefix("WARN: ").removePrefix("ERR: ").removePrefix("OK: ")
                .removePrefix("SKIP: ").removePrefix("LIKE: ").removePrefix("FOLLOW: ")
                .removePrefix("CMT: ").removePrefix("ACC: ").removePrefix("AUTH: ")
                .removePrefix("NOTIFY: ").removePrefix("ACCS: ").removePrefix("REST: ")
                .take(36)

            recentLogLines.addFirst(short)
            while (recentLogLines.size > 3) recentLogLines.removeLast()

            tvLog1?.text = recentLogLines.getOrNull(0) ?: ""
            tvLog2?.text = recentLogLines.getOrNull(1) ?: ""
            tvLog3?.text = recentLogLines.getOrNull(2) ?: ""
        }
    }

    // ── Real-time countdown ticker ────────────────────────────

    /**
     * v1.1.0: Ticker 1s — tự giảm sessionSecs/totalSecs và cập nhật UI.
     * Chạy liên tục sau khi update() đầu tiên được gọi, dừng khi hide().
     */
    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                if (tickSessionSecs > 0) tickSessionSecs--
                if (tickTotalSecs > 0) tickTotalSecs--
                tvSessionTime?.text = formatTime(tickSessionSecs)
                tvTotalTime?.text   = formatTime(tickTotalSecs)
                handler.postDelayed(this, 1_000)
            }
        }
        tickerRunnable = r
        handler.postDelayed(r, 1_000)
    }

    private fun stopTicker() {
        tickerRunnable?.let { handler.removeCallbacks(it) }
        tickerRunnable = null
    }

    // ── View builder ──────────────────────────────────────────

    private fun buildView(context: Context): View {
        val dp = { n: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, n.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(C_BG)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), C_BORDER)
            }
            minimumWidth = dp(160)
        }

        fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                text      = t
                textSize  = sizeSp
                setTextColor(color)
                typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            }

        // ── Header: "AT PRO  ●" ──
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val tvLogo = text("AT PRO", 9f, C_PURPLE, bold = true).apply { typeface = Typeface.DEFAULT_BOLD }
        val tvDot  = text("  ●", 9f, C_GREEN, bold = true)
        header.addView(tvLogo); tvHeader = tvDot; header.addView(tvDot)
        root.addView(header)

        fun divider() = View(context).apply {
            setBackgroundColor(C_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        root.addView(divider())

        // ── Account + progress ──
        tvAccount = text("@—  ·  —/—", 10f, C_TEXT).also { root.addView(it) }

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        val lblSession = text("Phiên  ", 9f, C_MUTED)
        tvSessionTime  = text("--:--", 9f, C_GREEN, bold = true)
        val lblSep     = text("   Tổng  ", 9f, C_MUTED)
        tvTotalTime    = text("--:--", 9f, C_TEXT)
        timeRow.addView(lblSession); timeRow.addView(tvSessionTime)
        timeRow.addView(lblSep);     timeRow.addView(tvTotalTime)
        root.addView(timeRow)

        // Action text
        tvAction = text("▶ Chờ...", 9f, C_MUTED).apply { setPadding(0, dp(3), 0, 0) }
        root.addView(tvAction)

        // [v1.1.0] Log area — 3 dòng gần nhất
        root.addView(divider())
        tvLog1 = text("", 8f, C_MUTED).also { root.addView(it) }
        tvLog2 = text("", 8f, 0xFF6B7280.toInt()).also { root.addView(it) }
        tvLog3 = text("", 8f, 0xFF4B5563.toInt()).also { root.addView(it) }

        // Divider trước nút điều khiển
        root.addView(divider())

        // ── Control buttons row ──
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeBtn(label: String, color: Int): TextView =
            TextView(context).apply {
                text     = label
                textSize = 9.5f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply {
                    setColor(color and 0x00FFFFFF or 0x1A000000)
                    cornerRadius = dp(6).toFloat()
                    setStroke(1, color and 0x00FFFFFF or 0x40000000)
                }
            }

        btnPauseResume = makeBtn("⏸ Dừng", C_AMBER).also { btn ->
            btn.setOnClickListener { onPauseResumeClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(5), 0)
            })
        }

        btnStop = makeBtn("⏹ Tắt", C_RED).also { btn ->
            btn.setOnClickListener { onStopClick() }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(btnRow)

        // ── Drag to move ──
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@setOnTouchListener false
                    initialX = lp.x; initialY = lp.y
                    touchX = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@setOnTouchListener false
                    // [v1.1.4 FIX] Gravity.END đảo ngược trục X: tăng lp.x = di chuyển sang TRÁI.
                    // Phải phủ nhận delta X để drag sang phải → popup di chuyển sang phải.
                    lp.x = initialX - (event.rawX - touchX).toInt()
                    lp.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(overlayView, lp); true
                }
                else -> false
            }
        }

        return root
    }

    // ── Button handlers ───────────────────────────────────────

    private fun onPauseResumeClick() {
        val engine = TikTokAccessibilityService.instance?.engine ?: return
        if (isPaused) { engine.resume(); isPaused = false }
        else          { engine.pause();  isPaused = true  }
        refreshPauseButton()
    }

    private fun onStopClick() {
        TikTokAccessibilityService.instance?.engine?.stop()
    }

    private fun refreshPauseButton() {
        if (isPaused) {
            btnPauseResume?.text = "▶ Tiếp"
            btnPauseResume?.setTextColor(C_GREEN)
            (btnPauseResume?.background as? GradientDrawable)?.apply {
                setColor(C_GREEN and 0x00FFFFFF or 0x1A000000)
                setStroke(1, C_GREEN and 0x00FFFFFF or 0x40000000)
            }
            tvHeader?.setTextColor(C_AMBER)
        } else {
            btnPauseResume?.text = "⏸ Dừng"
            btnPauseResume?.setTextColor(C_AMBER)
            (btnPauseResume?.background as? GradientDrawable)?.apply {
                setColor(C_AMBER and 0x00FFFFFF or 0x1A000000)
                setStroke(1, C_AMBER and 0x00FFFFFF or 0x40000000)
            }
            tvHeader?.setTextColor(C_GREEN)
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun formatTime(secs: Long): String {
        val s = maxOf(0L, secs)
        return if (s >= 3600)
            "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else
            "%02d:%02d".format(s / 60, s % 60)
    }
}
