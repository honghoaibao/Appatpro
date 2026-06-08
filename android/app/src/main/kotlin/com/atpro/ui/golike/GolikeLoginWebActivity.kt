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
import android.widget.Button
import android.widget.FrameLayout
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.atpro.golike.GolikeApi
import com.atpro.golike.GolikeRepository
import com.atpro.golike.GolikeResult
import com.atpro.data.LocalRepository
import kotlinx.coroutines.*
import android.util.Log

/**
 * GolikeLoginWebActivity — v1.2.2
 *
 * WebView trỏ vào https://app.golike.net cho người dùng đăng nhập bình thường.
 *
 * v1.2.2 thêm nút "Lấy ATH":
 *   1. Inject JS → extract token từ localStorage/sessionStorage
 *   2. Gửi token lên server Golike (api/users/me) để xác thực
 *   3. Nếu OK → save token + đóng activity (RESULT_OK)
 *   4. Nếu lỗi → hiển thị thông báo lỗi cho người dùng
 *
 * Auto-detection vẫn hoạt động song song qua onPageFinished.
 */
class GolikeLoginWebActivity : AppCompatActivity() {

    companion object {
        const val TAG          = "GolikeWebLogin"
        const val BASE_URL     = "https://app.golike.net"
        const val EXTRA_TOKEN  = "golike_token"
        const val RESULT_TOKEN = Activity.RESULT_FIRST_USER + 1

        private val LS_KEYS     = listOf("token", "access_token", "auth_token", "authToken", "_token")
        private val COOKIE_KEYS = listOf("token", "access_token", "Authorization")
    }

    private lateinit var webView:    WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText:  TextView
    private lateinit var athButton:   Button

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tokenFound    = false

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

    // ── Layout ────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
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
            textSize = 14f
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT,
            ).also { it.setMargins(dpToPx(16), 0, dpToPx(130), 0) }
        }

        // Nút "Lấy ATH" — v1.2.2
        athButton = Button(this).apply {
            text = "Lấy ATH"
            setTextColor(Color.BLACK)
            textSize = 12f
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#F5A623"))
            layoutParams = RelativeLayout.LayoutParams(
                dpToPx(110), dpToPx(36),
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.setMargins(0, 0, dpToPx(10), 0)
            }
            setOnClickListener { onAthButtonClick() }
        }

        header.addView(statusText)
        header.addView(athButton)

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
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls      = true
            displayZoomControls      = false
            useWideViewPort          = true
            loadWithOverviewMode     = true
            userAgentString          = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

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

    // ── Nút Lấy ATH (v1.2.2) ──────────────────────────────────────────────────

    /**
     * Người dùng bấm "Lấy ATH":
     * 1. Inject JS để đọc token từ localStorage/sessionStorage
     * 2. Bridge nhận token → gọi verifyAndSave()
     * Nếu JS không trả token → thông báo cho người dùng.
     */
    private fun onAthButtonClick() {
        if (tokenFound) return
        statusText.text = "Đang lấy token..."
        athButton.isEnabled = false
        athButton.setBackgroundColor(Color.parseColor("#888888"))

        // Inject JS rồi chờ bridge callback
        injectTokenCheckerForAth()

        // Nếu sau 4s không có token → báo lỗi
        activityScope.launch {
            delay(4_000)
            if (!tokenFound) {
                statusText.text = "Không tìm thấy token — hãy đăng nhập web trước"
                athButton.isEnabled = true
                athButton.setBackgroundColor(Color.parseColor("#F5A623"))
            }
        }
    }

    /** Inject JS extract token — gọi bridge.onAthTokenFound() thay vì onTokenFound(). */
    private fun injectTokenCheckerForAth() {
        val jsKeys = LS_KEYS.joinToString(", ") { "\"$it\"" }
        val js = """
            (function() {
                var keys = [$jsKeys];
                for (var i = 0; i < keys.length; i++) {
                    var val = localStorage.getItem(keys[i]);
                    if (val && val.length > 10) {
                        window.AndroidBridge.onAthTokenFound(val);
                        return;
                    }
                }
                for (var i = 0; i < keys.length; i++) {
                    var val = sessionStorage.getItem(keys[i]);
                    if (val && val.length > 10) {
                        window.AndroidBridge.onAthTokenFound(val);
                        return;
                    }
                }
                try {
                    var state = window.__INITIAL_STATE__ || window.__REDUX_STATE__ || {};
                    var t = state.token || (state.auth && state.auth.token) || '';
                    if (t && t.length > 10) { window.AndroidBridge.onAthTokenFound(t); return; }
                } catch(e) {}
                window.AndroidBridge.onAthTokenFound('');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Xác thực token với server Golike (api/users/me), sau đó lưu nếu OK.
     * Hiển thị kết quả trực tiếp trên statusText.
     */
    private fun verifyAndSave(rawToken: String) {
        val cleanToken = rawToken.removePrefix("Bearer ").trim()
        statusText.text = "Đang xác thực với server..."
        athButton.isEnabled = false

        activityScope.launch {
            try {
                val result = GolikeApi.getMe(cleanToken)
                when (result) {
                    is GolikeResult.Success -> {
                        val user = result.data.data
                        val name = user?.name?.ifEmpty { user.username } ?: "Golike"
                        Log.i(TAG, "ATH verify OK: $name")
                        statusText.text = "✓ Xác thực OK — $name"

                        val local = LocalRepository.getInstance(this@GolikeLoginWebActivity)
                        val repo  = GolikeRepository.getInstance(local)
                        repo.saveWebToken(cleanToken)

                        tokenFound = true
                        delay(800)
                        val intent = Intent().apply { putExtra(EXTRA_TOKEN, cleanToken) }
                        setResult(Activity.RESULT_OK, intent)
                        finish()
                    }
                    is GolikeResult.Error -> {
                        Log.w(TAG, "ATH verify failed: ${result.message}")
                        statusText.text = "Lỗi xác thực: ${result.message}"
                        athButton.isEnabled = true
                        athButton.setBackgroundColor(Color.parseColor("#F5A623"))
                        athButton.text = "Thử lại"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyAndSave error: ${e.message}")
                statusText.text = "Lỗi kết nối — kiểm tra internet"
                athButton.isEnabled = true
                athButton.setBackgroundColor(Color.parseColor("#F5A623"))
                athButton.text = "Thử lại"
            }
        }
    }

    // ── Auto-detection (giữ nguyên từ v1.2.1) ────────────────────────────────

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
                for (var i = 0; i < keys.length; i++) {
                    var val = sessionStorage.getItem(keys[i]);
                    if (val && val.length > 10) {
                        window.AndroidBridge.onTokenFound(val);
                        return;
                    }
                }
                try {
                    var state = window.__INITIAL_STATE__ || window.__REDUX_STATE__ || {};
                    var t = state.token || (state.auth && state.auth.token) || '';
                    if (t && t.length > 10) { window.AndroidBridge.onTokenFound(t); return; }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

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
        /** Auto-detection — gọi từ injectTokenChecker(). */
        @JavascriptInterface
        fun onTokenFound(token: String) {
            if (tokenFound || token.isBlank() || token.length < 10) return
            Log.d(TAG, "Token auto-detected từ JS bridge: ${token.take(20)}...")
            runOnUiThread { onTokenReceived(token) }
        }

        /** Manual ATH — gọi từ injectTokenCheckerForAth(). */
        @JavascriptInterface
        fun onAthTokenFound(token: String) {
            runOnUiThread {
                if (token.isBlank() || token.length < 10) {
                    statusText.text = "Không tìm thấy token — hãy đăng nhập web trước"
                    athButton.isEnabled = true
                    athButton.setBackgroundColor(Color.parseColor("#F5A623"))
                    athButton.text = "Lấy ATH"
                    return@runOnUiThread
                }
                Log.d(TAG, "ATH token nhận từ JS: ${token.take(20)}...")
                verifyAndSave(token)
            }
        }
    }

    // ── Token persistence (auto-detection path) ────────────────────────────────

    private fun onTokenReceived(rawToken: String) {
        if (tokenFound) return
        tokenFound = true

        val cleanToken = rawToken.removePrefix("Bearer ").trim()
        statusText.text = "Đăng nhập thành công — đang lưu..."
        athButton.isEnabled = false

        activityScope.launch {
            try {
                val local = LocalRepository.getInstance(this@GolikeLoginWebActivity)
                val repo  = GolikeRepository.getInstance(local)
                repo.saveWebToken(cleanToken)
                Log.i(TAG, "Auto token saved OK")
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
