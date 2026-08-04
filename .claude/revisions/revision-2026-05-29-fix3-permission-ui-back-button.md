# Fix 3 — Permission UI refresh + Back button

**Date:** 2026-05-29
**Files changed:** 5

---

## Bug 1 — Permission UI không cập nhật sau khi cấp quyền

**Root cause:** `refreshPermissions(context)` trong `ConfigScreen.kt` chỉ được gọi một lần qua
`LaunchedEffect(Unit)`. Khi người dùng rời sang màn hình Settings hệ thống để cấp quyền rồi
quay lại, `LaunchedEffect(Unit)` không chạy lại → UI vẫn hiển thị "Cấp quyền" dù đã cấp xong.

**Fix:** Thay thế bằng `DisposableEffect` theo dõi `Lifecycle.Event.ON_RESUME`. Mỗi lần
Activity/Screen resume (bao gồm sau khi user quay từ Settings về), `refreshPermissions()` được
gọi lại → state cập nhật ngay, animation `AnimatedContent` chuyển từ nút "Cấp quyền" sang icon
✓ xanh lá.

**File:** `ConfigScreen.kt`

---

## Bug 2 — Thiếu nút Back trên các màn hình

Tất cả các màn hình phụ đều không có nút quay lại trực quan.

**Fix:** Thêm `IconButton` với `Icons.Rounded.ArrowBackIosNew` gọi `activity.finish()` vào:

| Screen | Vị trí |
|--------|--------|
| `ConfigScreen.kt` | `navigationIcon` trong `SettingsTopBar` TopAppBar |
| `StatsScreen.kt` | Đầu `Row` header, trước tiêu đề |
| `LogsScreen.kt` | Đầu `Row` toolbar, trước tiêu đề |
| `AccountsScreen.kt` | Đầu `Row` toolbar, trước tiêu đề |
| `ScheduleScreen.kt` | `navigationIcon` trong TopAppBar |

**Imports thêm vào mỗi file:** `android.app.Activity`, `androidx.compose.ui.platform.LocalContext`

---

## Không thay đổi

- Logic farm, engine, accessibility service
- API/network
- Database
- Tất cả các file không liên quan
