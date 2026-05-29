package com.atpro.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.atpro.security.AppConstants

/**
 * TikTokDeepLinks — deeplink + activity launch helpers
 *
 * patch2 — Smali logic applied (BUG-SETTINGS-003):
 *   d0 = Base64.decode("c25zc2RrMTIzMzovL3NldHRpbmc=") → "snssdk1233://setting"
 *   privacyUri = Base64.decode("c25zc2RrMTIzMzovL3ByaXZhY3k=") → "snssdk1233://privacy"
 *
 *   FLAGS_DEEP = 0x14000000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP
 *     → mirror APK gốc: mở Settings & clear back-stack
 *
 *   [WARN] postDelayed 3500ms sau openSettings() xử lý ở AutomationEngine (step 0x15).
 */
object TikTokDeepLinks {
    const val TAG = "TikTokDeepLinks"

    // ── Anti-detection: URI constants decoded lazily từ Base64 ──────────────
    // Mimics smali <clinit> — tránh plain-text string scan

    /** snssdk1233://setting  (singular — từ APK smali, scheme chính) */
    private val d0: String by lazy {
        Base64.decode("c25zc2RrMTIzMzovL3NldHRpbmc=", Base64.DEFAULT)
            .toString(Charsets.UTF_8).trim()
    }

    /** snssdk1233://privacy */
    private val privacyUri: String by lazy {
        Base64.decode("c25zc2RrMTIzMzovL3ByaXZhY3k=", Base64.DEFAULT)
            .toString(Charsets.UTF_8).trim()
    }

    // ── Intent flags (smali 0x14000000) ────────────────────────────────────
    private const val FLAGS_DEEP =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    // ── Package helpers ─────────────────────────────────────────────────────
    fun pkg(context: Context): String = AppConstants.tiktokPackages
        .firstOrNull { isInstalled(context, it) } ?: AppConstants.tiktokPackage

    const val SCHEME_SNSSDK = "snssdk1180"
    const val SCHEME_TIKTOK = "tiktok"

    // ── Launch helpers ───────────────────────────────────────────────────────

    /** Mở TikTok về màn hình chính */
    fun openTikTok(context: Context): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME_SNSSDK://")).apply {
                addFlags(FLAGS_DEEP)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) { }

        try {
            val p = pkg(context)
            context.packageManager.getLaunchIntentForPackage(p)?.let {
                it.addFlags(FLAGS_DEEP)
                context.startActivity(it)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "openTikTok pkg launch: ${e.message}")
        }

        Log.e(TAG, "openTikTok: all attempts failed")
        return false
    }

    /**
     * Mở Settings TikTok.
     *
     * Thứ tự ưu tiên (patch2 — smali exact):
     *   1. d0 = snssdk1233://setting   (singular, từ APK smali)
     *   2. tiktok://settings
     *   3. snssdk1233://settings       (plural compat)
     *   4. musically://settings        (legacy alias)
     *
     * Tất cả dùng setPackage(pkg) + FLAGS_DEEP (0x14000000).
     */
    fun openSettings(context: Context): Boolean {
        val p = pkg(context)
        val schemes = listOf(
            d0,
            "$SCHEME_TIKTOK://settings",
            "snssdk1233://settings",
            "musically://settings",
        )
        for (url in schemes) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(p)
                    addFlags(FLAGS_DEEP)
                }
                context.startActivity(intent)
                Log.d(TAG, "openSettings: OK via $url (pkg=$p)")
                return true
            } catch (_: Exception) { }
        }
        Log.e(TAG, "openSettings: all schemes failed (pkg=$p)")
        return false
    }

    /**
     * Mở Privacy Settings TikTok.
     *
     * Schemes:
     *   1. snssdk1233://privacy
     *   2. tiktok://privacy
     *   3. musically://privacy
     *   fallback: snssdk1233://setting (settings chính)
     */
    fun openPrivacySettings(context: Context): Boolean {
        val p = pkg(context)
        val schemes = listOf(
            privacyUri,
            "$SCHEME_TIKTOK://privacy",
            "musically://privacy",
            d0,
            "$SCHEME_TIKTOK://settings",
        )
        for (url in schemes) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(p)
                    addFlags(FLAGS_DEEP)
                }
                context.startActivity(intent)
                Log.d(TAG, "openPrivacySettings: OK via $url (pkg=$p)")
                return true
            } catch (_: Exception) { }
        }
        Log.e(TAG, "openPrivacySettings: all schemes failed (pkg=$p)")
        return false
    }

    /** Mở trang thông báo TikTok */
    fun openNotifications(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(pkg(context), "com.ss.android.ugc.aweme.notification.UserListActivity")
                addFlags(FLAGS_DEEP)
            }
            context.startActivity(intent); true
        } catch (e: Exception) {
            openDeepLink(context, "$SCHEME_TIKTOK://notifications")
        }
    }

    /** Mở deeplink bất kỳ */
    fun openDeepLink(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(FLAGS_DEEP)
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
            .mapNotNull { p ->
                try { context.packageManager.getPackageInfo(p, 0) }
                catch (_: Exception) { null }
            }
            .firstOrNull()
            ?.versionName
    } catch (_: Exception) { null }
}
