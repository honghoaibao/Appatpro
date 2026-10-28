# Current Focus

_Last updated: 2026-10-26 — v1.5.0 (versionCode 37)_

## Trạng thái: v1.4.0 — chờ tài liệu Follow4Follow + CI + QA thiết bị

Golike/WebView đã gỡ hoàn toàn. Task mode đi qua `TaskProvider` nhưng chưa có provider nào
(`TASKS_ENABLED = false`). Sandbox không có Android SDK nên toàn dự án chưa được biên dịch;
chỉ các module thuần (`FarmBehavior`, `PopupLoopGuard`, `taskPostJobNurtureSecs`) đã được biên
dịch + chạy test. **CI là nguồn xác nhận cuối cùng.**

## Phiên bản gần đây

| Bản | Nội dung | Ghi chú chi tiết |
|-----|----------|------------------|
| v1.5.0 (37) | Thiết kế lại giao diện (`ui/theme/AtTheme.kt`); Services/Dashboard/hộp thoại/thanh điều hướng viết lại | `revision/v1.5.0.md` |
| v1.4.0 (36) | Gỡ Golike+WebView; `TaskProvider`; `RateGate`/`WatchPlan`; `PopupLoopGuard`; thẻ nhiệm vụ mới | `revision/v1.4.0.md` |
| v1.3.3 (35) | Bật Golike; nuôi acc ngắn/lâu giữa các job; delay trước khi lấy job; `doLikeTask()` double-tap dự phòng; CI dùng `setup-gradle@v6` | `revision/v1.3.3.md` |
| v1.3.2 (34) | Fix `findProfileTab()` bấm nhầm tab; popup cho Golike | `revision/v1.3.2.md` |
| v1.3.1 (33) | `getRootNode()` → `suspend`, `safeScreenRead()`; `ACCOUNT_UPDATE_PROMPT`; Facebook thoát bottom sheet | `revision/v1.3.1.md` |
| v1.3.0 (32) | Bỏ khóa cài đặt nuôi acc; Like/Comment đa nền tảng; xem Reels Facebook | `revision/v1.3.0.md` |

## Next actions

0. **QA giao diện trên máy thật:** chưa ai xem giao diện v1.5.0 hiển thị; gửi ảnh chụp để chỉnh bố cục các màn còn lại.
1. **Follow4Follow:** tài liệu đã nhận nhưng là API SMM panel (mua đơn tương tác), KHÔNG có endpoint làm job kiếm tiền → chưa khớp `TaskProvider`; chờ quyết định hướng (xem lịch sử hội thoại) (xác thực, endpoint, định dạng phản hồi, cooldown) → viết `Follow4FollowProvider`, trả ở `TaskProviders.current()`, đặt `TASKS_ENABLED = true`, lưu khoá API qua `StringEncryptor`.
2. **CI lần đầu v1.4.0:** biên dịch toàn dự án (phần UI/engine chưa được compile trong sandbox).
3. **QA thiết bị:** hiệu quả `RateGate` thực tế (tỉ lệ like/follow trong log), `PopupLoopGuard` khi popup cứng đầu, 6 cụm từ quyền Android 13.
4. Giữ các mục QA v1.3.3 còn hiệu lực khi có provider: double-tap idempotent, lock job, `findHomeTab()`/`findInboxTab()`.

## Màn hình native

| Screen    | Activity           | Trạng thái |
|-----------|--------------------|------------|
| Dashboard | DashboardActivity  | ✅ Active  |
| Accounts  | AccountsActivity   | ✅ Active  |
| Logs      | LogsActivity       | ✅ Active  |
| Stats     | StatsActivity      | ✅ Active  |
| Config    | ConfigActivity     | ✅ Active  |
| Schedule  | ScheduleActivity   | ✅ Active  |
| Services  | ServicesScreen (tab trong MainScreen) | ✅ Active |
| Nhiệm vụ kiếm tiền | Thẻ trong ServicesScreen | ⏳ "Sắp có" (gate bởi `TASKS_ENABLED`) |

Flutter: đã gỡ hoàn toàn ✅

---

# Lưu trữ — trạng thái tại session 14 (2026-05-25), giữ nguyên để tra cứu

_Last updated: 2026-05-25 (session 14 — fix farm button / deeplink / service regression)_

## Status: 4 bugs fixed ✅ (session 14)

---

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
