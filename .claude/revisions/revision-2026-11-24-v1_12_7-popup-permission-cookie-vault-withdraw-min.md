# Revision v1.12.7 — popup xin quyền, kho cookie FB/IG, rút tối thiểu, màn đăng nhập (2026-11-24)

> Trạng thái: **chưa build, chưa chạy test** (môi trường soạn thảo không tải được Maven/Android SDK). Cần chạy
> `./gradlew testDebugUnitTest` + kiểm tra trên máy thật trước khi phát hành. Chưa tăng `versionName` (vẫn v1.12.6).

## 1. Popup xin quyền ("Cho phép TikTok truy cập email/bạn bè Facebook?")
- Nguyên nhân: whitelist nút đóng không có "Không cho phép" → `scannerDismiss` bỏ qua → không đọc được @username.
- `ScanKeywords.denyButtons` + `permissionContext`; `ContentScanner.isPermissionPrompt` / `dismissLabelsFor`.
  Nút từ chối CHỈ được bấm khi màn hình có chữ ngữ cảnh xin quyền. Không bao giờ bấm OK / Cho phép.
- `AutomationEngine.detectCurrentAccount`: quét popup (`sweep`) trước khi đọc lại tên tài khoản ở Hồ sơ.
- Test: `ContentScannerTest` (nhóm "v1.12.7").

## 2. Giao diện
- `IdleView`: cuộn được + `imePadding()` (bàn phím không còn che ô nhập danh sách); thẻ hero gọn hơn cho FB/IG;
  bỏ khung cuộn lồng nhau của panel cookie.
- `AuthScreen` (Supabase): logo thật, chuyển Đăng nhập/Đăng ký bằng `AtSegmented` ở đầu thẻ.
- `F4fLoginDialog`: không gửi khi còn trống (nút Done), `username.trim()`, ghi chú mật khẩu không lưu.

## 3. Rút tiền F4F
- `F4fWithdrawLimits`: tối thiểu 50.000 coin = 50.000đ, mọi kênh. **Giả định (chủ app xác nhận): 1 coin = 1 VND**;
  chưa xác minh với server. Kiểm tra trong `F4fWithdrawRequest.validate()` (chặn trước khi chạm mạng) + gợi ý ở UI.
- `F4fServiceTest`: các test cũ nâng số tiền lên 50.000; thêm test hạn mức.

## 4. Kho cookie FB/IG (khung, CHƯA có bộ chạy thật)
- `CookieVault` (Keystore AES-256-GCM, alias `com.atpro.cookies.v1`, prefs `atpro_cookie_vault_v1`) + `CookieVaultCodec` (thuần, test JVM).
- Chỉ lưu cookie đủ khoá; UI chỉ thấy ID tài khoản. KHÔNG nằm trong `BackupConfigPolicy`, không log.
- `CookieDemo.parsePairs` tách ra từ `parse` (không đổi hành vi).
- TODO: gộp mã hoá dùng chung với `AndroidKeystoreAuthSessionStore` sau khi cả hai kiểm chứng trên máy.

## 5. Đồng bộ Supabase — quyết định
- Danh sách tài khoản nhập tay nay được LƯU CỤC BỘ (Room, khoá `custom_accounts`, giới hạn 20.000 ký tự).
- **Cố ý KHÔNG đưa vào `BackupConfigPolicy`**: merge hiện coi hai giá trị khác nhau của cùng khoá là `CONFIG_VALUE`
  conflict và `BackupCasResult.Conflicted` huỷ cả lần merge → khoá hay đổi có thể kẹt toàn bộ sao lưu.
  Muốn sync cần merge theo hợp tập + kiểm chứng trên máy (RT-03→06).
- FarmConfig đã được sync đủ khoá (chỉ loại notif_* và `discord_webhook_url` — webhook là bí mật).
- Đồng bộ tự động hiện chỉ chạy theo sự kiện (có thay đổi chờ gửi) + "Đồng bộ ngay" thủ công (vòng đọc–gộp–gửi, kéo dữ liệu
  máy khác về). Chưa có kéo định kỳ; `SyncStatusCodes.INPUT_FORCE_REMOTE_CHECK` đã khai báo nhưng chưa được dùng ở đâu.
  (Ghi chú sửa: bản đầu của mục này nói "không có đường chỉ kéo" — chưa chính xác, đã đính chính.)

## 6. Sửa lỗi build (có sẵn trên `main`, không phải do v1.12.7)
- `SyncWorker.kt:151`: `when` trên `BackupCasResult` thiếu nhánh `UpToDate` (nhánh này được thêm ở `BackupCasCoordinator`
  nhưng worker chưa xử lý; build `main` ngày 16/11 cũng fail vì lý do này).
- Xử lý: `UpToDate` → `completed(NO_PENDING_DATA)` (outcome UP_TO_DATE: không có thay đổi chờ gửi, đã khớp server, không tạo revision).
