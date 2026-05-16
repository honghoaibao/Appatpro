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
import com.atpro.bridge.FlutterBridge
import com.atpro.data.FarmForegroundService
import com.atpro.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine

class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ATProService"
        @Volatile var instance: TikTokAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var engine: AutomationEngine
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine   = AutomationEngine(this)
        Log.i(TAG, "✅ Accessibility Service connected")
        FlutterBridge.sendEvent("serviceStatus", mapOf("status" to "connected"))
        try { startForegroundService(FarmForegroundService.buildIntent(this)) }
        catch (e: Exception) { Log.w(TAG, "startForegroundService: ${e.message}") }
        serviceScope.launch {
            runCatching {
                LocalRepository.getInstance(this@TikTokAccessibilityService)
                    .log("✅ AT PRO Service started")
            }
            delay(800)
            FlutterBridge.sendEvent("serviceReady", mapOf("ready" to true))
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
        FlutterBridge.sendEvent("serviceStatus", mapOf("status" to "disconnected"))
        return super.onUnbind(intent)
    }

    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    // ── Gestures ─────────────────────────────────────────────

    /**
     * Fix 2: click() là fire-and-forget — không trả Boolean vì dispatchGesture async.
     * Dùng clickSuspend() nếu cần biết kết quả.
     */
    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(gesture, null, null)  // fire-and-forget
    }

    /**
     * clickNode: ưu tiên performAction (sync, reliable).
     * Chỉ fallback gesture nếu node không clickable.
     */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val b = Rect()
        node.getBoundsInScreen(b)
        return clickSuspend(b.centerX(), b.centerY())
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, null, null)
    }

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun clickSuspend(x: Int, y: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }

    suspend fun swipeSuspend(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }

    val scope: CoroutineScope get() = serviceScope
}
