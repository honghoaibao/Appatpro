# Android Rules

## Accessibility Service

- `TikTokAccessibilityService` is the **single entry point** for all UI interaction with TikTok
- Never cache `AccessibilityNodeInfo` across events — they become stale immediately
- Always call `nodeInfo.recycle()` (API < 34) or trust GC (API 34+) — check API level
- Use `NodeTraverser` for all tree queries. Do not write inline traversal logic in `AutomationEngine`
- Popup detection must run **before** every interaction. `PopupHandler.check()` is the gate

## Background work

- Long farming → `FarmForegroundService` (foreground service with notification)
- Scheduled farming → `ScheduledFarmManager` (AlarmManager or WorkManager — document the choice in ADR)
- Do not use plain `Service` for work lasting >10s on Android 12+
- Acquire a partial wake lock inside `FarmForegroundService` when farming actively

## OEM compatibility

- Xiaomi MIUI, OPPO ColorOS, Samsung One UI all have aggressive battery killers
- `BootReceiver` must re-register alarms on `BOOT_COMPLETED`
- Do not assume `BATTERY_OPTIMISATION_IGNORING` — guide users to whitelist manually
- Test schedule resume after simulated reboot on each new scheduler change

## Room / Database

- Schema migrations must be explicit (`addMigrations(...)` on `Room.databaseBuilder`)
- Never use `fallbackToDestructiveMigration()` in production builds
- DAO queries that return lists → return `Flow<List<T>>` for reactive UI
- DAO queries for single items → return `suspend fun` with nullable

## Permissions

- Declare only required permissions in `AndroidManifest.xml`
- Accessibility permission: guide user via `AccessibilitySettingsHelper` — never request programmatically (Android restriction)
- Overlay permission (`SYSTEM_ALERT_WINDOW`): required for `OverlayFarmMonitor` — check and request in `permission_screen`

## Security

- All account credentials → `StringEncryptor` before Room storage
- No plaintext secrets in logs
- No secrets in `BuildConfig` fields committed to repo
