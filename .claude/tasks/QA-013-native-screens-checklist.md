# QA-013: Native Compose Screens — OEM Device Checklist

Mục tiêu: xác nhận DashboardActivity, AccountsActivity, LogsActivity, StatsActivity hoạt động đúng
trên 3 OEM target trước khi flip feature flag lên production.

---

## Chuẩn bị

```kotlin
// FeatureFlags.kt — bật tất cả cho QA build
const val NATIVE_DASHBOARD = true
const val NATIVE_ACCOUNTS  = true
const val NATIVE_LOGS      = true
const val NATIVE_STATS     = true
```

Build: `./gradlew assembleDebug` → cài `atpro.apk`

---

## Device targets

| # | Device             | Android | OEM ROM         |
|---|--------------------|---------|-----------------|
| A | Xiaomi Redmi Note  | 13      | MIUI 14         |
| B | OPPO A-series      | 13      | ColorOS 14      |
| C | Samsung Galaxy A   | 14      | OneUI 6         |

---

## Test cases

### TC-01: DashboardActivity khởi động

| Step | Expected |
|------|----------|
| Mở AT PRO | DashboardActivity hiện (không phải Flutter) |
| Chưa bật Accessibility | Status dot = xám, hint = "Bật Accessibility Service trước" |
| Start button | Disabled |
| Bật Accessibility Service | Status dot = xanh lá, Start enabled (nếu có account) |

### TC-02: DashboardActivity — farm active

| Step | Expected |
|------|----------|
| Nhấn Bắt đầu farm | FarmingView hiện với PulseDot |
| PulseDot | Nhấp nháy mượt (animation không giật) |
| Tên account + progress | Cập nhật real-time |
| Stats grid (video/likes/follows/time) | Tăng mỗi lần loop |
| Nhấn Tạm dừng | isPaused=true, dot → amber, nút = "Tiếp tục" |
| Nhấn Tiếp tục | Farming resume |
| Nhấn Dừng | Trở về IdleView |

### TC-03: ShortcutRow navigation

| Button | Expected |
|--------|----------|
| 👤 Tài khoản | AccountsActivity mở (back button trả về Dashboard) |
| 📊 Thống kê | StatsActivity mở |
| 📋 Nhật ký | LogsActivity mở |

### TC-04: AccountsActivity

| Step | Expected |
|------|----------|
| Danh sách account | Hiện đúng số lượng, status badge đúng màu |
| Search "@" | Filter real-time |
| Filter chip "Checkpoint" | Chỉ hiện account có checkpoint=true |
| Swipe left trên account | Nền đỏ + icon delete hiện |
| Nhấn Xóa trong dialog | Account biến mất khỏi list |
| Nhấn Hủy | Không thay đổi |
| Account mới auto-saved | Hiện trong list sau khi farm scan |

### TC-05: LogsActivity

| Step | Expected |
|------|----------|
| List logs | Từ mới → cũ (newest on top) |
| Level filter "Lỗi" | Chỉ hiện ERROR logs |
| Copy all | Clipboard chứa tất cả log formatted |
| Clear all | List trống |
| Farm đang chạy → log mới | Auto-scroll lên top |

### TC-06: StatsActivity

| Step | Expected |
|------|----------|
| Không có data | Empty state hiện |
| Sau khi farm | Summary card hiện đúng totals |
| Chip "7 ngày" | Chỉ tính sessions trong 7 ngày |
| Chip "Tất cả" | Tính toàn bộ sessions |
| Daily group | Group đúng theo ngày, sorted descending |
| Multi-account ngày | Per-account rows hiện trong group |

### TC-07: Wake lock — FarmForegroundService

| Step | Expected |
|------|----------|
| Bắt đầu farm | Thông báo "AT PRO đang chạy..." hiện |
| Tắt màn hình sau 5 phút | Service vẫn sống (log tiếp tục ghi) |
| Sau 30 phút screen-off | Farm vẫn chạy, không bị kill |
| Mở lại màn hình | Stats cập nhật đúng |
| **MIUI**: vào Settings → Battery → App battery saver → AT PRO → No restrictions | Bắt buộc trước khi test |
| **ColorOS**: Settings → Battery → Startup Manager → AT PRO → bật | Bắt buộc trước khi test |

---

## OEM-specific issues — known risks

| OEM     | Issue                                   | Workaround |
|---------|-----------------------------------------|------------|
| MIUI 14 | MIUI kills background app sau 3 phút    | Battery → No restrictions |
| MIUI 14 | Notification icon có thể blank          | Cần adaptive icon đúng |
| ColorOS | Startup Manager chặn auto-start         | Manual whitelist |
| ColorOS | Gesture navigation → back gesture conflict với SwipeToDismiss | Test với 3-button nav |
| OneUI 6 | Dark mode override màu notification     | Kiểm tra notification text contrast |

---

## Pass criteria

Tất cả TC-01 → TC-07 pass trên tất cả 3 device → flip `FeatureFlags.*` = `true` trong release build.

Nếu fail: ghi issue vào `.claude/revisions/QA-013-results.md`, tạo TASK tương ứng.

---

## Sign-off

- [ ] Device A (MIUI 14): _______________
- [ ] Device B (ColorOS 14): _______________
- [ ] Device C (OneUI 6): _______________
- [ ] Wake lock 30 phút xác nhận: _______________
