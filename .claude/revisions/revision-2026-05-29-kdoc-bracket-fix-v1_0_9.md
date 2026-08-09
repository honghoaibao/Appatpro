# Revision — v1.0.9: KDoc bracket version tag fix

_Date: 2026-05-29_
_Files changed: 2_

---

## Mục tiêu

Fix 4 KDoc comment bị lỗi do dùng `[v1.0.9]` trong `/** ... */` block.

---

## Nguyên nhân

Kotlin KDoc parser hiểu `[text]` là **link reference** — nếu `text` không phải tên
symbol hợp lệ, trình biên dịch/Dokka phát sinh warning hoặc error:

```
warning: unresolved link: v1.0.9
```

Version tag `[v1.0.9]` không phải symbol → invalid link → build warning.

**`//` comment thường không bị ảnh hưởng** — parser không xử lý chúng.

---

## Thay đổi

Xóa ngoặc vuông khỏi version tag trong KDoc. `//` comment giữ nguyên.

| File | Line | Trước | Sau |
|------|------|-------|-----|
| `OverlayFarmMonitor.kt` | 20 | `* [v1.0.9] OverlayFarmMonitor…` | `* v1.0.9 OverlayFarmMonitor…` |
| `OverlayFarmMonitor.kt` | 112 | `* [v1.0.9] Cập nhật trạng thái…` | `* v1.0.9 Cập nhật trạng thái…` |
| `DashboardViewModel.kt` | 182 | `* [v1.0.9] Bắt đầu cập nhật…` | `* v1.0.9 Bắt đầu cập nhật…` |
| `DashboardViewModel.kt` | 205 | `* [v1.0.9] Tải APK…` | `* v1.0.9 Tải APK…` |

**Giữ nguyên (// comment):**
- `OverlayFarmMonitor.kt:231` — `// ── [v1.0.9] Control buttons row ──`
- `DashboardViewModel.kt:289` — `// [v1.0.9] Tiến độ tải APK…`

---

## Quy tắc phòng tránh

Trong KDoc (`/** ... */`), **không dùng `[text]` cho version tag**.
Dùng plain text: `v1.0.9`, hoặc tag KDoc chuẩn: `@since 1.0.9`.

---

## Files changed

| File | Action |
|------|--------|
| `data/OverlayFarmMonitor.kt` | Fix 2 KDoc lines (20, 112) |
| `ui/dashboard/DashboardViewModel.kt` | Fix 2 KDoc lines (182, 205) |
