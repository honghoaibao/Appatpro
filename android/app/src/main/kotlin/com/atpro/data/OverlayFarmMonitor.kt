package com.atpro.data

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView

/**
 * OverlayFarmMonitor — floating popup hiển thị thông tin phiên farm
 * Yêu cầu: SYSTEM_ALERT_WINDOW permission
 *
 * Hiển thị:
 *   - Acc hiện tại / tổng
 *   - Thời gian còn lại phiên hiện tại
 *   - Tổng thời gian còn lại
 *   - Hành động hiện tại
 */
object OverlayFarmMonitor {
    const val TAG = "OverlayMonitor"

    private var windowManager: WindowManager? = null
    private var overlayView: View?            = null
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private var tvAccount:     TextView? = null
    private var tvSessionTime: TextView? = null
    private var tvTotalTime:   TextView? = null
    private var tvAction:      TextView? = null
    private var tvId:          TextView? = null

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
        handler.post {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            overlayView = null; windowManager = null
            tvAccount = null; tvSessionTime = null; tvTotalTime = null; tvAction = null; tvId = null
        }
    }

    fun update(
        accountIndex:  Int,
        accountTotal:  Int,
        accountId:     String,
        sessionSecsLeft: Long,
        totalSecsLeft:   Long,
        action:        String,
    ) {
        handler.post {
            tvAccount?.text     = "Acc: $accountIndex/$accountTotal"
            tvId?.text          = "ID: @$accountId"
            tvSessionTime?.text = "Phiên: ${formatTime(sessionSecsLeft)}"
            tvTotalTime?.text   = "Tổng: ${formatTime(totalSecsLeft)}"
            tvAction?.text      = "▶ $action"
        }
    }

    private fun buildView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(0xCC1A1A2E.toInt())
        }

        fun makeText(text: String, sizeSp: Float = 11f, color: Int = 0xFFE5E7EB.toInt()): TextView =
            TextView(context).apply {
                this.text      = text
                textSize       = sizeSp
                setTextColor(color)
                typeface       = android.graphics.Typeface.MONOSPACE
            }

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeText("AT PRO", 10f, 0xFF6C63FF.toInt()).also {
                it.typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(makeText(" ●", 10f, 0xFF10B981.toInt()))
        }
        root.addView(header)

        // Divider
        root.addView(View(context).apply {
            setBackgroundColor(0x336C63FF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0,6,0,6) }
        })

        tvAccount     = makeText("Acc: -/-").also     { root.addView(it) }
        tvId          = makeText("ID: -").also        { root.addView(it) }
        tvSessionTime = makeText("Phiên: --:--").also { root.addView(it) }
        tvTotalTime   = makeText("Tổng: --:--").also  { root.addView(it) }
        tvAction      = makeText("▶ Chờ...", 10f, 0xFF9CA3AF.toInt()).also { root.addView(it) }

        // Make draggable
        var initialX = 0; var initialY = 0
        var touchX   = 0f; var touchY   = 0f
        root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@setOnTouchListener false
                    initialX = lp.x; initialY = lp.y
                    touchX   = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = (overlayView?.layoutParams as? WindowManager.LayoutParams) ?: return@setOnTouchListener false
                    lp.x = initialX + (event.rawX - touchX).toInt()
                    lp.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(overlayView, lp); true
                }
                else -> false
            }
        }
        return root
    }

    private fun formatTime(secs: Long): String {
        val s = maxOf(0L, secs)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
