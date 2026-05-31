# Revision — v1.1.3 — Popup Handler & Ad/Live Detection

**Date:** 2026-05-30  
**Version:** v1.1.3 (versionCode 13)

## Thay đổi

### NodeTraverser.kt
#### `detectLive()` — cải tiến 3 tầng
- **Tier 1 (Resource-ID):** Thêm 9 ID mới: `live_duration`, `live_viewer`, `live_viewers`,
  `live_anchor`, `live_host`, `live_close`, `live_exit`, `live_countdown`, `btn_exit_live`
- **Tier 2 (Strict signals — single-signal đủ):** Tách riêng strict signals khỏi soft signals.
  "đang trực tiếp" / "đang phát trực tiếp" một mình → return true ngay (không cần count >= 2)
- **Tier 3 (Soft signals — count >= 2):** "tặng quà", "người xem", "bình luận trực tiếp",
  "kết thúc phát sóng", "live now", "viewers"

#### `detectAd()` — MỚI
- Tier 1 (Resource-ID): skip button, badge, label, CTA button, container, countdown timer
- Tier 2 (Exact text node): "quảng cáo", "sponsored", "tài trợ", "bỏ qua quảng cáo", "skip ad"
  Dùng `exact=true` để tránh false-positive từ caption/hashtag

#### `PopupType` enum — thêm 2 type mới
- `SAVE_LOGIN` — popup lưu thông tin đăng nhập
- `PERMISSION_REQUEST` — popup cấp quyền hệ thống (dùng cho extension sau)

### PopupHandler.kt — Tier 2 keyword scan
#### Patterns mới (7 nhóm)
| Pattern | Action | Ghi chú |
|---------|--------|---------|
| "access your contacts", "truy cập danh bạ" | `deny_permission` | Quyền danh bạ |
| "access your email", "truy cập email" | `deny_permission` | Quyền email |
| "access your location", "truy cập vị trí" | `deny_permission` | Quyền vị trí |
| "access your microphone", "truy cập micro" | `deny_permission` | Quyền micro |
| "allow tiktok to access your camera" | `deny_permission` | Quyền camera |
| "save your login info", "lưu thông tin đăng nhập" | `save_login` | Click Save, không skip |
| "save password", "remember your password", "lưu mật khẩu" | `save_login` | Variants |
| "invite your friends", "mời bạn bè" | `dismiss` | Mời bạn bè |
| "update now", "update tiktok", "cập nhật ngay", "cập nhật ứng dụng" | `later` | Thêm variants cập nhật |
| "đánh giá ứng dụng" | `dismiss` | Rate app VN |

#### Actions mới trong `handleByKeyword()`
- `save_login`: click "lưu" / "save" / "có" / "yes" / "ok"
- `deny_permission`: click "không cho phép" / "từ chối" / "deny" / "don't allow" / "không"
- `later`: bổ sung "không phải bây giờ", "nhắc tôi sau" (đúng thứ tự ưu tiên)

### AutomationEngine.kt — Bước [5a] Skip ad
- Thêm bước `[5a] Skip ad` giữa [5] overlay update và [6] live skip
- Gọi `NodeTraverser.detectAd()` → swipeNext() + log + overlay update "⏭ Bỏ qua quảng cáo"

### android/app/build.gradle
- `versionCode`: 12 → **13**
- `versionName`: "v1.1.2" → **"v1.1.3"**

### .github/workflows/build.yml
- Fallback version (non-tag non-dispatch runs): "1.1.2" → **"1.1.3"**

## Không thay đổi
- Tier 1 popup detection (handle1234, FOLLOW_FRIENDS, GENERIC, ACCOUNT_SWITCH) — không đổi
- isOnFeedTab guard trong scanKeywords — giữ nguyên (permission dialogs khi popup thật
  sẽ thay thế window focus → isOnFeedTab = false → scan tiếp tục đúng)
