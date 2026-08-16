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
