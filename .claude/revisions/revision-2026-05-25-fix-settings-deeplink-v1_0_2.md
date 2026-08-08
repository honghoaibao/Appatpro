# Revision: BUG-SETTINGS-001 — Fix openSettings deep link (v1.0.2)

**Date**: 2026-05-25
**Version**: v1.0.1 → v1.0.2

## Triệu chứng

```
[12:00:58] ❌ Không tìm thấy nút chuyển đổi tài khoản
[12:00:58] ❌ Không mở được switch popup
[12:00:46] ⚙️ Mở Settings để đọc acc list...
```

## Root cause

`TikTokDeepLinks.openSettings()` dùng `setClassName(pkg, ACTIVITY_SETTING)` để mở trực tiếp
`SettingContainerActivity`. TikTok đặt `android:exported="false"` cho Activity này →
Android ném `SecurityException` → hàm trả `false` → engine không vào được Settings →
không tìm thấy nút chuyển tài khoản.

## Fix

`TikTokDeepLinks.kt` — `openSettings()` đổi thứ tự ưu tiên:

| Attempt | Method | Lý do |
|---------|--------|-------|
| 1 | `tiktok://settings` (deep link) | Hoạt động mọi vùng/phiên bản hiện đại |
| 2 | `snssdk1233://settings` | Fallback một số build khu vực cũ |
| 3 | `setClassName(ACTIVITY_SETTING)` | Last resort — fail nếu exported=false |

## Files changed

| File | Thay đổi |
|------|----------|
| `android/app/src/main/kotlin/com/atpro/data/TikTokDeepLinks.kt` | Rewrite `openSettings()` — deep link first |
| `android/app/build.gradle` | versionCode 1→2, versionName v1.0.1→v1.0.2 |
| `.github/workflows/build.yml` | default version 1.0.1→1.0.2 |
| `README.md` | Header, examples, changelog |
