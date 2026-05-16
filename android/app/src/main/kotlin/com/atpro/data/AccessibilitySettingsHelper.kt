package com.atpro.data

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * AccessibilitySettingsHelper.kt — Phase 4
 * Helpers để kiểm tra và mở settings cho Accessibility + Overlay.
 * Gọi từ FlutterBridge khi Flutter yêu cầu.
 */
object AccessibilitySettingsHelper {

    /**
     * Kiểm tra AT PRO Accessibility Service có đang bật không
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    /**
     * Kiểm tra quyền hiển thị trên ứng dụng khác (overlay)
     */
    fun isOverlayGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Mở màn hình Accessibility Settings
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Mở màn hình Overlay Settings cho AT PRO
     */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Mở App Info của AT PRO trong Settings
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Kiểm tra tất cả permissions và trả về Map status
     */
    fun getPermissionStatus(context: Context): Map<String, Boolean> = mapOf(
        "accessibility" to isAccessibilityEnabled(context),
        "overlay"       to isOverlayGranted(context),
    )
}
