# AT PRO — Tự động hóa mạng xã hội trên Android

Công cụ tự động hóa chạy trực tiếp trên thiết bị Android (không cần PC), dữ liệu lưu hoàn toàn local. Dịch vụ chính là **TikTok** (nuôi acc; làm nhiệm vụ kiếm tiền qua `TaskProvider` — đang chờ tích hợp **Follow4Follow**); có thêm luồng nuôi acc cho Facebook, Instagram, X/Twitter, Threads, Snapchat.

- **Phiên bản hiện tại:** v1.5.0 (`versionCode 37`)
- **Package:** `com.atpro` · **minSdk 24 / targetSdk & compileSdk 34**
- **Ngôn ngữ giao diện, log, comment:** tiếng Việt

## Stack

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose (Material 3) + hệ thiết kế riêng `ui/theme/AtTheme.kt` |
| Tự động hóa | Android Accessibility Service (`TikTokAccessibilityService`) |
| Lưu trữ | Room 2.6.1 (SQLite local), DataStore Preferences |
| Realtime UI | Kotlin Coroutines/Flow → StateFlow → Compose |
| Đa thiết bị | NanoHTTPD WebSocket Server (LAN) |
| Lập lịch | WorkManager + AlarmManager |
| Nhiệm vụ kiếm tiền | `TaskProvider` (lớp trừu tượng; Golike/WebView đã gỡ ở v1.4.0) |
| CI/CD | GitHub Actions + `gradle/actions/setup-gradle@v6` (Gradle 8.3, JDK 17) |

> Flutter đã được gỡ hoàn toàn (ADR-0004). Không có Hilt trong `build.gradle` — khởi tạo phụ thuộc qua singleton/`getInstance()`.

## Cấu trúc dự án

```
.
├── .github/workflows/build.yml          ← CI/CD build APK + tạo Release khi push tag
├── .claude/                             ← context, ADR, revision, tasks, skills (xem bên dưới)
├── scripts/
│   ├── generate_keystore.sh             ← Tạo keystore ký APK
│   ├── build_release.sh                 ← Build release local
│   └── commit_gradle_wrapper.sh         ← Sinh gradle-wrapper.jar để build local bằng ./gradlew
├── setup_signing.sh                     ← Tạo keystore + in sẵn 4 GitHub Secrets
├── assets/images/                       ← Icon
└── android/app/src/main/kotlin/com/atpro/
    ├── accessibility/   TikTokAccessibilityService, NodeTraverser
    ├── automation/      AutomationEngine, FarmBehavior (RateGate), IFarmHost, popup/ (PopupHandler, PopupLoopGuard)
    ├── data/            FarmConfig, LocalRepository, IFarmRepository, FarmForegroundService,
    │                    OverlayFarmMonitor, TikTokDeepLinks, BootReceiver, AccessibilitySettingsHelper
    ├── db/              AtProDatabase, dao/ (Account, Session, Log, Config), entity/
    ├── tasks/           TaskProvider (+ TaskJob/TaskAccount/TaskResult), TaskProviders
    ├── network/         LanWebSocketServer, UpdateChecker, DiscordNotifier
    ├── notification/    AtProNotificationManager
    ├── scheduler/       ScheduledFarmManager
    ├── security/        StringEncryptor (+ AppConstants, gồm cờ TASKS_ENABLED)
    └── ui/              theme/ (AtTheme: màu, kiểu chữ, thành phần, hiệu ứng), MainScreen, SplashActivity, dashboard/, accounts/, logs/, stats/,
                         config/, schedule/, services/
```

## Tính năng chính

- **Nuôi acc TikTok:** xem video, like, follow, comment, inbox, shop; chuyển acc bằng deep link; tự xử lý popup (2 tầng + chống lặp vòng); watchdog + tự hồi phục khi kẹt. Từ v1.4.0 tỉ lệ like/follow/xem bình luận được điều tiết bởi `RateGate` (bám sát cấu hình, ít dao động) và gắn với thời gian xem video.
- **Nhiệm vụ TikTok kiếm tiền (task mode):** engine nói chuyện với nhà cung cấp qua `TaskProvider` (lấy acc → lấy job → làm Like/Follow bằng Accessibility → báo cáo → nuôi acc). **Hiện chưa có nhà cung cấp nào:** Golike đã gỡ hoàn toàn ở v1.4.0, Follow4Follow chưa tích hợp (cần tài liệu API) nên `AppConstants.TASKS_ENABLED = false` và giao diện hiển thị "Sắp có".
- **Dịch vụ khác:** Facebook (kể cả xem Reels), Instagram, X, Threads, Snapchat — mức demo/nuôi cơ bản.
- **Lịch tự động:** tab Lịch (`ScheduledFarmManager`), khôi phục sau khi khởi động lại máy (`BootReceiver`).
- **Overlay:** cửa sổ nổi hiển thị trạng thái farm, thu nhỏ/kéo được (`OverlayFarmMonitor`).
- **Thông báo:** Android notification + Discord webhook; kiểm tra bản mới từ GitHub Releases (`UpdateChecker`).

## Setup

### 1. Clone repo

```bash
git clone https://github.com/honghoaibao/Appatpro.git
cd Appatpro
```

### 2. Tạo keystore (1 lần duy nhất, tùy chọn — bỏ qua để build debug)

```bash
chmod +x setup_signing.sh
./setup_signing.sh        # tạo android/keystore/atpro-release.jks + in 4 secrets để paste lên GitHub
```

Không commit `.jks` / `key.properties` (đã có trong `.gitignore`). Backup keystore ra nơi an toàn.

### 3. Build APK

**Khuyến nghị — build bằng CI** (xem mục CI/CD). CI cài Gradle 8.3 trực tiếp bằng `setup-gradle` và gọi `gradle assembleRelease`, **không cần** `gradle-wrapper.jar`.

**Build local:** repo cố ý không commit `gradle-wrapper.jar`. Chọn 1 trong 2 cách:

```bash
# Cách A: dùng Gradle 8.3 đã cài sẵn
cd android && gradle assembleRelease

# Cách B: sinh wrapper rồi dùng ./gradlew (1 lần)
bash scripts/commit_gradle_wrapper.sh
cd android && ./gradlew assembleRelease
```

APK: `android/app/build/outputs/apk/release/app-release.apk`

### 4. Cài lên thiết bị

```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```

### 5. Bật Accessibility Service

**Settings → Accessibility → Installed services → AT PRO Automation → ON**

## CI/CD (GitHub Actions)

Workflow: `.github/workflows/build.yml` (tên: *AT PRO — Build APK*).

- **Trigger:** push lên `main`, `dev`, `release/*`; push tag `v*`; pull request vào `main`/`dev`; chạy tay (`workflow_dispatch`).
- **Version:** tự đọc `versionName` từ `android/app/build.gradle` — không hardcode trong workflow. Hai input `version`/`build` của `workflow_dispatch` chỉ là giá trị gợi ý mặc định.
- **Artifact:** `dist/atpro.apk`; push tag `v*` tạo GitHub Release tự động.

### Secrets (tùy chọn — thiếu thì build debug)

| Secret | Mô tả |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 android/keystore/atpro-release.jks` |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_PASSWORD` | Mật khẩu key |
| `KEY_ALIAS` | Alias (mặc định: `atpro`) |

### Kích hoạt build

```bash
# Chạy tay
gh workflow run "AT PRO — Build APK" -f version=1.4.0 -f build=36

# Push tag → build + tạo GitHub Release
git tag v1.4.0
git push origin v1.4.0
```

## Tests

Unit test chạy trên JVM (JUnit4 + MockK), không cần emulator:

```bash
cd android
gradle test        # hoặc ./gradlew test nếu đã có wrapper
```

| File | Số test |
|------|---------|
| `NodeTraverserTest` | 39 |
| `AutomationEngineTest` | 8 |
| `AutomationEngineIntegrationTest` | 6 |
| `TaskNurtureTest` | 8 |
| `FarmBehaviorTest` | 9 |
| `PopupLoopGuardTest` | 6 |
| **Tổng** | **76** |

Chưa có test tự động cho phần phụ thuộc cây node thật (`taskOneAccount()`, `doLikeTask()`, `doSimpleFarm()`…) — cần QA trên thiết bị thật.

## Bảo mật

- **StringEncryptor** — AES encrypt package name, tránh quét chuỗi tĩnh; nơi đặt `AppConstants`.
- **Accessibility Service** — tên trung tính ("AT PRO Automation").
- Ràng buộc dự án (`.claude/context/constraints.md`): các trường nhạy cảm (credential, token) phải đi qua `StringEncryptor`; không commit keystore. Từ v1.4.0 app không còn lưu token Golike; khi tích hợp Follow4Follow, khóa API phải đi qua `StringEncryptor`.

## Tài liệu trong `.claude/`

| Thư mục | Nội dung |
|---------|----------|
| `CLAUDE.md` | Quy tắc làm việc với repo |
| `context/` | `project_overview`, `architecture`, `current_focus`, `constraints`, `glossary`, `GolikeAPI_Documentation` (lưu trữ — Golike đã gỡ) |
| `decisions/` | ADR-0001 … ADR-0007 |
| `principles/` | Quy tắc Android, kiến trúc, coding, git, hiệu năng, bảo mật |
| `revision/` | Ghi chú theo phiên bản: `v1.2.0.md` … `v1.4.0.md` |
| `revisions/` | Nhật ký sửa lỗi/phiên làm việc theo ngày (v1.0.x – v1.1.x) |
| `tasks/` | `backlog`, `completed_tasks`, `technical_debt`, checklist QA |
| `skills/` | Skill dùng trong quá trình phát triển |

## Changelog (tóm tắt)

Chi tiết từng bản nằm trong `.claude/revision/vX.Y.Z.md`.

| Bản | Điểm chính |
|-----|-----------|
| **v1.5.0** (37) | Thiết kế lại giao diện: hệ thiết kế `AtTheme` (một bảng màu, bề mặt phẳng, một màu nhấn), thanh điều hướng/tab/nút/hộp thoại mới, hiệu ứng (nhấn co nhẹ, vào màn lần lượt, chỉ báo trượt, số đếm lên, chấm trạng thái lan toả), màn Dịch vụ gọn còn một kiểu hàng, popup overlay và splash đồng bộ màu |
| v1.4.0 (36) | Gỡ Golike + WebView; thêm lớp `TaskProvider` (chờ Follow4Follow); thuật toán nuôi acc mới (`RateGate`, `WatchPlan`); popup chống lặp vòng + thêm hộp thoại quyền Android 13; thẻ "Nhiệm vụ kiếm tiền" thay thẻ Golike |
| v1.3.3 (35) | Bật lại Golike TikTok (`GOLIKE_ENABLED = true`, đã gỡ ở v1.4.0); nuôi acc ngắn 5–20s sau mỗi job + nuôi lâu định kỳ (`taskPostJobNurtureSecs`, +8 test); delay "như người thật" trước khi lấy job; `doLikeTask()` thêm double-tap dự phòng; CI chuyển sang `setup-gradle@v6` |
| v1.3.2 (34) | Sửa `findProfileTab()` bấm nhầm Trang chủ/Đề xuất (giới hạn dải đáy màn hình, +4 test); chuẩn bị popup cho Golike |
| v1.3.1 (33) | `getRootNode()` thành `suspend` (huỷ được khi app đích treo), thêm `safeScreenRead()`; popup `ACCOUNT_UPDATE_PROMPT`; Facebook tự thoát bottom sheet |
| v1.3.0 (32) | Bỏ khóa cài đặt nuôi acc; cải thiện tìm nút Like/Comment cho FB/X/IG/Threads/Snapchat; log chi tiết hơn; xem Reels Facebook |
| v1.2.x | Golike (đăng nhập WebView, task mode, API), tab Lịch tự động, popup farm có log màu, redesign Dịch vụ, sửa đếm ngược/LIKE |
| v1.1.8 | Daily Screen Time Limit — xem [`revision-2026-06-01-v1_1_8-daily-limit-screen-time.md`](.claude/revisions/revision-2026-06-01-v1_1_8-daily-limit-screen-time.md) |
