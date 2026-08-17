# Revision v1.3.3 Supplement — 2026-08-15

## Tóm tắt
Bổ sung bản v1.3.3 (không đổi versionCode/versionName — vẫn 35/v1.3.3): đổi
cách nhận/báo cáo job Golike TikTok từ gọi thẳng REST API sang **giả lập
người dùng thật** thao tác trực tiếp trên `https://app.golike.net/jobs/tiktok`
qua WebView + JS click mô phỏng. Đảo ngược có chủ đích kết luận trước đó
trong `v1.3.3.md` gốc (*"không cần tự động hoá UI riêng của app Golike"*) —
xem `ADR-0008-golike-webview-job-simulation.md` để biết đầy đủ lý do.

## Skill đã dùng
- `.claude/skills/karpathy-guidelines/SKILL.md` — thay đổi có phạm vi rõ,
  không lan sang phần không liên quan; giữ nguyên 3 hàm REST job cũ trong
  `GolikeApi.kt`/`GolikeRepository.kt` thay vì xoá (không phải yêu cầu),
  chỉ chú thích rõ chúng không còn được gọi từ task flow.
- `.claude/skills/mat/zoom-out.md` — đọc lại toàn bộ `taskOneAccount()`,
  `runTaskStateMachine()`, `IFarmHost.kt`, `TikTokAccessibilityService.kt`,
  `GolikeApi.kt`/`GolikeRepository.kt`/`GolikeModels.kt`,
  `GolikeLoginWebActivity.kt` (mẫu WebView tham khảo) trước khi code.
- `.claude/skills/mat/grill-with-docs.md` — không cần "grill" hỏi thêm vì
  yêu cầu đã rất cụ thể (7 ảnh chụp + mô tả từng bước bằng chữ), nhưng có áp
  dụng nguyên tắc "ghi ADR khi quyết định khó đảo ngược + có đánh đổi thật"
  của skill này → viết `ADR-0008`.
- Không dùng skill bên ngoài (`/mnt/skills/*`) — toàn bộ thay đổi nằm trong
  phạm vi Kotlin/Android WebView sẵn có của project.

## Bối cảnh — ảnh đính kèm (7 ảnh)
Ảnh 1: màn "Kiếm Thưởng" — nút "Nhận Job ngay", danh sách tài khoản kiếm
thưởng ("Danh sách công việc"). Ảnh 2-3: dropdown chọn tài khoản (cuộn được,
username dạng `hoaibao.209.X`). Ảnh 4-5: trang "Chi tiết" job (loại "TĂNG
LIKE CHO BÀI VIẾT" / "TĂNG LƯỢT THEO DÕI"), nút TikTok/Báo lỗi/Hoàn thành,
đếm ngược tự xoá job. Ảnh 6: dialog "Thành công" sau khi Hoàn thành. Ảnh 7:
dialog "Lỗi" ("Hệ thống kiểm tra bạn chưa thực hiện thao tác follow!").

## Thay đổi

### 1. `IFarmHost.kt` — thêm `getContext(): Context`
`AccessibilityService` không có `startActivityForResult()` — cần `Context`
thô để tự `context.startActivity()` mở `GolikeJobWebActivity` từ coroutine
nền. Interface method mới, không ảnh hưởng implementer nào khác ngoài
`TikTokAccessibilityService` (đã kiểm tra — chỉ 1 implementer trong
production; test dùng MockK strict mock nhưng `taskOneAccount()` return sớm
khi `golikeRepo == null`, tests không truyền golikeRepo nên không gọi tới
method mới → không vỡ test hiện có).

### 2. `TikTokAccessibilityService.kt`
`override fun getContext(): Context = this` (Service là Context) + import
`android.content.Context`.

### 3. `GolikeJobWebActivity.kt` (mới) — `com.atpro.ui.golike`
WebView tới `JOBS_URL = "https://app.golike.net/jobs/tiktok"`. Thư viện JS
`window.__atproJob` (idempotent, tự cài lại mỗi `onPageStarted`/`onPageFinished`)
tìm phần tử theo TEXT hiển thị + dispatch chuỗi sự kiện
`pointerdown/mousedown/pointerup/mouseup/click`:

- `openAccountPicker()` / `selectAccountByLabel(label)` — Bước 1
- `clickReceiveJobNow()` / `hasConfirmPopup()` / `clickPopupUnderstood()` /
  `clickPopupAgree()` — Bước 2 ("Nhận Job ngay" → "Xác nhận làm việc bằng
  App" → "Đã hiểu" → "Đồng ý")
- `getJobDetail()` — đọc loại job (`like`/`follow`/`unknown`) + "Job Id: ..."
  từ trang chi tiết
- `clickTikTokButton()` — Bước 3
- `clickHoanThanh()` / `readResultDialog()` / `clickDialogOk()` /
  `clickBaoLoi()` / `scrollAndClickSendReport()` — báo cáo + luồng Lỗi
- `goBack()` — bỏ qua job không khớp loại cấu hình (`window.history.back()`)

Kotlin-side: `evalJs()` (suspend, wrap `evaluateJavascript` bằng
`suspendCancellableCoroutine`, chạy trên `Dispatchers.Main.immediate`),
`callBool()`/`callJson()` (parse kết quả JS, có xử lý double-encode JSON của
`evaluateJavascript`), `pollUntil()` (polling có timeout thay cho JS-side
async coordination — đơn giản hơn, dễ debug hơn). `shouldOverrideUrlLoading`
+ `onCreateWindow` (WebView transport tạm) bắt điều hướng ra ngoài trang
(scheme `snssdk1180://...`, `intent://...`) → mở bằng
`TikTokDeepLinks.openDeepLink()`/`Intent.parseUri(URI_INTENT_SCHEME)` thay vì
để WebView tự load (sẽ lỗi `ERR_UNKNOWN_URL_SCHEME`).

`launchMode="singleTask"` (đăng ký trong `AndroidManifest.xml`) — 1 instance
sống suốt phiên làm task/acc, giữ nguyên tài khoản đã chọn + trạng thái
WebView khi chuyển qua lại với TikTok.

### 4. `GolikeJobBridge.kt` (mới) — `com.atpro.golike`
Singleton điều phối, giữ `WeakReference<GolikeJobWebActivity>`. API:
`ensureOpen(context)`, `bringToFront(context)`, `close()`, `selectAccount(label)`,
`receiveJob()`, `clickTikTokButton()`, `abandonJob()`, `completeJob()`. Sealed
class `GolikeJobReceiveResult` (`None`/`Failed`/`Received`) và
`GolikeJobReportResult` (`Success`/`ErrorReported`/`Unknown`) thay thế
`TikTokJobDto`/response REST cho phần dữ liệu đọc được từ màn hình.

### 5. `AndroidManifest.xml`
Đăng ký `<activity android:name=".ui.golike.GolikeJobWebActivity"
android:launchMode="singleTask" android:theme="@style/AppTheme" />` — đặt
trước `GolikeLoginWebActivity` (cùng nhóm WebView Golike).

### 6. `AutomationEngine.kt`
- **Import**: thêm `GolikeJobBridge`, `GolikeJobReceiveResult`,
  `GolikeJobReportResult`; xoá `TikTokJobDto` (đã unused từ trước, dọn kèm)
  và `GolikeResult` (chỉ dùng trong khối bị thay).
- **`taskOneAccount()`**: khối `[B]-[F]` cũ (gọi `gRepo.getTikTokJobs` /
  `skipTikTokJob` / `completeTikTokJob`) thay hoàn toàn bằng luồng
  `GolikeJobBridge`. Thêm bước mở trang + chọn tài khoản Golike (1 lần/acc,
  trước vòng lặp job). Guard đầu hàm đổi từ "lấy `gRepo` khỏi null" thành
  check `golikeRepo == null` đơn thuần (không còn cần instance repo trong
  hàm này nữa, nhưng vẫn giữ làm cổng an toàn "Golike đã wire chưa").
- **Hàm mới `ensureTikTokOnFeed()`**: gọi `host.launchTikTok()` + vòng lặp
  back-về-feed (logic y hệt bước `[E]` cũ, chỉ dời vị trí) — cần thiết vì
  giờ màn hình xen kẽ TikTok ↔ WebView Golike liên tục, phải đảm bảo TikTok
  đang ở tab Feed *ngay trước* mỗi lần gọi `doSimpleFarm()` (farm giả định
  đang ở feed), thay vì ngay sau khi làm job như luồng cũ.
- Job không khớp `config.taskJobType`: gọi `GolikeJobBridge.abandonJob()`
  (back khỏi trang chi tiết, để job tự hết hạn) thay vì gọi API skip riêng.
- `runTaskStateMachine()`: thêm `GolikeJobBridge.close()` trước
  `host.killTikTok()` khi xong tất cả tài khoản.

### 7. `GolikeRepository.kt`
Thêm đoạn KDoc "v1.3.3 (bổ sung) — KHÔNG còn được gọi từ task flow..." vào
`getTikTokJobs()`, `completeTikTokJob()`, `skipTikTokJob()` — giữ nguyên code,
chỉ ghi chú rõ lý do không xoá + hàm nào thay thế.

### 8. `.claude/decisions/ADR-0008-golike-webview-job-simulation.md` (mới)
Ghi nhận đầy đủ lý do đảo ngược quyết định + trade-off (xem file).

## Rủi ro đã biết / chưa thể xác minh
- Không có quyền truy cập DOM thật của `app.golike.net` (ngoài phạm vi
  domain cho phép của công cụ) — toàn bộ JS viết dựa theo TEXT hiển thị
  đọc từ 7 ảnh chụp màn hình người dùng cung cấp, chưa chạy thử trên máy
  thật. Nếu Golike đổi nhãn nút/tiêu đề, cần cập nhật lại các chuỗi text
  trong `JOB_SCRIPT` (`GolikeJobWebActivity.kt`).
- Không kiểm chứng được cơ chế điều hướng thật khi bấm nút "TikTok" (deep
  link cùng cửa sổ hay `target=_blank`) — đã code cả 2 khả năng
  (`shouldOverrideUrlLoading` + `onCreateWindow`) để phòng ngừa, nhưng chỉ
  xác nhận được khi chạy thật.
- Sandbox không có `kotlinc` để build thật — đã verify: cân bằng
  ngoặc `{}/()/[]`, escape bracket trong KDoc (`\[`/`\]`), cú pháp JS qua
  `node --check`, XML manifest hợp lệ qua `ElementTree`. **CI vẫn là bước
  verify cuối cùng** (đúng nguyên tắc đã ghi trong `v1.3.3.md` gốc).

## Rollback
```
IFarmHost.kt                 → xoá fun getContext()
TikTokAccessibilityService.kt → xoá override getContext(), xoá import Context
GolikeJobWebActivity.kt      → xoá file
GolikeJobBridge.kt           → xoá file
AndroidManifest.xml          → xoá khai báo <activity> GolikeJobWebActivity
AutomationEngine.kt          → khôi phục taskOneAccount() bản REST-API (xem
                                git diff/git log — hoặc v1.3.3.md gốc mục
                                tương ứng), xoá ensureTikTokOnFeed(), khôi
                                phục import TikTokJobDto/GolikeResult
GolikeRepository.kt          → xoá 3 đoạn KDoc "v1.3.3 (bổ sung)..." (code
                                giữ nguyên, không cần rollback logic)
ADR-0008, revision supplement này → có thể giữ lại làm lịch sử, không bắt
                                buộc xoá khi rollback code
```

## Bổ sung 2 (cùng ngày) — Cơ chế chọn tài khoản: acc đầu danh sách = acc đang đăng nhập

Yêu cầu tiếp theo của Bảo: *"Cập nhật cơ chế acc đầu danh sách là acc đang
đăng nhập (không cần đọc hồ sơ trước)"* — xác nhận qua kiểm thử thực tế:
trong dropdown "Tài khoản kiếm thưởng" trên `app.golike.net`, acc ĐẦU DANH
SÁCH (dòng account đầu tiên bên dưới header, không phải chính header) LUÔN
là acc TikTok đang đăng nhập trên thiết bị tại thời điểm mở dropdown — không
cần dò/so khớp theo username như bản đầu (`selectAccountByLabel`).

### Thay đổi
- **`GolikeJobWebActivity.kt`**: xoá JS `selectAccountByLabel(label)` (dò
  toàn bộ DOM tìm text khớp), thay bằng `selectFirstAccountInList()` — tìm
  dòng account đầu tiên nằm dưới header bằng heuristic: phần tử lá (không có
  con nào cũng khớp điều kiện text-ngắn), hiển thị, `top` nhỏ nhất trong các
  ứng viên hợp lệ dưới header = gần header nhất = đầu danh sách. Thêm JS
  `getSelectedAccountLabel()` (đọc lại tên hiển thị ở header sau khi chọn —
  CHỈ để log, không dùng để quyết định click). Hàm Kotlin `selectAccount()`
  đổi tham số `label` → `expectedLabel` (chỉ dùng log cảnh báo nếu acc đầu
  danh sách không khớp kỳ vọng, KHÔNG chặn luồng nếu không khớp — tin tưởng
  cơ chế mới). Xoá `jsStr()` (helper escape string cho JS — không còn nơi
  gọi sau khi bỏ tham số tìm-theo-tên). Thêm `decodeJsString()` (giải mã kết
  quả JS trả về 1 chuỗi thường, dùng cho `getSelectedAccountLabel()`).
- **`GolikeJobBridge.kt`**: đổi tên tham số `selectAccount(label)` →
  `selectAccount(expectedLabel)` cho khớp, cập nhật KDoc.
- **`AutomationEngine.kt`**: cập nhật comment + log message ở bước chọn tài
  khoản Golike trong `taskOneAccount()` cho khớp cơ chế mới. KHÔNG đổi logic
  gọi (`GolikeJobBridge.selectAccount(accountLabel)` — vẫn truyền
  `accountLabel` như cũ, giờ chỉ dùng để log đối chiếu).

### Rủi ro đã biết
- Heuristic "phần tử lá gần header nhất theo `top`" để tìm dòng account đầu
  tiên là suy đoán dựa trên cấu trúc UI điển hình (list item dạng
  avatar+text), CHƯA xác minh trên DOM thật (ngoài phạm vi domain cho phép
  của công cụ). Nếu Golike đổi cấu trúc list (vd ảo hoá danh sách/virtualized
  list chỉ render item đang hiển thị trong viewport), heuristic này có thể
  cần điều chỉnh.
- Cơ chế "đầu danh sách = đang đăng nhập" là thông tin do Bảo xác nhận qua
  kiểm thử thực tế trên máy — không tự suy luận được từ 7 ảnh chụp màn hình
  gốc (ảnh 2/3 cho thấy acc được đánh dấu ✓ không nằm ở vị trí đầu danh sách
  hiển thị, nhiều khả năng do dấu ✓ phản ánh lựa chọn thủ công trước đó lúc
  chụp ảnh, khác với "vị trí đầu khi mở dropdown mới" — không mâu thuẫn với
  cơ chế Bảo mô tả, chỉ là 2 tín hiệu khác nhau trên cùng 1 màn hình).

## Bổ sung 3 (cùng ngày) — Cơ chế đảm bảo: tự khởi động lại WebView Golike sau thất bại lần đầu

Yêu cầu: *"Thêm cơ chế đảm bảo, khởi động lại dịch vụ sau khi thất bại lần
đầu!"* — áp dụng cho "dịch vụ Golike" (`GolikeJobWebActivity`/`GolikeJobBridge`).
Trước bản này: nếu mở trang/chọn tài khoản Golike thất bại ngay từ đầu, hoặc
WebView "chết" giữa phiên (bị hệ thống kill do low-memory, crash...), code
bỏ cuộc ngay (hoặc lặng lẽ fail từng job một qua `consecFail` mà không bao
giờ thử mở lại WebView) — đây là lỗ hổng độ tin cậy thật sự đã phát hiện khi
rà lại code.

### Thay đổi
- **`GolikeJobBridge.kt`**: thêm `isAlive(): Boolean` — kiểm tra activity còn
  sống (WeakReference không rỗng, không đang finish, đã tải trang xong lần
  đầu). Dùng làm điều kiện short-circuit rẻ cho cơ chế tự khởi động lại.
- **`AutomationEngine.kt`**: thêm 2 hàm:
  - `tryOpenAndSelectGolike(context, accountLabel)` — gộp `ensureOpen()` +
    `selectAccount()` thành 1 bước "thử mở + chọn tài khoản".
  - `ensureGolikeReady(context, accountLabel)` — cơ chế đảm bảo chính: nếu
    `GolikeJobBridge.isAlive()` đã true → trả về ngay (no-op, rẻ). Nếu
    không, thử `tryOpenAndSelectGolike()` LẦN 1; nếu vẫn thất bại → LOG cảnh
    báo, `GolikeJobBridge.close()` (đóng instance cũ có thể đang "treo") +
    đợi 1s + thử lại LẦN 2 (khởi động lại từ đầu). Chỉ coi là thất bại thật
    sự sau khi CẢ 2 lần đều fail.

  Gọi `ensureGolikeReady()` ở 2 điểm trong `taskOneAccount()`:
  1. **Đầu phiên** (thay cho khối `ensureOpen()`+`selectAccount()` cũ gọi
     riêng lẻ, không có retry) — bắt buộc phải sẵn sàng trước khi vào vòng
     lặp job.
  2. **Đầu MỖI vòng lặp job**, ngay trước `bringToFront()`+`receiveJob()` —
     tự phát hiện + hồi phục nếu WebView chết GIỮA CHỪNG phiên làm việc
     (không chỉ 1 lần lúc đầu), short-circuit qua `isAlive()` nên không tốn
     chi phí khi WebView vẫn khoẻ mạnh bình thường.

  Nếu `ensureGolikeReady()` thất bại giữa phiên (sau khi đã thử khởi động
  lại) → `break` khỏi vòng lặp job cho acc đó (dừng làm việc, không lặp vô
  hạn) — nhất quán với cơ chế `taskMaxConsecFailures` sẵn có (bounded retry,
  không phải infinite retry).

### Rủi ro đã biết
- "Khởi động lại" chỉ thử LẠI 1 LẦN (đúng nghĩa đen "sau khi thất bại lần
  đầu" trong yêu cầu) — nếu nguyên nhân gốc là mạng/site Golike down thật sự
  (không phải lỗi thoáng qua), lần 2 nhiều khả năng cũng fail và code dừng
  hẳn cho acc đó — đây là hành vi có chủ đích (tránh vòng lặp thử lại vô hạn
  khi vấn đề không phải thoáng qua), không phải giới hạn kỹ thuật.

## Bổ sung 4 (cùng ngày) — Watchdog cho task mode + log popup toàn diện + fix bug treo dịch vụ Golike

Yêu cầu: *"Thêm cơ chế đảm bảo cho dịch vụ nuôi tiktok / Bổ sung log cho toàn
bộ popup / Fix dịch vụ golike: sau khi mở trang nhận nhiệm vụ tool bị dừng,
mất popup, không thao tác"*.

### 4.1 — Watchdog cho dịch vụ nuôi TikTok trong task mode
Rà lại code phát hiện: watchdog (đếm video mới, tự kill+relaunch TikTok nếu
treo quá `config.watchdogTimeoutSecs`) CHỈ được `startWatchdog()` trong
`farmMultipleAccounts()` (farm mode thường) — task mode (`runTaskStateMachine()`
/`taskOneAccount()`/`doSimpleFarm()`) hoàn toàn không bật/tiêu thụ cơ chế
này, dù đã tồn tại sẵn và rất đầy đủ (`recoverFromStuck()`,
`relaunchTikTokWithRetry()` với retry+backoff+Discord notify).

- **`runTaskStateMachine()`**: gọi `startWatchdog()` trước Phase 3 loop,
  `watchdogJob?.cancel()` sau khi xong tất cả acc (đối xứng với farm mode).
- **`doSimpleFarm()`**: thêm 2 phần bị thiếu khiến watchdog vốn "mù" với task
  mode dù có bật:
  1. Cập nhật `watchdogVideoCount`/`watchdogLastTick` sau mỗi video xem được
     (y hệt `farmOneAccount()`) — trước đây task mode không hề "nuôi" các
     biến này.
  2. Check + tiêu thụ `watchdogStuckFlag` (gọi `recoverFromStuck()` khi
     watchdog phát hiện treo) — y hệt bước "[3b]" của `farmOneAccount()`.
- **`taskOneAccount()`**: bọc toàn bộ đoạn `[B]-[F]` (thao tác trên WebView
  Golike) trong `isActionLocked = true` (flag CÓ SẴN, watchdog đã check từ
  trước) + `try/finally` — tránh watchdog false-alarm "không có video mới"
  trong lúc TikTok tạm không phải app foreground (đang ở trang Golike, hoàn
  toàn bình thường). Bước `[G]` (nuôi acc SAU job) CHỦ Ý không bọc — đó là
  lúc cần watchdog giám sát TikTok bình thường trở lại. `finally` cũng gọi
  `resetWatchdog()` — tránh thời gian đã trôi lúc thao tác Golike (có thể
  15-40s+, hơn nữa nếu `ensureGolikeReady()` phải khởi động lại) bị tính vào
  "không có video mới" của lần xem tiếp theo.

  Cấu trúc lại: đoạn `[B]-[F]` giờ là 1 biểu thức `try { ... } finally { ... }`
  trả về `Pair<job, report>` (destructure ngay sau) — cần thiết vì bước `[G]`
  (trong nhánh `Success`) phải nằm NGOÀI try/finally (không bọc isActionLocked)
  nhưng vẫn cần truy cập `job`/`report`.

### 4.2 — Log toàn diện cho popup
- **`PopupHandler.kt`**: thay vì dựa vào từng handler tự nhớ gọi `log()` (đã
  phát hiện thiếu sót: loại `ACCOUNT_SWITCH` không log gì, Tier 2 chỉ log
  TRƯỚC khi xử lý chứ không log kết quả), chuyển sang log TẬP TRUNG tại 2
  điểm return của `handleIfPresent()` — đảm bảo bao phủ 100% MỌI popup được
  xử lý (Tier 1 hoặc Tier 2), format thống nhất
  `POPUP: [TierX] type=... action=...`. Log chi tiết hơn có sẵn trong từng
  handler (vd `AUTH: 1234 popup`) vẫn giữ nguyên — lớp log tập trung chỉ là
  đảm bảo bao phủ, không thay thế.
- **`GolikeJobWebActivity.kt`** (`receiveJob()`/`completeJob()`): thêm log
  `POPUP-GOLIKE: ...` cho từng bước popup trên trang Golike — phát hiện popup
  "Xác nhận làm việc bằng App", bấm Đã hiểu/Đồng ý, đọc dialog kết quả
  (Thành công/Lỗi), luồng Báo lỗi (OK → Báo lỗi → Gửi báo cáo → OK) — trước
  bản này hoàn toàn im lặng, không có cách nào biết các bước này có thật sự
  chạy hay không khi debug từ xa.

### 4.3 — FIX bug "mở trang nhận nhiệm vụ xong tool bị dừng, mất popup, không thao tác"
**Nguyên nhân xác định**: `evalJs()` (hàm lõi gọi `webView.evaluateJavascript()`
qua `suspendCancellableCoroutine`) KHÔNG có timeout riêng. Nếu callback native
của `evaluateJavascript()` không bao giờ fire — kịch bản thực tế nhiều khả
năng nhất: `GolikeJobBridge.bringToFront()` gọi `context.startActivity()` từ
`AccessibilityService` (nền), nhưng activity CHƯA THẬT SỰ resume (1 số
ROM/thiết bị trì hoãn resume activity khởi từ background vài giây) khi
`evalJs()` được gọi ngay sau đó (chỉ `delay(500)`/`delay(800)` cố định, không
đợi tín hiệu resume thật) — coroutine treo VĨNH VIỄN ở
`suspendCancellableCoroutine`, kéo theo toàn bộ `taskOneAccount()` đứng im
không lỗi, không log tiếp — đúng khớp mô tả "tool bị dừng, mất popup [vì
không bao giờ tới được bước xử lý popup], không thao tác".

**Fix — 2 lớp bảo vệ trong `GolikeJobWebActivity.kt`**:
1. **`isResumed`** (mới, cập nhật qua `onResume()`/`onPause()`) +
   `waitForResumed()` (chờ CÓ GIỚI HẠN 4s) — gọi ĐẦU `evalJs()`, giảm khả
   năng rơi vào tình huống treo ngay từ đầu bằng cách đợi activity thật sự
   foreground trước khi gọi JS.
2. **`withTimeoutOrNull(EVAL_JS_TIMEOUT_MS = 6_000L)`** bọc quanh phần lõi
   `evalJs()` — trần cứng, đảm bảo hàm LUÔN trả về (rơi về `"null"`) dù
   callback native có bao giờ fire hay không. An toàn: callback fire trễ sau
   timeout vẫn được guard bởi `cont.isActive` (đã false sau khi bị hủy) nên
   không resume 2 lần / không crash.

   Đây chính là ứng dụng trực tiếp của nguyên tắc đã ghi trong
   `.claude/context/` (coroutine cancellability — suspend function dựa trên
   callback native cần timeout riêng, không thể tin tưởng callback luôn fire).

- **`GolikeJobBridge.kt`**: thêm log cảnh báo khi `withActivity()`/
  `waitAttached()` hết timeout mà không tìm được activity còn sống — dấu
  hiệu trực tiếp của đúng bug này nếu còn tái diễn sau fix, giúp phân biệt
  "JS treo" (đã fix) với "activity thật sự chết, không ai mở lại" (đã có
  `ensureGolikeReady()` xử lý từ bổ sung 3).

### Rủi ro đã biết (bổ sung 4)
- Chưa xác minh được TRÊN THIẾT BỊ THẬT nguyên nhân chính xác của bug (chỉ
  suy luận từ code — không có log thực tế từ lần treo để đối chiếu, vì bug
  chính là "không log tiếp được nữa"). `EVAL_JS_TIMEOUT_MS`/
  `RESUME_WAIT_TIMEOUT_MS` là giá trị ước lượng hợp lý, có thể cần điều
  chỉnh sau khi có log thực tế (giờ đã có log timeout rõ ràng để biết chính
  xác bước nào/mất bao lâu).
- Watchdog cho task mode dùng CHUNG `config.watchdogTimeoutSecs` (mặc định
  120s) với farm mode — chưa có cấu hình riêng cho task mode dù pattern hoạt
  động khác nhau (nhiều lần chuyển app qua lại hơn). Nếu 120s không phù hợp
  cho task mode sau khi chạy thử, có thể cần tách config riêng.
