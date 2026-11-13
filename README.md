# AT PRO — Tự động hóa mạng xã hội trên Android

Công cụ tự động hóa chạy trực tiếp trên thiết bị Android (không cần PC), dữ liệu lưu hoàn toàn local. Dịch vụ chính là **TikTok** (nuôi acc; làm nhiệm vụ kiếm tiền Like/Follow qua **Follow4Follow** — `TaskProvider`); có thêm luồng nuôi acc cho Facebook, Instagram, X/Twitter, Threads, Snapchat.

- **Phiên bản hiện tại:** v1.12.5 (`versionCode 45`)
- **Package:** `com.atpro` · **minSdk 24 / targetSdk & compileSdk 34**
- **Ngôn ngữ giao diện, log, comment:** tiếng Việt

## Stack

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose (Material 3) + hệ thiết kế riêng **Sky** `ui/theme/AtTheme.kt` (xanh da trời · trắng · xanh dương · đen; chế độ **Sáng/Tối** đổi ngay trong Cài đặt → Giao diện) |
| Tự động hóa | Android Accessibility Service (`TikTokAccessibilityService`) |
| Lưu trữ | Room 2.6.1 (SQLite local), DataStore Preferences |
| Realtime UI | Kotlin Coroutines/Flow → StateFlow → Compose |
| Đa thiết bị | NanoHTTPD WebSocket Server (LAN) |
| Lập lịch | WorkManager + AlarmManager |
| Nhiệm vụ kiếm tiền | `TaskProvider` → Follow4Follow (`tasks/f4f/`, `HttpURLConnection` + HMAC-SHA256 ký request; Golike/WebView đã gỡ ở v1.4.0) |
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
    ├── accessibility/   TikTokAccessibilityService, NodeTraverser, ContentScanner
    ├── automation/      AutomationEngine, FarmBehavior (RateGate), IFarmHost, popup/ (PopupHandler, PopupLoopGuard)
    ├── data/            FarmConfig, LocalRepository, IFarmRepository, FarmForegroundService,
    │                    OverlayFarmMonitor, TikTokDeepLinks, BootReceiver, AccessibilitySettingsHelper
    ├── db/              AtProDatabase, dao/ (Account, Session, Log, Config), entity/
    ├── tasks/           TaskProvider (+ TaskJob/TaskAccount/TaskResult), TaskProviders
    │   └── f4f/         Follow4FollowProvider, F4fApiClient, F4fSigner, F4fHttp, F4fAuth (đăng nhập + phiên), F4fHints/F4fJson
    ├── network/         LanWebSocketServer, UpdateChecker, DiscordNotifier
    ├── notification/    AtProNotificationManager
    ├── scheduler/       ScheduledFarmManager
    ├── security/        StringEncryptor (+ AppConstants, gồm cờ TASKS_ENABLED)
    └── ui/              theme/ (AtTheme: bảng màu Sáng/Tối, kiểu chữ, thành phần, hiệu ứng), f4f/ (Ví Follow4Follow: tổng quan, xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo), settings/ (Cài đặt một cấp: giao diện, công cụ, cấu hình), MainScreen (5 tab + màn hình phủ), dashboard/, accounts/, logs/, stats/,
                         config/, schedule/, services/, tasks/ (F4fAccountCard + F4fLoginDialog)
```

## Tính năng chính

- **Nuôi acc TikTok:** xem video, like, follow, comment, inbox, shop; chuyển acc bằng deep link; tự xử lý popup (2 tầng + quét an toàn theo whitelist ở mọi mốc + chống lặp vòng; v1.12.5); watchdog + tự hồi phục khi kẹt. Từ v1.4.0 tỉ lệ like/follow/xem bình luận được điều tiết bởi `RateGate` (bám sát cấu hình, ít dao động) và gắn với thời gian xem video.
- **Nhiệm vụ TikTok kiếm tiền (task mode) — Follow4Follow:** đăng nhập Follow4Follow trong app (tab Dịch vụ → *Nhiệm vụ kiếm tiền*, hoặc Cài đặt → Nhiệm vụ), sau đó engine lấy acc đã liên kết → lấy job Like/Follow → mở link bằng deep link → làm bằng Accessibility → báo hoàn thành (đợi ≥5s, xử lý giới hạn tốc độ 429) hoặc bỏ qua → nuôi acc ngắn giữa các job. Tự chuyển acc active phía server (`quick-switch`), dừng acc khi hết lượt trong ngày. App chỉ lưu token (không lưu mật khẩu). Tài liệu: [`.claude/context/Follow4FollowAPI_Documentation.md`](.claude/context/Follow4FollowAPI_Documentation.md). Cờ `AppConstants.TASKS_ENABLED` để ẩn/hiện toàn bộ tính năng.
- **Ví Follow4Follow (v1.7.0):** màn hình riêng mở từ tab Dịch vụ — số dư/cấp/tỷ giá/job còn lại hôm nay, **bảng xếp hạng** (podium top 3 + hạng của bạn), **rút tiền** qua 5 kênh (ngân hàng, ZaloPay, PayPal, Crypto, thẻ cào; luôn có hộp thoại xác nhận, chống gửi trùng), lịch sử job (Follow/Like), lịch sử rút tiền, giới thiệu (mã/link/hoa hồng + nhận hoa hồng), thông báo.
- **Dịch vụ khác:** Facebook (kể cả xem Reels), Instagram, X, Threads, Snapchat — mức demo/nuôi cơ bản.
- **Lịch tự động:** màn hình phủ mở từ Tổng quan hoặc Cài đặt (`ScheduledFarmManager`), khôi phục sau khi khởi động lại máy (`BootReceiver`).
- **Overlay:** cửa sổ nổi hiển thị trạng thái phiên chạy với **3 trạng thái** — Popup (tài khoản, tiến trình, Lượt thích · Đã thêm · Cảnh báo, trạng thái đang làm, Nhật ký hoạt động, Tạm dừng/Dừng) · Mini Popup · Floating Chip; kéo được (`OverlayFarmMonitor`, v1.12.5).
- **Dừng an toàn:** mọi đường dừng đều đưa máy về màn hình chính điện thoại trước khi tool tắt (v1.12.5).
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
gh workflow run "AT PRO — Build APK" -f version=1.12.5 -f build=45

# Push tag → build + tạo GitHub Release
git tag v1.12.5
git push origin v1.12.0
```

## Tests

Unit test chạy trên JVM với JUnit4 + MockK; các test chạm Android framework dùng Robolectric để mô phỏng Android, không cần emulator:

```bash
cd android
gradle test        # hoặc ./gradlew test nếu đã có wrapper
```

| File | Số test |
|------|---------|
| `NodeTraverserTest` | 39 |
| `AutomationEngineTest` | 12 |
| `AutomationEngineIntegrationTest` | 6 |
| `TaskNurtureTest` | 8 |
| `FarmBehaviorTest` | 9 |
| `PopupLoopGuardTest` | 6 |
| `ContentScannerTest` | 23 |
| `F4fSignerTest` | 5 |
| `F4fApiClientTest` | 11 |
| `Follow4FollowProviderTest` | 24 |
| `F4fAuthManagerTest` | 12 |
| `F4fTokenCodecTest` | 4 |
| `F4fServiceTest` | 26 |
| **Tổng** | **158** |

Phần Follow4Follow (ký request, đăng nhập, luồng job) test hoàn toàn offline bằng transport giả (`FakeF4fTransport`). Chưa có test tự động cho phần phụ thuộc cây node thật (`taskOneAccount()`, `doLikeTask()`, `doSimpleFarm()`…) — cần QA trên thiết bị thật.

## Bảo mật

- **StringEncryptor** — AES encrypt package name, tránh quét chuỗi tĩnh; nơi đặt `AppConstants`.
- **Accessibility Service** — tên trung tính ("AT PRO Automation").
- Ràng buộc dự án (`.claude/context/constraints.md`): các trường nhạy cảm (credential, token) phải đi qua `StringEncryptor`; không commit keystore. Từ v1.4.0 app không còn lưu token Golike. Từ v1.6.0 token Follow4Follow được che bằng `StringEncryptor` (che chuỗi, không phải mã hoá mạnh) và app **không lưu mật khẩu**; không log token/`x-sign`.

## Tài liệu trong `.claude/`

| Thư mục | Nội dung |
|---------|----------|
| `CLAUDE.md` | Quy tắc làm việc với repo |
| `context/` | `project_overview`, `architecture`, `current_focus`, `constraints`, `glossary`, `Follow4FollowAPI_Documentation` + `follow4follow/` (tham chiếu API đầy đủ + `f4f.py` đã field-test) |
| `decisions/` | ADR-0001 … ADR-0026 |
| `principles/` | Quy tắc Android, kiến trúc, coding, git, hiệu năng, bảo mật |
| `revision/` | Ghi chú theo phiên bản (giữ 10 bản gần nhất): `v1.3.3.md` … `v1.12.5.md` |
| `tasks/` | `backlog`, `completed_tasks`, `technical_debt` |
| `skills/` | Skill dùng trong quá trình phát triển |

## Changelog (tóm tắt)

Chi tiết từng bản nằm trong `.claude/revision/vX.Y.Z.md`.

| Bản | Điểm chính |
|-----|-----------|
| **v1.12.5** (45) | **Quét popup / quảng cáo / live ở mọi bước** (`ContentScanner` theo whitelist nút đóng — không bao giờ bấm OK; quét tại các mốc: vào Cài đặt, mở danh sách tài khoản, feed vừa tải, trang job; đóng popup đè lên feed); **sửa vuốt thừa** sau khi thấy nút Chuyển đổi tài khoản (vuốt ngắn + chậm + dò liên tục, bấm ngay bằng ACTION_CLICK); **mọi đường dừng đều về màn hình chính** trước khi tool tắt; **popup nổi mới** (Popup · Mini Popup · Floating Chip) |
| v1.12.0 (44) | **Nhận nhiệm vụ theo `f4f.py` v2.8** (danh sách acc `quick-switch`, luôn kèm `uid`, thang đổi acc S1–S5, 429 chờ-thử-lại, job hết hạn, trace); **chọn tài khoản nhận nhiệm vụ riêng** (Tự động / Chọn tay, `TaskAccountSelector`); **thông báo nâng cấp** (4 nhóm, giờ yên tĩnh, tổng kết nhiệm vụ + xu, tiến độ trên thông báo đang chạy, mở đúng tab, nút gửi thử); 401 giữa phiên dừng cả lượt (ADR-0014) |
| v1.11.0 (43) | **Sửa bấm nhầm "Đăng xuất" khi chuyển acc** ("Đăng xuất" chứa chữ x bị coi là nút đóng; nhận diện popup + chọn dòng theo acc đang dùng; `openSwitchList()` có chặn + huỷ hộp đăng xuất); **thanh kéo mới** `AtSlider`; nuôi lâu tối đa 50 job, thời lượng ≥ 30s; **tab Nuôi acc riêng** cho kiếm tiền; **ngưỡng số tim tối thiểu**; logic hashtag mới (không phân biệt dấu, ranh giới từ); **nhật ký dễ đọc** (`LogPolish`, chip "Chi tiết"); hiệu ứng popup/thẻ/nhật ký (ADR-0013) |
| v1.10.0 (42) | **Back về feed** sau báo cáo job (phân loại trang theo thanh điều hướng); **bảng xếp hạng F4F hiện xu** (`parseCoinText`); F4F làm lại (bỏ Giới thiệu, ô Xu chờ · Làm hôm nay · Tổng xu); **Thống kê kiếm tiền + biểu đồ** (`EarningsStore`, Canvas); Quyền/Thông báo tách riêng, thẻ quyền bố cục dọc + chặn cỡ chữ hệ thống; Cài đặt/Tài khoản/Lịch tinh gọn; **popup nhỏ + thanh đếm ngược**; tối ưu pin (memo cây node, gom ghi log); bỏ chữ thừa (ADR-0012) |
| v1.9.0 (41) | **Sửa lỗi không tìm thấy nút Follow** trên mọi loại hồ sơ (viết lại bộ dò: khớp nhãn chính xác trong hàng nút, bấm cha clickable, chờ hồ sơ tải, chạm cử chỉ dự phòng, bật `flagIncludeNotImportantViews`); **tối đa 150 job/acc**, nuôi trước job **tối thiểu 10s**; **popup nổi mới** với nhật ký thời gian thực + dữ liệu nhiệm vụ thật; bỏ 6 màn hình/khung dư thừa, `ConfigScreen`/`ScheduleScreen` đồng bộ `AtTopBar` (ADR-0011) |
| v1.8.0 (40) | **Sửa lỗi không bắt đầu được nhiệm vụ** (chế độ nhiệm vụ đọc acc hiện tại SAU khi mở popup chuyển tài khoản → "@?"); **chuẩn hoá acc đầu chỉ mở Hồ sơ** (không bấm vào acc đang đăng nhập, không switch tạm); **logo mới** (launcher + Splash + app); **bố cục lại**: 5 tab Tổng quan · Dịch vụ · Tài khoản · Nhật ký · Cài đặt, Thống kê/Lịch thành màn hình phủ, Cài đặt một cấp, `AtTopBar` chung; sửa lỗi build `animateFloat` + dọn cảnh báo Kotlin; API v2.7 (`coin_received`) |
| v1.7.0 (39) | **Giao diện Sky** (xanh da trời · trắng · xanh dương · đen) với chế độ **Sáng/Tối** đổi ngay; thành phần mới (`AtDialog`, `AtTextField`, `AtChip`, `AtListRow`, `AtHeroCard`, …) thay `AlertDialog`/ô nhập rời rạc; thiết kế lại thanh điều hướng, Dashboard (thẻ nổi bật gradient), hộp thoại, Splash, overlay; **Ví Follow4Follow**: xếp hạng, rút tiền 5 kênh, lịch sử, giới thiệu, thông báo (+26 test) |
| v1.6.0 (38) | Tích hợp **Follow4Follow**: đăng nhập trong app (hộp thoại + thẻ tài khoản, chỉ lưu token), lấy acc/job, `quick-switch` acc, `mark-start` cho job Like, complete (đợi ≥5s) / skip, xử lý 429 + hết lượt ngày + lệch đồng hồ; engine báo lại sau cooldown và skip khi lỗi nghiệp vụ; `TASKS_ENABLED = true`; +55 test; tài liệu API vào `.claude/context/`; dọn tài liệu cũ |
| v1.5.0 (37) | Thiết kế lại giao diện: hệ thiết kế `AtTheme` (một bảng màu, bề mặt phẳng, một màu nhấn), thanh điều hướng/tab/nút/hộp thoại mới, hiệu ứng (nhấn co nhẹ, vào màn lần lượt, chỉ báo trượt, số đếm lên, chấm trạng thái lan toả), màn Dịch vụ gọn còn một kiểu hàng, popup overlay và splash đồng bộ màu |
| v1.4.0 (36) | Gỡ Golike + WebView; thêm lớp `TaskProvider` (chờ Follow4Follow); thuật toán nuôi acc mới (`RateGate`, `WatchPlan`); popup chống lặp vòng + thêm hộp thoại quyền Android 13; thẻ "Nhiệm vụ kiếm tiền" thay thẻ Golike |
| v1.3.3 (35) | Bật lại Golike TikTok (`GOLIKE_ENABLED = true`, đã gỡ ở v1.4.0); nuôi acc ngắn 5–20s sau mỗi job + nuôi lâu định kỳ (`taskPostJobNurtureSecs`, +8 test); delay "như người thật" trước khi lấy job; `doLikeTask()` thêm double-tap dự phòng; CI chuyển sang `setup-gradle@v6` |
| v1.3.2 (34) | Sửa `findProfileTab()` bấm nhầm Trang chủ/Đề xuất (giới hạn dải đáy màn hình, +4 test); chuẩn bị popup cho Golike |
| v1.3.1 (33) | `getRootNode()` thành `suspend` (huỷ được khi app đích treo), thêm `safeScreenRead()`; popup `ACCOUNT_UPDATE_PROMPT`; Facebook tự thoát bottom sheet |
| v1.3.0 (32) | Bỏ khóa cài đặt nuôi acc; cải thiện tìm nút Like/Comment cho FB/X/IG/Threads/Snapchat; log chi tiết hơn; xem Reels Facebook |
| v1.2.x | Golike (đăng nhập WebView, task mode, API), tab Lịch tự động, popup farm có log màu, redesign Dịch vụ, sửa đếm ngược/LIKE |
| v1.1.8 | Daily Screen Time Limit (giới hạn thời gian dùng màn hình mỗi ngày) |
