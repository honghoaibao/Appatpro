# Revision: v1.0.6 — Fix feed detection (isLost false positive)

Date: 2026-05-28
Version: v1.0.6 (versionCode 7)

## Root Cause

TikTok 2026 VN layout sử dụng tab labels mới chưa có trong `hasNavBar()`:
- `"trang chủ"` (Home)
- `"đề xuất"` (For You / Recommendations)
- `"cộng đồng"` (Community/Friends)
- `"đã follow"` (Following)

Kết quả: `hasNavBar()` trả `false` khi đang ở feed → `isLost()` = `true`
→ `consecutiveLostCount` tăng đến 3 → `recoverToFeed()` gọi `pressBack()` liên tục
→ back loop không dừng, farming không tiến được.

## Symptom từ log

```
[22:37:30] 👤 [1/1] @toidodan
# Sau đó: back liên tục, không xem được video
```

## Fixes

### 1. NodeTraverser.kt — `hasNavBar()`: thêm VN labels còn thiếu

Thêm vào `NAV_TEXTS`:
- `"trang chủ"`, `"đề xuất"`, `"cộng đồng"`, `"đã follow"`

### 2. NodeTraverser.kt — thêm `isOnFeedTab()` (chiến lược kép)

Hàm mới thay thế `hasNavBar()` cho các check feed-state:

**Chiến lược 1 — isSelected trên Home tab** (chính xác nhất):
Tìm tab Home trong Bottom TabBar, kiểm tra `node.isSelected == true`.
Đây là cách TikTok đánh dấu tab đang active.

**Chiến lược 2 — keyword count fallback** (dự phòng):
Đếm số từ khóa feed khớp trong cây accessibility (trang chủ, đề xuất, cộng đồng...).
Threshold `>= 2` để tránh false positive từ popup có 1 từ trùng.

### 3. AutomationEngine.kt — thay `hasNavBar()` → `isOnFeedTab()`

- `waitFeedLoad()`: dùng `isOnFeedTab()` thay `hasNavBar()`
- `isLost()`: dùng `!isOnFeedTab()` thay `!hasNavBar()`
- `recoverToFeed()`: dùng `isOnFeedTab()` thay `hasNavBar()`
- `positionFirstAccount()` TH1: dùng `isOnFeedTab()` thay `hasNavBar()`

### 4. positionFirstAccount() TH1 — thêm pressBack() thứ 2

Khi TH1 (acc đang active), sau khi `openSwitchAndDiscover()`:
- lần 1 `pressBack()` đóng switch popup
- lần 2 `pressBack()` đóng Settings screen
- rồi mới check `isOnFeedTab()`

Trước đây chỉ 1 pressBack → vẫn còn ở Settings → `isLost() = true` → recover loop.

### 5. build.gradle — bump version

- `versionCode`: 6 → 7
- `versionName`: `v1.0.5` → `v1.0.6`

## Files changed

- `android/app/src/main/kotlin/com/atpro/accessibility/NodeTraverser.kt`
- `android/app/src/main/kotlin/com/atpro/automation/AutomationEngine.kt`
- `android/app/build.gradle`
