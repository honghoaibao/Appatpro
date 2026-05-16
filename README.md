# AT PRO Android — v1.4.7

TikTok automation tool — native Android APK. Không cần PC, không cần internet, lưu data hoàn toàn local.

## Stack

| Layer | Tech |
|-------|------|
| UI | Flutter 3.22 (Dart) |
| Automation | Kotlin — Android Accessibility Service |
| Storage | Room DB (SQLite local) |
| Config | Room DB (thay SharedPreferences) |
| Realtime UI | Kotlin Flow → EventChannel → Riverpod |
| Multi-device | Ktor WebSocket Server (LAN) |
| Obfuscation | R8 full mode + StringEncryptor |
| CI/CD | GitHub Actions |

## Cấu trúc

```
atpro_android/
├── .github/workflows/build.yml    ← CI/CD tự động build APK
├── scripts/
│   ├── generate_keystore.sh       ← Tạo keystore ký APK
│   └── build_release.sh           ← Build release local
├── android/app/src/main/kotlin/com/atpro/
│   ├── accessibility/
│   │   ├── TikTokAccessibilityService.kt  ← Core automation engine
│   │   └── NodeTraverser.kt               ← XML/Node tree parser
│   ├── automation/
│   │   ├── AutomationEngine.kt            ← Farm loop orchestrator
│   │   └── popup/PopupHandler.kt          ← 3-tier popup detection
│   ├── bridge/
│   │   └── FlutterBridge.kt               ← Flutter ↔ Kotlin bridge
│   ├── data/
│   │   ├── LocalRepository.kt             ← Single data access point
│   │   ├── FarmForegroundService.kt       ← Keep-alive service
│   │   ├── BootReceiver.kt                ← Auto-start on reboot
│   │   └── AccessibilitySettingsHelper.kt ← Permission utilities
│   ├── db/
│   │   ├── AtProDatabase.kt               ← Room database
│   │   ├── entity/Entities.kt             ← Account, Session, Log, Config
│   │   └── dao/Daos.kt                    ← CRUD + Flow queries
│   ├── network/
│   │   └── LanWebSocketServer.kt          ← Ktor WS server (port 8765)
│   ├── notification/
│   │   └── AtProNotificationManager.kt    ← Telegram + Discord
│   ├── scheduler/
│   │   └── ScheduledFarmManager.kt        ← AlarmManager scheduling
│   └── security/
│       └── StringEncryptor.kt             ← XOR string obfuscation
├── lib/
│   ├── main.dart                          ← App entry + routing
│   ├── services/
│   │   ├── at_pro_bridge.dart             ← Dart MethodChannel wrapper
│   │   └── providers.dart                 ← Riverpod state providers
│   └── screens/
│       ├── dashboard_screen.dart          ← Farm control + live stats
│       ├── accounts_screen.dart           ← Account management
│       ├── stats_screen.dart              ← Charts + session history
│       ├── log_screen.dart                ← Realtime log viewer
│       ├── config_screen.dart             ← All settings (sliders/toggles)
│       ├── export_screen.dart             ← CSV export + share
│       ├── schedule_screen.dart           ← Auto-schedule farm
│       ├── ws_monitor_screen.dart         ← LAN WS server info + test
│       ├── multi_device/
│       │   └── multi_device_screen.dart   ← Connect remote devices
│       └── setup/
│           └── permission_screen.dart     ← First-run permission wizard
└── supabase/ (removed — local-only)
```

## Setup nhanh

### 1. Clone và cài deps
```bash
git clone <repo>
cd atpro_android
flutter pub get
```

### 2. Tạo keystore (1 lần duy nhất)
```bash
chmod +x scripts/generate_keystore.sh
./scripts/generate_keystore.sh
```

### 3. Build APK
```bash
# Local build
./scripts/build_release.sh --version 1.4.7 --build 1

# Hoặc dùng Flutter trực tiếp
flutter build apk --release
```

### 4. Cài lên thiết bị
```bash
adb install dist/att_v1.4.7_fix1.apk
```

### 5. Bật Accessibility Service
Settings → Accessibility → Installed services → AT PRO Automation → ON

## CI/CD (GitHub Actions)

### Secrets cần thiết
| Secret | Mô tả |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 android/keystore/atpro-release.jks` |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_PASSWORD` | Mật khẩu key |
| `KEY_ALIAS` | Alias (mặc định: `atpro`) |

### Trigger build
```bash
# Push tag để tạo GitHub Release tự động
git tag v1.4.8
git push origin v1.4.8
```

## Bảo mật (TikTok detection avoidance)

1. **StringEncryptor** — XOR encrypt package names, tránh string scan
2. **R8 full mode** — rename tất cả class automation thành `a/b/c`
3. **repackageclasses 'x'** — gom tất cả vào package `x`
4. **Strip Log.d/v** — không leak info trong release build
5. **Accessibility Service name** — đặt tên trung tính ("AT PRO Automation")

## Mapping Python → Android

| Python (AT PRO cũ) | Kotlin/Flutter (mới) |
|--------------------|---------------------|
| `uiautomator2` | `TikTokAccessibilityService` |
| `XMLParser` | `NodeTraverser` |
| `core/automation.py` | `AutomationEngine.kt` |
| `ai/popup_handler.py` | `PopupHandler.kt` (+ Gemini fallback) |
| `core/config.py` (JSON file) | Room DB `configs` table |
| `core/stats.py` | Room DB `sessions` + `LocalRepository` |
| `ui/notifications.py` | `AtProNotificationManager.kt` |
| `smart_logger` | Flow → EventChannel → `LogScreen` |
| Terminal menu (rich) | Flutter screens |
| ADB connection | Accessibility Service (no ADB needed) |
