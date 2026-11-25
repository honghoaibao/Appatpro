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
- (Lịch sử) Trước mục 7, đồng bộ chỉ chạy theo sự kiện + "Đồng bộ ngay"; `INPUT_FORCE_REMOTE_CHECK` khai báo nhưng chưa dùng.
  Mục 7 bổ sung đồng bộ định kỳ. (Ghi chú sửa: bản đầu nói "không có đường chỉ kéo" — chưa chính xác, đã đính chính.)

## 6. Sửa lỗi build (có sẵn trên `main`, không phải do v1.12.7)
- `SyncWorker.kt:151`: `when` trên `BackupCasResult` thiếu nhánh `UpToDate` (nhánh này được thêm ở `BackupCasCoordinator`
  nhưng worker chưa xử lý; build `main` ngày 16/11 cũng fail vì lý do này).
- Xử lý: `UpToDate` → `completed(NO_PENDING_DATA)` (outcome UP_TO_DATE: không có thay đổi chờ gửi, đã khớp server, không tạo revision).

## 7. Sync: nhìn thấy nguyên nhân lỗi + đồng bộ tự động định kỳ + màn đăng nhập
**Vì sao**: người dùng báo không đồng bộ được thống kê/thu nhập/tài khoản nuôi và hay "xung đột dữ liệu cũ/mới". Nguyên nhân
thật chưa xác định được vì chi tiết lỗi bị nuốt (chỉ xung đột gộp mới có chi tiết; `LOCAL_SYNC_FAILED` bỏ hẳn ngoại lệ).
- `SyncFailureSummary` (mới, thuần): tóm tắt ≤320 ký tự cho lỗi máy chủ (`code` + HTTP), lý do bị từ chối, ngoại lệ cục bộ
  (tên lớp + thông điệp); che JWT / `Bearer …` / chuỗi ≥64 ký tự.
- `SyncOutcome.hasDetails` (MERGE_CONFLICT, REJECTED, FAILED): `SyncStatusStore` lưu/đọc chi tiết cho cả ba; lỗi tạm
  (`Result.retry()`) cũng hiện nguyên nhân gần nhất; `SyncStatusText.detail` hiển thị.
- `SyncWorker`: truyền chi tiết ở mọi nhánh lỗi; `INPUT_FORCE_REMOTE_CHECK` bỏ qua lối thoát "không có gì chờ gửi";
  `Mutex` tiến trình bảo đảm lần chạy định kỳ và theo sự kiện không chạy chồng.
- `SyncWorkScheduler.ensurePeriodic`: `PeriodicWorkRequest` 30 phút, cần mạng, `KEEP`; gọi khi đã đăng nhập (MainScreen);
  `cancel()` (đăng xuất) huỷ cả hai lịch.
- UI: `AtBranding.kt` (quầng sáng, huy hiệu, ô lợi ích); `AuthScreen` có hero + 3 lợi ích + thẻ đổ bóng;
  `F4fLoginDialog` tách file riêng, làm lại (huy hiệu, ô nhập có biểu tượng, ghi chú bảo mật, cuộn được khi bàn phím mở).
- Test: `SyncFailureSummaryTest` (mới), `SyncStatusTest` (+4).

**(Đã làm ở mục 8)**: tự giải quyết xung đột cũ/mới — bản đầu mục 7 ghi "chưa làm". Merge hiện huỷ cả lần khi có xung đột và 5 guard trong
`BackupRestoreCoordinator` cũng từ chối giá trị tự giải — đổi phải sửa cả hai + nhiều test và đảo chính sách "không tin mốc giờ
thiết bị". Cần: (a) văn bản trạng thái thật sau bản này, (b) chọn "mới nhất thắng" hay "đám mây thắng".

**Rollback**: revert commit; không đổi schema Room/Supabase, không đổi định dạng snapshot.
**Rủi ro**: chưa chạy được Gradle/test trong sandbox — cần CI; đồng bộ định kỳ tăng số lần gọi Supabase (≤ 2/giờ/máy khi không đổi).

## 8. Sync: giải xung đột "mới nhất thắng" (ADR-0028)
Chủ app xác nhận chính sách: thu nhập cộng dồn theo sự kiện (đã có sẵn), còn trạng thái tài khoản / cấu hình / lịch → bản mới hơn thắng, bằng nhau thì đám mây thắng.
- `BackupMergeEngine`: `newerWins` (chặn dấu thời gian ở `createdAt` snapshot); không còn tạo ACCOUNT_STATUS / CONFIG_VALUE / SCHEDULE_DEFINITION;
  tài khoản chưa từng sửa trạng thái không ghi đè bên đã sửa.
- `BackupRestoreCoordinator`: 3 điểm kiểm tra chỉ từ chối khi dữ liệu cục bộ mới hơn bản gộp; lịch thắng từ đám mây nhập TẮT + huỷ báo thức.
- Test: `BackupMergeEngineTest` — 3 test xung đột cũ thay bằng test chính sách mới (mới hơn thắng, hoà → đám mây, chặn mốc tương lai,
  cài mới không ghi đè). **Chưa chạy được** (sandbox không có Gradle).
- Ý tưởng chủ app (mỗi 0h một UUID mới cho thu nhập theo ngày): chưa làm — cần gộp thành tổng theo ngày/máy, đổi định dạng snapshot (format v4).
**Rollback**: revert commit (không đổi schema). **Rủi ro**: lịch bị tắt sau đồng bộ nếu đám mây thắng; một lần sửa cũ hơn bị thua âm thầm.
