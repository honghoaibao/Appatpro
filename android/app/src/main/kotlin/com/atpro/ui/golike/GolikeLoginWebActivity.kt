package com.atpro.ui.golike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.atpro.golike.GolikeRepository
import com.atpro.data.LocalRepository
import kotlinx.coroutines.*
import android.util.Log

/**
 * GolikeLoginWebActivity — v1.2.1
 *
 * Thay thế login form thủ công bằng WebView trỏ vào https://app.golike.net.
 * Người dùng đăng nhập bình thường trong trình giả lập web tích hợp.
 *
 * Sau khi đăng nhập, app tự động phát hiện token qua:
 *   1. JavaScript injection đọc localStorage.getItem("token")
 *   2. CookieManager đọc cookie "token" / "access_token"
 *
 * Khi tìm thấy token → gọi GolikeRepository.saveWebToken() → finish(RESULT_OK).
 *
 * Caller nhận RESULT_OK → gọi golikeVm.refreshUserInfo() để load thông tin user.
 *
 * Intent extras đầu vào: (none — mở thẳng base URL)
 * Intent extras đầu ra:
 *   EXTRA_TOKEN (String) — token tìm được (dùng để thông báo thôi; đã lưu DB rồi)
 */
class GolikeLoginWebActivity : AppCompatActivity() {

    companion object {
        const val TAG          = "GolikeWebLogin"
        const val BASE_URL     = "https://app.golike.net"
        const val EXTRA_TOKEN  = "golike_token"
        const val RESULT_TOKEN = Activity.RESULT_FIRST_USER + 1

        // localStorage keys Golike có thể dùng (thử lần lượt)
        private val LS_KEYS = listOf("token", "access_token", "auth_token", "authToken", "_token")
        // Cookie keys
        private val COOKIE_KEYS = listOf("token", "access_token", "Authorization")
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tokenFound     = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        setupWebView()
        webView.loadUrl(BASE_URL)
    }

    override fun onDestroy() {
        activityScope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    // ── Layout (programmatic — tránh XML dependency) ──────────────────────────

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        // Header bar
        val header = RelativeLayout(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(56),
            ).also { it.gravity = android.view.Gravity.TOP }
            setBackgroundColor(Color.parseColor("#13131F"))
        }

        statusText = TextView(this).apply {
            text = "Đăng nhập Golike"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT,
            )
        }
        header.addView(statusText)

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(3),
            ).also {
                it.gravity = android.view.Gravity.TOP
                it.topMargin = dpToPx(56)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6C63FF"))
            max = 100
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).also { it.topMargin = dpToPx(56) }
        }

        root.addView(header)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            useWideViewPort      = true
            loadWithOverviewMode = true
            userAgentString      = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // JavaScript bridge để nhận token từ web page
        webView.addJavascriptInterface(TokenBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (tokenFound) return
                injectTokenChecker()
                checkCookiesForToken(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    statusText.text = "Lỗi kết nối — kiểm tra internet"
                }
            }
        }
    }

    // ── Token extraction helpers ──────────────────────────────────────────────

    /**
     * Inject JS để đọc token từ localStorage của app.golike.net.
     * Thử lần lượt các key phổ biến.
     */
    private fun injectTokenChecker() {
        val jsKeys = LS_KEYS.joinToString(", ") { "\"$it\"" }
        val js = """
            (function() {
                var keys = [$jsKeys];
                for (var i = 0; i < keys.length; i++) {
                    var val = localStorage.getItem(keys[i]);
                    if (val && val.length > 10) {
                        window.AndroidBridge.onTokenFound(val);
                        return;
                    }
                }
                // Try sessionStorage
                for (var i = 0; i < keys.length; i++) {
                    var val = sessionStorage.getItem(keys[i]);
                    if (val && val.length > 10) {
                        window.AndroidBridge.onTokenFound(val);
                        return;
                    }
                }
                // Try Redux/Vuex state injected into window
                try {
                    var state = window.__INITIAL_STATE__ || window.__REDUX_STATE__ || {};
                    var t = state.token || (state.auth && state.auth.token) || '';
                    if (t && t.length > 10) { window.AndroidBridge.onTokenFound(t); return; }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Đọc cookies của domain hiện tại và tìm token. */
    private fun checkCookiesForToken(url: String?) {
        if (url == null) return
        val domain = try { java.net.URL(url).host } catch (_: Exception) { return }
        val cookieStr = CookieManager.getInstance().getCookie(domain) ?: return
        for (key in COOKIE_KEYS) {
            val regex = Regex("(?:^|;\\s*)${Regex.escape(key)}=([^;]+)")
            val match = regex.find(cookieStr) ?: continue
            val value = match.groupValues[1].trim()
            if (value.length > 10) {
                Log.d(TAG, "Token tìm thấy trong cookie key=$key")
                onTokenReceived(value)
                return
            }
        }
    }

    // ── JavaScript → Kotlin bridge ────────────────────────────────────────────

    inner class TokenBridge {
        @JavascriptInterface
        fun onTokenFound(token: String) {
            if (tokenFound || token.isBlank() || token.length < 10) return
            Log.d(TAG, "Token nhận từ JS bridge: ${token.take(20)}...")
            runOnUiThread { onTokenReceived(token) }
        }
    }

    // ── Token persistence ──────────────────────────────────────────────────────

    private fun onTokenReceived(rawToken: String) {
        if (tokenFound) return
        tokenFound = true

        // Strip "Bearer " prefix nếu có
        val cleanToken = rawToken.removePrefix("Bearer ").trim()
        statusText.text = "Đăng nhập thành công — đang lưu..."

        activityScope.launch {
            try {
                val local = LocalRepository.getInstance(this@GolikeLoginWebActivity)
                val repo  = GolikeRepository.getInstance(local)
                repo.saveWebToken(cleanToken)
                Log.i(TAG, "Token saved OK")
                val result = Intent().apply { putExtra(EXTRA_TOKEN, cleanToken) }
                setResult(Activity.RESULT_OK, result)
            } catch (e: Exception) {
                Log.e(TAG, "saveWebToken error: ${e.message}")
                setResult(Activity.RESULT_CANCELED)
            } finally {
                finish()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
