# Revision v1.1.9 Supplement — 2026-06-04

## Tóm tắt
Bổ sung bản v1.1.9: cập nhật tài liệu Golike API mới trong `.claude/`, thêm xử lý màn hình
"Tạm thời quay lại" (Swipe-to-return) của TikTok.

---

## Thay đổi

### 1. Cập nhật tài liệu Golike API

**File:** `.claude/context/GolikeAPI_Documentation.md`

- **Base URL** cập nhật: `https://golike.net/` → `https://golike.io/`
  (khớp với code thực tế trong `GolikeApi.kt`)
- Thêm ghi chú *Breaking change v1.1.9*: các field `coin`, `hold_coin`, `pending_coin`
  đổi kiểu từ `Int` → `Double` (API trả về số thập phân, ví dụ `250.5`)
- Cập nhật kiểu trong bảng mô tả field:
  - `data.coin` (login + me): `int` → `**double**`
  - `current_coin` (statistics): `int` → `**double**`
  - `hold_coin`, `pending_coin` (platform stats): `int` → `**double**`
- Cập nhật footer phiên bản: `1.0 – 2026-06-01` → `1.1 – 2026-06-04`

---

### 2. Xử lý màn hình "Hãy kéo / Tạm thời quay lại" (Swipe-to-return)

**Root cause:**
TikTok đã thêm variant mới của màn hình Screen Time Management.
Sau khi xem quá lâu, thay vì chỉ hiện bài tập hít thở ("Quay lại ngay bây giờ")
hoặc nhập passcode ("Quay lại TikTok"), TikTok có thể hiện màn hình swipe gesture
với text "Hãy kéo" ở header và nút fallback **"Tạm thời quay lại"** ở dưới cùng.

Engine hiện tại không nhận ra màn hình này → watchdog kích hoạt WDG-RECOVER liên tục
nhưng `pressBack` không thoát được → bị stuck cho đến khi session hết giờ.

**File: `accessibility/NodeTraverser.kt`**

`findReturnFromWellbeingButton()` — thêm 2 text mới:
```kotlin
"tạm thời quay lại",       // Swipe-to-return screen (screenshot 2026-06-04)
"temporarily go back",     // EN: Swipe-to-return screen
```
(đặt ngay sau "quay lại ngay bây giờ" để ưu tiên đúng thứ tự)

`detectWellbeingScreen()` WELLBEING_SIGNALS — thêm 2 signal:
```kotlin
"hãy kéo",                 // header "Drag/Swipe here" VN
"tạm thời quay lại",       // button text đặc trưng
```

**File: `automation/AutomationEngine.kt`**

Step [2b] trong `farmOneAccount()`:
- Cập nhật comment: nêu rõ 2 variant được xử lý (nghỉ ngơi + swipe-to-return)
- Log message: thay text hard-coded `'Quay lại ngay bây giờ'` bằng
  `btnText = wellbeingBtn.text?.take(30)` — hiển thị text nút thực tế tìm được
  (giúp debug khi TikTok thêm variant mới)

`recoverToFeed()` Tier 0b:
- Cập nhật comment tương tự, log message dùng `btnText` động

---

## Flow xử lý sau khi fix

```
Màn hình "Hãy kéo" xuất hiện
       ↓
farmOneAccount step [2b]:
  findReturnFromWellbeingButton(root)
  → tìm "tạm thời quay lại" → found ✓
  → clickNode(btn) → delay 1_500ms
  → continue vòng lặp farm bình thường
```

Nếu màn hình xuất hiện khi đang recover (watchdog):
```
recoverToFeed() Tier 0b:
  findReturnFromWellbeingButton(getRootNode())
  → found → click → delay 2_000ms
  → isOnFeedTab() → true → return true ✓
```

---

## Files thay đổi
```
.claude/context/GolikeAPI_Documentation.md
android/app/src/main/kotlin/com/atpro/accessibility/NodeTraverser.kt
android/app/src/main/kotlin/com/atpro/automation/AutomationEngine.kt
```

## Rollback
```
GolikeAPI_Documentation.md   → khôi phục Base URL = https://golike.net/, coin: int
NodeTraverser.kt              → xóa "tạm thời quay lại", "temporarily go back" khỏi RETURN_TEXTS
                              → xóa "hãy kéo", "tạm thời quay lại" khỏi WELLBEING_SIGNALS
AutomationEngine.kt           → khôi phục comment cũ + log message cố định
```
