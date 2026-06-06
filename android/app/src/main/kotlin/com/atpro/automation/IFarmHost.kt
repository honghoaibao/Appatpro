package com.atpro.automation

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope

/**
 * IFarmHost — abstraction giữa AutomationEngine và Android framework.
 *
 * Tách biệt:
 *   - Gesture/input dispatch (clickNode, swipe, pressBack, typeText)
 *   - Screen dimensions
 *   - Coroutine scope
 *   - Context-dependent operations (launchTikTok, killTikTok, overlay)
 *
 * Production: TikTokAccessibilityService implements IFarmHost
 * Unit tests:  FakeFarmHost hoặc MockK mock
 *
 * Mục đích: cho phép test AutomationEngine mà không cần Android runtime.
 */
interface IFarmHost {
    /** Scope để launch farm coroutines. Trong tests: TestScope. */
    val scope: CoroutineScope
    val screenWidth: Int
    val screenHeight: Int

    fun getRootNode(): AccessibilityNodeInfo?
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean
    suspend fun clickSuspend(x: Int, y: Int): Boolean
    suspend fun swipeSuspend(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean
    fun pressBack(): Boolean
    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean

    /** Mở TikTok về màn hình chính. Trả false nếu không cài hoặc launch fail. */
    fun launchTikTok(): Boolean
    /** Mở TikTok Settings cho account-switch flow. */
    fun openTikTokSettings(): Boolean
    /**
     * v1.2.1 Mở deeplink/URL bất kỳ (TikTok video link, profile link, v.v.).
     * Dùng trong task mode để mở link nhiệm vụ từ Golike.
     */
    fun openDeepLink(url: String): Boolean
    /**
     * Force-stop TikTok process.
     *
     * Thứ tự: HOME → delay 600ms → killBackgroundProcesses().
     * Phải là suspend để có thể delay() trước khi kill.
     * KILL_BACKGROUND_PROCESSES chỉ hoạt động với background process — HOME trước.
     */
    suspend fun killTikTok()
    /** Hiện floating overlay. OverlayFarmMonitor tự xử lý thread. */
    fun showFarmOverlay()
    /** Ẩn floating overlay. */
    fun hideFarmOverlay()
}
