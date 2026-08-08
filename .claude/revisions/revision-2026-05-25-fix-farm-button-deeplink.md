# Revision: Fix Farm Button — Deeplink & Service Regression

**Date**: 2026-05-25
**Session**: 14
**Status**: Applied ✅

---

## Problem

Nhấn "Bắt đầu farm" không làm gì cả.

---

## Root Cause Analysis

### BUG-FARM-001 (CRITICAL — root cause)

**File**: `AndroidManifest.xml`
**Issue**: Thiếu `<queries>` block cho TikTok packages.

Trên Android 11+ (API 30+), nếu không khai báo `<queries>`:
- `resolveActivity(packageManager)` luôn trả về `null` cho mọi TikTok intent
- `getLaunchIntentForPackage()` luôn trả về `null`
- `startActivity()` với implicit intent → throw `ActivityNotFoundException`

Flow bị đứt:
```
startFarm() → engine.startFarm(accounts)
  → switchToAccountViaSettings(account)
    → host.launchTikTok()
      → TikTokDeepLinks.openTikTok()
        → resolveActivity() = null → skip deeplink
        → getLaunchIntentForPackage() = null → return false
      ← false
    ← false (switch failed)
  → setCheckpoint(account, true)  ← tất cả accounts bị checkpoint
→ farm complete trong < 500ms
→ UI poll không kịp bắt isFarming=true → user thấy không có gì thay đổi
```

### BUG-FARM-002 (HIGH — session 12 regression)

**File**: `TikTokAccessibilityService.kt`
**Issue**: `onServiceConnected()` vẫn còn gọi `startForegroundService()`.

Session 12 đã fix `BootReceiver` và `MainActivity` nhưng bỏ sót chính service tự start lại.
Hệ quả: notification + WakeLock xuất hiện ngay khi user bật Accessibility Service, không phải khi bắt đầu farm.

### BUG-FARM-003 (HIGH — defensive fix)

**File**: `TikTokDeepLinks.kt`
**Issue**: `openTikTok()` dùng `resolveActivity()` làm điều kiện guard trước khi `startActivity()`.

Dù BUG-FARM-001 đã fix `<queries>`, pattern này vẫn sai về mặt defensive design:
`resolveActivity()` có thể trả về null ngay cả khi `startActivity()` thành công (một số OEM ROM).
Fix: thử `startActivity()` trực tiếp trong try-catch, không cần kiểm tra resolve trước.

### BUG-FARM-004 (LOW — UX)

**File**: `DashboardViewModel.kt`
**Issue**: `startHint` không có case `ALL_LOCAL + 0 accounts`.

Khi user bật ALL_LOCAL mode nhưng chưa có accounts trong DB:
- `canStart = true` (đúng — session 12 intentional)
- Nhưng không có hint nào giải thích rằng farm sẽ no-op ngay

---

## Changes

### Fix 1 — `AndroidManifest.xml`

Thêm `<queries>` block ở level manifest (sibling của `<application>`):

```xml
<queries>
    <package android:name="com.ss.android.ugc.trill" />
    <package android:name="com.zhiliaoapp.musically" />
    <package android:name="com.ss.android.ugc.aweme" />
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="snssdk1180" />
    </intent>
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="tiktok" />
    </intent>
</queries>
```

### Fix 2 — `TikTokAccessibilityService.kt`

Xóa khỏi `onServiceConnected()`:
```kotlin
// REMOVED:
try { startForegroundService(FarmForegroundService.buildIntent(this)) }
catch (e: Exception) { Log.w(TAG, "startForegroundService: ${e.message}") }
```

Thay bằng comment giải thích lý do không start.

### Fix 3 — `TikTokDeepLinks.kt`

Rewrite `openTikTok()` để không dùng `resolveActivity()` làm guard:

```kotlin
// Before: if (resolveActivity != null) startActivity()  ← bỏ lỡ khi resolve null
// After:  try { startActivity() } catch → fallback      ← thử trực tiếp
```

### Fix 4 — `DashboardViewModel.kt`

Thêm case vào `startHint`:
```kotlin
farmMode == FarmMode.ALL_LOCAL && activeCount == 0 ->
    "Chưa có tài khoản. Thêm tài khoản hoặc dùng chế độ Danh sách"
```

---

## Files Changed

- `android/app/src/main/AndroidManifest.xml` — thêm `<queries>` block (BUG-FARM-001)
- `android/app/src/main/kotlin/com/atpro/accessibility/TikTokAccessibilityService.kt` — xóa premature `startForegroundService()` (BUG-FARM-002)
- `android/app/src/main/kotlin/com/atpro/data/TikTokDeepLinks.kt` — fix `openTikTok()` pattern (BUG-FARM-003)
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardViewModel.kt` — thêm hint 0-account (BUG-FARM-004)

---

## Rollback

### Rollback Fix 1 (manifest queries)
Xóa toàn bộ `<queries>` block khỏi `AndroidManifest.xml`.
**Risk**: farm tiếp tục silent-fail trên Android 11+.

### Rollback Fix 2 (premature service start)
Thêm lại vào `onServiceConnected()` trước `serviceScope.launch`:
```kotlin
try { startForegroundService(FarmForegroundService.buildIntent(this)) }
catch (e: Exception) { Log.w(TAG, "startForegroundService: ${e.message}") }
```

### Rollback Fix 3 (openTikTok)
Revert về pattern cũ:
```kotlin
fun openTikTok(context: Context): Boolean {
    return try {
        val uri = Uri.parse("$SCHEME_SNSSDK://")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent); return true
        }
        context.packageManager.getLaunchIntentForPackage(pkg(context))?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it); true
        } ?: false
    } catch (e: Exception) {
        Log.e(TAG, "openTikTok: ${e.message}"); false
    }
}
```

### Rollback Fix 4 (startHint)
Xóa dòng:
```kotlin
farmMode == FarmMode.ALL_LOCAL && activeCount == 0 ->
    "Chưa có tài khoản. Thêm tài khoản hoặc dùng chế độ Danh sách"
```

---

## Remaining Blockers

Không có blocker mới. Sau khi apply fixes này:

1. **Verify trên device**:
   - Bật Accessibility Service
   - Nhấn "Bắt đầu farm" → farm phải start (notification xuất hiện, view chuyển sang FarmingView)
   - TikTok phải được launch bởi deeplink
   - Nếu 0 accounts (ALL_LOCAL): hint text xuất hiện bên dưới button

2. **TD-BUILD-001 cleanup** (từ session 11): xóa `.bak` files sau CI green
3. **TD-CI-001**: commit `gradle-wrapper.jar` cần machine có `gradle`
4. **Tests cần viết** (từ session 12): DashboardViewModelTest

---

## ADR

Không cần ADR mới — đây là bug fix, không phải architectural decision.
Nếu muốn document `<queries>` policy: thêm vào ADR-0003-accessibility-service-as-automation-driver.md.
