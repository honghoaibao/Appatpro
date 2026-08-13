# Revision: Fix Background, Farm Flow, Settings UI

**Date**: 2026-05-24
**Session**: 12
**Status**: Applied ✅

---

## Summary

3 bugs fixed. All changes are minimal and surgical — no architecture rewrite.

---

## Fix 1: Background service drain

### What changed
- `BootReceiver.kt` — removed `startForegroundService()` call on boot
- `MainActivity.kt` — removed `startForegroundService()` call on every app launch

### Why
`FarmForegroundService` holds a 4-hour `PARTIAL_WAKE_LOCK`, starts `LanWebSocketServer`, and runs a 10-second heartbeat. Starting it on every app open and every device boot caused continuous battery and RAM drain, even when not farming.

### New behavior
- Service starts only when `DashboardViewModel.startFarm()` is called
- Service stops via `ACTION_STOP` when `DashboardViewModel.stop()` is called

### Impacted files
- `data/BootReceiver.kt`
- `MainActivity.kt`
- `ui/dashboard/DashboardViewModel.kt`

### Rollback
```
# BootReceiver: add back
val svcIntent = Intent(context, FarmForegroundService::class.java)
context.startForegroundService(svcIntent)

# MainActivity: add back before startActivity()
try {
    startForegroundService(FarmForegroundService.buildIntent(this))
} catch (e: Exception) {}

# DashboardViewModel: remove startForegroundService from startFarm(), remove ACTION_STOP from stop()
```

---

## Fix 2: Farm flow — account block removed, dual-mode added

### What changed
- `ui/dashboard/DashboardViewModel.kt`:
  - Added `FarmMode` enum (`ALL_LOCAL`, `SELECTED_LIST`)
  - Added `farmMode: FarmMode` and `customAccounts: String` to `DashboardUiState`
  - Removed `activeCount > 0` from `canStart`
  - `startFarm()` now resolves account list based on mode
  - New methods: `setFarmMode()`, `setCustomAccounts()`
- `ui/dashboard/DashboardScreen.kt`:
  - Added `FarmModeToggle` composable (2-tab toggle)
  - Added `AccountListInput` composable (multiline text field, SELECTED_LIST mode only)
  - Account count display adapts to mode (`displayCount`)

### Why
`canStart` blocked on `activeCount > 0` — users with 0 saved accounts could never start. Requirement: allow farm start first, let engine discover/save accounts during run. Also added SELECTED_LIST mode so users can paste a curated list without depending on DB accounts.

### Behavior per mode
- `ALL_LOCAL`: farms active accounts from DB. If DB empty → `engine.startFarm([])` → engine completes immediately (no-op). This is acceptable; user should be informed via the 0-count display.
- `SELECTED_LIST`: farms usernames from text area. `@` prefix stripped automatically. Start button disabled if text field empty.

### Impacted files
- `ui/dashboard/DashboardViewModel.kt`
- `ui/dashboard/DashboardScreen.kt`

### Rollback
Revert `canStart` to: `serviceConnected && activeCount > 0 && !isFarming`
Remove `farmMode`, `customAccounts` from UiState and related VM methods.
Remove `FarmModeToggle` and `AccountListInput` from DashboardScreen.

---

## Fix 3: Settings UI restored

### What changed
- `ui/dashboard/DashboardScreen.kt` — added gear icon (`Icons.Rounded.Settings`) in IdleView header, tapping opens `ConfigActivity`

### Why
`ConfigActivity` + `ConfigScreen` were complete and registered in manifest but never linked from the dashboard's `ShortcutRow`. There was no navigation path to the Settings screen from the app.

### Impacted files
- `ui/dashboard/DashboardScreen.kt`

### Rollback
Remove the `IconButton` with `Icons.Rounded.Settings` from `IdleView`'s header row.

---

## Risks / remaining notes

1. **ALL_LOCAL + empty DB** — engine runs immediately and completes without farming. The large "0" count on screen is the visual feedback. Could add a more explicit warning in a future pass.
2. **Service start failure** — if `startForegroundService` fails in `startFarm()`, it's caught and logged. Engine still starts farming. This matches previous behavior in `MainActivity`.
3. **Stop sequence** — `stop()` calls `engine.stop()` first (stops the coroutine job), then sends `ACTION_STOP` to service (stopSelf + release WakeLock). If service is not running, `startService(stopIntent)` is a no-op on Android (service not found = silent fail).
4. **WS server not running before farm** — LanWebSocketServer no longer starts on app open. It starts with the service at farm-begin. Remote LAN clients will need to reconnect at that point. Low impact: the WS server was previously ephemeral anyway.

---

## Tests needed (not in scope this session)

- `DashboardViewModelTest`: verify `canStart` is `true` when `activeCount == 0` in `ALL_LOCAL`
- `DashboardViewModelTest`: verify `canStart` is `false` when `customAccounts` is blank in `SELECTED_LIST`
- `DashboardViewModelTest`: verify `startFarm()` resolves correct account list per mode
- `DashboardViewModelTest`: verify `stop()` sends ACTION_STOP intent
