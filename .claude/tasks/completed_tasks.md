# Completed Tasks

---

## TASK-001 ✅ Split Entities.kt into per-domain files
**Completed**: 2026-05-21
**Files**: `db/entity/AccountEntity.kt`, `SessionEntity.kt`, `FarmLogEntity.kt`, `ConfigEntity.kt`
**Note**: `DailyStatRow` co-located in `SessionEntity.kt` (return type của SessionDao).

---

## TASK-002 ✅ Split Daos.kt into per-domain files
**Completed**: 2026-05-21
**Files**: `db/dao/AccountDao.kt`, `SessionDao.kt`, `LogDao.kt`, `ConfigDao.kt`
**Note**: `TotalsRow` co-located in `SessionDao.kt`. `AtProDatabase.kt` imports unchanged (wildcard `*`).

---

## TASK-005 ✅ LanWebSocketServer reconnect on network change
**Completed**: 2026-05-21
**Files**: `network/LanWebSocketServer.kt`, `data/FarmForegroundService.kt`
**Note**: `ConnectivityManager.NetworkCallback` với debounced restart (2s delay). `start()` signature thay đổi → `start(context: Context)`.

---

## TASK-006 ✅ PopupHandler documentation
**Completed**: 2026-05-21
**Files**: `automation/popup/PopupHandler.kt`
**Note**: Mọi `when` branch và `if` condition trong Tier 1, Tier 2, handleByKeyword đều có comment giải thích popup type + TikTok scenario.

---

## TASK-003 ✅ Unit tests — AutomationEngine
**Completed**: 2026-05-21
**Files**: `automation/IFarmHost.kt`, `data/IFarmRepository.kt`, `automation/AutomationEngine.kt` (refactored), `accessibility/TikTokAccessibilityService.kt` (implements IFarmHost), `data/LocalRepository.kt` (implements IFarmRepository), `test/.../AutomationEngineTest.kt` (7 tests)
**Note**: Interface extraction required to decouple from Android runtime. Full loop integration tests → TASK-008.

---

## TASK-004 ✅ Wake lock — FarmForegroundService
**Completed**: 2026-05-21
**Files**: `data/FarmForegroundService.kt`
**Note**: PARTIAL_WAKE_LOCK, 4h timeout, idempotent acquire/release, try-catch safety, documented MIUI/ColorOS workarounds.

---

## TASK-008 ✅ Integration tests — AutomationEngine farm loop
**Completed**: 2026-05-22
**Files**: `test/.../AutomationEngineIntegrationTest.kt` (6 tests)
**Tests**: launchTikTok fail, feed load fail, switch+minutesZero, checkpoint mid-loop, multi-account order, auto-save accounts
**Note**: Pure JUnit4 + MockK (no Robolectric needed — NodeTraverser is mockable as `object`). Full AccessibilityNodeInfo tree tests → TASK-011.

---

## TASK-009 ✅ Native Compose — AccountsScreen
**Completed**: 2026-05-22
**Files**: `ui/accounts/AccountsViewModel.kt`, `AccountsScreen.kt`, `AccountsActivity.kt`
**Note**: SwipeToDismiss + confirm dialog. Room Flow → StateFlow. Search + 4 filter chips. FeatureFlags.NATIVE_ACCOUNTS = false.

---

## TASK-010 ✅ Native Compose — LogsScreen
**Completed**: 2026-05-22
**Files**: `ui/logs/LogsViewModel.kt`, `LogsScreen.kt`, `LogsActivity.kt`
**Note**: Real-time Room Flow, level filter, auto-scroll to newest, copy-all, clear-all. FeatureFlags.NATIVE_LOGS = false.

---

## TASK-011 ✅ NodeTraverser unit tests
**Completed**: 2026-05-22
**Files**: `test/.../accessibility/NodeTraverserTest.kt` (22 tests)
**Tests**: findByText (5), findByResourceId (2), hasNavBar (4), detectCheckpoint (4), parseAccountList (3), detectPopup (3), detectLive (2), findAllByClass (1)
**Note**: Pure MockK — no Robolectric. node() helper creates full fake AccessibilityNodeInfo trees.

---

## TASK-012 ✅ Native Compose — StatsScreen
**Completed**: 2026-05-22
**Files**: `ui/stats/StatsViewModel.kt`, `StatsScreen.kt`, `StatsActivity.kt`
**Note**: Range selector (7d/30d/all), summary card + daily group breakdown. FeatureFlags.NATIVE_STATS = false. ShortcutRow wired. Manifest registered.

---

## TASK-014 ✅ Mark Flutter screens as legacy
**Completed**: 2026-05-22
**Files**: `lib/screens/dashboard_screen.dart`, `accounts_screen.dart`, `log_screen.dart`, `config_screen.dart`, `stats_screen.dart`
**Note**: LEGACY header added. Screens still functional — remove only after QA-013 sign-off.

---

## TASK-015 ✅ ConfigScreen — native Compose
**Completed**: 2026-05-22
**Files**: `ui/config/ConfigViewModel.kt`, `ConfigScreen.kt`, `ConfigActivity.kt`
**Note**: 3 tabs (Thời gian/Hành động/Thông báo). load/save via ConfigDao. isDirty tracking, SnackBar on save, TikTok version from PackageManager. FeatureFlags.NATIVE_CONFIG=false.

---

## TASK-016 ✅ ScheduleScreen — native Compose
**Completed**: 2026-05-22
**Files**: `ui/schedule/ScheduleViewModel.kt`, `ScheduleScreen.kt`, `ScheduleActivity.kt`
**Note**: Wraps ScheduledFarmManager (WorkManager). Add dialog (label+time+day picker), toggle via Switch, swipe-to-delete with confirm. FeatureFlags.NATIVE_SCHEDULE=false.

---

## TASK-013 ✅ Flip feature flags — all native
**Completed**: 2026-05-22
**Files**: `ui/FeatureFlags.kt`
**Note**: All 6 flags = true. Manual QA checklist (QA-013-native-screens-checklist.md) must be run on 3 OEM devices before production release.

---

## TASK-017 ✅ Remove Flutter dependency
**Completed**: 2026-05-22
**Deleted**: `lib/`, `pubspec.yaml`, `pubspec.lock`, `.flutter-plugins*`, `.metadata`, `drawable/launch_background.xml`
**Rewritten**: `MainActivity.kt`, `bridge/FlutterBridge.kt` (stub), `app/build.gradle`, `android/settings.gradle`, `.github/workflows/build.yml`, `res/values/styles.xml`, `.gitignore`
**Modified**: `ui/dashboard/DashboardScreen.kt` (ShortcutRow), `AndroidManifest.xml`, `res/values/colors.xml`
**Note**: `bridge/FlutterBridge.kt` kept as stub (Option A — TD-NEW-001). 38 tests unaffected. Build pipeline now pure Gradle.

---

## HOTFIX-001 ✅ KDoc bracket version tag — v1.0.9
**Completed**: 2026-05-29
**Files**: `data/OverlayFarmMonitor.kt` (lines 20, 112), `ui/dashboard/DashboardViewModel.kt` (lines 182, 205)
**Note**: `[v1.0.9]` trong KDoc bị parser hiểu là link reference → warning. Fix: bỏ ngoặc vuông → `v1.0.9`. `//` comment giữ nguyên. Xem `revisions/revision-2026-05-29-kdoc-bracket-fix-v1_0_9.md`.
