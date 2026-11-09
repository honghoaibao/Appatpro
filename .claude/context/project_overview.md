# Project Overview — Appatpro

_Last updated: 2026-10-30 — v1.7.0 (versionCode 39)_

## Appatpro là gì?

Appatpro (AT PRO) là công cụ tự động hóa mạng xã hội thuần Android. Chạy trực tiếp trên thiết bị, dùng Accessibility API để điều khiển TikTok (và Facebook, Instagram, X, Threads, Snapchat ở mức nuôi acc cơ bản), quản lý nhiều tài khoản qua các thao tác tự động (xem, like, follow, comment…) và làm **nhiệm vụ kiếm tiền** (like/follow có trả phí) qua `TaskProvider` — nhà cung cấp hiện tại là **Follow4Follow** (đăng nhập trong app; Golike đã gỡ ở v1.4.0).

**Mục tiêu:** một thiết bị, offline-first cho phần nuôi acc, không phụ thuộc cloud. Chỉ phần nhiệm vụ kiếm tiền cần mạng.

## Kiến trúc hiện tại (một lớp, thuần Kotlin)

```
┌──────────────────────────────────────────────────────────┐
│  UI — Jetpack Compose (ui/)                              │
│  dashboard · accounts · logs · stats · config ·          │
│  schedule · services · tasks (đăng nhập F4F)             │
├──────────────────────────────────────────────────────────┤
│  automation/   AutomationEngine · FarmBehavior · PopupHandler│
│               · PopupLoopGuard · IFarmHost               │
│  accessibility/ TikTokAccessibilityService · NodeTraverser│
├──────────────────────────────────────────────────────────┤
│  tasks/   TaskProvider · TaskJob · TaskAccount · TaskResult│
│           f4f/ Follow4FollowProvider · F4fApiClient · F4fAuth│
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
| TaskProvider / Follow4Follow | ✅ Đã tích hợp (v1.6.0, `TASKS_ENABLED = true`) | Xem `Follow4FollowAPI_Documentation.md`, `decisions/ADR-0008` |

## Key facts

- Package: `com.atpro` · minSdk 24 · target/compileSdk 34
- DB: Room 2.6.1 (SQLite, source of truth local-first); Supabase AT App được dùng cho tài khoản tùy chọn và cloud backup; cấu hình `FarmConfig` đọc/ghi dạng key-value qua `LocalRepository`
- State: Coroutines/Flow → StateFlow → Compose (không còn Riverpod)
- Accessibility: `TikTokAccessibilityService` thực hiện toàn bộ thao tác UI; `getRootNode()` là `suspend` (từ v1.3.1)
- Đa thiết bị: `LanWebSocketServer` qua LAN
- Lập lịch: `ScheduledFarmManager` (WorkManager + AlarmManager)
- Overlay: `OverlayFarmMonitor` (cửa sổ nổi trạng thái farm)
- Nhiệm vụ kiếm tiền: `com.atpro.tasks.TaskProvider` → `tasks/f4f/` (Follow4Follow); tài liệu API: `context/Follow4FollowAPI_Documentation.md` + `context/follow4follow/` (tham chiếu đầy đủ & `f4f.py`)
- Auth (phase 3): Supabase email/password Auth; access/refresh token được mã hóa AES-GCM qua Android Keystore; không bắt buộc đăng nhập để chạy công cụ.
- CI: GitHub Actions, Gradle 8.3 qua `setup-gradle@v6`, JDK 17; `gradle-wrapper.jar` cố ý không commit
