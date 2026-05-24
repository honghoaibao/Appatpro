package com.atpro.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.atpro.security.AppConstants

/**
 * TikTokDeepLinks — deeplink + activity launch helpers
 * Source: TikTok AndroidManifest analysis
 */
object TikTokDeepLinks {
    const val TAG = "TikTokDeepLinks"

    // Package IDs (from AppConstants encrypted)
    fun pkg(context: Context): String = AppConstants.tiktokPackages
        .firstOrNull { isInstalled(context, it) } ?: AppConstants.tiktokPackage

    // Activities from manifest
    const val ACTIVITY_SPLASH   = "com.ss.android.ugc.aweme.splash.SplashActivity"
    const val ACTIVITY_SETTING  = "com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity"
    const val ACTIVITY_NOTIF    = "com.ss.android.ugc.aweme.notification.UserListActivity"

    // Deeplink schemes from manifest
    const val SCHEME_SNSSDK  = "snssdk1180"
    const val SCHEME_TIKTOK  = "tiktok"

    // ── Launch helpers ────────────────────────────────────────

    /** Mở TikTok về màn hình chính */
    fun openTikTok(context: Context): Boolean {
        return try {
            // Try deeplink first
            val uri = Uri.parse("$SCHEME_SNSSDK://")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent); return true
            }
            // Fallback: launch package
            context.packageManager.getLaunchIntentForPackage(pkg(context))?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it); true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "openTikTok: ${e.message}"); false
        }
    }

    /**
     * Mở Settings & Quyền riêng tư TikTok — dùng SettingContainerActivity
     * Đây là bước đầu trong flow chuyển tài khoản
     */
    fun openSettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(pkg(context), ACTIVITY_SETTING)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent); true
        } catch (e: Exception) {
            Log.e(TAG, "openSettings: ${e.message}"); false
        }
    }

    /** Mở trang thông báo TikTok */
    fun openNotifications(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(pkg(context), ACTIVITY_NOTIF)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent); true
        } catch (e: Exception) {
            // Fallback deeplink
            openDeepLink(context, "$SCHEME_TIKTOK://notifications")
        }
    }

    /** Mở Shop TikTok */
    fun openShop(context: Context): Boolean =
        openDeepLink(context, "https://shop.tiktok.com") ||
        openDeepLink(context, "$SCHEME_TIKTOK://shop")

    /** Mở deeplink bất kỳ */
    fun openDeepLink(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent); true
        } catch (e: Exception) {
            Log.e(TAG, "openDeepLink($url): ${e.message}"); false
        }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    fun getInstalledVersion(context: Context): String? = try {
        AppConstants.tiktokPackages
            .mapNotNull { pkg ->
                try { context.packageManager.getPackageInfo(pkg, 0) }
                catch (_: Exception) { null }
            }
            .firstOrNull()
            ?.versionName
    } catch (_: Exception) { null }
}
