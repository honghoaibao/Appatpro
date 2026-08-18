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
import kotlinx.coroutines.withTimeoutOrNull
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
 *   selectAccount(expectedLabel) — Bước 1: chọn acc ĐẦU DANH SÁCH (luôn là
 *                            acc đang đăng nhập trên thiết bị — không dò
 *                            theo username; expectedLabel chỉ để log debug)
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
         * v1.3.3 (bổ sung 4) — trần cứng cho mỗi lệnh gọi JS (`evalJs()`).
         * Xem giải thích đầy đủ tại `evalJs()`.
         */
        private const val EVAL_JS_TIMEOUT_MS = 6_000L

        /** v1.3.3 (bổ sung 4) — thời gian tối đa chờ Activity resume trước khi vẫn cứ thử gọi JS. */
        private const val RESUME_WAIT_TIMEOUT_MS = 4_000L

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
                // v1.3.3 (bổ sung 2) — acc ĐẦU DANH SÁCH trong dropdown LUÔN
                // là acc TikTok đang đăng nhập trên thiết bị (xác nhận qua
                // kiểm thử thực tế) — không cần dò/so khớp theo username nữa.
                // Tìm dòng account đầu tiên nằm DƯỚI header "Tài khoản kiếm
                // thưởng": phần tử lá (không có con nào cũng khớp điều kiện),
                // text ngắn kiểu username 1 dòng, top nhỏ nhất trong các ứng
                // viên hợp lệ = gần header nhất = đầu danh sách.
                selectFirstAccountInList: function() {
                  var header = findByText('Tài khoản kiếm thưởng', false);
                  if (!header) return false;
                  var headerRow = header, hops = 0;
                  while (headerRow.parentElement && hops < 6) {
                    var hr = headerRow.getBoundingClientRect();
                    if (hr.width > window.innerWidth * 0.7) break;
                    headerRow = headerRow.parentElement;
                    hops++;
                  }
                  var headerBottom = headerRow.getBoundingClientRect().bottom;

                  var all = document.querySelectorAll('*');
                  var candidates = [];
                  for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!isVisible(el)) continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.top < headerBottom - 2) continue;
                    var t = textOf(el);
                    if (!t || t.length < 2 || t.length > 40) continue;
                    if (t.split(' ').length > 4) continue;
                    var hasMatchingChild = false;
                    for (var c = 0; c < el.children.length; c++) {
                      var ct = textOf(el.children[c]);
                      if (ct && ct.length >= 2 && ct.length <= 40) { hasMatchingChild = true; break; }
                    }
                    if (hasMatchingChild) continue;
                    candidates.push({ el: el, top: rect.top });
                  }
                  if (!candidates.length) return false;
                  candidates.sort(function(a, b) { return a.top - b.top; });
                  return realClick(candidates[0].el);
                },
                // Đọc lại tên account đang hiển thị ở header sau khi chọn —
                // chỉ để LOG đối chiếu debug phía Kotlin, không dùng để quyết
                // định click.
                getSelectedAccountLabel: function() {
                  var header = findByText('Tài khoản kiếm thưởng', false);
                  if (!header) return '';
                  var row = header, hops = 0;
                  while (row.parentElement && hops < 5) {
                    var t = textOf(row);
                    if (t.length > 'Tài khoản kiếm thưởng'.length + 2) break;
                    row = row.parentElement;
                    hops++;
                  }
                  if (!row) return '';
                  return textOf(row).split('Tài khoản kiếm thưởng').join('').trim();
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

    /**
     * v1.3.3 (bổ sung 4) — true khi Activity thật sự đang RESUMED (foreground,
     * có focus). Fix bug "mở trang xong tool bị dừng, không thao tác": trước
     * bản này, `evalJs()` gọi `webView.evaluateJavascript()` ngay lập tức sau
     * `GolikeJobBridge.bringToFront()` mà không đợi Activity thật sự resume —
     * trên 1 số thiết bị/ROM, `startActivity()` gọi từ AccessibilityService
     * (background) có thể bị hệ thống trì hoãn resume vài giây; JS gọi lúc đó
     * có thể không bao giờ chạy (callback không bao giờ fire) → coroutine treo
     * vĩnh viễn ở `suspendCancellableCoroutine` (không có timeout riêng trước
     * đây) → toàn bộ taskOneAccount() bị "đứng", không lỗi, không tiếp tục.
     */
    @Volatile
    private var isResumed: Boolean = false

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

    override fun onResume() {
        super.onResume()
        isResumed = true
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
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

    /** Bước 1 — mở dropdown "Tài khoản kiếm thưởng" rồi chọn acc ĐẦU DANH SÁCH.
     *
     * v1.3.3 (bổ sung 2) — acc đầu danh sách LUÔN là acc TikTok đang đăng
     * nhập trên thiết bị (xác nhận qua kiểm thử thực tế) — không cần dò/so
     * khớp theo username nữa, tức không cần "đọc hồ sơ" TikTok trước để biết
     * tên acc cần tìm. `\[expectedLabel\]` CHỈ dùng để log đối chiếu debug (cảnh
     * báo nếu acc đầu danh sách không khớp kỳ vọng) — KHÔNG ảnh hưởng quyết
     * định click.
     */
    suspend fun selectAccount(expectedLabel: String): Boolean {
        injectScript()
        val opened = pollUntil(6_000) { callBool("window.__atproJob.openAccountPicker()") }
        if (!opened) return false
        delay(500)
        val clicked = pollUntil(6_000) { callBool("window.__atproJob.selectFirstAccountInList()") }
        if (clicked) {
            delay(300)
            val actual = decodeJsString(evalJs("window.__atproJob.getSelectedAccountLabel()"))
            if (expectedLabel.isNotBlank() && !actual.contains(expectedLabel, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "selectAccount: acc đầu danh sách ('$actual') không khớp kỳ vọng " +
                        "('$expectedLabel') — vẫn tiếp tục, tin tưởng acc đầu danh sách",
                )
            }
        }
        return clicked
    }

    /** Bước 2 — "Nhận Job ngay" → popup "Đã hiểu"/"Đồng ý" → đọc trang chi tiết. */
    suspend fun receiveJob(): GolikeJobReceiveResult {
        injectScript()
        val clicked = pollUntil(8_000) { callBool("window.__atproJob.clickReceiveJobNow()") }
        if (!clicked) {
            Log.i(TAG, "POPUP-GOLIKE: Không bấm được 'Nhận Job ngay' trong 8s — coi như không có job")
            return GolikeJobReceiveResult.None
        }

        // v1.3.3 (bổ sung 4) — log popup xác nhận "Xác nhận làm việc bằng App"
        // (Đã hiểu → Đồng ý) — trước bản này bước này hoàn toàn im lặng, khó
        // biết được popup có thật sự xuất hiện/được xử lý hay không khi debug.
        val popupShown = pollUntil(5_000, 300L) { callBool("window.__atproJob.hasConfirmPopup()") }
        if (popupShown) {
            Log.i(TAG, "POPUP-GOLIKE: Popup 'Xác nhận làm việc bằng App' xuất hiện — bấm Đã hiểu → Đồng ý")
            val understood = pollUntil(3_000) { callBool("window.__atproJob.clickPopupUnderstood()") }
            delay(400)
            val agreed = pollUntil(3_000) { callBool("window.__atproJob.clickPopupAgree()") }
            Log.i(TAG, "POPUP-GOLIKE: Đã hiểu=$understood, Đồng ý=$agreed")
        } else {
            Log.i(TAG, "POPUP-GOLIKE: Không thấy popup xác nhận trong 5s (có thể không xuất hiện lần này)")
        }

        var detail = JSONObject()
        val detailReady = pollUntil(7_000, 400L) {
            detail = callJson("window.__atproJob.getJobDetail()")
            detail.optBoolean("found", false)
        }
        if (!detailReady) {
            Log.w(TAG, "POPUP-GOLIKE: Không thấy trang chi tiết job sau 7s (clicked=$clicked, popupShown=$popupShown)")
            return GolikeJobReceiveResult.Failed("Không thấy trang chi tiết job sau khi nhận")
        }

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
        if (!clicked) {
            Log.w(TAG, "POPUP-GOLIKE: Không bấm được 'Hoàn thành' trong 5s")
            return GolikeJobReportResult.Unknown
        }

        var status = "none"
        val dialogShown = pollUntil(10_000, 400L) {
            status = callJson("window.__atproJob.readResultDialog()").optString("status", "none")
            status != "none"
        }
        if (!dialogShown) {
            Log.w(TAG, "POPUP-GOLIKE: Không thấy dialog kết quả (Thành công/Lỗi) sau 10s")
            return GolikeJobReportResult.Unknown
        }
        Log.i(TAG, "POPUP-GOLIKE: Dialog kết quả = '$status'")

        return when (status) {
            "success" -> {
                val ok = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                Log.i(TAG, "POPUP-GOLIKE: Dialog Thành công → bấm OK ($ok)")
                GolikeJobReportResult.Success
            }
            "error" -> {
                val ok1 = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                delay(500)
                val baoLoi = pollUntil(4_000) { callBool("window.__atproJob.clickBaoLoi()") }
                delay(700)
                val guiBaoCao = pollUntil(4_000) { callBool("window.__atproJob.scrollAndClickSendReport()") }
                delay(900)
                val ok2 = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                Log.i(
                    TAG,
                    "POPUP-GOLIKE: Dialog Lỗi → OK=$ok1, Báo lỗi=$baoLoi, Gửi báo cáo=$guiBaoCao, OK cuối=$ok2",
                )
                GolikeJobReportResult.ErrorReported
            }
            else -> GolikeJobReportResult.Unknown
        }
    }

    // ── Helpers JS bridge ────────────────────────────────────────────────

    /**
     * v1.3.3 (bổ sung 5) — FIX crash thật gặp trên thiết bị: "A WebView
     * method was called on thread 'DefaultDispatcher-worker-N'". Nguyên
     * nhân: hàm này gọi thẳng `webView.evaluateJavascript()` KHÔNG qua
     * `withContext(Dispatchers.Main...)` như `evalJs()` — trong khi nó được
     * gọi làm bước ĐẦU TIÊN trong `selectAccount()`/`receiveJob()`, chạy
     * trên coroutine scope của `AutomationEngine` (Dispatchers.Default, không
     * phải main). Dùng `runOnUiThread {}` — an toàn gọi từ BẤT KỲ thread nào
     * (thực thi ngay nếu đã ở main, tự post vào main Looper nếu không).
     */
    private fun injectScript() {
        if (!::webView.isInitialized) return
        runOnUiThread {
            if (::webView.isInitialized && !isFinishing) {
                webView.evaluateJavascript(JOB_SCRIPT, null)
            }
        }
    }

    /**
     * v1.3.3 (bổ sung 4) — FIX bug "mở trang xong tool bị dừng, không thao
     * tác": trước bản này hàm này KHÔNG có timeout riêng — nếu callback của
     * `evaluateJavascript()` không bao giờ fire (Activity chưa thật sự
     * resume, WebView bị hệ thống throttle...), `suspendCancellableCoroutine`
     * treo VĨNH VIỄN, kéo theo toàn bộ `taskOneAccount()` đứng im không lỗi.
     *
     * 2 lớp bảo vệ:
     *   1. `waitForResumed()` — đợi CÓ GIỚI HẠN Activity thật sự resume
     *      trước khi gọi JS (giảm khả năng rơi vào tình huống treo ngay từ đầu).
     *   2. `withTimeoutOrNull(EVAL_JS_TIMEOUT_MS)` — trần cứng, đảm bảo hàm
     *      LUÔN trả về (rơi về "null") dù callback native có bao giờ fire
     *      hay không. An toàn: callback fire trễ sau timeout vẫn được guard
     *      bởi `cont.isActive` (đã false sau khi bị hủy) nên không resume 2 lần.
     */
    private suspend fun evalJs(script: String): String {
        waitForResumed()
        val result = withTimeoutOrNull(EVAL_JS_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    if (isFinishing || !::webView.isInitialized) {
                        if (cont.isActive) cont.resume("null") {}
                        return@suspendCancellableCoroutine
                    }
                    try {
                        webView.evaluateJavascript(script) { jsResult ->
                            if (cont.isActive) cont.resume(jsResult ?: "null") {}
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume("null") {}
                    }
                }
            }
        }
        if (result == null) {
            Log.w(
                TAG,
                "evalJs: TIMEOUT sau ${EVAL_JS_TIMEOUT_MS}ms (isResumed=$isResumed) — " +
                    "script=${script.take(80)}",
            )
        }
        return result ?: "null"
    }

    /** Đợi CÓ GIỚI HẠN Activity thật sự resume — xem giải thích ở `evalJs()`. */
    private suspend fun waitForResumed() {
        if (isResumed) return
        val deadline = System.currentTimeMillis() + RESUME_WAIT_TIMEOUT_MS
        var waited = false
        while (!isResumed && System.currentTimeMillis() < deadline) {
            waited = true
            delay(100L)
        }
        if (waited && !isResumed) {
            Log.w(TAG, "waitForResumed: vẫn chưa resume sau ${RESUME_WAIT_TIMEOUT_MS}ms — vẫn thử gọi JS")
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

    /** Giải mã kết quả JS trả về 1 chuỗi thường (không phải JSON object) — vd `getSelectedAccountLabel()`. */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            if (raw.startsWith("\"")) (JSONTokener(raw).nextValue() as? String) ?: "" else raw
        } catch (e: Exception) {
            ""
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
