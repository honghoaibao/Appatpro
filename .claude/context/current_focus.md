# Current Focus

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
