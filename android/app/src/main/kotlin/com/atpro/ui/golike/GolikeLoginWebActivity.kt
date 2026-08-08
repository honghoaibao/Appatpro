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
 * GolikeLoginWebActivity — v1.2.2 (rewritten from new smali), v1.3.5 (dò token)
 *
 * Đọc từ GoLikeLoginActivity.smali (bản gốc, xem lịch sử bên dưới):
 *
 * ── Cờ (fields) ────────────────────────────────────────────────────────────
 *   C: Boolean  — đã nhận được token (chặn duplicate)
 *   E: Handler  — mainHandler (Looper.getMainLooper())
 *
 * ── Phương thức ─────────────────────────────────────────────────────────────
 *   p(String): Boolean  — validate token: startsWith("eyJ") && length > 50
 *                         HOẶC length > 100 (fallback)
 *   q(String): Unit     — nhận + lưu token (gọi từ bridge trên bg thread)
 *
 * ── v1.3.5 — VIẾT LẠI cơ chế dò token, KHÔNG còn theo smali gốc ──────────────
 * Lý do: bản gốc (smali port) chỉ bắt đầu dò token SAU KHI người dùng bấm nút
 * "Lấy ATH", và nếu không thấy token thì TỰ ĐỘNG `window.location.reload()`
 * mỗi 2s, tối đa 6 lần. Thực tế dùng cho thấy cơ chế này "may rủi, phải thử
 * nhiều lần mới được" — vì:
 *   1. Nếu người dùng bấm nút TRƯỚC KHI đăng nhập xong (rất dễ xảy ra — nút
 *      luôn hiện sẵn, không có gì ngăn bấm sớm), `reload()` sẽ TẢI LẠI TRANG
 *      NGAY GIỮA CHỪNG lúc họ đang nhập email/mật khẩu hoặc đang chờ xử lý
 *      đăng nhập — xoá sạch form/trạng thái đang nhập, phải đăng nhập lại từ
 *      đầu. Đây gần như chắc chắn là nguyên nhân chính của việc "phải thực
 *      hiện rất nhiều lần".
 *   2. Tổng thời gian chờ chỉ ~12s (6 lần × 2s) — không đủ cho 1 lượt đăng
 *      nhập thật (gõ email/mật khẩu, có thể có captcha/OTP).
 *   3. Nếu Golike là SPA (không tải lại trang sau khi đăng nhập thành công —
 *      chỉ điều hướng phía client), `onPageFinished` không fire lại sau khi
 *      đăng nhập xong → không có gì kích hoạt dò lại nếu người dùng chưa bấm
 *      nút đúng lúc.
 *
 * Cơ chế mới — dò LIÊN TỤC, KHÔNG BAO GIỜ reload trang:
 *   1. Ngay khi trang tải xong (`onPageFinished`) — dò token TỰ ĐỘNG, không
 *      cần đợi người dùng bấm nút gì cả.
 *   2. Dò bằng `setInterval` mỗi 1.2s trong tối đa 5 phút (đủ thời gian cho
 *      1 lượt đăng nhập thật) — quét lại y hệt logic cũ (localStorage,
 *      sessionStorage, cookie, `window.__NUXT__`/`__INITIAL_STATE__`, đệ quy
 *      JSON) nhưng KHÔNG bao giờ gọi `reload()`.
 *   3. Thêm `MutationObserver` trên `document.body` — khi DOM đổi nhiều (dấu
 *      hiệu SPA vừa điều hướng sau khi đăng nhập xong), dò lại NGAY thay vì
 *      đợi tick tiếp theo của interval.
 *   4. Nút "Lấy ATH" đổi vai trò: không còn là điều kiện BẮT BUỘC để bắt đầu
 *      dò (dò đã tự chạy từ bước 1) — giờ chỉ là nút "Kiểm tra ngay" để người
 *      dùng chủ động trigger 1 lượt quét tức thì + xem trạng thái, an toàn
 *      bấm bất cứ lúc nào vì không còn reload.
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
         * Áp dụng TRƯỚC khi gọi `\[isValidToken\]` — cả ở auto-extract VÀ nhập thủ công.
         */
        fun sanitizeToken(raw: String): String {
            var t = raw.trim()
            if (t.startsWith("Bearer ", ignoreCase = true)) {
                t = t.substring(7).trim()
            }
            return t
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    @Volatile var C = false          // tokenReceived
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

        // setWebViewClient(La2/f instance) — onPageFinished → startAutoPoll()
        y.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // v1.3.5: TỰ ĐỘNG bắt đầu dò token ngay khi trang tải xong —
                // không đợi người dùng bấm nút. Đây là fix chính cho lỗi
                // "may rủi, phải thử nhiều lần" (xem doc comment đầu file).
                updateStatus("🔍 Đang tự động dò token sau khi bạn đăng nhập...")
                startAutoPoll()
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

        // v1.3.5: nút không còn là điều kiện BẮT BUỘC để bắt đầu dò (đã tự
        // chạy từ onPageFinished) — giờ chỉ là "Kiểm tra ngay", an toàn bấm
        // bất cứ lúc nào vì startAutoPoll() không bao giờ reload trang.
        A.setOnClickListener {
            if (!C) {
                updateStatus("🔍 Đang kiểm tra...")
                startAutoPoll()
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

    // ── startAutoPoll(): inject JS dò token liên tục — v1.3.5 ──────────────────

    /**
     * v1.3.5 — thay thế hoàn toàn `r()` cũ. Không còn khái niệm "retry bằng
     * reload trang" — xem lý do ở doc comment đầu file.
     *
     * Gọi an toàn NHIỀU LẦN (từ `onPageFinished` VÀ từ nút "Kiểm tra ngay") —
     * mỗi lần gọi luôn quét ngay lập tức 1 lượt; phần khởi tạo
     * `setInterval`/`MutationObserver` tự chặn trùng bằng cờ JS
     * `window.__atproPolling` nên không bao giờ chồng nhiều interval.
     */
    fun startAutoPoll() {
        if (C) return          // đã có token — không cần dò nữa

        Log.d(TAG, "🔍 Bắt đầu/kiểm tra lại — dò token (không reload trang)")

        // JS — logic quét giữ nguyên từ v1.2.5 (localStorage/sessionStorage/
        // cookie/window.__NUXT__/deepFind đệ quy JSON), CHỈ đổi phần điều phối:
        // setInterval quét lặp lại thay vì reload trang.
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
            // v1.3.5: gom toàn bộ logic quét vào 1 hàm scanOnce() dùng lại được
            // cho cả lần quét ngay lập tức LẪN mỗi tick của setInterval.
            append("   function scanOnce() {")
            append("       try {")
            append("           for (var i = 0; i < localStorage.length; i++) {")
            append("               var val = localStorage.getItem(localStorage.key(i));")
            append("               if (val) {")
            append("                   if (sendToken(val)) return true;")
            append("                   var matches = val.match(tokenPattern);")
            append("                   if (matches && matches.length > 0 && sendToken(matches[0])) return true;")
            append("                   try {")
            append("                       var parsed = JSON.parse(val);")
            append("                       if (parsed) {")
            append("                           if (parsed.token && sendToken(parsed.token)) return true;")
            append("                           if (parsed.access_token && sendToken(parsed.access_token)) return true;")
            append("                           if (parsed.authorization && sendToken(parsed.authorization)) return true;")
            append("                           var deep = deepFind(parsed, 0);")
            append("                           if (deep && sendToken(deep)) return true;")
            append("                       }")
            append("                   } catch(e) {}")
            append("               }")
            append("           }")
            append("       } catch(e) {}")
            append("       try {")
            append("           for (var i = 0; i < sessionStorage.length; i++) {")
            append("               var val = sessionStorage.getItem(sessionStorage.key(i));")
            append("               if (val) {")
            append("                   if (sendToken(val)) return true;")
            append("                   try {")
            append("                       var parsedS = JSON.parse(val);")
            append("                       var deepS = deepFind(parsedS, 0);")
            append("                       if (deepS && sendToken(deepS)) return true;")
            append("                   } catch(e) {}")
            append("               }")
            append("           }")
            append("       } catch(e) {}")
            append("       try {")
            append("           var cookies = document.cookie.split(';');")
            append("           for (var i = 0; i < cookies.length; i++) {")
            append("               var parts = cookies[i].trim().split('=');")
            append("               if (parts.length >= 2) {")
            append("                   var val = parts.slice(1).join('=');")
            append("                   if (sendToken(val)) return true;")
            append("               }")
            append("           }")
            append("       } catch(e) {}")
            append("       try {")
            append("           if (window.__NUXT__) {")
            append("               var dn = deepFind(window.__NUXT__, 0);")
            append("               if (dn && sendToken(dn)) return true;")
            append("           }")
            append("           if (window.__INITIAL_STATE__) {")
            append("               var di = deepFind(window.__INITIAL_STATE__, 0);")
            append("               if (di && sendToken(di)) return true;")
            append("           }")
            append("       } catch(e) {}")
            append("       return false;")
            append("   }")
            // v1.3.5: quét NGAY lập tức mỗi lần hàm ngoài được gọi (kể cả khi
            // interval đã chạy sẵn từ trước) — để nút "Kiểm tra ngay" luôn có
            // tác dụng tức thì thay vì phải đợi tick tiếp theo.
            append("   if (scanOnce()) return;")
            // v1.3.5: phần khởi tạo interval/observer CHỈ chạy 1 LẦN — chặn
            // trùng bằng cờ toàn cục, tránh chồng nhiều interval nếu hàm này
            // được gọi lại nhiều lần (mỗi lần onPageFinished, mỗi lần bấm nút).
            append("   if (window.__atproPolling) return;")
            append("   window.__atproPolling = true;")
            append("   window.__atproTicks = 0;")
            // v1.3.5: KHÔNG reload trang nữa — chỉ setInterval quét lại, đủ
            // thời gian cho 1 lượt đăng nhập thật (250 tick * 1.2s ≈ 5 phút).
            append("   var MAX_TICKS = 250;")
            append("   window.__atproIntervalId = setInterval(function() {")
            append("       window.__atproTicks++;")
            append("       if (scanOnce()) {")
            append("           clearInterval(window.__atproIntervalId);")
            append("           window.__atproPolling = false;")
            append("           return;")
            append("       }")
            append("       if (window.__atproTicks >= MAX_TICKS) {")
            append("           clearInterval(window.__atproIntervalId);")
            append("           window.__atproPolling = false;")
            append("           try { AndroidTokenReceiver.onSearchExhausted(); } catch(e) {}")
            append("       }")
            append("   }, 1200);")
            // v1.3.5: MutationObserver — DOM đổi nhiều (dấu hiệu SPA vừa điều
            // hướng, VD sau khi đăng nhập xong) → quét lại NGAY, không đợi
            // tick tiếp theo. Chỉ là tối ưu tốc độ, interval vẫn là cơ chế
            // chính đảm bảo cuối cùng vẫn bắt được token.
            append("   try {")
            append("       var mo = new MutationObserver(function() {")
            append("           if (window.__atproPolling) scanOnce();")
            append("       });")
            append("       mo.observe(document.body, { childList: true, subtree: true });")
            append("   } catch(e) {}")
            append("})()")
        }

        y.evaluateJavascript(js, null)
    }

    // ── q(String): nhận + lưu token — theo smali ─────────────────────────────

    /**
     * Tương đương hàm q(GoLikeLoginActivity, String)V trong smali.
     * Gọi từ bridge trên JavaBridge thread → cần runOnUiThread.
     */
    fun q(token: String) {
        if (C) return   // đã nhận rồi — smali: if-eqz v0, :cond_0 → goto_0

        C = true

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
         * v1.3.5 — JS gọi khi đã dò đủ ~5 phút (250 tick × 1.2s) mà không tìm
         * thấy token. Gợi ý người dùng dùng nút "Nhập thủ công" thay vì tiếp
         * tục chờ. Không còn nghĩa là "thất bại nhanh" như bản reload-retry cũ
         * (~12s) — 5 phút là đủ thời gian cho 1 lượt đăng nhập thật, nên tới
         * đây thường là dấu hiệu Golike lưu token ở chỗ khác ngoài dự kiến.
         */
        @JavascriptInterface
        fun onSearchExhausted() {
            Log.w(TAG, "Đã dò ~5 phút — không tìm thấy token tự động")
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
            text      = "🔍 Kiểm tra ngay"
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
        A.text = "🔍 Kiểm tra ngay"
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
