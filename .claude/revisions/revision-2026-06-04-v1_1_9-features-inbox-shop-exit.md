# Revision v1.1.9 Features — 2026-06-04

## Tóm tắt
Bổ sung tính năng: Xem Hộp thư, Xem Cửa hàng, Thoát TikTok sau farm.
Sửa lỗi: overlay "Xem video" không đếm ngược + feed không lướt.
Cập nhật nhận diện wellbeing screen theo ảnh thực tế (header đầy đủ).

---

## Bug fix: "Xem video" không đếm ngược / feed không lướt

**Root cause:** Khi `detectAd` / `detectLive` / `detectDiary` trả về true liên tiếp
(false positive hoặc TikTok không phản hồi swipe), vòng lặp farm bị stuck trong
chu kỳ `[4] "Xem video" → [5a/6/6b] swipeNext() → continue` mà không bao giờ
đến bước `[7]` — nên overlay không hiện countdown `(Xs)` và feed không lướt.

**Fix:** Thêm `consecutiveSkipCount` counter trong `farmOneAccount()`:
- Mỗi lần skip (ad/live/diary) → `consecutiveSkipCount++`
- Mỗi khi xem được 1 video thành công → `consecutiveSkipCount = 0`
- Khi `consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS (8)`:
  → gọi `forceSwipeNext()` thay vì `swipeNext()` + `delay(2_000)` + reset counter

**Files:** `AutomationEngine.kt`

---

## Cập nhật wellbeing screen detection

Từ ảnh thực tế (2026-06-04), màn hình "Hãy kéo giãn cơ thể" có text đầy đủ:
- Header: **"Hãy kéo giãn cơ thể"** (không phải chỉ "Hãy kéo")
- Subtitle: "Bạn đã đạt đến giới hạn hằng ngày."
- Button: "Tam thời quay lại" (font rendering — thêm cả variant "tam thời")

**`NodeTraverser.kt`:**
- `WELLBEING_SIGNALS`: thêm `"hãy kéo giãn cơ thể"`, `"bạn đã đạt đến giới hạn hằng ngày"`
- `findReturnFromWellbeingButton()`: thêm `"tam thời quay lại"` (variant không dấu ạ)

---

## Tính năng mới 1: Xem Hộp thư

Thỉnh thoảng ghé qua tab Hộp thư sau khi xem + tương tác xong một video,
trước khi lướt sang video tiếp theo.

**Cài đặt mới trong `FarmConfig` / `ConfigUiState`:**
| Key | Default | Mô tả |
|-----|---------|--------|
| `inboxViewRate` | `0.0` (tắt) | Xác suất ghé Hộp thư sau mỗi video |
| `inboxViewDurationSecs` | `15` | Thời gian xem (giây) |

**Flow:**
```
[10b] Random < inboxViewRate?
  → findInboxTab() → click → delay 1_500ms
  → for s in duration..1: overlay "Hộp thư (Xs)" + delay 1s
  → findHomeTab() hoặc pressBack() → delay 1_200ms
```

**Files:** `FarmConfig.kt`, `LocalRepository.kt`, `AutomationEngine.kt` (doViewInbox),
`ConfigViewModel.kt`, `ConfigScreen.kt`

---

## Tính năng mới 2: Xem Cửa hàng

Thỉnh thoảng ghé qua tab Cửa hàng sau khi xem + tương tác xong một video.

**Cài đặt mới:**
| Key | Default | Mô tả |
|-----|---------|--------|
| `shopViewRate` | `0.0` (tắt) | Xác suất ghé Cửa hàng sau mỗi video |
| `shopScrollCount` | `3` | Số lần cuộn sản phẩm |

**Flow:**
```
[10b] (nếu chưa ghé inbox) Random < shopViewRate?
  → findShopTab() → click → delay 2_000ms
  → repeat scrollCount: overlay "Cửa hàng (i/n)" + swipeUp + Human.delay(1_500–2_500)
  → findHomeTab() hoặc pressBack() → delay 1_500ms
  → !isOnFeedTab? → recoverToFeed()
```

**Lưu ý:** Trong 1 lần xem video, chỉ 1 trong 2 tab được ghé (ưu tiên inbox).

---

## Tính năng mới 3: Thoát TikTok sau khi hoàn thành farm

Khi `phaseFarmLoop()` xong toàn bộ danh sách, sau khi gửi notification:
```kotlin
log("DONE: Farm hoàn thành tất cả N tài khoản — đóng TikTok")
delay(800)
host.killTikTok()
return FarmPhase.Done
```

Tránh TikTok tiếp tục chạy nền sau khi nuôi xong.

---

## Tab tìm kiếm mới trong NodeTraverser

- `findInboxTab()`: resource-id, text ("hộp thư", "inbox"), contentDescription
- `findShopTab()`: resource-id, text ("cửa hàng", "shop"), contentDescription

---

## UI mới trong ConfigScreen

Card **"Ghé qua tab khác"** (màu Cyan/Amber) với 4 controls:
- Slider tỉ lệ Hộp thư (0–50%, hiển thị "Tắt" khi = 0)
- Slider thời gian xem Hộp thư (5–60s, disabled khi rate = 0)
- Slider tỉ lệ Cửa hàng (0–50%, hiển thị "Tắt" khi = 0)
- Slider số lần cuộn (1–10, disabled khi rate = 0)

---

## Files thay đổi
```
android/app/src/main/kotlin/com/atpro/data/FarmConfig.kt
android/app/src/main/kotlin/com/atpro/data/LocalRepository.kt
android/app/src/main/kotlin/com/atpro/accessibility/NodeTraverser.kt
android/app/src/main/kotlin/com/atpro/automation/AutomationEngine.kt
android/app/src/main/kotlin/com/atpro/ui/config/ConfigViewModel.kt
android/app/src/main/kotlin/com/atpro/ui/config/ConfigScreen.kt
```

## Rollback
- `FarmConfig.kt` / `LocalRepository.kt` / `ConfigViewModel.kt`: xóa 4 field inbox/shop
- `AutomationEngine.kt`: xóa `consecutiveSkipCount`, hàm `doViewInbox/doViewShop`,
  step [10b], `killTikTok()` ở phaseFarmLoop
- `NodeTraverser.kt`: xóa `findInboxTab/findShopTab`, revert WELLBEING_SIGNALS
- `ConfigScreen.kt`: xóa card "Ghé qua tab khác"
