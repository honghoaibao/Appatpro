# Revision: BUG-SETTINGS-002 — setPackage missing (v1.0.3)

**Date**: 2026-05-25
**Version**: v1.0.2 → v1.0.3

## Triệu chứng (vẫn lặp lại sau v1.0.2)

```
[12:37:16] ❌ Không mở được switch popup
[12:37:16] ❌ Không tìm thấy nút chuyển đổi tài khoản
[12:37:03] ⚙️ Mở Settings để đọc acc list...
```

## Root cause thực sự

v1.0.2 đã đổi sang deep link nhưng **thiếu `setPackage()`**:

```kotlin
// v1.0.2 — SAI (không có setPackage)
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tiktok://settings")).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
context.startActivity(intent)
```

Khi không có `setPackage`, Android xử lý đây là **Implicit Intent**:
- Nếu không có app nào đăng ký scheme `tiktok://` → `ActivityNotFoundException` thầm lặng → trả `false`
- Nếu có nhiều app → hiện chooser dialog (từ Accessibility Service không thể tương tác)
- Nếu trình duyệt đăng ký → mở browser

Kết quả: `startActivity()` throw exception trong catch block → `openSettings()` trả `false` →
engine vẫn log "❌ Không mở được switch popup" dù deep link đúng scheme.

## Fix

Thêm `setPackage(pkg(context))` — pin intent chính xác vào TikTok package:

```kotlin
// v1.0.3 — ĐÚNG
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
    setPackage(pkg)                      // ← Ép route tới TikTok, không phải browser
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

`setPackage()` buộc Android route tới `DeepLinkHandlerActivity` (exported=true) bên trong TikTok.
Activity đó kiểm tra path `settings` rồi tự gọi `TikTokSettingActivity` từ nội bộ — hợp lệ vì
cùng process, không bị `exported=false` chặn.

## Schemes thử theo thứ tự

| # | Scheme | Ghi chú |
|---|--------|---------|
| 1 | `tiktok://settings` | Scheme chính, mọi phiên bản quốc tế |
| 2 | `snssdk1233://settings` | Build khu vực (SEA, một số phiên bản cũ) |
| 3 | `musically://settings` | Alias TikTok cũ trước khi đổi tên |

Tất cả đều kèm `setPackage(pkg)`.

## Files changed

| File | Thay đổi |
|------|----------|
| `TikTokDeepLinks.kt` | `openSettings()` — thêm `setPackage`, gom 3 schemes vào loop, bỏ component-name attempt |
| `build.gradle` | versionCode 2→3, v1.0.2→v1.0.3 |
| `.github/workflows/build.yml` | default 1.0.2→1.0.3 |
| `README.md` | Header, changelog |
