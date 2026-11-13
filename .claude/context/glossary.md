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
| **Follow4Follow (F4F)** | Nơi nhận nhiệm vụ kiếm coin (Like/Follow TikTok) — nhà cung cấp của `TaskProvider` từ v1.6.0 |
| **accountActive** | Tài khoản TikTok đang được kích hoạt phía server F4F; job trả về là của acc này. Đổi bằng `quick-switch` |
| **mark-start** | Báo F4F "bắt đầu job LIKE" ngay trước khi mở link (`onJobOpening`) |
| **x-ts / x-sign** | Header ký request F4F: Unix giây + HMAC-SHA256(`{ts}:{METHOD}:{path}`) |
| **Sky (hệ thiết kế)** | Bảng màu xanh da trời · trắng · xanh dương · đen, 2 chế độ Sáng/Tối (`AtPalettes`, `AtThemeState`) — ADR-0009 |
| **AtColors** | Nguồn màu duy nhất của UI; các thuộc tính là getter đọc palette hiện hành — KHÔNG sao chép vào `val` cấp file |
| **Màn hình phủ (Overlay)** | Thống kê / Lịch tự động / Ví F4F mở toàn màn hình đè lên các tab, có nút quay lại; Back hệ thống đóng (ADR-0010) |
| **AtTopBar** | Thanh tiêu đề chung của mọi màn hình (`title`, `subtitle`, `onBack`, `actions`) |
| **Chuẩn hoá acc** | Đọc @username thật của acc có tên hiển thị không hợp lệ trong switch popup. Acc ĐẦU (đang login) chỉ mở Hồ sơ; acc khác mới cần switch thật |
| **Ví Follow4Follow** | Màn hình `F4fHubScreen`: tổng quan, xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo |
| **account_daily_usage** | Lượt job/ngày của một acc F4F (`done/limit/remaining/reached`); `reached` → engine dừng acc đó |

## Android layer

| Term | Definition |
|------|-----------|
| **AutomationEngine** | Orchestrates a full farm run. Iterates accounts, delegates single-account farming, reports progress via FlutterBridge |
| **TikTokAccessibilityService** | The Android `AccessibilityService` that drives TikTok UI. Entry point for all node-level interaction |
| **NodeTraverser** | Utility for traversing and querying the AccessibilityNodeInfo tree |
| **PopupHandler** | Detects and dismisses TikTok popups before the farming loop continues |
| **ContentScanner** | Bộ quét một lần cây giao diện → POPUP / SPONSORED / APP_INSTALL / LIVE / CLEAN theo chữ hiển thị; chỉ bấm nút đóng nằm trong whitelist (v1.12.5) |
| **Sweep (quét mốc)** | `PopupHandler.sweep()` — dọn popup an toàn tại một bước của luồng (vào Cài đặt, mở danh sách tài khoản, feed vừa tải…), có tuỳ chọn quét trễ 1,2s |
| **Nút đóng mềm / cứng** | Mềm: `Để sau`, `Not now`, `Bỏ qua`… (chỉ popup mới có). Cứng: `Đóng`, `Hủy`… (cũng là nhãn nút X của bảng chuyển đổi tài khoản nên bị cấm khi bảng đang hiện) |
| **Về Home khi dừng** | `goHomeBeforeStop()` — mọi đường dừng gọi `GLOBAL_ACTION_HOME` đúng một lần trước khi tool tắt (v1.12.5) |
| **FarmForegroundService** | Android foreground service that keeps AutomationEngine alive while the screen is off or the app is backgrounded |
| **ScheduledFarmManager** | Manages start/stop schedules for farm runs (`FarmSchedule`) |
| **FarmSchedule** | A named schedule: days of week + start/stop time |
| **LanWebSocketServer** | WebSocket server broadcasting farm status to other devices on the same LAN |
| **OverlayFarmMonitor** | Floating overlay window showing real-time account/progress info while farming. Ba trạng thái (v1.12.5): **Popup** (đầy đủ) · **Mini Popup** · **Floating Chip** |
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

## Nhiệm vụ kiếm tiền & thông báo (v1.11–v1.12)

| Term | Meaning |
|------|---------|
| **Thang đổi acc (S1–S5)** | Chuỗi bước `Follow4FollowProvider.forceActivate` khi fetch kèm `uid` không đưa server về đúng acc: active-acc → uid số → quick-switch cũ → uid tên lại → fetch trơn (mirror `_switch_force` của `f4f.py` v2.8) |
| **`accountActive`** | Acc F4F đang active phía server trong response `earn-coin`; `null`/khác acc cần làm → không được dùng `jobs`, không complete/skip |
| **`jobGone`** | `TaskResult.Error.jobGone` — complete/skip báo "Không tìm thấy dữ liệu": job đã bị xoá/hết → bỏ job, không skip |
| **Tài khoản nhận nhiệm vụ** | Lựa chọn riêng của nhiệm vụ (`task_account_mode` AUTO/SELECTED + `task_selected_accounts`), luật ở `TaskAccountSelector` — khác "danh sách chọn" của Nuôi TikTok |
| **Dòng chi tiết (`detail`)** | Dòng nhật ký chỉ để gỡ lỗi (tag `DEV`, `CFG`, `BTN`, `FEED`, `F4F`…) — ẩn ở popup/Nhật ký trừ khi bật chip "Chi tiết" (`LogPolish`) |
| **Giờ yên tĩnh** | Khung giờ thông báo vẫn hiện nhưng không âm/rung (`NotificationPolicy.isQuiet`) |
| **Nhóm thông báo** | `NotifCategory`: ALERT · SESSION · ACCOUNT · TASK — mỗi nhóm công tắc + kênh riêng |

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
