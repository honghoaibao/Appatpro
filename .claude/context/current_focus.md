# Current Focus

_Last updated: 2026-05-24 (session 12 — fix background, farm flow, settings UI)_

## Status: Build unblocked ✅ — 3 bugs fixed (session 12)

---

### Session 12 changes (2026-05-24)

**Fix 1 — Background service drain (RESOLVED)**

- `BootReceiver` và `MainActivity` không còn auto-start `FarmForegroundService`
- `FarmForegroundService` chỉ start khi `DashboardViewModel.startFarm()` được gọi
- `FarmForegroundService` stop qua `ACTION_STOP` khi `DashboardViewModel.stop()` được gọi
- WakeLock và WS server không còn chạy 24/7

**Fix 2 — Farm flow / account block (RESOLVED)**

- `canStart` không còn yêu cầu `activeCount > 0`
- Thêm `FarmMode` enum: `ALL_LOCAL` và `SELECTED_LIST`
- `ALL_LOCAL`: farm toàn bộ active acc trong DB (DB rỗng → engine no-ops gracefully)
- `SELECTED_LIST`: farm theo danh sách username user nhập tay (multiline text field)
- `DashboardViewModel`: thêm `setFarmMode()`, `setCustomAccounts()`
- `DashboardScreen`: thêm `FarmModeToggle` + `AccountListInput` composables

**Fix 3 — Settings UI missing (RESOLVED)**

- Thêm settings icon (⚙️) vào header của `IdleView` trong `DashboardScreen`
- Tap → mở `ConfigActivity` (đã có sẵn, chỉ thiếu route)
- `ConfigActivity`, `ConfigScreen`, `ConfigViewModel` không bị sửa

---

### Session 11 changes (2026-05-23) — còn active

**TD-BUILD-001 (RESOLVED)**: `DependencyHandler.module()` build failure

- Kotlin 1.8.22 → 1.9.25, Compose Compiler 1.4.8 → 1.5.15
- ADR-0007 created

---

### Session 10 (2026-05-22) — còn active

**TD-CI-003 (RESOLVED)**: `android/settings.gradle` block order fixed

---

### Flutter status: FULLY REMOVED ✅

---

## Native screens: all active + Settings accessible

| Screen    | Activity           | Status          |
|-----------|--------------------|-----------------|
| Dashboard | DashboardActivity  | ✅ Active        |
| Accounts  | AccountsActivity   | ✅ Active        |
| Logs      | LogsActivity       | ✅ Active        |
| Stats     | StatsActivity      | ✅ Active        |
| Config    | ConfigActivity     | ✅ Active + linked|
| Schedule  | ScheduleActivity   | ✅ Active        |

---

## Next actions (priority order)

1. **QA session 12 fixes on device:**
   - Verify app does NOT show foreground notification on open (service not running)
   - Verify "Bắt đầu farm" is enabled even with 0 DB accounts (ALL_LOCAL mode)
   - Verify SELECTED_LIST mode: text field shows, button disabled when blank
   - Verify ⚙️ icon → opens ConfigActivity (Cài đặt screen with 3 tabs)
   - Verify farming starts FarmForegroundService notification
   - Verify "Dừng" stops farming AND dismisses notification

2. **Write VM tests (session 13):**
   - `canStart` logic per `FarmMode`
   - Account list resolution per mode
   - Service lifecycle (mock context)

3. **Push & verify CI green** (session 11 Kotlin upgrade)

4. **Cleanup backups** (after CI green):
   ```bash
   rm android/settings.gradle.bak android/settings.gradle.bak2 android/app/build.gradle.bak
   ```

5. **TD-CI-001** — commit `gradle-wrapper.jar` locally

---

## Tests: 38 total (unchanged from session 11)

- AutomationEngineTest:            7
- AutomationEngineIntegrationTest: 7
- NodeTraverserTest:              22
