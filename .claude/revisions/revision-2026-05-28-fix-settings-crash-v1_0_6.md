# Revision: Fix settings crash — PermissionScreen.kt border API

**Date**: 2026-05-28
**Version**: v1.0.6 (versionCode 7)
**Status**: Applied ✅

---

## Root Cause

`PermissionScreen.kt` trong package `ui.dashboard` dùng API sai cho `border` parameter
của `OutlinedButton`:

```kotlin
// TRƯỚC (SAI) — gây compile error / crash
border = ButtonDefaults.outlinedButtonBorder.copy(
    brush = androidx.compose.ui.graphics.SolidColor(iconColor.copy(alpha = 0.4f)),
),
```

### Hai lỗi trong một dòng

1. **`BorderStroke.copy()` không tồn tại** — `BorderStroke` là plain `class` (không phải
   `data class`), không có `.copy()` method. Kotlin sẽ báo
   `Unresolved reference: copy` → compile failure.

2. **`ButtonDefaults.outlinedButtonBorder` deprecated** — Trong Material3 1.3.0
   (BOM 2024.09.00), property này đã bị deprecate và thay bằng
   `fun outlinedButtonBorder(enabled: Boolean = true): BorderStroke`.
   Dù deprecated shim vẫn còn, việc gọi `.copy()` trên kết quả trả về vẫn lỗi
   vì reason #1 ở trên.

### Tại sao crash "khi vào Settings"

`PermissionScreen.kt` nằm trong cùng compilation unit với `ConfigActivity`. Compile error
trong file này → toàn bộ module Android fail build → không tạo được APK / install lỗi.

---

## Fix

File: `android/app/src/main/kotlin/com/atpro/ui/dashboard/PermissionScreen.kt`

```kotlin
// SAU (ĐÚNG) — khớp pattern với ConfigScreen.kt SettingsPermissionItem
border = BorderStroke(1.dp, SolidColor(iconColor.copy(alpha = 0.4f))),
```

### Import thêm

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.SolidColor
```

### Import xóa

```kotlin
// Không còn cần (và không còn dùng):
// import androidx.compose.ui.graphics.Brush  ← GIỮ LẠI vì dùng trong Brush.linearGradient()
```

---

## Files changed

| File | Thay đổi |
|------|----------|
| `PermissionScreen.kt` | Replace `outlinedButtonBorder.copy(brush=...)` → `BorderStroke(1.dp, SolidColor(...))` |
| `PermissionScreen.kt` | Thêm `import BorderStroke`, `import SolidColor`; xóa unused `import Brush` wait kept for linearGradient |

---

## Version

`versionCode 7` / `versionName "v1.0.6"` — không thay đổi (đã đúng từ bản trước).
