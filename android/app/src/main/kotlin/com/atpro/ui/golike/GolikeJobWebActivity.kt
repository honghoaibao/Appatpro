package com.atpro.ui.golike

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.atpro.data.TikTokDeepLinks
import com.atpro.golike.GolikeJobReceiveResult
import com.atpro.golike.GolikeJobReportResult
import com.atpro.golike.GolikeJobBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * GolikeJobWebActivity — v1.3.3 (bổ sung)
 *
 * Thay thế luồng "nhận job" cũ (gọi thẳng REST API `GolikeApi.getTikTokJobs` /
 * `completeTikTokJob` / `skipTikTokJob`, xem `GolikeRepository.kt`) bằng luồng
 * GIẢ LẬP NGƯỜI THẬT thao tác trực tiếp trên `https://app.golike.net/jobs/tiktok`
 * bằng WebView + JS click mô phỏng (dispatch `MouseEvent` thật lên đúng phần
 * tử theo TEXT hiển thị, không gọi API trực tiếp).
 *
 * Đảo ngược quyết định trước đó — xem `.claude/decisions/ADR-0008-...`: bản
 * v1.3.3 gốc từng kết luận KHÔNG cần tự động hoá UI riêng của Golike; đây là
 * thay đổi có chủ đích theo yêu cầu mới của Bảo.
 *
 * ── Vì sao dùng JS click thay vì AccessibilityNodeInfo trên nội dung WebView ──
 * WebView (Chromium) CÓ expose cây accessibility cho nội dung DOM, nhưng độ
 * tin cậy/độ trễ khi dò qua `AccessibilityNodeInfo` với 1 trang SPA phức tạp
 * (nhiều lớp overlay/dialog) kém ổn định hơn nhiều so với việc chạy JS ngay
 * trong ngữ cảnh trang — JS luôn thấy đúng DOM hiện tại, không phụ thuộc
 * timing render-tree của accessibility. Click được dispatch bằng chuỗi sự
 * kiện `pointerdown/mousedown/pointerup/mouseup/click` (không gọi thẳng
 * `el.click()`) để giống thao tác chạm thật hơn — nhiều SPA chỉ lắng nghe
 * `mousedown`/`pointerdown` cho hiệu ứng nhấn, gọi `.click()` trực tiếp có
 * thể bỏ qua các listener đó.
 *
 * ── Vòng đời ─────────────────────────────────────────────────────────────
 * `launchMode="singleTask"` (xem AndroidManifest) — chỉ 1 instance sống suốt
 * phiên làm task của 1 tài khoản TikTok; `GolikeJobBridge.bringToFront()`
 * đưa activity đã có lên trước (onNewIntent, KHÔNG onCreate lại) mỗi khi cần
 * quay lại từ TikTok, giữ nguyên trạng thái đã chọn tài khoản + trang hiện tại.
 *
 * ── Các hàm public (được `GolikeJobBridge` gọi từ coroutine nền) ───────────
 *   selectAccount(label)   — Bước 1: chọn tài khoản kiếm thưởng
 *   receiveJob()            — Bước 2: "Nhận Job ngay" + popup xác nhận + đọc chi tiết
 *   clickTikTokButton()     — Bước 3: mở link nhiệm vụ (nút "TikTok")
 *   abandonJob()            — job không khớp loại cấu hình → back, để tự hết hạn
 *   completeJob()            — "Hoàn thành" + đọc dialog kết quả + xử lý Báo lỗi nếu cần
 */
class GolikeJobWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GolikeJobWeb"
        const val JOBS_URL = "https://app.golike.net/jobs/tiktok"

        /**
         * Thư viện JS nhỏ gắn vào `window.__atproJob` — tìm phần tử theo TEXT
         * hiển thị (không phụ thuộc class/id CSS, vốn có thể đổi bất cứ lúc
         * nào phía Golike) rồi dispatch chuỗi sự kiện chuột giống thao tác
         * chạm thật. Idempotent — có cờ `__atproJobInstalled` chặn cài lại
         * nhiều lần khi bị inject lại ở mỗi `onPageStarted`/`onPageFinished`.
         */
        private val JOB_SCRIPT = """
            (function() {
              if (window.__atproJobInstalled) { return true; }
              window.__atproJobInstalled = true;

              function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
              function textOf(el) {
                try { return norm(el.innerText !== undefined ? el.innerText : el.textContent); }
                catch (e) { return ''; }
              }
              function isVisible(el) {
                if (!el || !el.getBoundingClientRect) return false;
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                if (!cs) return true;
                if (cs.visibility === 'hidden' || cs.display === 'none') return false;
                if (parseFloat(cs.opacity || '1') === 0) return false;
                return true;
              }
              function findByText(target, exact, root) {
                root = root || document.body;
                var all = root.querySelectorAll('*');
                var best = null, bestLen = 1e9;
                for (var i = 0; i < all.length; i++) {
                  var el = all[i];
                  if (!isVisible(el)) continue;
                  var t = textOf(el);
                  if (!t) continue;
                  var match = exact ? (t === target) : (t.indexOf(target) !== -1);
                  if (!match) continue;
                  if (t.length < bestLen) { best = el; bestLen = t.length; }
                }
                return best;
              }
              function clickableAncestor(el) {
                var t = el, hops = 0;
                while (t && hops < 8) {
                  var tag = (t.tagName || '').toLowerCase();
                  var role = t.getAttribute ? t.getAttribute('role') : null;
                  var cursor = '';
                  try { cursor = window.getComputedStyle(t).cursor; } catch (e) {}
                  if (tag === 'button' || tag === 'a' || role === 'button' ||
                      cursor === 'pointer' || typeof t.onclick === 'function') {
                    return t;
                  }
                  t = t.parentElement;
                  hops++;
                }
                return el;
              }
              function realClick(el) {
                if (!el) return false;
                try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
                var target = clickableAncestor(el);
                var r = target.getBoundingClientRect();
                var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                var types = ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'];
                for (var i = 0; i < types.length; i++) {
                  var ev;
                  try {
                    ev = new MouseEvent(types[i], {
                      bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy
                    });
                  } catch (e) {
                    ev = document.createEvent('MouseEvent');
                    ev.initMouseEvent(types[i], true, true, window, 1, cx, cy, cx, cy, false, false, false, false, 0, null);
                  }
                  target.dispatchEvent(ev);
                }
                return true;
              }
              function clickText(target, exact) {
                var el = findByText(target, !!exact);
                if (!el) return false;
                return realClick(el);
              }

              window.__atproJob = {
                openAccountPicker: function() {
                  return clickText('Tài khoản kiếm thưởng', false);
                },
                selectAccountByLabel: function(label) {
                  var target = (label || '').toLowerCase();
                  if (!target) return false;
                  var all = document.querySelectorAll('*');
                  var best = null, bestLen = 1e9;
                  for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!isVisible(el)) continue;
                    var t = textOf(el);
                    if (!t) continue;
                    if (t.toLowerCase().indexOf(target) === -1) continue;
                    if (t.length < bestLen) { best = el; bestLen = t.length; }
                  }
                  if (!best) return false;
                  return realClick(best);
                },
                clickReceiveJobNow: function() {
                  return clickText('Nhận Job ngay', false);
                },
                hasConfirmPopup: function() {
                  return !!findByText('Xác nhận làm việc bằng App', false);
                },
                clickPopupUnderstood: function() {
                  return clickText('Đã hiểu', true) || clickText('Đã hiểu', false);
                },
                clickPopupAgree: function() {
                  return clickText('Đồng ý', true) || clickText('Đồng ý', false);
                },
                getJobDetail: function() {
                  var isLike = !!findByText('TĂNG LIKE CHO BÀI VIẾT', false);
                  var isFollow = !!findByText('TĂNG LƯỢT THEO DÕI', false);
                  var hasHoanThanh = !!findByText('Hoàn thành', false);
                  var jobId = '';
                  var all = document.querySelectorAll('*');
                  for (var i = 0; i < all.length; i++) {
                    var t = textOf(all[i]);
                    if (t.indexOf('Job Id:') !== -1 && t.length < 60) { jobId = t; break; }
                  }
                  var found = isLike || isFollow || hasHoanThanh;
                  var type = isLike ? 'like' : (isFollow ? 'follow' : 'unknown');
                  return JSON.stringify({ found: found, type: type, jobId: jobId });
                },
                clickTikTokButton: function() {
                  return clickText('TikTok', true);
                },
                clickHoanThanh: function() {
                  return clickText('Hoàn thành', false);
                },
                clickBaoLoi: function() {
                  return clickText('Báo lỗi', false);
                },
                readResultDialog: function() {
                  var success = !!findByText('Thành công', true);
                  var error = !!findByText('Lỗi', true);
                  var status = success ? 'success' : (error ? 'error' : 'none');
                  return JSON.stringify({ status: status });
                },
                clickDialogOk: function() {
                  return clickText('OK', true) || clickText('OK', false);
                },
                scrollAndClickSendReport: function() {
                  try { window.scrollTo(0, document.body.scrollHeight); } catch (e) {}
                  var labels = ['Gửi báo cáo', 'Gửi Báo Cáo', 'GỬI BÁO CÁO'];
                  for (var i = 0; i < labels.length; i++) {
                    if (clickText(labels[i], false)) return true;
                  }
                  return false;
                },
                goBack: function() {
                  try { window.history.back(); return true; } catch (e) { return false; }
                }
              };
              return true;
            })();
        """.trimIndent()
    }

    private lateinit var webView: WebView
    private lateinit var statusLabel: TextView

    /** true sau khi trang tải xong lần đầu + JS đã cài đặt — `GolikeJobBridge` chờ cờ này. */
    @Volatile
    var isPageReady: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        setupWebView()
        GolikeJobBridge.attach(this)
        if (webView.url.isNullOrEmpty()) {
            Log.d(TAG, "Load: $JOBS_URL")
            webView.loadUrl(JOBS_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask — activity được tái dùng (onNewIntent, KHÔNG onCreate lại),
        // WebView giữ nguyên trang + tài khoản đã chọn. Không cần làm gì thêm.
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        GolikeJobBridge.detach(this)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            setDomStorageEnabled(true)
            setLoadWithOverviewMode(true)
            setUseWideViewPort(true)
            setBuiltInZoomControls(false)
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageReady = false
                updateStatus("Đang tải trang Golike...")
                injectScript()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectScript()
                isPageReady = true
                updateStatus("Sẵn sàng")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) updateStatus("Lỗi tải trang Golike")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleOutgoingUrl(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                // v1.3.3 (bổ sung) — nút "TikTok" trên trang chi tiết job có thể
                // mở bằng target=_blank (cửa sổ mới) thay vì điều hướng cùng
                // WebView — bắt qua WebView "transport" tạm rồi đọc URL đích.
                val transport = WebView(this@GolikeJobWebActivity)
                transport.settings.javaScriptEnabled = true
                transport.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val url = req?.url?.toString() ?: return false
                        handleOutgoingUrl(url)
                        return true
                    }
                }
                val transportObj = resultMsg?.obj as? WebView.WebViewTransport
                transportObj?.webView = transport
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    /**
     * Bắt điều hướng ra ngoài trang web (scheme khác http/https — deep link
     * TikTok kiểu `snssdk1180://...`, hoặc `intent://...#Intent;...;end`) và
     * mở bằng Intent thay vì để WebView tự load (WebView không tự xử lý
     * custom scheme, sẽ báo lỗi ERR_UNKNOWN_URL_SCHEME và không làm gì cả).
     * Trả true nếu đã "nuốt" điều hướng này (không cho WebView load tiếp).
     */
    private fun handleOutgoingUrl(url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        try {
            if (url.startsWith("intent://")) {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                TikTokDeepLinks.openDeepLink(this, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOutgoingUrl($url): ${e.message}")
        }
        return true
    }

    // ── API public — gọi từ GolikeJobBridge (coroutine nền) ────────────────

    /** Bước 1 — mở dropdown "Tài khoản kiếm thưởng" rồi chọn dòng khớp `\[label\]`. */
    suspend fun selectAccount(label: String): Boolean {
        injectScript()
        val opened = pollUntil(6_000) { callBool("window.__atproJob.openAccountPicker()") }
        if (!opened) return false
        delay(500)
        return pollUntil(6_000) { callBool("window.__atproJob.selectAccountByLabel(${jsStr(label)})") }
    }

    /** Bước 2 — "Nhận Job ngay" → popup "Đã hiểu"/"Đồng ý" → đọc trang chi tiết. */
    suspend fun receiveJob(): GolikeJobReceiveResult {
        injectScript()
        val clicked = pollUntil(8_000) { callBool("window.__atproJob.clickReceiveJobNow()") }
        if (!clicked) return GolikeJobReceiveResult.None

        val popupShown = pollUntil(5_000, 300L) { callBool("window.__atproJob.hasConfirmPopup()") }
        if (popupShown) {
            pollUntil(3_000) { callBool("window.__atproJob.clickPopupUnderstood()") }
            delay(400)
            pollUntil(3_000) { callBool("window.__atproJob.clickPopupAgree()") }
        }

        var detail = JSONObject()
        val detailReady = pollUntil(7_000, 400L) {
            detail = callJson("window.__atproJob.getJobDetail()")
            detail.optBoolean("found", false)
        }
        if (!detailReady) return GolikeJobReceiveResult.Failed("Không thấy trang chi tiết job sau khi nhận")

        return GolikeJobReceiveResult.Received(
            type = detail.optString("type", "unknown"),
            jobIdLabel = detail.optString("jobId", ""),
        )
    }

    /** Bước 3 — bấm nút "TikTok" trên trang chi tiết để mở link nhiệm vụ. */
    suspend fun clickTikTokButton(): Boolean =
        pollUntil(5_000) { callBool("window.__atproJob.clickTikTokButton()") }

    /** Job không khớp loại đã cấu hình — thoát trang chi tiết, để job tự hết hạn. */
    suspend fun abandonJob(): Boolean = callBool("window.__atproJob.goBack()")

    /** "Hoàn thành" → đọc dialog kết quả → OK, hoặc OK → Báo lỗi → cuộn → Gửi báo cáo → OK. */
    suspend fun completeJob(): GolikeJobReportResult {
        val clicked = pollUntil(5_000) { callBool("window.__atproJob.clickHoanThanh()") }
        if (!clicked) return GolikeJobReportResult.Unknown

        var status = "none"
        val dialogShown = pollUntil(10_000, 400L) {
            status = callJson("window.__atproJob.readResultDialog()").optString("status", "none")
            status != "none"
        }
        if (!dialogShown) return GolikeJobReportResult.Unknown

        return when (status) {
            "success" -> {
                pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                GolikeJobReportResult.Success
            }
            "error" -> {
                pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                delay(500)
                pollUntil(4_000) { callBool("window.__atproJob.clickBaoLoi()") }
                delay(700)
                pollUntil(4_000) { callBool("window.__atproJob.scrollAndClickSendReport()") }
                delay(900)
                pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                GolikeJobReportResult.ErrorReported
            }
            else -> GolikeJobReportResult.Unknown
        }
    }

    // ── Helpers JS bridge ────────────────────────────────────────────────

    private fun injectScript() {
        if (::webView.isInitialized) webView.evaluateJavascript(JOB_SCRIPT, null)
    }

    private suspend fun evalJs(script: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            if (isFinishing || !::webView.isInitialized) {
                if (cont.isActive) cont.resume("null") {}
                return@suspendCancellableCoroutine
            }
            try {
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result ?: "null") {}
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("null") {}
            }
        }
    }

    private suspend fun callBool(expr: String): Boolean {
        val raw = evalJs("(function(){try{return !!($expr);}catch(e){return false;}})();")
        return raw.trim() == "true"
    }

    private suspend fun callJson(expr: String): JSONObject {
        val raw = evalJs(
            "(function(){try{return ($expr);}catch(e){return JSON.stringify({error:String(e)});}})();"
        )
        return decodeJsResult(raw)
    }

    private fun decodeJsResult(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") return JSONObject()
        return try {
            val unwrapped = if (raw.startsWith("\"")) {
                (JSONTokener(raw).nextValue() as? String) ?: raw
            } else raw
            JSONObject(unwrapped)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private suspend fun pollUntil(
        timeoutMs: Long,
        intervalMs: Long = 400L,
        check: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            delay(intervalMs)
        }
        return false
    }

    /** Escape chuỗi Kotlin thành literal string JS an toàn (bọc nháy đơn). */
    private fun jsStr(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")
        return "'$esc'"
    }

    private fun updateStatus(text: String) {
        runOnUiThread { if (::statusLabel.isInitialized) statusLabel.text = text }
    }

    // ── UI tối giản: thanh trạng thái + nút đóng + WebView full màn hình ───

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(match, match)
            setBackgroundColor(Color.parseColor("#0D0D14"))
        }

        val header = RelativeLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(match, dp(48)).also { it.gravity = Gravity.TOP }
            setBackgroundColor(Color.parseColor("#13131F"))
        }

        statusLabel = TextView(this).apply {
            text = "Đang mở nhiệm vụ Golike..."
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = RelativeLayout.LayoutParams(match, match).also {
                it.setMargins(dp(14), 0, dp(70), 0)
            }
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            layoutParams = RelativeLayout.LayoutParams(dp(44), dp(32)).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.setMargins(0, 0, dp(10), 0)
            }
        }
        closeBtn.setOnClickListener { finish() }

        header.addView(statusLabel)
        header.addView(closeBtn)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(match, match).also { it.topMargin = dp(48) }
        }

        root.addView(header)
        root.addView(webView)
        setContentView(root)
    }
}
