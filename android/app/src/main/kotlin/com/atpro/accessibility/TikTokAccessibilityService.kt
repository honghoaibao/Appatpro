package com.atpro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.automation.AutomationEngine
import com.atpro.automation.IFarmHost
import com.atpro.network.LanWebSocketServer
import com.atpro.data.FarmForegroundService
import com.atpro.data.LocalRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.data.TikTokDeepLinks
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * TikTokAccessibilityService — entry point tunggal cho mọi tương tác với TikTok.
 *
 * Implements [IFarmHost]: cung cấp gesture, screen dimensions, scope,
 * và các context-dependent operations cho [AutomationEngine].
 *
 * Quan trọng:
 *   - Không cache AccessibilityNodeInfo across events (stale ngay lập tức)
 *   - Không viết traversal logic trực tiếp ở đây → dùng NodeTraverser
 *   - Popup check qua PopupHandler, không inline trong service
 */
class TikTokAccessibilityService : AccessibilityService(), IFarmHost {

    companion object {
        const val TAG = "ATProService"
        @Volatile var instance: TikTokAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var engine: AutomationEngine
        private set

    // ── IFarmHost implementation ──────────────────────────────

    override val scope: CoroutineScope      get() = serviceScope
    override val screenWidth:  Int          get() = resources.displayMetrics.widthPixels
    override val screenHeight: Int          get() = resources.displayMetrics.heightPixels
    override fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    override suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val b = Rect(); node.getBoundsInScreen(b)
        return clickSuspend(b.centerX(), b.centerY())
    }

    override suspend fun clickSuspend(x: Int, y: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }

    override suspend fun swipeSuspend(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }

    override fun pressBack(): Boolean  = performGlobalAction(GLOBAL_ACTION_BACK)
    override fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun launchTikTok(): Boolean     = TikTokDeepLinks.openTikTok(this)
    override fun openTikTokSettings(): Boolean = TikTokDeepLinks.openSettings(this)

    override fun killTikTok() {
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)
                ?.killBackgroundProcesses(TikTokDeepLinks.pkg(this))
        }
    }

    override fun showFarmOverlay() { OverlayFarmMonitor.show(this) }
    override fun hideFarmOverlay() { OverlayFarmMonitor.hide() }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine   = AutomationEngine(this, LocalRepository.getInstance(this))
        Log.i(TAG, "✅ Accessibility Service connected")
        LanWebSocketServer.broadcast("serviceStatus", mapOf("status" to "connected"))
        try { startForegroundService(FarmForegroundService.buildIntent(this)) }
        catch (e: Exception) { Log.w(TAG, "startForegroundService: ${e.message}") }
        serviceScope.launch {
            runCatching { LocalRepository.getInstance(this@TikTokAccessibilityService).log("✅ AT PRO Service started") }
            delay(800)
            LanWebSocketServer.broadcast("serviceReady", mapOf("ready" to true))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (::engine.isInitialized && engine.isFarming) engine.onEvent(event)
    }

    override fun onInterrupt() {
        if (::engine.isInitialized) engine.pause()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        serviceScope.cancel()
        if (::engine.isInitialized) engine.stop()
        LanWebSocketServer.broadcast("serviceStatus", mapOf("status" to "disconnected"))
        return super.onUnbind(intent)
    }

    // ── Legacy helpers (kept for other callers) ───────────────

    /** Fire-and-forget click — dùng clickSuspend() nếu cần kết quả. */
    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null, null
        )
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400) {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
            null, null
        )
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
