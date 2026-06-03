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
 * v1.1.0 OverlayFarmMonitor — floating popup hiển thị thông tin phiên farm.
 *
 * Thay đổi v1.1.8:
 *   - Fix bề ngang popup: width cố định 220dp qua WindowManager.LayoutParams thay vì WRAP_CONTENT.
 *   - Minimize → hình tròn 52dp: ẩn toàn bộ panel, hiện FrameLayout oval với label "AT".
 *     Tap vào circle để restore. WindowManager.LayoutParams cập nhật khi toggle.
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
    private var tvHeader:      View?     = null   // v1.1.9: changed from TextView → status dot View
    private var statusDotBg:   GradientDrawable? = null  // v1.1.9: tintable dot background
    private var tvAccount:     TextView? = null
    private var tvSessionTime: TextView? = null
    private var tvTotalTime:   TextView? = null
    private var tvAction:      TextView? = null
    private var btnPauseResume: TextView? = null
    private var btnStop:       TextView? = null
    // [v1.1.7] Minimize support — thu gọn overlay thành chỉ còn header
    private var btnMinimize:   View?     = null   // v1.1.9: changed from TextView → ImageButton
    private var contentArea:   View?     = null
    private var isMinimized:   Boolean   = false
    // [v1.1.8] Circle minimize — ẩn toàn bộ panel, hiện hình tròn nhỏ
    private var fullPanel:     View?     = null
    private var circleView:    View?     = null
    private var panelWidthPx:  Int       = 0
    private var circleSizePx:  Int       = 0

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
                    panelWidthPx,   // [v1.1.8] Lớp ngoài cố định — không cho popup giãn ngang
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
            tvHeader       = null; tvAccount      = null; statusDotBg    = null
            tvSessionTime  = null; tvTotalTime    = null
            tvAction       = null; btnPauseResume = null
            btnStop        = null; btnMinimize    = null
            contentArea    = null; isMinimized    = false
            fullPanel      = null; circleView     = null
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
            tvAction?.text      = if (msg.isEmpty()) "Chờ..." else msg
            tvAction?.setTextColor(if (msg.isEmpty()) C_MUTED else C_AMBER)
        }
    }

    // [v1.1.8] Last-posted values — skip redundant handler.post when nothing changed
    private var lastAccountText = ""
    private var lastActionText  = ""

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
        // Skip post jika tidak ada perubahan yang berarti
        if (accountText == lastAccountText && action == lastActionText && !sessionDiff && !totalDiff) return

        lastAccountText = accountText
        lastActionText  = action

        handler.post {
            tvAccount?.text = accountText
            tvAction?.text  = action
            tvAction?.setTextColor(C_MUTED)

            // [v1.1.0] Sync ticker state với giá trị thực từ engine
            // Chỉ reset nếu lệch >2s để tránh giật khi update liên tục
            if (sessionDiff) {
                tickSessionSecs = sessionSecsLeft
                tvSessionTime?.text = formatTime(tickSessionSecs)
            }
            if (totalDiff) {
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
                   msg.startsWith("SAVE:") || msg.startsWith("SCAN: Lost") ||
                   msg.startsWith("WDG:")  || msg.startsWith("RECOVER:")   // [v1.1.8] noise giảm
        if (skip) return

        // [v1.1.8] Bỏ qua nếu trùng với dòng log cuối — tránh spam overlay
        val SHORT_MSG = msg
            .removePrefix("WARN: ").removePrefix("ERR: ").removePrefix("OK: ")
            .removePrefix("SKIP: ").removePrefix("LIKE: ").removePrefix("FOLLOW: ")
            .removePrefix("CMT: ").removePrefix("ACC: ").removePrefix("AUTH: ")
            .removePrefix("NOTIFY: ").removePrefix("ACCS: ").removePrefix("REST: ")
            .removePrefix("LIMIT: ").removePrefix("WELLBEING: ")
            .take(36)
        if (recentLogLines.firstOrNull() == SHORT_MSG) return

        handler.post {
            recentLogLines.addFirst(SHORT_MSG)
            while (recentLogLines.size > 3) recentLogLines.removeLast()

            tvLog1?.text = recentLogLines.getOrNull(0) ?: ""
            tvLog2?.text = recentLogLines.getOrNull(1) ?: ""
            tvLog3?.text = recentLogLines.getOrNull(2) ?: ""
        }
    }

    // ── Real-time countdown ticker ────────────────────────────

    /**
     * v1.1.0: Ticker — tự giảm sessionSecs/totalSecs và cập nhật UI.
     * Chạy liên tục sau khi update() đầu tiên được gọi, dừng khi hide().
     *
     * [v1.1.9] Tối ưu nhiệt/RAM: khi minimize → tick 2s thay vì 1s.
     * UI không hiển thị → cập nhật 2s/lần đủ độ chính xác, giảm 50% wake-up.
     */
    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                val intervalMs = if (isMinimized) 2_000L else 1_000L
                // [v1.1.4.1 FIX] Không đếm ngược khi đang tạm dừng.
                if (!isPaused) {
                    val dec = if (isMinimized) 2L else 1L
                    if (tickSessionSecs > 0) tickSessionSecs = maxOf(0, tickSessionSecs - dec)
                    if (tickTotalSecs > 0)   tickTotalSecs   = maxOf(0, tickTotalSecs - dec)
                    // Chỉ cập nhật TextView khi panel đang hiển thị — tránh vẽ lãng phí
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

    // ── View builder ──────────────────────────────────────────

    private fun buildView(context: Context): View {
        val dp = { n: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, n.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        // [v1.1.8] Lưu kích thước px để dùng khi cập nhật LayoutParams lúc minimize/restore
        panelWidthPx = dp(220)
        circleSizePx = dp(52)

        // ── Outer wrapper — drag target, chứa cả panel lẫn circle ──
        val wrapper = FrameLayout(context)

        // ── Inner panel (toàn bộ nội dung popup) ──
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(C_BG)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), C_BORDER)
            }
        }
        fullPanel = root
        wrapper.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Circle indicator — chỉ hiện khi thu nhỏ ──
        // [v1.1.8] Khi minimize: ẩn panel, hiện hình tròn nhỏ 52dp x 52dp.
        val circle = FrameLayout(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_BG)
                setStroke(dp(2), C_PURPLE)
            }
        }
        val circleLabel = TextView(context).apply {
            text = "AT"
            textSize = 10f
            setTextColor(C_PURPLE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        circle.addView(circleLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        circle.setOnClickListener { onMinimizeClick() }  // tap circle → restore
        circleView = circle
        wrapper.addView(circle, FrameLayout.LayoutParams(circleSizePx, circleSizePx))

        fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                text      = t
                textSize  = sizeSp
                setTextColor(color)
                typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            }

        // ── Header: "AT PRO  ●  [─]" ──
        // [v1.1.9] Dot là View hình tròn với GradientDrawable — không còn emoji "●"
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvLogo = text("AT PRO", 9f, C_PURPLE, bold = true).apply { typeface = Typeface.DEFAULT_BOLD }
        val dotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C_GREEN)
        }.also { statusDotBg = it }
        val dotView = View(context).apply { background = dotBg }
        header.addView(tvLogo)
        header.addView(dotView, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(dp(5), 0, 0, 0)
        })
        tvHeader = dotView

        val spacerH = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        header.addView(spacerH)

        // [v1.1.9] Nút minimize — ImageButton với ic_ov_min.xml thay vì text "⊟"
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

        // [v1.1.7] contentArea — toàn bộ nội dung bên dưới header
        // Ẩn/hiện cùng lúc khi minimize/restore
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentArea = body

        fun divider() = View(context).apply {
            setBackgroundColor(C_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        body.addView(divider())

        // ── Account + progress ──
        tvAccount = text("@—  ·  —/—", 10f, C_TEXT).also { body.addView(it) }

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
        body.addView(timeRow)

        // Action text
        tvAction = text("Chờ...", 9f, C_MUTED).apply { setPadding(0, dp(3), 0, 0) }
        body.addView(tvAction)

        // [v1.1.0] Log area — 3 dòng gần nhất
        body.addView(divider())
        tvLog1 = text("", 8f, C_MUTED).also { body.addView(it) }
        tvLog2 = text("", 8f, 0xFF6B7280.toInt()).also { body.addView(it) }
        tvLog3 = text("", 8f, 0xFF4B5563.toInt()).also { body.addView(it) }

        // Divider trước nút điều khiển
        body.addView(divider())

        // ── Control buttons row ──
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // [v1.1.9] makeBtn: icon vector drawable + text thay vì emoji prefix
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

        // ── Drag to move ──
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        wrapper.setOnTouchListener { _, event ->
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

        return wrapper
    }

    // ── Button handlers ───────────────────────────────────────

    // [v1.1.8] Minimize: ẩn toàn bộ panel → hiện hình tròn nhỏ; restore ngược lại.
    // Cập nhật WindowManager.LayoutParams để overlay thu/giãn đúng kích thước.
    // [v1.1.9] Khi restore: cập nhật lại time labels ngay (ticker 2s không cập nhật TextView khi minimized).
    private fun onMinimizeClick() {
        handler.post {
            isMinimized = !isMinimized
            val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@post
            if (isMinimized) {
                fullPanel?.visibility  = View.GONE
                circleView?.visibility = View.VISIBLE
                lp.width  = circleSizePx
                lp.height = circleSizePx
            } else {
                circleView?.visibility = View.GONE
                fullPanel?.visibility  = View.VISIBLE
                lp.width  = panelWidthPx
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                // Sync labels bị bỏ qua khi minimized
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

    // [v1.1.9] Helper: cập nhật icon + text + màu cho button đồng thời
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
            statusDotBg?.setColor(C_AMBER)       // dot amber = tạm dừng
        } else {
            updateBtnStyle(btn, "Dừng", C_AMBER, R.drawable.ic_ov_pause)
            statusDotBg?.setColor(C_GREEN)        // dot green = đang chạy
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
