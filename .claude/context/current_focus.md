# Current Focus

_Last updated: 2026-08-21 (v1.3.4 — fix chuẩn hoá acc đầu + luồng job Golike + UI Cài đặt/card Golike)_

## Status: v1.3.4 — 6 hạng mục đã code, CHƯA build/test trên máy thật ⚠️

Lịch sử chi tiết từng bản nằm ở `.claude/revisions/` (append-only, không sửa
lại các file cũ). File này chỉ tóm tắt trạng thái GẦN NHẤT để bắt đầu phiên
làm việc tiếp theo nhanh hơn — các mục "Session 12/14" bên dưới đã cũ (tháng
5/2026), giữ lại cho tham khảo lịch sử, không còn phản ánh trạng thái hiện tại.

### v1.3.4 (2026-08-21) — xem đầy đủ ở `revision-2026-08-21-v1_3_4.md`

1. **FIX root cause** bug "chuẩn hoá click nhầm acc đầu" (bỏ ngỏ từ mục 7.3
   revision v1.3.3 supplement) — `NodeTraverser.parseAccountListWithNodes()`
   gộp Pass 2 (username ASCII) + Pass 3 (display name Unicode) thành 1 vòng
   lặp bảo toàn thứ tự traversal, thay vì 2 vòng tách riêng khiến acc active
   dạng Unicode luôn bị dồn xuống cuối danh sách. **Chưa test trên máy thật.**
2. Luồng job Golike `[D]`: mở link TikTok → tự thao tác thật (double-tap
   Like / bấm Follow qua `doLikeTask()`/`doFollowTask()`, GIỮ NGUYÊN như bản
   gốc) → rồi mới back về nuôi acc. (Có 1 lần đổi tạm sang "chỉ mở rồi back"
   theo mô tả yêu cầu ban đầu, Bảo xác nhận lại đó là nhầm lẫn — đã revert.)
3. Job loại không hỗ trợ (comment, yêu thích) → tự Báo lỗi để bỏ qua
   (`GolikeJobBridge.reportAndSkipJob()`, MỚI) thay vì back() chờ hết hạn.
4. Đăng xuất Golike → xoá luôn cookie/localStorage WebView
   (`GolikeRepository.clearWebViewSession()`, MỚI), không chỉ token app.
5. Làm mới giao diện phần Cài đặt (`ConfigScreen.kt`) + card Golike
   (`ServicesScreen.kt`) — mở rộng ngôn ngữ hình ảnh sẵn có, KHÔNG làm lại
   từ đầu. Mang tính chủ quan/thẩm mỹ — chưa có ảnh trước/sau để Bảo duyệt.
6. Version bump: versionCode 35→36, versionName v1.3.3→v1.3.4 (`build.gradle`
   + `.github/workflows/build.yml`).

### Việc cần làm tiếp theo
- Build & QA trên thiết bị thật — đặc biệt mục 1 (bug chuẩn hoá) vì sandbox
  không có `kotlinc`/thiết bị để tự kiểm.
- Xác nhận giao diện mới (mục 5) có đúng hướng Bảo muốn không.
- Nếu job loại comment/favourite vẫn xử lý sai → cần ảnh chụp trang chi tiết
  job đó để đối chiếu tiêu đề chính xác Golike hiển thị (xem rủi ro đã ghi
  trong revision).

### Bổ sung cùng ngày (sau khi Bảo test v1.3.4 trên máy thật)
- `findProfileTab()`: đổi cơ chế chính sang thuần vị trí (node clickable
  ngoài cùng bên phải trong dải Y đáy màn hình) thay vì theo tên/resourceId
  — cách cũ giữ làm fallback. Ảnh hưởng `detectCurrentAccount()` +
  `verifyCurrentAccount()`.
- FIX root cause "bấm nút TikTok không mở link": `realClick()` (dùng chung
  cho MỌI nút trong JS lib) đổi bước "click" cuối từ `dispatchEvent()`
  (untrusted, không tự trigger default action như theo href thẻ `<a>`)
  sang gọi thẳng `target.click()` thật. Bảo xác nhận bấm tay thật → mở
  TikTok bình thường, khớp đúng giả thuyết "untrusted event không điều
  hướng". Có thể cải thiện luôn các nút khác dùng chung `realClick()`.
- **Cả 2 mục trên CHƯA build & test lại trên máy thật** — cần Bảo xác nhận
  vòng tiếp theo.

---

## Lịch sử cũ (trước 2026-08-21, giữ để tham khảo)

### Session 14 changes (2026-05-25)

**BUG-FARM-001 — CRITICAL (RESOLVED): Missing `<queries>` in manifest**

- `AndroidManifest.xml` thiếu `<queries>` block cho TikTok packages
- Trên Android 11+ (API 30+): `resolveActivity()` và `getLaunchIntentForPackage()` luôn null
- Hậu quả: `launchTikTok()` = false → accounts bị checkpoint ngay → farm hoàn thành < 500ms → user thấy không có gì xảy ra
- Fix: thêm `<queries>` khai báo 3 TikTok packages + 2 schemes (`snssdk1180`, `tiktok`)

**BUG-FARM-002 — HIGH (RESOLVED): Session 12 regression — premature `startForegroundService`**

- `TikTokAccessibilityService.onServiceConnected()` vẫn gọi `startForegroundService()`
- Session 12 fix BootReceiver + MainActivity nhưng bỏ sót service tự start lại
- Fix: xóa call đó, thêm comment giải thích

**BUG-FARM-003 — HIGH (RESOLVED): `openTikTok()` dùng `resolveActivity()` làm guard**

- Pattern cũ: `if (resolveActivity != null) startActivity()` — miss khi resolve null
- Fix: try `startActivity()` trực tiếp, catch exception

**BUG-FARM-004 — LOW (RESOLVED): `startHint` thiếu case `ALL_LOCAL + 0 accounts`**

- Fix: thêm hint "Chưa có tài khoản. Thêm tài khoản hoặc dùng chế độ Danh sách"

---

### Session 12 changes (2026-05-24)

Fix 1: Background service drain — RESOLVED
Fix 2: Farm flow / dual mode — RESOLVED
Fix 3: Settings UI restored — RESOLVED

---

### Flutter status: FULLY REMOVED ✅

---

## Native screens

| Screen    | Activity           | Status           |
|-----------|--------------------|------------------|
| Dashboard | DashboardActivity  | ✅ Active        |
| Accounts  | AccountsActivity   | ✅ Active        |
| Logs      | LogsActivity       | ✅ Active        |
| Stats     | StatsActivity      | ✅ Active        |
| Config    | ConfigActivity     | ✅ Active+linked |
| Schedule  | ScheduleActivity   | ✅ Active        |

---

## Next actions

1. **QA session 14 fixes on device:**
   - "Bắt đầu farm" → FarmingView + notification xuất hiện
   - TikTok được mở bởi deeplink `snssdk1180://`
   - Notification KHÔNG xuất hiện khi chỉ bật Accessibility (chưa farm)
   - Hint hiện khi ALL_LOCAL + 0 accounts

2. **QA session 12 leftovers:**
   - SELECTED_LIST mode, ⚙️ nav, "Dừng" stops farm

3. **TD-BUILD-001 cleanup**: xóa `.bak` files sau CI green

4. **TD-CI-001**: commit `gradle-wrapper.jar`

5. **DashboardViewModelTest** (session 12 debt)

---

## Tests: 38 total (unchanged)
