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
import com.atpro.golike.CaptchaScreenPoints
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
import kotlin.random.Random

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
 * kiện `pointerdown/mousedown/pointerup/mouseup` trước (giống thao tác chạm
 * thật, nhiều SPA cần các sự kiện này riêng cho hiệu ứng nhấn), sau đó gọi
 * `target.click()` THẬT cho bước "click" cuối cùng (v1.3.5 — trước đó dispatch
 * thêm 1 `MouseEvent('click')` thay vì gọi `.click()`, xem lý do đổi + bug đã
 * gặp tại `realClick()`: sự kiện dispatch bằng tay là "untrusted", KHÔNG tự
 * kích hoạt default action của phần tử như theo `href` của thẻ `<a>` — chỉ
 * `el.click()` (hoặc chạm thật) mới chắc chắn làm việc đó).
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
         * v1.3.3 (bổ sung 6) — trần chờ trang chi tiết job sẵn sàng sau khi
         * nhận job (tăng từ 7s cũ) — Bảo xác nhận thực tế có thể mất 1-20s.
         */
        private const val POST_RECEIVE_WAIT_MS = 22_000L

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
                // v1.3.5 FIX: 'click' KHÔNG còn nằm trong types dispatch bằng
                // tay bên dưới — xem lý do ở target.click() cuối hàm.
                var types = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
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
                // v1.3.5 FIX (Bảo xác nhận: bấm tay thật → TikTok mở bình
                // thường; tool bấm hộ → không mở) — dùng target.click() THẬT
                // cho bước cuối thay vì dispatch thêm 1 MouseEvent('click')
                // như trước. Lý do: sự kiện tạo bằng dispatchEvent() là
                // "untrusted" (isTrusted = false) theo chuẩn DOM — trình
                // duyệt CHỈ chạy "default action" của phần tử (vd thẻ
                // <a href="..."> tự điều hướng theo href) cho sự kiện
                // "trusted" (người dùng chạm thật) HOẶC khi gọi qua API
                // el.click() (API này LUÔN kích hoạt default action bất kể
                // trusted hay không, theo đặc tả HTML). Nếu nút "TikTok" là
                // thẻ <a href> thật (rất có thể, vì đây là nút mở link ngoài
                // trang), dispatch suông trước đây chỉ chạy đúng các JS
                // listener (nếu có) — KHÔNG bao giờ thực sự điều hướng đi
                // đâu — khớp chính xác hiện tượng quan sát được. Giữ nguyên
                // chuỗi pointerdown/mousedown/pointerup/mouseup phía trên
                // (một số SPA cần các sự kiện này riêng cho hiệu ứng nhấn),
                // chỉ đổi bước "click" cuối cùng.
                try {
                  target.click();
                } catch (e) {
                  var ev2;
                  try {
                    ev2 = new MouseEvent('click', {
                      bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy
                    });
                  } catch (e2) {
                    ev2 = document.createEvent('MouseEvent');
                    ev2.initMouseEvent('click', true, true, window, 1, cx, cy, cx, cy, false, false, false, false, 0, null);
                  }
                  target.dispatchEvent(ev2);
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
                },

                // v1.3.3 (bổ sung 6) — Captcha kéo-thả "Kéo khối vuông đi
                // xuyên qua vòng nét đứt rồi thả vào vòng đích" — CHỈ xuất
                // hiện sau khi bấm "Nhận Job ngay".
                detectDragCaptcha: function() {
                  return !!findByText('xuyên qua vòng nét đứt', false) ||
                         !!findByText('vòng nét đứt', false);
                },
                // Định vị 3 điểm (tâm phần tử, toạ độ CSS px relative viewport):
                // khối vuông kéo được, vòng nét đứt (waypoint), vòng đích.
                // Heuristic (không có quyền xem DOM thật): vòng = phần tử gần
                // vuông (tỉ lệ w/h ~1) có border-radius >= ~35% cạnh nhỏ nhất;
                // trong đó border-style chứa "dashed" → vòng nét đứt, còn lại
                // → vòng đích. Khối vuông = phần tử KHÔNG tròn, có nền màu,
                // kích thước nhỏ (24-90px), GẦN vòng nét đứt nhất (loại các
                // khối màu khác không liên quan trên trang).
                getDragCaptchaPoints: function() {
                  function centerOf(el) {
                    var r = el.getBoundingClientRect();
                    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
                  }
                  function roughlySquareBox(r) {
                    if (r.width < 20 || r.height < 20) return false;
                    var ratio = r.width / r.height;
                    return ratio > 0.75 && ratio < 1.35;
                  }
                  function radiusFraction(el, r) {
                    var cs = window.getComputedStyle(el);
                    var br = cs.borderTopLeftRadius || cs.borderRadius || '';
                    if (br.indexOf('%') !== -1) return parseFloat(br) / 100;
                    var px = parseFloat(br) || 0;
                    return px / Math.min(r.width, r.height);
                  }

                  var all = document.querySelectorAll('*');
                  var dashedCircle = null, targetCircle = null, squareCandidates = [];

                  for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    if (!isVisible(el)) continue;
                    var r = el.getBoundingClientRect();
                    if (!roughlySquareBox(r)) continue;
                    if (r.width < 24 || r.width > 140) continue;

                    var cs = window.getComputedStyle(el);
                    var isCircleShape = radiusFraction(el, r) >= 0.35;
                    var borderStyle = (cs.borderTopStyle || cs.borderStyle || '');
                    var bg = cs.backgroundColor || '';
                    var hasBg = bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent';

                    if (isCircleShape && borderStyle.indexOf('dashed') !== -1) {
                      if (!dashedCircle) dashedCircle = el;
                      continue;
                    }
                    if (isCircleShape) {
                      if (!targetCircle) targetCircle = el;
                      continue;
                    }
                    if (hasBg && r.width <= 90 && r.height <= 90) {
                      squareCandidates.push(el);
                    }
                  }

                  if (!dashedCircle || !targetCircle || squareCandidates.length === 0) {
                    return JSON.stringify({ found: false });
                  }

                  var dc = centerOf(dashedCircle);
                  squareCandidates.sort(function(a, b) {
                    var ca = centerOf(a), cb = centerOf(b);
                    var da = Math.pow(ca.x - dc.x, 2) + Math.pow(ca.y - dc.y, 2);
                    var db = Math.pow(cb.x - dc.x, 2) + Math.pow(cb.y - dc.y, 2);
                    return da - db;
                  });

                  return JSON.stringify({
                    found: true,
                    square: centerOf(squareCandidates[0]),
                    dashedCircle: dc,
                    targetCircle: centerOf(targetCircle)
                  });
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
        humanDelay(500, 1_000)
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

    /** Bước 2a — bấm "Nhận Job ngay". */
    suspend fun clickReceiveJobNow(): Boolean {
        injectScript()
        val clicked = pollUntil(8_000) { callBool("window.__atproJob.clickReceiveJobNow()") }
        if (!clicked) {
            Log.i(TAG, "POPUP-GOLIKE: Không bấm được 'Nhận Job ngay' trong 8s — coi như không có job")
        }
        return clicked
    }

    /**
     * v1.3.3 (bổ sung 6) — Phát hiện captcha kéo-thả "Kéo khối vuông đi
     * xuyên qua vòng nét đứt rồi thả vào vòng đích" — CHỈ xuất hiện sau khi
     * bấm "Nhận Job ngay" (Bước 2a). Trả null nếu không có captcha; nếu có,
     * quy đổi toạ độ CSS (JS) sang toạ độ MÀN HÌNH THẬT (px) để
     * `AutomationEngine` dùng `host.dragPath()` — CỬ CHỈ THẬT qua
     * AccessibilityService, KHÔNG dùng JS-dispatch synthetic event như các
     * nút bấm khác trong file này: captcha kéo-thả thường được thiết kế để
     * phát hiện/chặn synthetic pointer event (`new MouseEvent(...)` có
     * `isTrusted = false`, nhiều thư viện anti-bot kiểm tra cờ này hoặc phân
     * tích pattern di chuyển) — cử chỉ thật đáng tin cậy hơn hẳn ở đây, nhất
     * quán với ADR-0003 (AccessibilityService là driver chính cho mọi tương
     * tác cần "giống người thật").
     *
     * Quy đổi: `screenPx = webViewScreenXY + cssPx * webView.scale * density`.
     * GIẢ ĐỊNH trang Golike có `<meta name="viewport">` chuẩn (1 CSS px ≈ 1dp)
     * — CHƯA xác minh được trên DOM thật (ngoài phạm vi domain công cụ cho phép).
     */
    suspend fun detectCaptchaPoints(): CaptchaScreenPoints? {
        val json = callJson("window.__atproJob.getDragCaptchaPoints()")
        if (!json.optBoolean("found", false)) return null

        val square = json.optJSONObject("square")
        val dashed = json.optJSONObject("dashedCircle")
        val target = json.optJSONObject("targetCircle")
        if (square == null || dashed == null || target == null) return null

        val loc = IntArray(2)
        webView.getLocationOnScreen(loc)
        val density = resources.displayMetrics.density
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val factor = density * scale

        fun toScreen(obj: JSONObject): Pair<Int, Int> {
            val x = loc[0] + (obj.optDouble("x", 0.0) * factor).toInt()
            val y = loc[1] + (obj.optDouble("y", 0.0) * factor).toInt()
            return x to y
        }

        val points = CaptchaScreenPoints(toScreen(square), toScreen(dashed), toScreen(target))
        Log.i(
            TAG,
            "POPUP-GOLIKE: Captcha kéo-thả phát hiện — square=${points.square} " +
                "dashed=${points.dashedCircle} target=${points.targetCircle}",
        )
        return points
    }

    /** Kiểm tra captcha ĐÃ BIẾN MẤT (đã giải xong) hay chưa — gọi lại sau khi kéo. */
    suspend fun isCaptchaGone(): Boolean = !callBool("window.__atproJob.detectDragCaptcha()")

    /**
     * Bước 2b — phần còn lại SAU KHI đã xử lý captcha (nếu có): popup xác
     * nhận "Xác nhận làm việc bằng App" (Đã hiểu → Đồng ý) → đọc trang chi
     * tiết job.
     *
     * v1.3.3 (bổ sung 6) — timeout chờ trang chi tiết tăng từ 7s lên
     * `POST_RECEIVE_WAIT_MS` (22s): Bảo xác nhận qua thực tế "sau khi nhận
     * jobs có thể phải đợi từ 1-20s" — 7s cũ có thể chưa đủ trong trường hợp
     * chậm nhất. `pollUntil` tự thoát sớm ngay khi sẵn sàng nên không tốn
     * thời gian thừa khi nhanh — chỉ nới TRẦN, không ép chờ đủ.
     */
    suspend fun handlePopupAndDetail(): GolikeJobReceiveResult {
        val popupShown = pollUntil(5_000, 300L) { callBool("window.__atproJob.hasConfirmPopup()") }
        if (popupShown) {
            Log.i(TAG, "POPUP-GOLIKE: Popup 'Xác nhận làm việc bằng App' xuất hiện — bấm Đã hiểu → Đồng ý")
            // v1.3.3 (bổ sung 7) — "đang quá nhanh, cần delay lại": người
            // thật cần đọc nội dung popup trước khi bấm "Đã hiểu", rồi lại
            // 1 khoảng ngắn nữa trước khi bấm "Đồng ý" — trước bản này cả 2
            // mốc đều bấm gần như tức thì (chỉ cách nhau 400ms cố định).
            humanDelay(600, 1_400)
            val understood = pollUntil(3_000) { callBool("window.__atproJob.clickPopupUnderstood()") }
            humanDelay(500, 1_100)
            val agreed = pollUntil(3_000) { callBool("window.__atproJob.clickPopupAgree()") }
            Log.i(TAG, "POPUP-GOLIKE: Đã hiểu=$understood, Đồng ý=$agreed")
        } else {
            Log.i(TAG, "POPUP-GOLIKE: Không thấy popup xác nhận trong 5s (có thể không xuất hiện lần này)")
        }

        var detail = JSONObject()
        val detailReady = pollUntil(POST_RECEIVE_WAIT_MS, 500L) {
            detail = callJson("window.__atproJob.getJobDetail()")
            detail.optBoolean("found", false)
        }
        if (!detailReady) {
            Log.w(TAG, "POPUP-GOLIKE: Không thấy trang chi tiết job sau ${POST_RECEIVE_WAIT_MS}ms (popupShown=$popupShown)")
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
                humanDelay(400, 900)
                val ok = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                Log.i(TAG, "POPUP-GOLIKE: Dialog Thành công → bấm OK ($ok)")
                GolikeJobReportResult.Success
            }
            "error" -> {
                humanDelay(400, 900)
                val ok1 = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
                humanDelay(500, 1_000)
                val baoLoi = pollUntil(4_000) { callBool("window.__atproJob.clickBaoLoi()") }
                humanDelay(600, 1_200)
                val guiBaoCao = pollUntil(4_000) { callBool("window.__atproJob.scrollAndClickSendReport()") }
                humanDelay(700, 1_300)
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

    /**
     * v1.3.4 — Job loại KHÔNG được hỗ trợ (comment, yêu thích/favourite...) —
     * BỎ QUA CHỦ ĐỘNG bằng chính luồng "Báo lỗi" ngay trên trang chi tiết,
     * thay vì chỉ `abandonJob()` (back() rồi để job tự hết hạn theo đếm ngược).
     *
     * Khác nhánh "error" của `completeJob()`: ở ĐÂY đang đứng ngay trên trang
     * chi tiết job (3 nút TikTok/Báo lỗi/Hoàn thành hiển thị trực tiếp, CHƯA
     * có dialog "Thành công/Lỗi" nào che trước) — nên KHÔNG có bước "OK" đầu
     * tiên như nhánh đó. Dùng lại nguyên JS `clickBaoLoi()` /
     * `scrollAndClickSendReport()` / `clickDialogOk()` đã có sẵn.
     */
    suspend fun reportAndSkipJob(): GolikeJobReportResult {
        humanDelay(500, 1_100)
        val baoLoi = pollUntil(4_000) { callBool("window.__atproJob.clickBaoLoi()") }
        if (!baoLoi) {
            Log.w(TAG, "POPUP-GOLIKE: Job loại không hỗ trợ — không bấm được 'Báo lỗi'")
            return GolikeJobReportResult.Unknown
        }
        humanDelay(600, 1_200)
        val guiBaoCao = pollUntil(4_000) { callBool("window.__atproJob.scrollAndClickSendReport()") }
        humanDelay(700, 1_300)
        val ok = pollUntil(3_000) { callBool("window.__atproJob.clickDialogOk()") }
        Log.i(
            TAG,
            "POPUP-GOLIKE: Job loại không hỗ trợ → Báo lỗi=$baoLoi, Gửi báo cáo=$guiBaoCao, OK=$ok",
        )
        return GolikeJobReportResult.ErrorReported
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

    /**
     * v1.3.3 (bổ sung 7) — Delay ngẫu nhiên giữa 2 thao tác click liên tiếp
     * trong cùng 1 chuỗi (vd "Đã hiểu" → "Đồng ý") — người thật không bấm 2
     * nút liên tiếp cách nhau 1 khoảng CỐ ĐỊNH. Không dùng được `Human`
     * (private trong `AutomationEngine.kt`, khác file) nên dùng
     * `kotlin.random.Random` trực tiếp — đơn giản, không cần chia sẻ state.
     */
    private suspend fun humanDelay(minMs: Long, maxMs: Long) =
        delay(if (maxMs > minMs) Random.nextLong(minMs, maxMs) else minMs)

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
