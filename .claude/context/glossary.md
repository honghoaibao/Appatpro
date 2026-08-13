# Glossary — Appatpro Domain Language

Use these terms consistently. Prefer short canonical names.
When code or docs deviate, flag it and align.

---

## Core concepts

| Term | Definition |
|------|-----------|
| **Farm / Farming** | The automated process of running one or more TikTok accounts through interactions (watch, like, follow) for a configured duration |
| **Farm run** | One full execution of farming across all selected accounts |
| **Account** | A TikTok username managed by Appatpro (`AccountEntity`) |
| **Session** | One farm cycle for one account (`SessionEntity`). Has a start/end time and interaction counters |
| **FarmConfig** | The runtime configuration for a farm run: minutes per account, like rate, follow rate, rest intervals, etc. |
| **Interaction** | A single automated action: watch video, like, follow, comment |
| **Popup** | An in-app TikTok dialog that blocks automation and must be dismissed (`PopupHandler`) |

## Android layer

| Term | Definition |
|------|-----------|
| **AutomationEngine** | Orchestrates a full farm run. Iterates accounts, delegates single-account farming, reports progress via FlutterBridge |
| **TikTokAccessibilityService** | The Android `AccessibilityService` that drives TikTok UI. Entry point for all node-level interaction |
| **NodeTraverser** | Utility for traversing and querying the AccessibilityNodeInfo tree |
| **PopupHandler** | Detects and dismisses TikTok popups before the farming loop continues |
| **FarmForegroundService** | Android foreground service that keeps AutomationEngine alive while the screen is off or the app is backgrounded |
| **ScheduledFarmManager** | Manages start/stop schedules for farm runs (`FarmSchedule`) |
| **FarmSchedule** | A named schedule: days of week + start/stop time |
| **LanWebSocketServer** | WebSocket server broadcasting farm status to other devices on the same LAN |
| **OverlayFarmMonitor** | Floating overlay window showing real-time account/progress info while farming |
| **LocalRepository** | Single data access point for all Room operations |
| **AtProDatabase** | Room database instance. Tables: accounts, sessions, farm_logs, config |
| **AtProNotificationManager** | Android notification management for farm start, progress, errors |
| **StringEncryptor** | AES encryption for sensitive data at rest (account credentials, tokens) |
| **BootReceiver** | Restores scheduled farms on device reboot |

## Flutter/bridge layer (legacy)

| Term | Definition |
|------|-----------|
| **FlutterBridge** | The Kotlin `object` that owns MethodChannel + EventChannel. Only bridge between Flutter and Android core |
| **MethodChannel** | `com.atpro/control` — Flutter calls into Kotlin (start farm, stop farm, get config, etc.) |
| **EventChannel** | `com.atpro/events` — Kotlin pushes events to Flutter (farmStatus, currentAccount, logLine, etc.) |
| **at_pro_bridge.dart** | Flutter-side bridge class that wraps MethodChannel calls |

## Status values

| Term | Values |
|------|--------|
| **Account status** | `active` / `checkpoint` / `banned` |
| **Farm status event** | `started` / `stopped` / `error` / `completed` |

## Avoid these ambiguous terms

| Avoid | Use instead |
|-------|-------------|
| "bot" | "automation" or "farm" |
| "scrape" | "watch" or "interact" |
| "server" | be specific: "LanWebSocketServer" or "FarmForegroundService" |
| "app" | "Appatpro" (the product) or "TikTok" (the target app) |
