package com.atpro.ui.golike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * GolikeLoginWebActivity — v1.2.2 (rewritten from smali reference)
 *
 * Logic theo mẫu GoLikeLoginActivity.smali:
 *   - Bridge: "AndroidTokenReceiver" / onTokenReceived(String)
 *   - URL: https://app.golike.net/home
 *   - JS inject EXACT từ smali: search localStorage → sessionStorage → cookies
 *     với setTimeout(callee, 2000) để tự retry — tránh việc app tự lấy khi
 *     web vừa load, chỉ chạy khi người dùng bấm nút "Lấy ATH"
 *   - Token hợp lệ: startsWith("eyJ") && length > 100
 *   - Sau khi có token: verify với server → lưu → finish
 *
 * Cờ trạng thái (theo smali):
 *   C (tokenReceived) — true khi đã lấy được token, chặn duplicate
 *   D (isSearching)   — true khi người dùng đã bấm nút, JS đang chạy
 *   F (retryCount)    — đếm số lần inject (embed vào JS string như smali)
 */
class GolikeLoginWebActivity : AppCompatActivity() {

    companion object {
        const val TAG         = "GoLikeLogin"
        const val BASE_URL    = "https://app.golike.net/home"
        const val EXTRA_TOKEN = "golike_token"

        /** Token valid theo smali: q(String)Z */
        fun isValidToken(s: String?): Boolean {
            if (s == null) return false
            return s.length > 50 || s.startsWith("eyJ")
        }
    }

    // ── Cờ trạng thái theo smali ──────────────────────────────────────────────
    /** C: đã nhận được token, chặn duplicate */
    @Volatile private var C = false
    /** D: đang trong quá trình tìm token (người dùng đã bấm nút) */
    @Volatile private var D = false
    /** F: số lần inject JS (embed vào JS string như smali gốc) */
    private var F = 0

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var webView:     WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText:  TextView
    private lateinit var athButton:   Button

    /** Handler main thread theo smali: Handler(Looper.getMainLooper()) */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Timeout job — hủy sau 30s nếu không tìm thấy token */
    private var timeoutJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        setupWebView()
        webView.loadUrl(BASE_URL)
    }

    override fun onBackPressed() {
        // Theo smali: onBackPressed kiểm tra canGoBack trước khi close
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        timeoutJob?.cancel()
        webView.destroy()
        super.onDestroy()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        // Header
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

        // Nút "Lấy ATH" — chỉ kích hoạt khi người dùng chủ động bấm
        athButton = Button(this).apply {
            text      = "Lấy ATH"
            textSize  = 12f
            isAllCaps = false
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.parseColor("#F5A623"))
            layoutParams = RelativeLayout.LayoutParams(dp(108), dp(36)).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.setMargins(0, 0, dp(10), 0)
            }
            setOnClickListener { onAthButtonClick() }
        }

        header.addView(statusText)
        header.addView(athButton)

        // ProgressBar
        progressBar = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#6C63FF"))
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(3)).also {
                it.gravity   = Gravity.TOP
                it.topMargin = dp(56)
            }
        }

        // WebView
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
            domStorageEnabled        = true          // theo smali: setDomStorageEnabled
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

        // Theo smali: CookieManager accept third party
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Theo smali: addJavascriptInterface(La2/g instance, "AndroidTokenReceiver")
        webView.addJavascriptInterface(AndroidTokenReceiver(), "AndroidTokenReceiver")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress   = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // KHÔNG tự động inject khi page load xong
                // Chỉ reset nút nếu page reload trong khi đang tìm
                if (D && !C) {
                    statusText.text = "Trang đã tải — bấm \"Lấy ATH\" để lấy token"
                    D = false
                    resetButton()
                }
            }

            override fun onReceivedError(view: WebView?,
                req: WebResourceRequest?, err: WebResourceError?) {
                super.onReceivedError(view, req, err)
                if (req?.isForMainFrame == true)
                    statusText.text = "Lỗi kết nối — kiểm tra internet"
            }
        }
    }

    // ── p() — inject JS theo smali ────────────────────────────────────────────

    /**
     * Tương đương hàm p() trong smali.
     * Chỉ chạy khi: C=false (chưa có token) VÀ D=true (đang tìm kiếm).
     * Embed giá trị F vào JS string y hệt smali gốc.
     */
    private fun injectJs() {
        if (C) return        // đã có token, không inject nữa
        if (!D) return       // chưa bấm nút, không inject

        F++
        Log.d(TAG, "🔍 Lần thứ $F - Inject JavaScript lấy token...")

        // JS EXACT từ smali (lines 1640-1648), chỉ thay F bằng giá trị thực
        val js = buildString {
            append("javascript:(function() {")
            append("   var TOKEN = null;")
            append("   try {")
            append("       for (var i = 0; i < localStorage.length; i++) {")
            append("           var key = localStorage.key(i);")
            append("           var val = localStorage.getItem(key);")
            append("           if (val && val.startsWith('eyJ') && val.length > 100) {")
            append("               console.log('Found token in localStorage key: ' + key);")
            append("               AndroidTokenReceiver.onTokenReceived(val);")
            append("               return;")
            append("           }")
            append("           try {")
            append("               var parsed = JSON.parse(val);")
            append("               if (parsed && (parsed.token || parsed.access_token || parsed.authorization)) {")
            append("                   TOKEN = parsed.token || parsed.access_token || parsed.authorization;")
            append("                   if (TOKEN && TOKEN.startsWith('eyJ') && TOKEN.length > 100) {")
            append("                       AndroidTokenReceiver.onTokenReceived(TOKEN);")
            append("                       return;")
            append("                   }")
            append("               }")
            append("           } catch(e) {}")
            append("       }")
            append("   } catch(e) { console.log('localStorage error:', e); }")
            append("   try {")
            append("       for (var i = 0; i < sessionStorage.length; i++) {")
            append("           var val = sessionStorage.getItem(sessionStorage.key(i));")
            append("           if (val && val.startsWith('eyJ') && val.length > 100) {")
            append("               AndroidTokenReceiver.onTokenReceived(val); return;")
            append("           }")
            append("       }")
            append("   } catch(e) {}")
            append("   try {")
            append("       var cookies = document.cookie.split(';');")
            append("       for (var i = 0; i < cookies.length; i++) {")
            append("           var val = cookies[i].trim().split('=')[1];")
            append("           if (val && val.startsWith('eyJ') && val.length > 100) {")
            append("               AndroidTokenReceiver.onTokenReceived(val); return;")
            append("           }")
            append("       }")
            append("   } catch(e) {}")
            append("   console.log('No valid token found yet');")
            // Theo smali: embed F vào JS rồi check < 6
            append("   if ($F < 6) {")
            append("       setTimeout(arguments.callee, 2000);")
            append("   }")
            append("})()")
        }

        webView.evaluateJavascript(js, null)   // null callback theo smali
    }

    // ── Nút Lấy ATH ──────────────────────────────────────────────────────────

    /**
     * Người dùng bấm nút → set D=true, reset F, inject JS lần đầu.
     * JS tự retry mỗi 2s (setTimeout(callee, 2000)) cho đến khi tìm thấy
     * hoặc timeout 30s.
     */
    private fun onAthButtonClick() {
        if (C) return   // đã có token rồi

        // Reset trạng thái
        D = true
        F = 0
        timeoutJob?.cancel()

        statusText.text = "🔍 Đang tìm token..."
        athButton.isEnabled = false
        athButton.setBackgroundColor(Color.parseColor("#888888"))
        athButton.text = "Đang tìm..."

        // Inject JS — JS sẽ tự retry mỗi 2s
        injectJs()

        // Timeout 30s: nếu không tìm được → báo lỗi
        timeoutJob = scope.launch {
            delay(30_000)
            if (!C) {
                Log.w(TAG, "Timeout 30s — không tìm thấy token")
                D = false
                statusText.text = "Không tìm thấy token — hãy đăng nhập Golike rồi thử lại"
                resetButton()
            }
        }
    }

    // ── r(String) — nhận token từ JS bridge ──────────────────────────────────

    /**
     * Tương đương hàm r(String) trong smali.
     * Gọi từ AndroidTokenReceiver.onTokenReceived trên background thread.
     */
    private fun onTokenReceivedFromJs(token: String) {
        if (C) return   // C: đã nhận rồi, theo smali: if-eqz v0, :cond_0

        C = true        // set C = true
        D = false       // set D = false
        timeoutJob?.cancel()

        Log.d(TAG, "🎉 LẤY ĐƯỢC TOKEN! Độ dài: ${token.length}")

        // Theo smali: runOnUiThread để update UI rồi verify + save
        runOnUiThread {
            statusText.text = "🎉 Tìm thấy token — đang xác thực..."
            athButton.isEnabled = false
            athButton.setBackgroundColor(Color.parseColor("#888888"))
            verifyAndSave(token)
        }
    }

    // ── Verify + Save ─────────────────────────────────────────────────────────

    private fun verifyAndSave(token: String) {
        scope.launch {
            try {
                val result = GolikeApi.getMe(token)
                when (result) {
                    is GolikeResult.Success -> {
                        val user = result.data.data
                        val name = user?.name?.takeIf { it.isNotBlank() }
                            ?: user?.username ?: "Golike"

                        Log.i(TAG, "Verify OK: $name")
                        statusText.text = "✓ Đăng nhập thành công — $name"

                        // Lưu token qua GolikeRepository (dùng saveWebToken như cũ)
                        val repo = GolikeRepository.getInstance(
                            LocalRepository.getInstance(this@GolikeLoginWebActivity))
                        repo.saveWebToken(token)

                        delay(700)
                        setResult(Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_TOKEN, token))
                        finish()
                    }
                    is GolikeResult.Error -> {
                        Log.w(TAG, "Verify error ${result.code}: ${result.message}")
                        C = false   // reset để cho phép thử lại
                        statusText.text = "Token không hợp lệ — thử đăng nhập lại"
                        resetButton()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyAndSave: ${e.message}")
                C = false
                statusText.text = "Lỗi kết nối — kiểm tra internet rồi thử lại"
                resetButton()
            }
        }
    }

    // ── AndroidTokenReceiver (La2/g; trong smali) ─────────────────────────────

    /**
     * Tương đương class La2/g; được nhúng vào WebView với tên "AndroidTokenReceiver".
     * Method onTokenReceived(String) được JS gọi khi tìm thấy token.
     */
    inner class AndroidTokenReceiver {
        /**
         * Gọi từ JS: AndroidTokenReceiver.onTokenReceived(val)
         * Chạy trên JavaBridge thread (không phải main thread).
         */
        @JavascriptInterface
        fun onTokenReceived(token: String) {
            Log.d(TAG, "onTokenReceived: len=${token.length} prefix=${token.take(10)}")

            // Validate theo smali q(): startsWith("eyJ") && length > 100
            if (!token.startsWith("eyJ") || token.length <= 100) {
                Log.w(TAG, "Token không hợp lệ, bỏ qua (len=${token.length})")
                return
            }

            // Gọi r(String) theo smali pattern
            onTokenReceivedFromJs(token)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetButton() {
        athButton.isEnabled = true
        athButton.setBackgroundColor(Color.parseColor("#F5A623"))
        athButton.text = "Lấy ATH"
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
