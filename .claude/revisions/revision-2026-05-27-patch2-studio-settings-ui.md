# Revision: patch2 — Studio Deeplink Logic + Settings UI Redesign

**Date**: 2026-05-27
**Version**: v1.0.3-patch2 (versionCode 4)
**Status**: Applied ✅

---

## Tóm tắt

Hai thay đổi chính trong patch2:
1. **Smali deeplink logic** — Áp dụng logic reverse-engineer từ APK smali vào `TikTokDeepLinks`
2. **ConfigScreen UI redesign** — Thiết kế lại giao diện Settings với 4 tab, card layout, và tab TikTok App mới

---

## 1. Deeplink Logic (BUG-SETTINGS-003 / smali apply)

### Vấn đề

patch1 đã fix `setPackage()` nhưng chưa dùng đúng scheme từ APK gốc.
Reverse-engineer smali `<clinit>` của TikTok cho thấy:
- Scheme chính là `snssdk1233://setting` (**singular**, không phải `settings`)
- Các string này được **Base64-encode** trong APK để tránh string scan detection

### Thay đổi `TikTokDeepLinks.kt`

#### Anti-detection: Base64-decoded constants
```kotlin
// Mimics smali <clinit> — lazy decode tại runtime
private val d0: String by lazy {
    Base64.decode("c25zc2RrMTIzMzovL3NldHRpbmc=", Base64.DEFAULT)
        .toString(Charsets.UTF_8).trim()
    // → "snssdk1233://setting"
}
private val e0: String by lazy {
    Base64.decode("c25zc2RrNTY3NzUzOi8v", Base64.DEFAULT)
        .toString(Charsets.UTF_8).trim()
    // → "snssdk567753://"
}
```

#### FLAG_ACTIVITY_CLEAR_TOP (smali 0x14000000)
```kotlin
// Trước (patch1): FLAG_ACTIVITY_NEW_TASK only
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

// Sau (patch2): mirror APK gốc
private const val FLAGS_DEEP =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
// = 0x10000000 | 0x04000000 = 0x14000000
```

`CLEAR_TOP` đảm bảo: nếu Settings đã mở trong back-stack → bring to front + clear trên nó,
không tạo instance mới. Hành vi này khớp chính xác với APK gốc.

#### TikTok Studio support
```kotlin
fun openSettings(context: Context, isStudio: Boolean = false): Boolean {
    val targetPkg = if (isStudio) studioPkg() else pkg(context)
    val schemes = if (isStudio) listOf(
        "${e0}setting",              // snssdk567753://setting
        "$SCHEME_TIKTOK://settings",
    ) else listOf(
        d0,                          // snssdk1233://setting  ← smali exact
        "$SCHEME_TIKTOK://settings",
        "snssdk1233://settings",     // plural compat
        "musically://settings",
    )
    // ...
}
```

#### New: `openPrivacySettings()`
```kotlin
fun openPrivacySettings(context: Context, isStudio: Boolean = false): Boolean
```
Schemes: `snssdk1233://privacy` → `tiktok://privacy` → fallback `settings`

#### `postDelayed` note
Smali pattern sau khi fire Settings intent: `postDelayed(3500ms, step_0x15)`.
**Không implement ở đây** — AutomationEngine đã có delay sau `openSettings()`.

### Thay đổi `StringEncryptor.kt`

Thêm `TIKTOK_STUDIO_PKG` encrypted:
```kotlin
// "com.ss.android.tt.creator"
val TIKTOK_STUDIO_PKG: String by lazy {
    decrypt(intArrayOf(0x28,0x1d,0x54,0xa4,0x82,0x5f,0x59,0x85,0x33,0xf5,0xce,
        0x2c,0x22,0x16,0x17,0xfe,0x85,0x02,0x14,0x96,0x38,0xf0,0xc8,0x2c,0x39))
}
```

Update `AppConstants.tiktokStudioPackage`.

---

## 2. ConfigScreen UI Redesign

### Tab strip
- Trước: `TabRow` Material 3 mặc định (plain text tabs)
- Sau: Custom pill-style tab strip với icon + label, animated background

### Tab layout
| Tab | Trước | Sau |
|-----|-------|-----|
| Thời gian | Flat list | Cards với section headers + accent colors |
| Hành động | Flat list | Cards với section headers |
| Thông báo | Flat list | Cards, cleaner spacing |
| TikTok App | ❌ Không có | ✅ Mới — App selector + Quick actions + Deeplink info |

### Tab "TikTok App" (mới)
Components:
- **`AppVariantOption`** — chọn TikTok hoặc TikTok Studio, pill style với checkmark animated
- **`AppInfoCard`** — hiển thị package + version, highlighted khi đang dùng
- **`QuickActionButton`** — "Mở Settings TikTok", "Mở Quyền riêng tư", có scheme hint
- **Deeplink info table** — hiển thị package, scheme, flags hiện tại

### Top bar save button
- Trước: `TextButton("Lưu")` plain text
- Sau: Animated `Button` (filled) với icon Check, chỉ hiện khi `isDirty`, animate in/out

### Config persistence
`tiktok_app_variant` key → Room, load/save qua `ConfigViewModel`.

---

## Files changed

| File | Thay đổi |
|------|----------|
| `StringEncryptor.kt` | Thêm `TIKTOK_STUDIO_PKG`, `AppConstants.tiktokStudioPackage` |
| `TikTokDeepLinks.kt` | Base64 constants d0/e0/privacyUri, FLAGS_DEEP=0x14000000, Studio support, `openPrivacySettings()` |
| `ConfigScreen.kt` | Full redesign: 4 tabs, CfgCard, CfgSectionHeader, TikTok App tab, quick actions |
| `ConfigViewModel.kt` | `tikTokApp` + `tikTokStudioVersion` fields, Studio version detection, save/load |
| `build.gradle` | versionCode 3→4, v1.0.3-patch2 |
