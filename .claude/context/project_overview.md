# Project Overview — Appatpro

## What is Appatpro?

Appatpro is an Android-native TikTok farming automation tool. It runs directly on an Android device, uses the Accessibility API to drive TikTok, and manages multiple TikTok accounts through automated interactions (watch, like, follow).

**Target**: Single-device, offline-first, no cloud dependency.

## Current architecture (May 2026)

Two layers, one binary:

```
┌─────────────────────────────────────────┐
│  Flutter UI (lib/)                      │  ← thin display layer, legacy/bridge
│  Screens: dashboard, accounts, logs,    │
│  stats, config, schedule, export        │
├─────────────────────────────────────────┤
│  FlutterBridge (MethodChannel/Event)    │  ← narrow bridge, see ADR-0001
├─────────────────────────────────────────┤
│  Android Core (android/)   ← OWNED HERE │
│  AutomationEngine          (automation) │
│  TikTokAccessibilityService(accessibility)│
│  NodeTraverser             (accessibility)│
│  PopupHandler              (automation) │
│  FarmForegroundService     (data)       │
│  ScheduledFarmManager      (scheduler)  │
│  LanWebSocketServer        (network)    │
│  AtProNotificationManager  (notification)│
│  LocalRepository + Room DB (db/data)   │
│  StringEncryptor           (security)   │
└─────────────────────────────────────────┘
```

## Status flags

| Layer       | Status            | Direction               |
|-------------|-------------------|-------------------------|
| Kotlin core | ✅ Primary        | Expand here             |
| Flutter UI  | ⚠️ Legacy bridge  | Shrink over time        |
| FlutterBridge | ⚠️ Necessary bridge | Keep narrow, document |

## Key facts

- Package: `com.atpro`
- DB: Room (SQLite, local only — no Supabase)
- State: Riverpod (Flutter side), Coroutines/Flow (Kotlin side)
- Accessibility: `TikTokAccessibilityService` drives all UI interaction
- Multi-device: `LanWebSocketServer` over LAN (WebSocket)
- Scheduling: `ScheduledFarmManager`
- Overlay: `OverlayFarmMonitor` (floating status window)
