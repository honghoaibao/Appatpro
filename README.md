# AT PRO — TikTok Automation Android

Công cụ tự động hóa TikTok chạy trực tiếp trên thiết bị Android. Không cần PC, không cần kết nối internet liên tục, dữ liệu lưu hoàn toàn local.

## Stack

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose (Kotlin) |
| Automation | Android Accessibility Service |
| Storage | Room DB (SQLite local) |
| Realtime UI | Kotlin Flow → StateFlow → Compose |
| Multi-device | NanoHTTPD WebSocket Server (LAN) |
| Scheduling | WorkManager + AlarmManager |
| CI/CD | GitHub Actions |

## Cấu trúc dự án

```
atpro_v1_0_9/
├── .github/workflows/build.yml        ← CI/CD tự động build APK
├── scripts/
│   ├── generate_keystore.sh           ← Tạo keystore ký APK
│   └── build_release.sh               ← Build release local
└── android/
    ├── gradlew
    └── app/src/main/kotlin/com/atpro/
        ├── accessibility/
        │   ├── TikTokAccessibilityService.kt
        │   └── NodeTraverser.kt
        ├── automation/
        │   ├── AutomationEngine.kt
        │   └── popup/PopupHandler.kt
        ├── data/
        │   ├── LocalRepository.kt
        │   ├── FarmForegroundService.kt
        │   ├── BootReceiver.kt
        │   └── AccessibilitySettingsHelper.kt
        ├── db/
        │   ├── AtProDatabase.kt
        │   ├── entity/
        │   └── dao/
        ├── network/
        │   └── LanWebSocketServer.kt
        ├── notification/
        │   └── AtProNotificationManager.kt
        ├── scheduler/
        │   └── ScheduledFarmManager.kt
        ├── security/
        │   └── StringEncryptor.kt
        └── ui/
            ├── dashboard/
            ├── accounts/
            ├── logs/
            ├── stats/
            ├── config/
            └── schedule/
```

## Setup

### 1. Clone repo

```bash
git clone <repo>
cd atpro_v1_0_9
```

### 2. Generate Gradle Wrapper (1 lần duy nhất sau clone)

```bash
cd android
gradle wrapper --gradle-version 8.3 --distribution-type all
chmod +x gradlew
cd ..
```

> Commit `gradlew`, `gradlew.bat`, và `gradle/wrapper/gradle-wrapper.jar` vào repo sau bước này.

### 3. Tạo keystore (1 lần duy nhất)

```bash
chmod +x scripts/generate_keystore.sh
./scripts/generate_keystore.sh
```

### 4. Build APK

```bash
cd android
./gradlew assembleRelease
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`

### 5. Cài lên thiết bị

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

### 6. Bật Accessibility Service

**Settings → Accessibility → Installed services → AT PRO Automation → ON**

## CI/CD (GitHub Actions)

Workflow: `.github/workflows/build.yml`

### Secrets (optional — bỏ qua để build debug)

| Secret | Mô tả |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 android/keystore/atpro-release.jks` |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_PASSWORD` | Mật khẩu key |
| `KEY_ALIAS` | Alias (mặc định: `atpro`) |

### Trigger build

```bash
# Manual trigger
gh workflow run "AT PRO — Build APK" -f version=1.0.9 -f build=10

# Push tag → tạo GitHub Release tự động
git tag v1.0.9
git push origin v1.0.9
```

## Tests

Unit tests chạy trên JVM, không cần emulator:

```bash
cd android
./gradlew test
```

| File | Tests |
|------|-------|
| `AutomationEngineTest` | 7 |
| `AutomationEngineIntegrationTest` | 7 |
| `NodeTraverserTest` | 24 |

## Bảo mật

- **StringEncryptor** — AES encrypt package names, tránh static string scan
- **Accessibility Service** — tên trung tính ("AT PRO Automation")

## Changelog

### v1.1.8 (2026-06-01)

**Bug fix — Daily Screen Time Limit (hình chụp)**

| # | File | Thay đổi |
|---|------|----------|
| 1 | `NodeTraverser.kt` | Thêm `PopupType.DAILY_LIMIT`; tách nhánh 4-EditText: kiểm tra `DAILY_LIMIT_SIGNALS` trước khi kết luận `VERIFY_1234` |
| 2 | `NodeTraverser.kt` | Thêm `detectDailyLimitScreen()`, `findReturnToTikTokButton()` |
| 3 | `PopupHandler.kt` | Thêm `handleDailyLimit()`: gõ passcode "1234" → click "Quay lại TikTok" |
| 4 | `PopupHandler.kt` | Thêm Tier 2 keyword fallback cho daily limit |
| 5 | `AutomationEngine.kt` | Main loop step [2c]: phát hiện + xử lý daily limit trước `isLostWithRetry()` |
| 6 | `AutomationEngine.kt` | `recoverToFeed()` Tier 0a: daily limit trước Tier 0b (wellbeing) |
| 7 | `AutomationEngine.kt` | Cache `getRootNode()` 1 lần/iteration → giảm IPC call từ ~6 xuống ~2 |

**Optimizations**

| # | File | Thay đổi |
|---|------|----------|
| 8 | `FarmForegroundService.kt` | Heartbeat 10s → 15s — giảm wake count, tiết kiệm pin |
| 9 | `OverlayFarmMonitor.kt` | `update()` dirty-check: skip `handler.post` khi không có gì thay đổi |
| 10 | `OverlayFarmMonitor.kt` | `addLog()` dedup: bỏ qua message trùng dòng cuối; lọc thêm `WDG:`, `RECOVER:` noise |

**Root cause của bug:** Màn hình Daily Limit có 4 `EditText` → `detectPopup()` nhận diện là `VERIFY_1234` → `handle1234()` gõ "1234" nhưng không tìm được nút "Confirm" (không tồn tại) → `pressBack()` → đóng TikTok.

**Fix:** Phân biệt hai màn hình bằng context text trước khi kiểm tra số EditText. Daily Limit luôn chứa "quay lại tiktok"/"nhập mật mã"/... — các từ này không xuất hiện trên VERIFY_1234.
