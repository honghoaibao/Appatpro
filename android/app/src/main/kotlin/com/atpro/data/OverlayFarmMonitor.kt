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

    // ── Palette (design tokens) — v1.3.1: làm giàu màu sắc, tăng độ tương phản ──
    private const val C_BG       = 0xF50C0C1E.toInt()
    private const val C_BG2      = 0xF2161633.toInt()
    private const val C_BORDER   = 0x668B7CFF.toInt()
    private const val C_PURPLE   = 0xFF8B7FFF.toInt()
    private const val C_GREEN    = 0xFF14C98F.toInt()
    private const val C_AMBER    = 0xFFF7A93B.toInt()
    private const val C_RED      = 0xFFF34D5C.toInt()
    private const val C_TEXT     = 0xFFF3F3FA.toInt()
    private const val C_MUTED    = 0xFF9498B5.toInt()
    private const val C_DIM      = 0xFF565B75.toInt()

    // ── Log-entry colours (v1.3.1: đồng bộ với palette mới) ──
    private const val C_LOG_PURPLE = 0xFFA79DFF.toInt()   // OPEN, SWITCH, ACC, RELOAD, RECOVER
    private const val C_LOG_CYAN   = 0xFF3DDCF0.toInt()   // SCAN, LIST, CMT, FEED, FB, IG, X
    private const val C_LOG_AMBER  = 0xFFF7A93B.toInt()   // CFG, NORM, WARN, FIX, WATCH, WDG
    private const val C_LOG_GREEN  = 0xFF14C98F.toInt()   // FOUND, HOME, FOLLOW, OK, DONE
    private const val C_LOG_PINK   = 0xFFFF7AAE.toInt()   // TIM (like ❤)
    private const val C_LOG_RED    = 0xFFF34D5C.toInt()   // ERR
    private const val C_LOG_MUTED  = 0xFF787D9E.toInt()   // default / BTN / SKIP

    // ─────────────────────────────────────────────────────────
    //  Internal state
    // ─────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var overlayView:   View?           = null
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private var tvHeader:       View?     = null
    private var statusDotBg:    GradientDrawable? = null
    private var statusDotGlowBg: GradientDrawable? = null
    private var tvServiceLabel: TextView? = null   // v1.2.9: label đối diện AT PRO
    private var tvAccount:      TextView? = null
    private var tvSessionTime:  TextView? = null
    private var tvTotalTime:    TextView? = null
    // v1.2.9: Golike task mode rows
    // v1.3.2: cấu trúc lại — thêm progress bar trực quan (progressFill) + pill
    // trạng thái lỗi (tvConsecErrors đổi từ text phẳng sang pill bo tròn màu),
    // gộp 3 view lẻ vào 1 nhóm taskRows để show/hide 1 lần thay vì 3 lần.
    private var tvTaskAccount:  TextView? = null
    private var tvTaskProgress: TextView? = null
    private var progressTrack:  LinearLayout? = null   // v1.3.2: thanh tiến độ — child(0)=fill, child(1)=empty
    private var tvConsecErrors: TextView? = null
    private var taskRows:       View?     = null   // v1.3.2: nhóm 3 row trên, show/hide 1 lần
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
                farmRows?.visibility = if (isTaskMode) View.GONE else View.VISIBLE
                taskRows?.visibility = if (isTaskMode) View.VISIBLE else View.GONE
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
            statusDotGlowBg = null
            tvServiceLabel = null
            tvSessionTime  = null; tvTotalTime    = null
            tvTaskAccount  = null; tvTaskProgress = null; tvConsecErrors = null
            progressTrack  = null; taskRows       = null
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
     * `[v1.3.2]` Thêm cập nhật thanh tiến độ (progressTrack) + pill lỗi đổi màu nền/viền.
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
            tvTaskProgress?.text = "✓ $successCount / $totalCount"

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
                tv.text = "Lỗi liên tiếp: $consecErrors"
                val c = if (consecErrors > 0) C_LOG_RED else C_LOG_GREEN
                tv.setTextColor(c)
                val strokeW = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 1f, tv.context.resources.displayMetrics
                ).toInt().coerceAtLeast(1)
                (tv.background as? GradientDrawable)?.apply {
                    setColor(c and 0x00FFFFFF or 0x22000000)
                    setStroke(strokeW, c and 0x00FFFFFF or 0x55000000)
                }
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

        // ── v1.3.1: mở rộng whitelist — các tag này đã tồn tại trong log() khắp
        // codebase (recovery, Facebook/IG/X nurture, task mode, watchdog...) nhưng
        // trước đây parseLogMsg() không nhận diện → không BAO GIỜ hiện lên overlay,
        // dù vẫn ghi logcat. Người dùng chỉ thấy "action" chung chung (từ update())
        // mà không thấy các sự kiện quan trọng như đang khôi phục/đang gặp lỗi gì.

        // ACCS: sự kiện tài khoản (follow-friends popup, account-update-prompt...)
        msg.startsWith("ACCS:") ->
            LogEntry("👤 " + msg.removePrefix("ACCS:").trim().take(42), C_LOG_PURPLE)

        // AUTH: xác thực/mật mã
        msg.startsWith("AUTH:") ->
            LogEntry("🔐 " + msg.removePrefix("AUTH:").trim().take(42), C_LOG_AMBER)

        // WATCH: rời feed bất thường khi đang xem video
        msg.startsWith("WATCH:") ->
            LogEntry("👁 " + msg.removePrefix("WATCH:").trim().take(42), C_LOG_AMBER)

        // WDG-RECOVER / RECOVER: đang khôi phục về feed sau khi bị kẹt
        msg.startsWith("WDG-RECOVER:") ->
            LogEntry("🛠 " + msg.removePrefix("WDG-RECOVER:").trim().take(42), C_LOG_PURPLE, dots = true)
        msg.startsWith("RECOVER:") ->
            LogEntry("🛠 " + msg.removePrefix("RECOVER:").trim().take(42), C_LOG_PURPLE, dots = true)

        // WDG: cảnh báo watchdog chung
        msg.startsWith("WDG:") ->
            LogEntry("🐕 " + msg.removePrefix("WDG:").trim().take(42), C_LOG_AMBER)

        // POPUP / NOTIFY: xử lý popup lạ
        msg.startsWith("POPUP:") ->
            LogEntry("💬 " + msg.removePrefix("POPUP:").trim().take(42), C_LOG_AMBER)
        msg.startsWith("NOTIFY:") ->
            LogEntry("🔔 " + msg.removePrefix("NOTIFY:").trim().take(42), C_LOG_AMBER)

        // WELLBEING: màn hình nghỉ ngơi/giới hạn sử dụng của TikTok
        msg.startsWith("WELLBEING:") ->
            LogEntry("🌙 " + msg.removePrefix("WELLBEING:").trim().take(42), C_LOG_AMBER)

        // LIMIT: giới hạn tốc độ hành động (comment/like rate limit)
        msg.startsWith("LIMIT:") ->
            LogEntry("⏱ " + msg.removePrefix("LIMIT:").trim().take(42), C_LOG_AMBER)

        // CMT SKIP / LIKE SKIP: bỏ qua do rate limit
        msg.startsWith("CMT SKIP:") ->
            LogEntry("⏭ Bỏ qua bình luận (giới hạn)", C_LOG_MUTED)
        msg.startsWith("LIKE SKIP:") ->
            LogEntry("⏭ Bỏ qua thích (giới hạn)", C_LOG_MUTED)

        // SKIP: bỏ qua hành động chung
        msg.startsWith("SKIP:") ->
            LogEntry("⏭ " + msg.removePrefix("SKIP:").trim().take(42), C_LOG_MUTED)

        // DONE: hoàn tất phiên/nhiệm vụ
        msg.startsWith("DONE:") ->
            LogEntry("🏁 " + msg.removePrefix("DONE:").trim().take(42), C_LOG_GREEN, pulse = true)

        // FB REELS / FB: nuôi acc Facebook
        msg.startsWith("FB REELS:") ->
            LogEntry("🎬 " + msg.removePrefix("FB REELS:").trim().take(42), C_LOG_CYAN)
        msg.startsWith("FB:") ->
            LogEntry("📘 " + msg.removePrefix("FB:").trim().take(42), C_LOG_CYAN)

        // IG: nuôi acc Instagram
        msg.startsWith("IG:") ->
            LogEntry("📷 " + msg.removePrefix("IG:").trim().take(42), C_LOG_CYAN)

        // X: nuôi acc X/Twitter
        msg.startsWith("X:") ->
            LogEntry("🐦 " + msg.removePrefix("X:").trim().take(42), C_LOG_CYAN)

        // LIVE-FEED: phát hiện live trong feed
        msg.startsWith("LIVE-FEED:") ->
            LogEntry("🔴 " + msg.removePrefix("LIVE-FEED:").trim().take(42), C_LOG_AMBER)

        // TASK-FOLLOW / TASK-LIKE / TASK: chế độ nhiệm vụ Golike
        msg.startsWith("TASK-FOLLOW:") ->
            LogEntry("✓ " + msg.removePrefix("TASK-FOLLOW:").trim().take(42), C_LOG_GREEN, pulse = true)
        msg.startsWith("TASK-LIKE:") ->
            LogEntry("❤ " + msg.removePrefix("TASK-LIKE:").trim().take(42), C_LOG_PINK, pulse = true)
        msg.startsWith("TASK:") ->
            LogEntry("📌 " + msg.removePrefix("TASK:").trim().take(42), C_LOG_PURPLE)

        // REST: nghỉ giữa các phiên/tài khoản
        msg.startsWith("REST:") ->
            LogEntry("☕ " + msg.removePrefix("REST:").trim().take(42), C_LOG_MUTED, dots = true)

        // SEARCH / SHOP / INBOX / SAVE: hành vi nuôi acc phụ trợ
        msg.startsWith("SEARCH:") ->
            LogEntry("🔎 " + msg.removePrefix("SEARCH:").trim().take(42), C_LOG_CYAN)
        msg.startsWith("SHOP:") ->
            LogEntry("🛍 " + msg.removePrefix("SHOP:").trim().take(42), C_LOG_CYAN)
        msg.startsWith("INBOX:") ->
            LogEntry("✉ " + msg.removePrefix("INBOX:").trim().take(42), C_LOG_CYAN)
        msg.startsWith("SAVE:") ->
            LogEntry("🔖 " + msg.removePrefix("SAVE:").trim().take(42), C_LOG_GREEN, pulse = true)

        // DEV: log debug nội bộ
        msg.startsWith("DEV:") ->
            LogEntry("🧪 " + msg.removePrefix("DEV:").trim().take(42), C_LOG_MUTED)

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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                colors = intArrayOf(C_BG, C_BG2)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                gradientType = GradientDrawable.LINEAR_GRADIENT
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), C_BORDER)
            }
            // v1.3.1: elevation thật (API 21+) → Android tự đổ bóng mềm phía dưới
            // panel, tạo cảm giác "nổi" thay vì phẳng như trước.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10).toFloat()
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
        // v1.3.1: vòng glow mờ phía sau chấm trạng thái — cảm giác "đang sống" rõ hơn
        // thay vì 1 chấm đặc phẳng lì.
        val dotGlowBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C_GREEN and 0x00FFFFFF or 0x33000000)
        }.also { statusDotGlowBg = it }
        val dotGlowView = View(context).apply { background = dotGlowBg }
        val dotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(C_GREEN)
        }.also { statusDotBg = it }
        val dotView = View(context).apply { background = dotBg }
        val dotStack = FrameLayout(context)
        dotStack.addView(dotGlowView, FrameLayout.LayoutParams(dp(11), dp(11)).apply {
            gravity = Gravity.CENTER
        })
        dotStack.addView(dotView, FrameLayout.LayoutParams(dp(7), dp(7)).apply {
            gravity = Gravity.CENTER
        })
        header.addView(tvLogo)
        header.addView(dotStack, LinearLayout.LayoutParams(dp(11), dp(11)).apply {
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
            background = GradientDrawable().apply {
                colors = intArrayOf(0x00000000, C_BORDER, 0x00000000)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                gradientType = GradientDrawable.LINEAR_GRADIENT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(7), 0, dp(7)) }
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

        // ── TASK mode (v1.3.2): popup nhiệm vụ Golike TikTok — account,
        // thanh tiến độ trực quan, pill trạng thái lỗi. Gộp vào 1 taskGroup
        // để show/hide 1 lần (thay vì set visibility từng view lẻ như trước).
        val taskGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
        }

        tvTaskAccount = text("@—", 10.5f, C_TEXT).also { taskGroup.addView(it) }

        // Hàng nhãn "Nhiệm vụ" + giá trị "✓ N / Tổng M" — cùng pattern với
        // hàng "Phiên / Tổng" của FARM mode phía trên.
        val taskProgressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        val lblTask = text("Nhiệm vụ  ", 8.5f, C_DIM)
        tvTaskProgress = text("✓ 0 / 0", 9f, C_GREEN, bold = true)
        taskProgressRow.addView(lblTask)
        taskProgressRow.addView(tvTaskProgress)
        taskGroup.addView(taskProgressRow)

        // Thanh tiến độ — track mờ + fill xanh, tỉ lệ set qua layout_weight
        // (không cần đo pixel, tự co giãn đúng theo panelWidthPx hiện tại).
        val fillView = View(context).apply {
            background = GradientDrawable().apply {
                setColor(C_GREEN); cornerRadius = dp(3).toFloat()
            }
        }
        val emptyView = View(context)
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(C_BORDER and 0x00FFFFFF or 0x33000000)
                cornerRadius = dp(3).toFloat()
            }
            addView(fillView,  LinearLayout.LayoutParams(0, dp(4), 0f))
            addView(emptyView, LinearLayout.LayoutParams(0, dp(4), 1f))
        }
        progressTrack = track
        taskGroup.addView(track, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
        ).apply { setMargins(0, dp(4), 0, dp(1)) })

        // Pill trạng thái lỗi liên tiếp — nền/viền đổi màu xanh↔đỏ theo
        // consecErrors (set động trong updateTask()), thay cho text phẳng cũ.
        tvConsecErrors = text("Lỗi liên tiếp: 0", 8.5f, C_LOG_GREEN).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(C_LOG_GREEN and 0x00FFFFFF or 0x22000000)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), C_LOG_GREEN and 0x00FFFFFF or 0x55000000)
            }
        }
        taskGroup.addView(tvConsecErrors, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(5), 0, 0) })

        body.addView(taskGroup)
        taskRows = taskGroup

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
                    setColor(color and 0x00FFFFFF or 0x22000000)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), color and 0x00FFFFFF or 0x55000000)
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
        val strokeW = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)
        (btn.background as? GradientDrawable)?.apply {
            setColor(color and 0x00FFFFFF or 0x22000000)
            setStroke(strokeW, color and 0x00FFFFFF or 0x55000000)
        }
    }

    private fun refreshPauseButton() {
        val btn = btnPauseResume ?: return
        if (isPaused) {
            updateBtnStyle(btn, "Tiếp tục", C_GREEN, R.drawable.ic_ov_play)
            statusDotBg?.setColor(C_AMBER)
            statusDotGlowBg?.setColor(C_AMBER and 0x00FFFFFF or 0x33000000)
        } else {
            updateBtnStyle(btn, "Dừng", C_AMBER, R.drawable.ic_ov_pause)
            statusDotBg?.setColor(C_GREEN)
            statusDotGlowBg?.setColor(C_GREEN and 0x00FFFFFF or 0x33000000)
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
