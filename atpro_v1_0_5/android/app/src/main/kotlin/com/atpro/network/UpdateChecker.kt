package com.atpro.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

/**
 * UpdateChecker — kiểm tra phiên bản mới từ GitHub Releases.
 *
 * API: https://api.github.com/repos/honghoaibao/Appatpro/releases/latest
 *
 * Logic:
 *  1. Fetch JSON release mới nhất từ GitHub.
 *  2. So sánh tag_name với versionName hiện tại.
 *  3. Nếu khác nhau → trả về UpdateInfo để UI hiển thị dialog.
 *  4. Nếu giống hoặc lỗi mạng → trả về null (không hiện gì).
 */
object UpdateChecker {

    private const val TAG     = "UpdateChecker"
    private const val API_URL =
        "https://api.github.com/repos/honghoaibao/Appatpro/releases/latest"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class GhAsset(
        val name:                  String = "",
        val browser_download_url:  String = "",
    )

    @Serializable
    private data class GhRelease(
        val tag_name: String       = "",
        val name:     String       = "",
        val body:     String       = "",
        val html_url: String       = "",
        val assets:   List<GhAsset> = emptyList(),
    )

    /**
     * Kiểm tra cập nhật.
     * @return [UpdateInfo] nếu có bản mới, null nếu đã mới nhất hoặc lỗi.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentTag = "v" + (context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
                ?.removePrefix("v") ?: return@withContext null)

            val rawJson = URL(API_URL).openConnection().run {
                connectTimeout = 6_000
                readTimeout    = 6_000
                setRequestProperty("Accept",     "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AtPro-Android")
                connect()
                getInputStream().bufferedReader().readText()
            }

            val release = json.decodeFromString<GhRelease>(rawJson)
            Log.d(TAG, "Latest: ${release.tag_name}, current: $currentTag")

            // Đã là bản mới nhất — không hiển thị
            if (isSameVersion(release.tag_name, currentTag)) return@withContext null

            // Ưu tiên file .apk đầu tiên trong assets; fallback về trang release
            val downloadUrl = release.assets
                .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?.browser_download_url
                ?.takeIf { it.isNotBlank() }
                ?: release.html_url

            UpdateInfo(
                tagName     = release.tag_name,
                releaseName = release.name.ifBlank { release.tag_name },
                body        = release.body.trim(),
                htmlUrl     = release.html_url,
                downloadUrl = downloadUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** So sánh hai tag (e.g. "v1.0.5" vs "v1.0.5", bỏ qua prefix "v"). */
    private fun isSameVersion(remote: String, local: String): Boolean =
        remote.trimStart('v').trim().equals(
            local.trimStart('v').trim(), ignoreCase = true
        )
}

/** Thông tin về phiên bản mới lấy từ GitHub Releases. */
data class UpdateInfo(
    /** Tag release, e.g. "v1.0.5" */
    val tagName:     String,
    /** Tên release, e.g. "AT PRO v1.0.5" */
    val releaseName: String,
    /** Nội dung changelog */
    val body:        String,
    /** Link trang release trên GitHub */
    val htmlUrl:     String,
    /** Link tải APK trực tiếp (từ assets), fallback = htmlUrl */
    val downloadUrl: String,
)
