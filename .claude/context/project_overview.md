# Project Overview — Appatpro

_Last updated: 2026-10-26 — v1.4.0 (versionCode 36)_

## Appatpro là gì?

Appatpro (AT PRO) là công cụ tự động hóa mạng xã hội thuần Android. Chạy trực tiếp trên thiết bị, dùng Accessibility API để điều khiển TikTok (và Facebook, Instagram, X, Threads, Snapchat ở mức nuôi acc cơ bản), quản lý nhiều tài khoản qua các thao tác tự động (xem, like, follow, comment…) và làm **nhiệm vụ kiếm tiền** (like/follow có trả phí) qua `TaskProvider` — hiện chưa có nhà cung cấp (Golike đã gỡ, Follow4Follow chờ tài liệu API).

**Mục tiêu:** một thiết bị, offline-first cho phần nuôi acc, không phụ thuộc cloud. Chỉ phần nhiệm vụ kiếm tiền cần mạng.

## Kiến trúc hiện tại (một lớp, thuần Kotlin)

```
┌──────────────────────────────────────────────────────────┐
│  UI — Jetpack Compose (ui/)                              │
│  dashboard · accounts · logs · stats · config ·          │
│  schedule · services                                     │
├──────────────────────────────────────────────────────────┤
│  automation/   AutomationEngine · FarmBehavior · PopupHandler│
│               · PopupLoopGuard · IFarmHost               │
│  accessibility/ TikTokAccessibilityService · NodeTraverser│
├──────────────────────────────────────────────────────────┤
│  tasks/   TaskProvider · TaskJob · TaskAccount · TaskResult│
│  data/    LocalRepository · FarmConfig · FarmForegroundService│
│           OverlayFarmMonitor · TikTokDeepLinks · BootReceiver│
│  db/      Room (Account/Session/Log/Config)              │
│  scheduler/ ScheduledFarmManager                         │
│  network/ LanWebSocketServer · UpdateChecker · DiscordNotifier│
│  notification/ AtProNotificationManager                  │
│  security/ StringEncryptor (+ AppConstants)              │
└──────────────────────────────────────────────────────────┘
```

Chi tiết từng package và quy tắc phụ thuộc: xem `architecture.md`.

## Status flags

| Layer | Trạng thái | Hướng đi |
|-------|-----------|----------|
| Kotlin core | ✅ Primary | Mở rộng tại đây |
| Flutter / FlutterBridge | ❌ Đã gỡ (ADR-0004, TD-NEW-001) | Không tái đưa vào |
| Golike | ❌ Đã gỡ hoàn toàn (v1.4.0) | Không tái đưa vào |
| TaskProvider / Follow4Follow | ⏳ Giao diện sẵn, chưa có provider (`TASKS_ENABLED = false`) | Xem `current_focus.md` |

## Key facts

- Package: `com.atpro` · minSdk 24 · target/compileSdk 34
- DB: Room 2.6.1 (SQLite, local only — không Supabase); cấu hình `FarmConfig` đọc/ghi dạng key-value qua `LocalRepository`
- State: Coroutines/Flow → StateFlow → Compose (không còn Riverpod)
- Accessibility: `TikTokAccessibilityService` thực hiện toàn bộ thao tác UI; `getRootNode()` là `suspend` (từ v1.3.1)
- Đa thiết bị: `LanWebSocketServer` qua LAN
- Lập lịch: `ScheduledFarmManager` (WorkManager + AlarmManager)
- Overlay: `OverlayFarmMonitor` (cửa sổ nổi trạng thái farm)
- Nhiệm vụ kiếm tiền: `com.atpro.tasks.TaskProvider` (Follow4Follow dự kiến); `GolikeAPI_Documentation.md` chỉ còn là tài liệu lưu trữ
- CI: GitHub Actions, Gradle 8.3 qua `setup-gradle@v6`, JDK 17; `gradle-wrapper.jar` cố ý không commit
