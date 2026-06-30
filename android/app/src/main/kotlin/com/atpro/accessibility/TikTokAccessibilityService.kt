package com.atpro.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
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
        // Guard: empty or off-screen bounds → skip instead of crashing GestureDescription
        if (b.isEmpty || b.left < 0 || b.top < 0) {
            Log.w(TAG, "clickNode: invalid bounds $b — falling back to ACTION_CLICK")
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return clickSuspend(b.centerX(), b.centerY())
    }

    override suspend fun clickSuspend(x: Int, y: Int): Boolean {
        // GestureDescription throws IllegalArgumentException if path bounds are negative.
        if (x < 0 || y < 0) {
            Log.w(TAG, "clickSuspend($x, $y): negative coords — skipping gesture")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            try {
                val path    = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                    override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
                }, null)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "clickSuspend($x, $y): gesture rejected — ${e.message}")
                cont.resume(false) {}
            }
        }
    }

    override suspend fun swipeSuspend(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            Log.w(TAG, "swipeSuspend($x1,$y1→$x2,$y2): negative coords — skipping gesture")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            try {
                val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                    override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
                }, null)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "swipeSuspend($x1,$y1→$x2,$y2): gesture rejected — ${e.message}")
                cont.resume(false) {}
            }
        }
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
    override fun openDeepLink(url: String): Boolean = TikTokDeepLinks.openDeepLink(this, url)

    override suspend fun killTikTok() {
        // [FIX] killBackgroundProcesses() chỉ hoạt động với BACKGROUND process.
        // TikTok đang ở foreground → call cũ là no-op dù có permission.
        // Giải pháp: HOME trước → TikTok vào background → delay ngắn → kill.
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        delay(600)
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)
                ?.killBackgroundProcesses(TikTokDeepLinks.pkg(this))
        }
    }

    override fun showFarmOverlay(serviceLabel: String) { OverlayFarmMonitor.show(this, serviceLabel) }
    override fun hideFarmOverlay() { OverlayFarmMonitor.hide() }

    /**
     * v1.2.3 — Mở app bất kỳ theo package name.
     * v1.2.9 — Dùng REORDER_TO_FRONT thay CLEAR_TOP:
     *   Facebook FbMainTabActivity là singleTop — CLEAR_TOP gây recreate không cần thiết.
     *   REORDER_TO_FRONT đưa task hiện có lên foreground mà không phá stack.
     *   Cũng check isAppInstalled() trước để log lỗi rõ ràng hơn (app chưa cài
     *   vs <queries> thiếu khai báo — cả hai đều khiến getLaunchIntentForPackage trả null).
     */
    override fun launchApp(packageName: String): Boolean {
        val installed = try {
            packageManager.getPackageInfo(packageName, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }

        if (!installed) {
            Log.e(TAG, "launchApp($packageName): app chưa cài đặt trên thiết bị này")
            return false
        }

        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(intent)
                true
            } else {
                Log.e(TAG, "launchApp($packageName): app đã cài nhưng không có launch intent " +
                    "— kiểm tra <queries> trong AndroidManifest.xml đã khai báo package này chưa")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp($packageName): ${e.message}")
            false
        }
    }

    /**
     * v1.2.3 — Force-stop app bất kỳ. Cùng cơ chế killTikTok():
     * HOME trước (đưa app vào background) → delay ngắn → killBackgroundProcesses().
     */
    override suspend fun killApp(packageName: String) {
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        delay(600)
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        engine   = AutomationEngine(
            this,
            LocalRepository.getInstance(this),
            com.atpro.golike.GolikeRepository.getInstance(LocalRepository.getInstance(this)),
        )
        Log.i(TAG, "OK: Accessibility Service connected")
        LanWebSocketServer.broadcast("serviceStatus", mapOf("status" to "connected"))
        // NOTE: FarmForegroundService intentionally NOT started here.
        // Service starts only when DashboardViewModel.startFarm() is called.
        // Starting it here caused premature WakeLock + notification on every accessibility enable.
        // Fix: BUG-FARM-002 (2026-05-25) — session 12 regression.
        serviceScope.launch {
            runCatching { LocalRepository.getInstance(this@TikTokAccessibilityService).log("OK: AT PRO Service started") }
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
        if (x < 0 || y < 0) return
        runCatching {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
                null, null
            )
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return
        runCatching {
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
                null, null
            )
        }
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
