package com.atpro.ui.golike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.atpro.data.LocalRepository
import com.atpro.golike.GolikeApi
import com.atpro.golike.GolikeRepository
import com.atpro.golike.GolikeResult
import kotlinx.coroutines.*

/**
 * GolikeLoginWebActivity — v1.2.2 (fix ATH extraction)
 *
 * WebView → https://app.golike.net để người dùng đăng nhập.
 *
 * Chiến lược lấy token (ưu tiên từ cao → thấp):
 *   1. XHR/fetch interceptor (inject sớm từ onPageStarted) — bắt Authorization header
 *      từ MỌI API call sau khi đăng nhập → đáng tin cậy nhất với SPA / AJAX app
 *   2. Enumerate ALL localStorage + sessionStorage — tìm JWT (eyJ...) hoặc key chứa
 *      "token/auth/jwt" + recursive JSON parse
 *   3. document.cookie — non-HttpOnly cookies
 *   4. Native CookieManager — bao gồm HttpOnly cookies (chỉ Android native đọc được)
 *   5. Thử gọi trực tiếp /api/users/me qua WebView rồi parse JSON response
 */
class GolikeLoginWebActivity : AppCompatActivity() {

    companion object {
        const val TAG         = "GolikeWebLogin"
        const val BASE_URL    = "https://app.golike.net"
        const val EXTRA_TOKEN = "golike_token"

        /* Injected vào EVERY onPageStarted — intercept XHR/fetch request headers */
        private val JS_INTERCEPTOR = """
(function() {
    if (window.__glInterceptInstalled) return;
    window.__glInterceptInstalled = true;
    window.__glToken = '';

    // Intercept XHR setRequestHeader — bắt Authorization / Token header gửi lên server
    var _open   = XMLHttpRequest.prototype.open;
    var _setHdr = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function(name, val) {
        if (val && val.length > 15) {
            var n = (name || '').toLowerCase();
            if (n === 'authorization' || n === 'token' || n === 'x-auth-token') {
                window.__glToken = val.replace(/^Bearer\s+/i, '').trim();
            }
        }
        return _setHdr.apply(this, arguments);
    };

    // Intercept fetch — bắt headers trong init object
    var _fetch = window.fetch;
    if (typeof _fetch === 'function') {
        window.fetch = function(input, init) {
            try {
                var h = (init && init.headers) ? init.headers : null;
                if (h) {
                    var auth = (typeof h.get === 'function')
                        ? (h.get('Authorization') || h.get('Token') || h.get('token'))
                        : (h['Authorization'] || h['Token'] || h['token'] || h['authorization'] || '');
                    if (auth && auth.length > 15) {
                        window.__glToken = auth.replace(/^Bearer\s+/i, '').trim();
                    }
                }
            } catch(e) {}
            return _fetch.apply(this, arguments);
        };
    }
})();
""".trimIndent()

        /* Inject khi người dùng bấm "Lấy ATH" — enumerate ALL storage */
        private val JS_EXTRACT = """
(function() {
    var jwtRe   = /^eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\./;
    var tokenRe = /token|auth(?!or)|bearer|jwt/i;

    // 1. XHR interceptor đã bắt được?
    if (window.__glToken && window.__glToken.length > 15) {
        window.AndroidBridge.onAthTokenFound(window.__glToken);
        return;
    }

    // Helper: tìm token trong object (recursive tối đa 4 cấp)
    function dig(obj, depth) {
        if (!obj || typeof obj !== 'object' || depth > 4) return null;
        var keys = ['token','access_token','accessToken','authToken','auth_token',
                    'jwt','bearer','userToken','user_token'];
        for (var i = 0; i < keys.length; i++) {
            var v = obj[keys[i]];
            if (typeof v === 'string' && v.length > 15) return v;
        }
        for (var k in obj) {
            if (typeof obj[k] === 'object') {
                var r = dig(obj[k], depth + 1);
                if (r) return r;
            }
        }
        return null;
    }

    // Helper: thử parse string → tìm token
    function fromStr(val) {
        if (!val || val.length < 16) return null;
        if (jwtRe.test(val)) return val.trim();
        if (val.charAt(0) === '{') {
            try { var r = dig(JSON.parse(val), 0); if (r) return r; } catch(e) {}
        }
        return null;
    }

    // 2. Enumerate ALL localStorage
    var ls = window.localStorage;
    for (var i = 0; i < ls.length; i++) {
        var k = ls.key(i); var v = ls.getItem(k);
        var r = fromStr(v) || (tokenRe.test(k) && v && v.length > 15 && !v.startsWith('{') ? v.trim() : null);
        if (r) { window.AndroidBridge.onAthTokenFound(r); return; }
    }

    // 3. Enumerate ALL sessionStorage
    var ss = window.sessionStorage;
    for (var i = 0; i < ss.length; i++) {
        var k = ss.key(i); var v = ss.getItem(k);
        var r = fromStr(v) || (tokenRe.test(k) && v && v.length > 15 && !v.startsWith('{') ? v.trim() : null);
        if (r) { window.AndroidBridge.onAthTokenFound(r); return; }
    }

    // 4. document.cookie (non-HttpOnly)
    var pairs = document.cookie.split(';');
    for (var i = 0; i < pairs.length; i++) {
        var p = pairs[i].trim(); var idx = p.indexOf('=');
        if (idx < 0) continue;
        var ck = p.substring(0, idx).trim();
        var cv = p.substring(idx + 1).trim();
        if (cv && (jwtRe.test(cv) || (tokenRe.test(ck) && cv.length > 15))) {
            window.AndroidBridge.onAthTokenFound(cv); return;
        }
    }

    // Không tìm thấy
    window.AndroidBridge.onAthTokenFound('');
})();
""".trimIndent()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var webView:     WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText:  TextView
    private lateinit var athButton:   Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** true = token đã được verify + save, activity chuẩn bị finish */
    @Volatile private var tokenSaved = false

    /** true = JS đã trả callback (token hoặc empty) — tránh double-fire timeout */
    @Volatile private var jsCallbackFired = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        setupWebView()
        webView.loadUrl(BASE_URL)
    }

    override fun onDestroy() {
        scope.cancel()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        val header = RelativeLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(56)).also {
                it.gravity = Gravity.TOP
            }
            setBackgroundColor(Color.parseColor("#13131F"))
        }

        statusText = TextView(this).apply {
            text     = "Đăng nhập Golike"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = RelativeLayout.LayoutParams(MATCH, MATCH).also {
                it.setMargins(dp(14), 0, dp(128), 0)
            }
        }

        athButton = Button(this).apply {
            text     = "Lấy ATH"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.parseColor("#F5A623"))
            layoutParams = RelativeLayout.LayoutParams(dp(108), dp(36)).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.setMargins(0, 0, dp(10), 0)
            }
            setOnClickListener { onAthClick() }
        }

        header.addView(statusText)
        header.addView(athButton)

        progressBar = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#6C63FF"))
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(3)).also {
                it.gravity    = Gravity.TOP
                it.topMargin  = dp(56)
            }
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).also {
                it.topMargin = dp(56)
            }
        }

        root.addView(header)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

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
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                progressBar.progress  = p
                progressBar.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            /** Inject interceptor ở đây — trước khi trang chạy JS */
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(JS_INTERCEPTOR, null)
            }

            /** Auto-detect: chạy extraction sau mỗi page load */
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (tokenSaved) return
                // Thử native cookie trước (bao gồm HttpOnly)
                val native = extractFromCookieManager(url)
                if (native != null) {
                    Log.d(TAG, "Auto: CookieManager → ${native.take(20)}...")
                    onTokenReceived(native)
                    return
                }
                // Rồi mới thử JS localStorage/sessionStorage
                view?.evaluateJavascript(JS_EXTRACT) { /* result via Bridge */ }
            }

            override fun onReceivedError(view: WebView?,
                req: WebResourceRequest?, err: WebResourceError?) {
                super.onReceivedError(view, req, err)
                if (req?.isForMainFrame == true)
                    statusText.text = "Lỗi kết nối — kiểm tra internet"
            }
        }
    }

    // ── Nút Lấy ATH ──────────────────────────────────────────────────────────

    private fun onAthClick() {
        if (tokenSaved) return
        jsCallbackFired = false
        statusText.text = "Đang tìm token..."
        athButton.isEnabled = false
        athButton.setBackgroundColor(Color.parseColor("#888888"))

        // 1. Native cookie (nhanh, sync, bao gồm HttpOnly)
        val native = extractFromCookieManager(webView.url)
        if (native != null) {
            Log.d(TAG, "Lấy ATH: CookieManager → ${native.take(20)}...")
            verifyAndSave(native)
            return
        }

        // 2. JS enumeration (localStorage + sessionStorage + document.cookie)
        webView.evaluateJavascript(JS_EXTRACT, null)

        // 3. Timeout: nếu sau 5s JS vẫn chưa fire callback → báo lỗi
        scope.launch {
            delay(5_000)
            if (!jsCallbackFired && !tokenSaved) {
                Log.w(TAG, "ATH timeout — không tìm thấy token trong storage")
                statusText.text = "Không tìm thấy token — hãy chắc chắn đã đăng nhập Golike"
                athButton.isEnabled = true
                athButton.setBackgroundColor(Color.parseColor("#F5A623"))
                athButton.text = "Thử lại"
            }
        }
    }

    // ── Verify + Save ─────────────────────────────────────────────────────────

    private fun verifyAndSave(rawToken: String) {
        val token = rawToken.removePrefix("Bearer ").removeSurrounding("\"").trim()
        if (token.length < 16) {
            showNotFound(); return
        }
        statusText.text = "Đang xác thực với Golike..."
        athButton.isEnabled = false

        scope.launch {
            try {
                val result = GolikeApi.getMe(token)
                when (result) {
                    is GolikeResult.Success -> {
                        val user = result.data.data
                        val name = user?.name?.ifEmpty { user.username }
                            ?: user?.username ?: "Golike"
                        Log.i(TAG, "Verify OK: $name")
                        statusText.text = "✓ Đăng nhập thành công — $name"

                        val repo = GolikeRepository.getInstance(
                            LocalRepository.getInstance(this@GolikeLoginWebActivity))
                        repo.saveWebToken(token)
                        tokenSaved = true

                        delay(700)
                        setResult(Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_TOKEN, token))
                        finish()
                    }
                    is GolikeResult.Error -> {
                        Log.w(TAG, "Verify error ${result.code}: ${result.message}")
                        statusText.text = "Token không hợp lệ (${result.code}) — thử đăng nhập lại"
                        resetAthButton()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyAndSave: ${e.message}")
                statusText.text = "Lỗi kết nối — kiểm tra internet rồi thử lại"
                resetAthButton()
            }
        }
    }

    /** Auto-detect path — không verify trước khi save để tránh chậm UX */
    private fun onTokenReceived(rawToken: String) {
        if (tokenSaved) return
        tokenSaved = true
        val token  = rawToken.removePrefix("Bearer ").removeSurrounding("\"").trim()
        statusText.text = "Đang lưu thông tin đăng nhập..."
        athButton.isEnabled = false

        scope.launch {
            try {
                val repo = GolikeRepository.getInstance(
                    LocalRepository.getInstance(this@GolikeLoginWebActivity))
                repo.saveWebToken(token)
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
            } catch (e: Exception) {
                Log.e(TAG, "saveWebToken: ${e.message}")
                setResult(Activity.RESULT_CANCELED)
            } finally { finish() }
        }
    }

    // ── Native CookieManager extraction (bao gồm HttpOnly) ───────────────────

    /**
     * Đọc cookies qua CookieManager — bao gồm HttpOnly (JS không thể đọc được,
     * nhưng native Android code thì đọc được).
     * Tìm: JWT pattern (eyJ...) hoặc cookie key chứa "token/auth/jwt/bearer".
     */
    private fun extractFromCookieManager(url: String?): String? {
        val cookieUrl = url?.takeIf { it.startsWith("http") } ?: BASE_URL
        val raw = try {
            CookieManager.getInstance().getCookie(cookieUrl) ?: return null
        } catch (_: Exception) { return null }

        val jwtRe   = Regex("""eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.""")
        val tokenRe = Regex("""token|auth(?!or)|jwt|bearer""", RegexOption.IGNORE_CASE)

        for (pair in raw.split(";")) {
            val trimmed = pair.trim()
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (value.length < 16) continue
            if (jwtRe.containsMatchIn(value)) return value
            if (tokenRe.containsMatchIn(key)) return value
        }
        return null
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showNotFound() {
        statusText.text = "Không tìm thấy token — hãy chắc chắn đã đăng nhập Golike"
        resetAthButton()
    }

    private fun resetAthButton() {
        athButton.isEnabled = true
        athButton.setBackgroundColor(Color.parseColor("#F5A623"))
        athButton.text = "Thử lại"
    }

    // ── JS → Kotlin bridge ────────────────────────────────────────────────────

    inner class Bridge {
        /** Gọi từ JS_EXTRACT — cả auto-detect lẫn manual ATH */
        @JavascriptInterface
        fun onAthTokenFound(token: String) {
            jsCallbackFired = true
            Log.d(TAG, "JS bridge: token='${token.take(24)}' len=${token.length}")
            runOnUiThread {
                if (tokenSaved) return@runOnUiThread
                if (token.isBlank() || token.length < 16) {
                    // JS không tìm thấy → timeout job sẽ hiện lỗi
                    return@runOnUiThread
                }
                verifyAndSave(token)
            }
        }

        /** Gọi từ injectTokenChecker cũ (nếu còn được gọi ở đâu đó) */
        @JavascriptInterface
        fun onTokenFound(token: String) = onAthTokenFound(token)
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
