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
import com.atpro.golike.GolikeRepository
import kotlinx.coroutines.*

/**
 * GolikeLoginWebActivity — v1.2.2 (rewritten from new smali)
 *
 * Đọc từ GoLikeLoginActivity.smali (bản mới):
 *
 * ── Cờ (fields) ────────────────────────────────────────────────────────────
 *   C: Boolean  — đã nhận được token (chặn duplicate)
 *   D: Boolean  — đang trong quá trình tìm token (user đã bấm nút)
 *   F: Int      — số lần inject JS đã thực hiện (embed vào JS, max 6)
 *   E: Handler  — mainHandler (Looper.getMainLooper())
 *
 * ── Phương thức ─────────────────────────────────────────────────────────────
 *   p(String): Boolean  — validate token: startsWith("eyJ") && length > 50
 *                         HOẶC length > 100 (fallback)
 *   q(String): Unit     — nhận + lưu token (gọi từ bridge trên bg thread)
 *   r(): Unit           — inject JS (gọi từ button click VÀ onPageFinished)
 *
 * ── JS mới (khác bản cũ) ───────────────────────────────────────────────────
 *   • sendToken() helper với regex /eyJ[a-zA-Z0-9_\-\.]{50,}/g
 *   • Retry KHÔNG dùng arguments.callee mà dùng window.location.reload()
 *   • onPageFinished → gọi r() → inject lại → tạo vòng retry tự nhiên
 *
 * ── Flow ─────────────────────────────────────────────────────────────────────
 *   1. User bấm "Lấy ATH" → D=true, F=0 → r()
 *   2. r() inject JS → tìm trong localStorage/sessionStorage/cookie
 *   3a. Tìm thấy → AndroidTokenReceiver.onTokenReceived(token) → q() → lưu → finish
 *   3b. Không thấy, F<6 → setTimeout(reload, 2000) → onPageFinished → r() lại
 */
class GolikeLoginWebActivity : AppCompatActivity() {

    companion object {
        const val TAG         = "GoLikeLogin"
        const val BASE_URL    = "https://app.golike.net/home"
        const val EXTRA_TOKEN = "golike_token"

        // ── p(String): Boolean — validator theo smali ─────────────────────
        fun isValidToken(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            // (startsWith("eyJ") && length > 50) || length > 100
            if (s.startsWith("eyJ") && s.length > 50) return true
            if (s.length > 100) return true
            return false
        }
    }

    // ── Fields theo smali ─────────────────────────────────────────────────────
    @Volatile var C = false          // tokenReceived
    @Volatile var D = false          // isSearching
    var F = 0                        // retryCount
    val E = Handler(Looper.getMainLooper())   // mainHandler

    // ── Views ─────────────────────────────────────────────────────────────────
    lateinit var y: WebView          // field y = WebView
    lateinit var z: ProgressBar      // field z = ProgressBar
    lateinit var A: Button           // field A = Button (Lấy ATH)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── onCreate ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()

        // WebView settings theo smali
        y.settings.apply {
            javaScriptEnabled    = true
            setDomStorageEnabled(true)
            setLoadWithOverviewMode(true)
            setUseWideViewPort(true)
            setBuiltInZoomControls(true)
            displayZoomControls  = false
            cacheMode            = WebSettings.LOAD_DEFAULT   // -1 = LOAD_DEFAULT
            databaseEnabled      = true
            allowFileAccess      = true
            allowContentAccess   = true
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW  // 0
        }

        // CookieManager.setAcceptThirdPartyCookies
        CookieManager.getInstance().setAcceptThirdPartyCookies(y, true)

        // addJavascriptInterface(La2/g instance, "AndroidTokenReceiver")
        y.addJavascriptInterface(TokenBridge(), "AndroidTokenReceiver")

        // setWebViewClient(La2/f instance) — onPageFinished → r()
        y.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // La2/f.onPageFinished → gọi r() — tạo retry loop tự nhiên sau reload
                r()
            }

            override fun onReceivedError(view: WebView?,
                req: WebResourceRequest?, err: WebResourceError?) {
                super.onReceivedError(view, req, err)
                if (req?.isForMainFrame == true)
                    updateStatus("Lỗi kết nối — kiểm tra internet")
            }
        }

        y.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                z.progress   = newProgress
                z.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        // Log + loadUrl theo smali
        Log.d(TAG, "🚀 Load GoLike: $BASE_URL")
        y.loadUrl(BASE_URL)

        // Button click → set D=true, reset F, gọi r()
        A.setOnClickListener {
            if (!C) {
                D = true
                F = 0
                updateStatus("🔍 Đang tìm token...")
                A.isEnabled = false
                A.setBackgroundColor(Color.parseColor("#888888"))
                A.text = "Đang tìm..."
                r()
            }
        }
    }

    override fun onBackPressed() {
        if (y.canGoBack()) y.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        y.destroy()
        super.onDestroy()
    }

    // ── r(): inject JS — theo smali ───────────────────────────────────────────

    /**
     * Tương đương hàm r()V trong smali.
     * Guard: nếu C (done) → skip; nếu !D (chưa bấm nút) → skip.
     * Tăng F, build JS với F nhúng vào (như smali gốc), evaluateJavascript.
     *
     * JS retry: setTimeout(() => window.location.reload(), 2000)
     * → onPageFinished sẽ gọi r() lại → vòng lặp tự nhiên, max 6 lần.
     */
    fun r() {
        if (C) return          // đã có token
        if (!D) return         // chưa bấm nút

        F++
        Log.d(TAG, "🔍 Lần thứ $F - Inject JavaScript lấy token...")

        // JS EXACT từ smali r()V — chỉ thay F bằng giá trị thực
        val js = buildString {
            append("javascript:(function() {")
            append("   var tokenPattern = /eyJ[a-zA-Z0-9_\\-\\.]{50,}/g;")
            append("   function sendToken(token) {")
            append("       if (token && token.length > 50 && token.startsWith('eyJ')) {")
            append("           AndroidTokenReceiver.onTokenReceived(token);")
            append("           return true;")
            append("       }")
            append("       return false;")
            append("   }")
            // localStorage
            append("   try {")
            append("       for (var i = 0; i < localStorage.length; i++) {")
            append("           var val = localStorage.getItem(localStorage.key(i));")
            append("           if (val) {")
            append("               if (sendToken(val)) return;")
            append("               var matches = val.match(tokenPattern);")
            append("               if (matches && matches.length > 0 && sendToken(matches[0])) return;")
            append("               try {")
            append("                   var parsed = JSON.parse(val);")
            append("                   if (parsed) {")
            append("                       if (parsed.token && sendToken(parsed.token)) return;")
            append("                       if (parsed.access_token && sendToken(parsed.access_token)) return;")
            append("                       if (parsed.authorization && sendToken(parsed.authorization)) return;")
            append("                   }")
            append("               } catch(e) {}")
            append("           }")
            append("       }")
            append("   } catch(e) {}")
            // sessionStorage
            append("   try {")
            append("       for (var i = 0; i < sessionStorage.length; i++) {")
            append("           var val = sessionStorage.getItem(sessionStorage.key(i));")
            append("           if (val && sendToken(val)) return;")
            append("       }")
            append("   } catch(e) {}")
            // cookies
            append("   try {")
            append("       var cookies = document.cookie.split(';');")
            append("       for (var i = 0; i < cookies.length; i++) {")
            append("           var parts = cookies[i].trim().split('=');")
            append("           if (parts.length >= 2) {")
            append("               var val = parts.slice(1).join('=');")
            append("               if (sendToken(val)) return;")
            append("           }")
            append("       }")
            append("   } catch(e) {}")
            // Retry: reload page (KHÔNG dùng arguments.callee)
            append("   console.log('No token found in attempt ' + $F);")
            append("   if ($F < 6) {")
            append("       setTimeout(function() {")
            append("           window.location.reload();")
            append("       }, 2000);")
            append("   }")
            append("})()")
        }

        y.evaluateJavascript(js, null)   // null callback theo smali
    }

    // ── q(String): nhận + lưu token — theo smali ─────────────────────────────

    /**
     * Tương đương hàm q(GoLikeLoginActivity, String)V trong smali.
     * Gọi từ bridge trên JavaBridge thread → cần runOnUiThread.
     */
    fun q(token: String) {
        if (C) return   // đã nhận rồi — smali: if-eqz v0, :cond_0 → goto_0

        C = true
        D = false

        // Log theo smali
        Log.d(TAG, "🎉 LẤY ĐƯỢC TOKEN! Độ dài: ${token.length}")
        val preview = token.substring(0, minOf(100, token.length))
        Log.d(TAG, "📝 Token: $preview...")

        // Lưu thẳng — smali không gọi API verify
        // SharedPreferences("golike_auth", 0) key "golike_token"
        getSharedPreferences("golike_auth", 0).edit()
            .putString("golike_token", token)
            .apply()

        // Lưu thêm qua GolikeRepository (tương đương B.a key "token" trong smali)
        scope.launch(Dispatchers.IO) {
            try {
                val repo = GolikeRepository.getInstance(
                    LocalRepository.getInstance(this@GolikeLoginWebActivity))
                repo.saveWebToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "saveWebToken: ${e.message}")
            }
        }

        // runOnUiThread → finish — tương đương m0(activity, 4, token) trong smali
        runOnUiThread {
            updateStatus("✓ Đăng nhập thành công!")
            A.isEnabled = false
            A.setBackgroundColor(Color.parseColor("#10B981"))
            A.text = "✓ OK"

            // Delay nhỏ để user thấy trạng thái rồi finish
            E.postDelayed({
                setResult(Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_TOKEN, token))
                finish()
            }, 800)
        }
    }

    // ── AndroidTokenReceiver (La2/g trong smali) ─────────────────────────────

    inner class TokenBridge {
        @JavascriptInterface
        fun onTokenReceived(token: String) {
            Log.d(TAG, "onTokenReceived: len=${token.length}")
            // Validate theo p(String) từ smali
            if (!isValidToken(token)) {
                Log.w(TAG, "Token không hợp lệ, bỏ qua")
                return
            }
            q(token)   // gọi q() theo smali pattern
        }
    }

    // ── Layout (programmatic — không dùng XML) ────────────────────────────────

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

        val statusLabel = TextView(this).apply {
            text     = "Đăng nhập Golike"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER_VERTICAL or Gravity.START
            id       = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(MATCH, MATCH).also {
                it.setMargins(dp(14), 0, dp(128), 0)
            }
        }

        A = Button(this).apply {
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
        }

        header.addView(statusLabel)
        header.addView(A)
        // Lưu ref statusText qua tag
        statusLabel.tag = "status"

        z = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#6C63FF"))
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(3)).also {
                it.gravity   = Gravity.TOP
                it.topMargin = dp(56)
            }
        }

        y = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).also {
                it.topMargin = dp(56)
            }
        }

        root.addView(header)
        root.addView(z)
        root.addView(y)
        setContentView(root)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateStatus(text: String) {
        E.post {
            val root = window.decorView as ViewGroup
            val label = root.findViewWithTag<TextView>("status")
            label?.text = text
        }
    }

    private fun resetButton() {
        A.isEnabled = true
        A.setBackgroundColor(Color.parseColor("#F5A623"))
        A.text = "Lấy ATH"
        D = false
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
