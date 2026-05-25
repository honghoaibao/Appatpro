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
        // Attempt 1: deeplink snssdk1180://
        // On API 30+ resolveActivity() requires <queries> — but startActivity() can still succeed.
        // We try directly and catch ActivityNotFoundException instead of checking resolveActivity().
        // <queries> block in manifest (BUG-FARM-001 fix) makes both resolveActivity() and
        // getLaunchIntentForPackage() work correctly, but this try-direct approach is defensive.
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME_SNSSDK://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) { /* fall through */ }

        // Attempt 2: getLaunchIntentForPackage (works on API 30+ after <queries> fix)
        try {
            val pkg = pkg(context)
            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "openTikTok pkg launch: ${e.message}")
        }

        Log.e(TAG, "openTikTok: all attempts failed — TikTok not installed or invisible")
        return false
    }

    /**
     * Mở Settings TikTok — dùng cho account-switch flow.
     *
     * Thứ tự ưu tiên (BUG-SETTINGS-002):
     *   1. `tiktok://settings`    + setPackage(pkg)  — scheme chính, pin đúng package
     *   2. `snssdk1233://settings`+ setPackage(pkg)  — fallback build khu vực
     *   3. `musically://settings` + setPackage(pkg)  — fallback alias cũ
     *
     * ⚠️ BẮT BUỘC dùng setPackage(pkg):
     *   Nếu không có setPackage, Android xử lý deep link như Implicit Intent — có thể
     *   mở trình duyệt, hiện chooser dialog, hoặc throw ActivityNotFoundException thầm lặng.
     *   setPackage() ép hệ thống chỉ route tới DeepLinkHandlerActivity bên trong TikTok
     *   (exported=true), Activity đó mới tự mở TikTokSettingActivity (exported=false) nội bộ.
     *
     * ⛔ KHÔNG dùng setClassName(ACTIVITY_SETTING) trực tiếp:
     *   TikTokSettingActivity có android:exported="false" → SecurityException.
     */
    fun openSettings(context: Context): Boolean {
        val pkg = pkg(context)
        val schemes = listOf("$SCHEME_TIKTOK://settings", "snssdk1233://settings", "musically://settings")

        for (url in schemes) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(pkg)                      // ← Pin đúng TikTok, bỏ qua browser/chooser
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "openSettings: OK via $url (pkg=$pkg)")
                return true
            } catch (_: Exception) { /* thử scheme tiếp theo */ }
        }

        Log.e(TAG, "openSettings: all schemes failed (pkg=$pkg) — TikTok not installed or manifest changed")
        return false
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
