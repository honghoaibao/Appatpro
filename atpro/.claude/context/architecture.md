# Architecture — Appatpro

_Last updated: 2026-10-31 — v1.8.0 (bố cục 5 tab; ui/settings/; Thống kê/Lịch thành màn hình phủ; AtTopBar; logo mới; sửa thứ tự phát hiện acc ở task mode). Trước đó: v1.7.0 (Sky Sáng/Tối + ui/f4f/); v1.6.0 (tasks/f4f/); v1.5.0 (ui/theme); v1.4.0 (gỡ golike/ → tasks/); v1.3.3 (UpdateChecker, DiscordNotifier, ui/services). (2026-05-24: FlutterBridge, FeatureFlags đã gỡ.)_

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
├── tasks/                  ANDROID-FIRST ✅   (v1.4.0 — thay golike/; v1.6.0 — có provider thật)
│   ├── TaskProvider.kt                 — interface + TaskJob/TaskAccount/TaskResult; TaskProviders.current() = Follow4FollowProvider (singleton)
│   └── f4f/                            — Follow4Follow (v1.6.0), xem context/Follow4FollowAPI_Documentation.md
│       ├── Follow4FollowProvider.kt    — luồng acc → quick-switch → job → mark-start → complete/skip; quota, 429, 401
│       ├── F4fApiClient.kt             — gắn x-ts/x-sign/Bearer, bóc envelope, hiệu chỉnh đồng hồ; không ném exception
│       ├── F4fSigner.kt                — HMAC-SHA256 ký request (pure, có test vector)
│       ├── F4fHttp.kt                  — F4fTransport (tách để test) + UrlConnectionTransport
│       ├── F4fAuth.kt                  — F4fAuthManager (login/refresh/logout, StateFlow), F4fSessionStorage, F4fTokenCodec, singleton F4fAuth
│       ├── F4fService.kt, F4fModels.kt — Ví (v1.7.0): dashboard, xếp hạng, rút tiền 5 kênh, lịch sử, giới thiệu, thông báo
│       └── F4fHints.kt, F4fJson.kt     — gợi ý lỗi nghiệp vụ; đọc JSON khoan dung
├── network/                ANDROID-FIRST ✅
│   ├── LanWebSocketServer.kt           — LAN broadcast
│   ├── UpdateChecker.kt                — kiểm tra bản mới từ GitHub Releases
│   └── DiscordNotifier.kt              — gửi thông báo qua Discord webhook
├── notification/           ANDROID-FIRST ✅
│   └── AtProNotificationManager.kt     — Android notifications + Telegram/Discord webhooks
├── scheduler/              ANDROID-FIRST ✅
│   └── ScheduledFarmManager.kt         — schedule management (contains FarmAlarmReceiver)
├── security/               ANDROID-FIRST ✅
│   └── StringEncryptor.kt              — che chuỗi (XOR): package TikTok, khoá ký F4F, token F4F khi lưu (+ AppConstants, cờ TASKS_ENABLED)
├── ui/                     ANDROID-FIRST ✅ (Jetpack Compose)
│   ├── dashboard/   DashboardActivity + DashboardScreen + DashboardViewModel
│   ├── accounts/    AccountsScreen    + AccountsViewModel   (tab; v1.9.0: bỏ AccountsActivity)
│   ├── logs/        LogsScreen        + LogsViewModel       (tab; v1.9.0: bỏ LogsActivity)
│   ├── stats/       StatsScreen + StatsCharts + StatsViewModel (màn hình phủ; v1.10.0: tab Nuôi acc / Kiếm tiền, biểu đồ Canvas)
│   ├── config/      ConfigActivity    + ConfigScreen    + ConfigViewModel
│   ├── schedule/    ScheduleScreen    + ScheduleViewModel   (màn hình phủ; v1.9.0: bỏ ScheduleActivity)
│   ├── services/    ServicesScreen    — chọn dịch vụ/ServiceMode; hàng Nhiệm vụ kiếm tiền (chưa đăng nhập F4F → mở hộp thoại đăng nhập)
│   ├── tasks/       F4fAccountCard.kt — F4fLoginDialog + F4fAccountCard (Cài đặt → Nhiệm vụ)
│   ├── settings/    SettingsHubScreen — tab Cài đặt một cấp (Giao diện · Công cụ: Thống kê, Lịch · Cấu hình · Ứng dụng) → ConfigActivity mở thẳng mục (v1.8.0)
│   ├── f4f/         F4fHubScreen (6 tab) · F4fWithdrawPanel · F4fHubViewModel · F4fHubCommon — Ví Follow4Follow (v1.7.0)
│   ├── theme/       AtTheme.kt — AtPalettes (Sáng/Tối) · AtThemeState · AtColors (getter động) · AtTheme · thành phần chuẩn
│   └── MainScreen.kt                   — khung 5 tab (Tổng quan · Dịch vụ · Tài khoản · Nhật ký · Cài đặt) + màn hình phủ Thống kê/Lịch/Ví F4F
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

> v1.12.0: `tasks/f4f/Follow4FollowProvider.kt` theo `f4f.py` v2.8 (danh sách acc `quick-switch`, `earn-coin` luôn kèm `uid`, thang đổi acc S1–S5, `jobGone`, trace); `tasks/TaskAccountSelector.kt` (chọn acc nhận nhiệm vụ: máy ∩ liên kết ∩ chọn tay); `notification/NotificationPolicy.kt` (lõi thuần: nhóm, giờ yên tĩnh, chống lặp, câu chữ) + `AtProNotificationManager` (vỏ Android: kênh, quyền, nhóm, điều hướng `NotificationNavigator`, tiến độ `RunStatusHolder` → `FarmForegroundService`). Xem ADR-0014.
>
> v1.11.0: `accessibility/VideoText.kt` (đọc số tim/hashtag), `data/LogPolish.kt` (nhật ký dễ đọc), `ui/theme/AtSlider.kt`; `NodeTraverser` không bao giờ chọn nhãn Đăng xuất; `AutomationEngine.openSwitchList()`. Xem ADR-0013.

> v1.10.0: `data/EarningsStore.kt` (nhật ký thu nhập từng job) + `data/EarningsStats.kt` (`summarizeEarnings`, hàm thuần) → tab Kiếm tiền của Thống kê. Xem ADR-0012.
