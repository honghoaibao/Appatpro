# ADR-0008: Nhận/báo cáo job Golike qua WebView giả lập người thật, thay REST API trực tiếp

**Date**: 2026-08-15
**Status**: Accepted

## Context

`v1.3.3.md` (gốc) từng kết luận rõ: *"Không thấy dấu hiệu cần tự động hoá UI
riêng của app Golike (captcha kéo-thả, dialog 'Xác nhận làm việc bằng App'
đều là màn hình của app Golike, nằm ngoài luồng tool hiện tại)"* — kiến trúc
lúc đó gọi thẳng REST API Golike qua `GolikeApi.kt`/`GolikeRepository.kt`
(`getTikTokJobs` → `completeTikTokJob`/`skipTikTokJob`), không đụng tới UI
của app/web Golike.

Yêu cầu mới (bổ sung v1.3.3, dựa trên ảnh chụp luồng thật trên
`app.golike.net/jobs/tiktok`): đổi hẳn cách nhận job sang **giả lập một
người dùng thật** thao tác trực tiếp trên trang web đó — chọn tài khoản
kiếm thưởng, bấm "Nhận Job ngay", xử lý popup xác nhận, mở TikTok làm
nhiệm vụ, quay lại bấm "Hoàn thành", xử lý dialog Thành công/Lỗi (kể cả
luồng "Báo lỗi" khi Golike chưa phát hiện thao tác). Đây là đảo ngược có
chủ đích so với kết luận trước — không phải phát hiện thêm scope bị bỏ sót.

## Decision

Thêm `GolikeJobWebActivity` (WebView tới `https://app.golike.net/jobs/tiktok`)
+ `GolikeJobBridge` (singleton điều phối từ coroutine nền của
`AutomationEngine`/`TikTokAccessibilityService`). Toàn bộ thao tác trên
trang được thực hiện bằng **JS click mô phỏng** — tìm phần tử theo TEXT hiển
thị (không phụ thuộc class/id CSS), dispatch chuỗi sự kiện
`pointerdown/mousedown/pointerup/mouseup/click` — KHÔNG gọi thẳng
`GolikeApi.getTikTokJobs()`/`completeTikTokJob()`/`skipTikTokJob()` nữa từ
`taskOneAccount()`. 3 hàm REST đó vẫn giữ nguyên trong
`GolikeApi.kt`/`GolikeRepository.kt` (không xoá — có chú thích rõ "không còn
được gọi từ task flow").

`IFarmHost` được bổ sung `getContext(): Context` — cần thiết vì
`AccessibilityService` không có `startActivityForResult()`, phải tự
`context.startActivity()` với `FLAG_ACTIVITY_NEW_TASK` để mở
`GolikeJobWebActivity` từ coroutine nền.

## Rationale

**Tại sao đảo ngược quyết định trước, không phải "sửa" nó?**
Kết luận trước dựa trên thông tin lúc đó: kiến trúc REST-API-only đã hoạt
động và không thấy lý do kỹ thuật để đổi. Yêu cầu mới không phải bug/thiếu
sót — là thay đổi có chủ đích của Bảo (khả năng liên quan tới hành vi phát
hiện bot phía Golike khi gọi API trực tiếp mà không có traffic UI tương ứng
— không được xác nhận rõ, nhưng đây là động cơ hợp lý phổ biến cho kiểu đổi
hướng này).

**Tại sao JS click mô phỏng, không phải AccessibilityNodeInfo trên WebView?**
WebView (Chromium) có expose accessibility tree cho nội dung DOM, nhưng độ
ổn định/độ trễ khi dò `AccessibilityNodeInfo` trên 1 SPA nhiều lớp
overlay/dialog kém tin cậy hơn hẳn so với JS chạy ngay trong ngữ cảnh trang
— luôn thấy đúng DOM hiện tại, không phụ thuộc timing render-tree của
accessibility. Đây cũng là pattern nhất quán với `GolikeLoginWebActivity`
(đã dùng JS-based network interceptor để dò token, không dùng accessibility).

**Tại sao dispatch chuỗi sự kiện chuột, không gọi thẳng `el.click()`?**
Nhiều SPA chỉ lắng nghe `pointerdown`/`mousedown` cho hiệu ứng nhấn (ripple,
active state) — `el.click()` bỏ qua các listener đó, có thể khiến UI trông
"click nhưng không phản hồi" dù logic click vẫn chạy. Dispatch đủ chuỗi sự
kiện giống thao tác chạm thật hơn.

**Tại sao tìm phần tử theo TEXT hiển thị, không theo CSS selector/id?**
Không có quyền truy cập trực tiếp DOM thật của `app.golike.net` để xác định
class/id ổn định (ngoài phạm vi domain cho phép của công cụ). TEXT hiển thị
(đọc trực tiếp từ ảnh chụp màn hình người dùng cung cấp) là điểm neo đáng
tin cậy nhất hiện có, và cũng chính là thứ một người dùng thật nhìn vào để
bấm — khớp tinh thần "giả lập người thật" của yêu cầu.

**Tại sao 1 `GolikeJobWebActivity` instance sống suốt phiên (singleTask),
không mở/đóng lại mỗi job?**
Giữ nguyên trạng thái đã chọn tài khoản (Bước 1 chỉ cần làm 1 lần/acc theo
đúng mô tả luồng của Bảo) — mở lại từ đầu mỗi job sẽ phải chọn lại tài khoản
liên tục, không cần thiết và không giống hành vi người dùng thật.

**Tại sao job không khớp `config.taskJobType` lại "để tự hết hạn" thay vì
gọi skip?**
Luồng UI mới không có nút "skip" riêng biệt như API cũ. Trang chi tiết job
tự hiển thị đếm ngược tự xoá (quan sát được từ ảnh: *"sau 93 giây Job sẽ tự
xoá"*) — thoát ra bằng back (không bấm Hoàn thành/Báo lỗi) là hành vi tự
nhiên nhất của 1 người dùng thật không muốn làm job đó, và tránh báo cáo sai
sự thật lên hệ thống Golike (khác với việc "Báo lỗi" — vốn nên dành cho
trường hợp ĐÃ cố làm nhưng không được ghi nhận).

## Consequences

- **Rủi ro DOM đổi**: vì neo theo TEXT hiển thị, nếu Golike đổi nhãn nút
  ("Nhận Job ngay", "Hoàn thành", "Báo lỗi", "Gửi báo cáo", tiêu đề job...)
  thì luồng sẽ gãy. Không có cách tránh hoàn toàn khi không truy cập được
  DOM thật để viết selector ổn định hơn.
- **Thời gian mỗi chu kỳ job dài hơn**: nhiều bước UI + polling + delay mô
  phỏng người thật, chậm hơn gọi API trực tiếp — đánh đổi có chủ đích.
- **Chuyển đổi ứng dụng thường xuyên hơn**: mỗi job cần chuyển qua lại giữa
  `GolikeJobWebActivity` và TikTok — `ensureTikTokOnFeed()` (mới) đảm bảo
  TikTok về đúng tab Feed trước khi farm tiếp, bù cho rủi ro TikTok bị để
  lại ở màn hình không mong muốn sau khi chuyển app.
- **3 hàm REST job cũ trở thành dead code trong luồng chính** (không xoá,
  xem GolikeRepository.kt) — có thể dọn trong 1 revision sau nếu xác nhận
  chắc chắn không cần dùng lại.

## Alternatives considered

**Option A — Giữ REST API, chỉ thêm bước "mở app Golike" cho có vẻ tự nhiên**
Rejected: không đáp ứng yêu cầu cốt lõi — Bảo yêu cầu rõ hành vi PHẢI đi qua
đúng các bước UI thật (chọn acc, popup xác nhận, nút Hoàn thành, dialog kết
quả), không phải giả vờ mở app rồi vẫn gọi API ngầm.

**Option B — AccessibilityNodeInfo để dò UI trong WebView (như dò UI TikTok)**
Rejected (xem Rationale) — kém ổn định hơn JS-in-page cho nội dung SPA nhiều
lớp; cũng không nhất quán với pattern JS-based đã dùng ở
`GolikeLoginWebActivity`.

**Option C — Mở/đóng `GolikeJobWebActivity` lại từ đầu mỗi job**
Rejected — phải chọn lại tài khoản mỗi lần, chậm hơn và không cần thiết;
`singleTask` + `bringToFront()` giữ nguyên trạng thái rẻ hơn nhiều.

## Follow-up (không blocking)

- Cân nhắc dọn hẳn `getTikTokJobs`/`completeTikTokJob`/`skipTikTokJob` khỏi
  `GolikeApi.kt`/`GolikeRepository.kt` ở 1 revision sau, nếu xác nhận không
  còn nhu cầu dùng lại luồng REST-based.
- Nếu Golike đổi UI khiến JS text-matching gãy thường xuyên, cân nhắc thêm
  cơ chế fallback (vd. thử nhiều nhãn text khác nhau, hoặc log DOM snapshot
  để debug nhanh hơn) — chưa cần thiết ở bản này.
