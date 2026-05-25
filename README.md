# AT PRO Android — v1.0.2

TikTok automation tool — native Android APK. Không cần PC, không cần internet, lưu data hoàn toàn local.

> **Phiên bản hiện tại**: v1.0.2 (thử nghiệm #2) — native Compose, không có Flutter.

## Stack

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose (Kotlin) |
| Automation | Kotlin — Android Accessibility Service |
| Storage | Room DB (SQLite local) |
| Config | Room DB |
| Realtime UI | Kotlin Flow → StateFlow → Compose |
| Multi-device | NanoHTTPD WebSocket Server (LAN) |
| Scheduling | WorkManager + AlarmManager |
| CI/CD | GitHub Actions |

## Cấu trúc

```
atpro_android/
├── .github/workflows/build.yml    ← CI/CD tự động build APK
├── scripts/
│   ├── generate_keystore.sh       ← Tạo keystore ký APK
│   └── build_release.sh           ← Build release local
└── android/
    ├── gradlew                    ← Gradle wrapper (generate: xem bên dưới)
    ├── app/src/main/kotlin/com/atpro/
    │   ├── accessibility/
    │   │   ├── TikTokAccessibilityService.kt  ← Core automation engine
    │   │   └── NodeTraverser.kt               ← XML/Node tree parser
    │   ├── automation/
    │   │   ├── AutomationEngine.kt            ← Farm loop orchestrator
    │   │   └── popup/PopupHandler.kt          ← 3-tier popup detection
    │   ├── data/
    │   │   ├── LocalRepository.kt             ← Single data access point
    │   │   ├── FarmForegroundService.kt       ← Keep-alive service
    │   │   ├── BootReceiver.kt                ← Auto-start on reboot
    │   │   └── AccessibilitySettingsHelper.kt ← Permission utilities
    │   ├── db/
    │   │   ├── AtProDatabase.kt               ← Room database
    │   │   ├── entity/                        ← AccountEntity, SessionEntity, LogEntity, ConfigEntity
    │   │   └── dao/                           ← AccountDao, SessionDao, LogDao, ConfigDao
    │   ├── network/
    │   │   └── LanWebSocketServer.kt          ← NanoHTTPD WS server (port 8765)
    │   ├── notification/
    │   │   └── AtProNotificationManager.kt    ← Telegram + Discord
    │   ├── scheduler/
    │   │   └── ScheduledFarmManager.kt        ← WorkManager scheduling
    │   ├── security/
    │   │   └── StringEncryptor.kt             ← AES string encryption
    │   └── ui/                                ← 6 Compose screens
    │       ├── dashboard/   DashboardActivity + Screen + ViewModel
    │       ├── accounts/    AccountsActivity  + Screen + ViewModel
    │       ├── logs/        LogsActivity      + Screen + ViewModel
    │       ├── stats/       StatsActivity     + Screen + ViewModel
    │       ├── config/      ConfigActivity    + Screen + ViewModel
    │       └── schedule/    ScheduleActivity  + Screen + ViewModel
    └── app/src/test/           ← 38 unit tests (JUnit4 + MockK)
```

## Setup

### 1. Clone repo

```bash
git clone <repo>
cd atpro_android
```

### 2. Generate Gradle Wrapper (1 lần duy nhất sau clone)

```bash
cd android
gradle wrapper --gradle-version 8.3 --distribution-type all
chmod +x gradlew
cd ..
```

> Sau bước này commit `gradlew`, `gradlew.bat`, và `gradle/wrapper/gradle-wrapper.jar` vào repo
> để CI không cần generate lại.

### 3. Tạo keystore (1 lần duy nhất)

```bash
chmod +x scripts/generate_keystore.sh
./scripts/generate_keystore.sh
```

### 4. Build APK

```bash
# Local build (release)
cd android
./gradlew assembleRelease

# Hoặc dùng script
cd ..
chmod +x scripts/build_release.sh
./scripts/build_release.sh --version 1.0.2 --build 2
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`

### 5. Cài lên thiết bị

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

### 6. Bật Accessibility Service

Settings → Accessibility → Installed services → AT PRO Automation → ON

## CI/CD (GitHub Actions)

Workflow file: `.github/workflows/build.yml`

### Secrets cần thiết (optional — bỏ qua để build debug)

| Secret | Mô tả |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 android/keystore/atpro-release.jks` |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_PASSWORD` | Mật khẩu key |
| `KEY_ALIAS` | Alias (mặc định: `atpro`) |

### Trigger build

```bash
# Manual trigger (GitHub UI hoặc gh CLI)
gh workflow run "AT PRO — Build APK" -f version=1.0.2 -f build=2

# Push tag để tạo GitHub Release tự động
git tag v1.0.2
git push origin v1.0.2
```

### APK artifact

- File name: `atpro.apk`
- Artifact name: `atpro-v{version}-b{build}`
- Retention: 30 ngày

## Changelog

### v1.0.2 — BUG-SETTINGS-001 fix
- **Fix**: `TikTokDeepLinks.openSettings()` không mở được Settings TikTok do gọi trực tiếp
  `SettingContainerActivity` (có `android:exported="false"`) → `SecurityException` → engine báo
  _"❌ Không tìm thấy nút chuyển đổi tài khoản"_.
- **Giải pháp**: ưu tiên deep link `tiktok://settings` → fallback `snssdk1233://settings` →
  cuối cùng mới thử component name.
- **File đổi**: `TikTokDeepLinks.kt`, `build.gradle` (versionCode 2, versionName v1.0.2),
  `build.yml` (default 1.0.2), `README.md`.

### v1.0.1 — thử nghiệm #1
- Native Compose, bỏ Flutter hoàn toàn.
- Farm loop đa tài khoản, Room DB local.

## Bảo mật (TikTok detection avoidance)

1. **StringEncryptor** — AES encrypt package names, tránh string scan
2. **Accessibility Service name** — đặt tên trung tính ("AT PRO Automation")

## Tests

38 unit tests, JUnit4 + MockK, chạy trên JVM (không cần emulator):

```bash
cd android
./gradlew test
```

| File | Tests |
|------|-------|
| `AutomationEngineTest` | 7 |
| `AutomationEngineIntegrationTest` | 7 |
| `NodeTraverserTest` | 22 |

## Mapping Python → Android

| Python (AT PRO Python) | Kotlin/Android |
|------------------------|----------------|
| `uiautomator2` | `TikTokAccessibilityService` |
| `XMLParser` | `NodeTraverser` |
| `core/automation.py` | `AutomationEngine.kt` |
| `ai/popup_handler.py` | `PopupHandler.kt` |
| `core/config.py` (JSON file) | Room DB `configs` table |
| `core/stats.py` | Room DB `sessions` + `LocalRepository` |
| `ui/notifications.py` | `AtProNotificationManager.kt` |
| `smart_logger` | Flow → StateFlow → `LogsScreen` |
| Terminal menu (rich) | Jetpack Compose screens |
| ADB connection | Accessibility Service (no ADB needed) |
