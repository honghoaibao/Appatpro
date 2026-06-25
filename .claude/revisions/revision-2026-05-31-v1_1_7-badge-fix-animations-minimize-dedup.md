# Revision — v1.1.7 (2026-05-31)

## Tóm tắt
Sửa badge text vỡ, thêm hiệu ứng animation, thêm nút thu nhỏ overlay, bỏ logic trùng video 3 lần.

---

## 1. Sửa lỗi chữ vỡ trong badge (`ServicesScreen.kt`)

**Root cause:** Trong `Row` header của mỗi card:
```
[icon 38dp] [Column title+subtitle] [Spacer(weight=1f)] [Badge]
```
`Spacer(weight(1f))` chiếm toàn bộ không gian còn lại → badge bị ép vào chiều rộng gần 0 →
text wrap theo chiều dọc.

**Fix:** Thay `Spacer(weight(1f))` bằng `Column(modifier = Modifier.weight(1f))` trên column
title/subtitle. Column giờ chiếm available space, badge co về kích thước tự nhiên.
Thêm `maxLines = 1` cho text trong badge để phòng thủ thêm một tầng.

**Áp dụng:** `TikTokFarmCard` và `GolikeEarnCard`.

---

## 2. Thêm hiệu ứng animation (`ServicesScreen.kt`)

- **`GolikeEarnCard`**: Thêm `pressed` state + `animateFloatAsState` scale 0.97f (120ms tween),
  đồng bộ với `TikTokFarmCard`. Thêm `clickable` để bắt touch event.
- **`ServiceChip`**: Thêm alpha animation (1f → 0.6f, 100ms tween) khi press.
  Scale không phù hợp cho chip nhỏ → dùng alpha pulse thay thế.

---

## 3. Nút thu nhỏ overlay (`OverlayFarmMonitor.kt`)

**Thêm:** Nút `⊟` / `⊞` ở góc phải header row của floating overlay.

**Cơ chế:**
- Toàn bộ nội dung bên dưới header (account, time, logs, buttons) được wrap vào
  một `LinearLayout body` (`contentArea`).
- Click `⊟` → `body.visibility = GONE` + text button đổi thành `⊞`.
- Click `⊞` → `body.visibility = VISIBLE` + text button đổi về `⊟`.
- State `isMinimized` được reset về `false` trong `hide()`.

**Không thay đổi:** Drag-to-move, countdown ticker, Pause/Stop logic.

---

## 4. Bỏ logic trùng video 3 lần (`AutomationEngine.kt`)

**Trước:** Threshold `sameVideoCount >= 3` → cùng 1 video được xem tới 3 lần trước khi
force swipe. Gây nhầm lẫn: trông như app bị kẹt trong khi thực ra đang đợi threshold.

**Sau:** Threshold `>= 1` → ngay khi fingerprint lặp lại lần đầu, force swipe ngay lập tức.

**Lưu ý:** `sameVideoCount` vẫn được giữ nguyên logic tích lũy để log chính xác
(`"Kẹt cùng video N lần"`), chỉ threshold thay đổi.

---

## 5. Version bump

- `build.gradle`: `versionCode 18 → 19`, `versionName "v1.1.6" → "v1.1.7"`
- `build.yml`: default inputs `1.1.6 / 18 → 1.1.7 / 19`; fallback VER `1.1.6 → 1.1.7`

---

## Files thay đổi

- `android/app/build.gradle`
- `.github/workflows/build.yml`
- `android/app/src/main/kotlin/com/atpro/ui/services/ServicesScreen.kt`
- `android/app/src/main/kotlin/com/atpro/data/OverlayFarmMonitor.kt`
- `android/app/src/main/kotlin/com/atpro/automation/AutomationEngine.kt`
