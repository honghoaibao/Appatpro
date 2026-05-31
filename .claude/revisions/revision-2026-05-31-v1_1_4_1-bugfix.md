# Revision — v1.1.4.1 (build 15)

**Date:** 2026-05-31
**Files changed:** 4

## Bugs fixed

### 1. Popup countdown vẫn chạy khi đang tạm dừng
**File:** `data/OverlayFarmMonitor.kt`

`startTicker()` giảm `tickSessionSecs`/`tickTotalSecs` mỗi giây mà không kiểm tra
`isPaused`. Khi người dùng nhấn ⏸, đồng hồ trên popup vẫn đếm ngược.

**Fix:** Bọc phần decrement trong `if (!isPaused)`. Ticker vẫn tiếp tục `postDelayed`
(để khi resume không cần khởi động lại), chỉ bỏ qua việc giảm số và cập nhật UI.

### 2. Permission state sai sau khi thoát app và vào lại
**Files:** `ui/config/ConfigViewModel.kt`, `ui/config/ConfigScreen.kt`

**Root cause — race condition:**
`ConfigViewModel.init` gọi `load()` — coroutine đọc DB (suspend trên IO dispatcher).
Trong khi đó `DisposableEffect` trong `ConfigScreen` đăng ký observer lifecycle và
`refreshPermissions()` cập nhật `accessibilityGranted = true`. Sau đó, khi `load()`
hoàn tất từ DB, nó gọi `_state.value = s` — replace toàn bộ state với `ConfigUiState`
mới có `accessibilityGranted = false` (default) → **ghi đè kết quả của `refreshPermissions()`**.

Kết quả người dùng thấy: mở Settings → chưa cấp (sai). Bấm "Cấp quyền" → thoát ra
(ON_RESUME fires) → hiển thị đã cấp (đúng).

**Fix 1 — `ConfigViewModel.load()`:** Đổi `_state.value = s` thành `_state.update { prev -> s.copy(accessibilityGranted = prev.accessibilityGranted, ...) }`.
Đảm bảo `load()` không bao giờ xoá trạng thái quyền đã được set.

**Fix 2 — `ConfigScreen`:** Thêm `vm.refreshPermissions(context)` vào `LaunchedEffect(Unit)`
(gộp với `loadTikTokVersion`). Đảm bảo permissions được đọc ngay lập tức khi screen mở,
kể cả lần đầu tiên (không phụ thuộc vào ON_RESUME catchup của lifecycle observer).

### 3. Version bump
**Files:** `android/app/build.gradle`, `.github/workflows/build.yml`

- `versionCode`: 14 → 15
- `versionName`: `"v1.1.4"` → `"v1.1.4.1"`
- `build.yml` defaults: version `1.1.2` → `1.1.4.1`, build `12` → `15`
