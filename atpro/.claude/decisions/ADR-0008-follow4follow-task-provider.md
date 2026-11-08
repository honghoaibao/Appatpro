# ADR-0008: Follow4Follow là nhà cung cấp nhiệm vụ đầu tiên của `TaskProvider`

**Date**: 2026-10-29
**Status**: Accepted

## Context

v1.4.0 gỡ Golike (REST + WebView) và để lại lớp trừu tượng `TaskProvider` chưa có triển khai (`TASKS_ENABLED = false`).
Tài liệu Follow4Follow đầu tiên nhận được là API kiểu SMM panel (mua đơn) — không khớp. Bộ tài liệu mới (API reference v2,
82 endpoint, công cụ `f4f.py` đã field-test) mô tả đầy đủ phía **kiếm coin**: đăng nhập, acc liên kết, `earn-coin`,
`complete-job`, `skip-job`, `mark-start`. Mọi request phải ký HMAC-SHA256 (`x-ts`/`x-sign`).

## Decision

1. Triển khai `Follow4FollowProvider : TaskProvider` trong `com.atpro.tasks.f4f`, **gọi API thuần HTTP** (không WebView, không giả lập web) bằng `HttpURLConnection` — không thêm thư viện mạng mới.
2. Tách tầng: `F4fSigner` (ký) → `F4fTransport` (truyền tải, thay được trong test) → `F4fApiClient` (header/envelope/đồng hồ) → `Follow4FollowProvider` (nghiệp vụ). `F4fAuthManager` lo đăng nhập + phiên + `StateFlow` cho UI.
3. Mở rộng `TaskProvider` tối thiểu, tương thích engine: `nextJob(account, kinds)`, `onJobOpening()` (mark-start + mốc 5s), `noJobReason()`, và `TaskAccount.providerData`.
4. Đăng nhập **trong app**; chỉ lưu token (qua `StringEncryptor`), **không lưu mật khẩu**. Token hết hạn (401) → xoá phiên, engine dừng với `isAuthError`.
5. Hành vi engine: sau 429 ở `complete` → chờ rồi báo lại 1 lần; lỗi nghiệp vụ → `skip` để nhả job.
6. Bật `TASKS_ENABLED = true`, giữ làm cờ ẩn/hiện toàn bộ tính năng.

## Consequences

- (+) Không phụ thuộc WebView/JS; logic chính test được offline (55 test, transport giả).
- (+) Thêm nhà cung cấp khác = thêm một `TaskProvider`, không sửa engine.
- (−) Khoá ký là hằng số công khai trong bundle web: site đổi bundle → phải cập nhật `F4F_SIGN_KEY` (cách tìm: `API_reference_v2.txt` mục 6).
- (−) Token chỉ được che (XOR) chứ chưa mã hoá bằng Keystore. Nâng mức bảo vệ cần ADR riêng.
- (−) Chưa có luồng liên kết acc TikTok vào F4F trong app (làm trên web); `report-job` chưa nối vào engine.

## Alternatives considered

- **Mô phỏng web bằng WebView** (như Golike v1.3.3): dễ vỡ, nặng, bị phát hiện bot — đã bỏ ở v1.4.0.
- **Thêm OkHttp/Retrofit**: không cần — 6 endpoint, JSON nhỏ; `HttpURLConnection` + `kotlinx.serialization` có sẵn là đủ.
