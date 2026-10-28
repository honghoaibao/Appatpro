# Architecture — Appatpro

_Last updated: 2026-10-26 — v1.5.0 (thêm ui/theme); v1.4.0 (gỡ golike/ → tasks/, thêm FarmBehavior, PopupLoopGuard). Trước đó đồng bộ với v1.3.3: thêm golike/, UpdateChecker, DiscordNotifier, ui/services, ui/golike; getRootNode() là suspend. (Trước đó 2026-05-24: FlutterBridge, FeatureFlags đã gỡ.)_

## Layer map

```
android/app/src/main/kotlin/com/atpro/
├── accessibility/          ANDROID-FIRST ✅
│   ├── TikTokAccessibilityService.kt   — accessibility entry point
│   └── NodeTraverser.kt                — node tree queries
├── automation/             ANDROID-FIRST ✅
│   ├── AutomationEngine.kt             — farm + task-mode orchestration (taskPostJobNurtureSecs ở top-level)
│   ├── FarmBehavior.kt                 — RateGate + WatchPlan (thuật toán hành vi, pure Kotlin, có test)
│   ├── IFarmHost.kt                    — interface host (getRootNode() là suspend từ v1.3.1)
│   └── popup/
│       └── PopupHandler.kt             — popup detection & dismissal
├── data/                   ANDROID-FIRST ✅
│   ├── FarmConfig.kt                   — farm configuration model
│   ├── FarmForegroundService.kt        — foreground service lifecycle
│   ├── LocalRepository.kt              — Room data access (implements IFarmRepository)
│   ├── IFarmRepository.kt              — interface repo cho engine/test
│   ├── OverlayFarmMonitor.kt           — floating overlay
│   ├── TikTokDeepLinks.kt              — TikTok deep link constants
│   ├── AccessibilitySettingsHelper.kt  — accessibility permission helpers
│   └── BootReceiver.kt                 — reboot → restore schedules
├── db/                     ANDROID-FIRST ✅
│   ├── AtProDatabase.kt                — Room DB instance
│   ├── dao/                            — DAO interfaces (AccountDao, SessionDao, LogDao, ConfigDao)
│   └── entity/                         — AccountEntity, SessionEntity, FarmLogEntity, ConfigEntity
├── ui/theme/               ANDROID-FIRST ✅   (v1.5.0)
│   └── AtTheme.kt                      — AtColors (nguồn màu DUY NHẤT), AtType, AtTheme, AtCard/AtButton/AtSegmented/AtStatus/LiveDot/AtDialogCard, pressClickable/staggerIn/AnimatedNumber
├── tasks/                  ANDROID-FIRST ✅   (v1.4.0 — thay golike/)
│   └── TaskProvider.kt                 — interface + TaskJob/TaskAccount/TaskResult; TaskProviders.current() = null (chờ Follow4Follow)
├── network/                ANDROID-FIRST ✅
│   ├── LanWebSocketServer.kt           — LAN broadcast
│   ├── UpdateChecker.kt                — kiểm tra bản mới từ GitHub Releases
│   └── DiscordNotifier.kt              — gửi thông báo qua Discord webhook
├── notification/           ANDROID-FIRST ✅
│   └── AtProNotificationManager.kt     — Android notifications + Telegram/Discord webhooks
├── scheduler/              ANDROID-FIRST ✅
│   └── ScheduledFarmManager.kt         — schedule management (contains FarmAlarmReceiver)
├── security/               ANDROID-FIRST ✅
│   └── StringEncryptor.kt              — AES encryption
├── ui/                     ANDROID-FIRST ✅ (Jetpack Compose)
│   ├── dashboard/   DashboardActivity + DashboardScreen + DashboardViewModel
│   ├── accounts/    AccountsActivity  + AccountsScreen  + AccountsViewModel
│   ├── logs/        LogsActivity      + LogsScreen      + LogsViewModel
│   ├── stats/       StatsActivity     + StatsScreen     + StatsViewModel
│   ├── config/      ConfigActivity    + ConfigScreen    + ConfigViewModel
│   ├── schedule/    ScheduleActivity  + ScheduleScreen  + ScheduleViewModel
│   ├── services/    ServicesScreen    — chọn dịch vụ/ServiceMode, thẻ Nhiệm vụ kiếm tiền (gate TASKS_ENABLED)
│   ├── MainScreen.kt                   — khung tab điều hướng
│   └── SplashActivity.kt
├── AtProApplication.kt     — Application class (MultiDexApplication), global ctx
└── MainActivity.kt         — AppCompatActivity: start service → DashboardActivity → finish()
```

Flutter (`lib/`) has been removed entirely.
`bridge/FlutterBridge.kt` has been removed (TD-NEW-001 resolved).
`ui/FeatureFlags.kt` has been removed (TD-NEW-002 resolved).

## Data flow

```
TikTok (UI) ←→ TikTokAccessibilityService
                      ↓
              AutomationEngine
              ↙         ↘
     LocalRepository   LanWebSocketServer.broadcast()
          ↓                    ↓
       Room DB          LAN WebSocket clients (multi-device)
```

## Dependency rules

```
accessibility   → (no internal deps)
automation      → accessibility, data, tasks, network, notification
data            → db
db              → (no internal deps above Room)
tasks           → (no internal deps)
network         → (no internal deps)
notification    → (no internal deps)
scheduler       → data, db
security        → (no internal deps)
ui              → db, data, scheduler, tasks, automation (via StateFlow)
```

## Entry flow

```
Launcher → MainActivity (AppCompatActivity)
               ↓
         startForegroundService(FarmForegroundService)
               ↓
         startActivity(DashboardActivity)
               ↓ finish()
         DashboardActivity (Compose)
```

## Notification channels

| Channel ID          | Created by            | Purpose                    |
|---------------------|-----------------------|----------------------------|
| atpro_farm_channel  | FarmForegroundService | Foreground service status  |
| atpro_farm          | AtProApplication      | Push: farm status          |
| atpro_alert         | AtProApplication      | Push: warnings/errors      |
| atpro_done          | AtProApplication      | Push: session complete     |

## Flutter status

Flutter is fully removed. No Flutter SDK dependency anywhere in the build chain.

## Task mode (TaskProvider) — luồng tóm tắt (v1.4.0)

```
runTaskStateMachine()
  → taskOneAccount()
      [A] nuôi acc đệm (taskFarmBeforeJobSecs)
      [B] Human.delay(1.2–3s) → provider.nextJob(account)
      [C..E] doLikeTask() / doFollowTask()
      [F] provider.complete() / provider.skip()
      [G] nếu job thành công → doSimpleFarm(taskPostJobNurtureSecs(...))   ← nuôi ngắn 5–20s, hoặc nuôi lâu mỗi N job
  → chuyển acc khi đủ taskJobsPerAccount hoặc taskMaxConsecFailures
```

Khởi tạo: `TikTokAccessibilityService.onServiceConnected()` → `AutomationEngine(taskProvider = TaskProviders.current(ctx))` → `DashboardViewModel.startTask()` → `engine.startTask(mode, inputList)`.

---

## Session 12 changes (2026-05-24)

### FarmForegroundService lifecycle (updated)

Service now starts/stops with farming, not with app open:

```
startFarm()
  → DashboardViewModel.startFarm()
    → appContext.startForegroundService(FarmForegroundService)   ← NEW
    → engine.startFarm(accounts)

stop()
  → DashboardViewModel.stop()
    → engine.stop()
    → appContext.startService(ACTION_STOP → FarmForegroundService.stopSelf())  ← NEW
```

**Previously**: `MainActivity.onCreate()` and `BootReceiver` both started the service unconditionally.

### Farm flow (updated)

```
DashboardUiState.farmMode = ALL_LOCAL | SELECTED_LIST

startFarm():
  ALL_LOCAL      → accounts = DB active accounts (may be empty)
  SELECTED_LIST  → accounts = parse(customAccounts text, one username per line)

canStart:
  ALL_LOCAL      → serviceConnected && !isFarming   (no account count check)
  SELECTED_LIST  → serviceConnected && !isFarming && customAccounts.isNotBlank()
```

### Settings navigation (restored)

`ConfigActivity` was always registered in manifest but had no route in the UI.
Now accessible via ⚙️ icon in `DashboardScreen.IdleView` header.
