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

        /**
         * v1.2.5 — Đồng bộ CHÍNH XÁC theo golike.py `_validate_jwt()`:
         * JWT hợp lệ = đúng 3 phần ngăn bởi "." (header.payload.signature),
         * KHÔNG phần nào rỗng. Thay cho check cũ (startsWith eyJ && length>50)
         * vốn chỉ là heuristic — giờ dùng tiêu chuẩn cấu trúc JWT thật.
         */
        fun isValidToken(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            val parts = s.split(".")
            return parts.size == 3 && parts.all { it.isNotEmpty() }
        }

        /**
         * v1.2.5 — Theo golike.py `_sanitize_jwt()`: bỏ khoảng trắng đầu/cuối
         * và tiền tố "Bearer "/"bearer " nếu người dùng dán cả header đầy đủ.
         * Áp dụng TRƯỚC khi gọi [isValidToken] — cả ở auto-extract VÀ nhập thủ công.
         */
        fun sanitizeToken(raw: String): String {
            var t = raw.trim()
            if (t.startsWith("Bearer ", ignoreCase = true)) {
                t = t.substring(7).trim()
            }
            return t
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
    lateinit var M: Button           // v1.2.5 — Button "Nhập thủ công"

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

        // v1.2.5 — Nút "Nhập thủ công": fallback khi tự động lấy token thất bại.
        M.setOnClickListener { showManualTokenDialog() }
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

        // JS — dựa trên smali, cải tiến v1.2.5: tìm đệ quy trong JSON
        // (fix lỗi "không lấy được token" khi Golike lưu lồng sâu, VD:
        //  {state:{auth:{token:"eyJ..."}}} — bản cũ chỉ tìm top-level key)
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
            // v1.2.5: tìm đệ quy mọi string trong object JSON (tối đa 5 cấp,
            // tránh vòng lặp vô hạn / object quá lớn).
            append("   function deepFind(obj, depth) {")
            append("       if (!obj || depth > 5) return null;")
            append("       if (typeof obj === 'string') {")
            append("           if (obj.startsWith('eyJ') && obj.length > 50) return obj;")
            append("           var m = obj.match(tokenPattern);")
            append("           if (m && m.length > 0) return m[0];")
            append("           return null;")
            append("       }")
            append("       if (typeof obj === 'object') {")
            append("           for (var k in obj) {")
            append("               try {")
            append("                   var r = deepFind(obj[k], depth + 1);")
            append("                   if (r) return r;")
            append("               } catch(e) {}")
            append("           }")
            append("       }")
            append("       return null;")
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
            // v1.2.5: thử key cụ thể trước (nhanh), sau đó deepFind() toàn object
            append("                       if (parsed.token && sendToken(parsed.token)) return;")
            append("                       if (parsed.access_token && sendToken(parsed.access_token)) return;")
            append("                       if (parsed.authorization && sendToken(parsed.authorization)) return;")
            append("                       var deep = deepFind(parsed, 0);")
            append("                       if (deep && sendToken(deep)) return;")
            append("                   }")
            append("               } catch(e) {}")
            append("           }")
            append("       }")
            append("   } catch(e) {}")
            // sessionStorage — v1.2.5: cũng thử deepFind nếu là JSON
            append("   try {")
            append("       for (var i = 0; i < sessionStorage.length; i++) {")
            append("           var val = sessionStorage.getItem(sessionStorage.key(i));")
            append("           if (val) {")
            append("               if (sendToken(val)) return;")
            append("               try {")
            append("                   var parsedS = JSON.parse(val);")
            append("                   var deepS = deepFind(parsedS, 0);")
            append("                   if (deepS && sendToken(deepS)) return;")
            append("               } catch(e) {}")
            append("           }")
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
            // v1.2.5: quét window.__NUXT__ / window.__INITIAL_STATE__ nếu site dùng SSR state
            append("   try {")
            append("       if (window.__NUXT__) {")
            append("           var dn = deepFind(window.__NUXT__, 0);")
            append("           if (dn && sendToken(dn)) return;")
            append("       }")
            append("       if (window.__INITIAL_STATE__) {")
            append("           var di = deepFind(window.__INITIAL_STATE__, 0);")
            append("           if (di && sendToken(di)) return;")
            append("       }")
            append("   } catch(e) {}")
            // Retry: reload page (KHÔNG dùng arguments.callee)
            append("   console.log('No token found in attempt ' + $F);")
            append("   if ($F < 6) {")
            append("       setTimeout(function() {")
            append("           window.location.reload();")
            append("       }, 2000);")
            append("   } else {")
            // v1.2.5: hết 6 lần retry → báo cho Android biết để gợi ý nhập thủ công
            append("       try { AndroidTokenReceiver.onSearchExhausted(); } catch(e) {}")
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
            // v1.2.5 — sanitize trước khi validate (đồng bộ golike.py)
            val clean = sanitizeToken(token)
            if (!isValidToken(clean)) {
                Log.w(TAG, "Token không hợp lệ, bỏ qua")
                return
            }
            q(clean)   // gọi q() theo smali pattern
        }

        /**
         * v1.2.5 — JS gọi khi đã thử hết 6 lần (F >= 6) mà không tìm thấy token.
         * Gợi ý người dùng dùng nút "Nhập thủ công" thay vì tiếp tục chờ.
         */
        @JavascriptInterface
        fun onSearchExhausted() {
            Log.w(TAG, "Đã thử $F lần — không tìm thấy token tự động")
            runOnUiThread {
                if (!C) {
                    updateStatus("⚠ Không tìm thấy token — thử 'Nhập thủ công'")
                    resetButton()
                }
            }
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

        // v1.2.5 — Sub-bar chứa nút "Nhập thủ công" (fallback khi lấy auto thất bại)
        val subBar = RelativeLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(34)).also {
                it.gravity   = Gravity.TOP
                it.topMargin = dp(56)
            }
            setBackgroundColor(Color.parseColor("#0F0F1A"))
        }

        val hintLabel = TextView(this).apply {
            text     = "Không lấy được token tự động?"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            gravity  = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = RelativeLayout.LayoutParams(MATCH, MATCH).also {
                it.setMargins(dp(14), 0, dp(118), 0)
            }
        }

        M = Button(this).apply {
            text      = "✎ Nhập thủ công"
            textSize  = 11f
            isAllCaps = false
            setTextColor(Color.parseColor("#F5A623"))
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = RelativeLayout.LayoutParams(dp(112), dp(26)).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.setMargins(0, 0, dp(10), 0)
            }
        }

        subBar.addView(hintLabel)
        subBar.addView(M)

        z = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#6C63FF"))
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(3)).also {
                it.gravity   = Gravity.TOP
                it.topMargin = dp(90)   // header(56) + subBar(34)
            }
        }

        y = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).also {
                it.topMargin = dp(90)   // header(56) + subBar(34)
            }
        }

        root.addView(header)
        root.addView(subBar)
        root.addView(z)
        root.addView(y)
        setContentView(root)
    }

    // ── v1.2.5: Nhập token thủ công ──────────────────────────────────────────

    /**
     * Dialog cho phép người dùng dán token JWT lấy được từ nguồn khác
     * (VD: DevTools trình duyệt, app khác) khi tự động lấy token thất bại.
     * Dùng lại y nguyên flow q(token) — lưu SharedPreferences + GolikeRepository,
     * cùng tiêu chuẩn validate isValidToken() như flow tự động.
     */
    private fun showManualTokenDialog() {
        val input = EditText(this).apply {
            hint = "Dán token (bắt đầu bằng eyJ...)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(TextView(this@GolikeLoginWebActivity).apply {
                text = "Mở DevTools (F12) trên trình duyệt đã đăng nhập Golike → " +
                       "Application/Storage → Local Storage → tìm key chứa 'token' → " +
                       "copy giá trị bắt đầu bằng 'eyJ' và dán vào đây."
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(dp(8), 0, dp(8), dp(8))
            })
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Nhập token thủ công")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                val raw   = input.text.toString()
                val clean = sanitizeToken(raw)   // v1.2.5 — bỏ "Bearer " + trim, đồng bộ golike.py
                when {
                    clean.isEmpty() ->
                        updateStatus("⚠ Chưa nhập token")
                    !isValidToken(clean) ->
                        updateStatus("⚠ Token không hợp lệ (cần đúng 3 phần header.payload.signature)")
                    else -> {
                        Log.d(TAG, "Token nhập thủ công: len=${clean.length}")
                        q(clean)   // dùng lại flow lưu + finish giống auto-detect
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .setCancelable(true)
            .show()
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
