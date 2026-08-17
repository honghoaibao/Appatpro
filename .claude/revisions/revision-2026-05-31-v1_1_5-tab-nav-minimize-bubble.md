# Revision — v1.1.5 (2026-05-31)

## Tóm tắt
Cập nhật giao diện lớn: tab navigation + popup minimize bubble.

## Thay đổi

### 1. Popup minimize — `DashboardScreen.kt`
- `StartupStatusDialog` thêm param `onMinimize: () -> Unit`
- Header row thêm `IconButton(KeyboardArrowDown)` góc phải để thu nhỏ
- Khi minimize: dialog đóng, hiện `MinimizedBubble` tại `BottomEnd` của DashboardScreen
- `MinimizedBubble`: hình tròn 58dp, logo app, pulse ring animation, đổi màu theo pause/run
- Tap bubble → mở lại dialog; `LaunchedEffect(isStartingUp)` reset khi farm kết thúc

### 2. Tab navigation — `MainScreen.kt` (mới) + `DashboardActivity.kt`
- Tạo `MainScreen.kt`: `Scaffold` + `NavigationBar` 4 tab (Dashboard · Thống kê · Tài khoản · Nhật ký)
- `DashboardActivity` khởi tạo 4 ViewModel, render `MainScreen` thay vì `DashboardScreen` trực tiếp
- `contentWindowInsets = WindowInsets(0)` tránh double status bar padding
- `NavigationBar` tự xử lý gesture bar qua `navigationBarsPadding()`
- `ShortcutRow()` bị xóa khỏi `IdleView` (redundant với tab nav)

### 3. Backward compat screens — `StatsScreen` · `AccountsScreen` · `LogsScreen`
- Thêm `onNavigateUp: (() -> Unit)? = null` (default null = backward compat)
- `activity?.finish()` → `onNavigateUp?.invoke() ?: activity?.finish()`
- Khi dùng trong tab: `onNavigateUp = { selectedTab = Tab.DASHBOARD }`
- Khi dùng standalone Activity: giữ nguyên hành vi cũ

### 4. Version bump
- `build.gradle`: versionCode 17, versionName "v1.1.5"
- `build.yml`: default version 1.1.5, build 17, fallback VER "1.1.5"

## Files thay đổi
- `android/app/build.gradle`
- `.github/workflows/build.yml`
- `android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt` ← NEW
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardActivity.kt`
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/stats/StatsScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/accounts/AccountsScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/logs/LogsScreen.kt`
