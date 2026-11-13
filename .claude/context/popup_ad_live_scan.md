# Quét popup · quảng cáo · live (v1.12.5)

Tài liệu tham chiếu cho cơ chế quét màn hình TikTok. Nguồn thiết kế: "Hướng dẫn xây tool bỏ qua quảng cáo & livestream" (`huong-dan-ai-tiktok-auto-skip.md`, mục 5–6.5). Lý do quyết định: ADR-0026. Chi tiết thay đổi: `revision/v1.12.5.md`.

## Thành phần
| File | Vai trò |
|------|---------|
| `accessibility/ContentScanner.kt` | Phân loại một lần quét → `POPUP / SPONSORED / APP_INSTALL / LIVE / CLEAN`; bấm nút đóng theo whitelist. Phần `classify()` thuần Kotlin, có test. |
| `accessibility/NodeTraverser.kt` | `detectAd()` / `detectLive()` = quét `ContentScanner` (Tier −1) **hoặc** các tầng cũ. |
| `automation/popup/PopupHandler.kt` | `handleIfPresent()` (Tier 1 → **1.5** → 2) và `sweep()` (chỉ quét an toàn, tại các mốc). |
| `automation/popup/PopupLoopGuard.kt` | Popup lì: lần 3 → Back, lần 6 → bỏ cuộc (cửa sổ 30 giây). |

## Luật chấm điểm
R0 nút đóng bấm được (≤ 20 ký tự, nguyên chữ ∈ whitelist) → POPUP ngay · R1 chứa `được tài trợ/sponsored/promoted` +2 · R2 `nhạc quảng cáo` +1 · R3 nguyên chữ `đang live/nhấn để xem live/…` +2 · R4 nguyên chữ `live/trực tiếp/live now` +1 · R5 nút `nhận/cài đặt/install…` (bấm được, ≤ 18 ký tự) = tín hiệu cài app.
Quyết định: POPUP › sponsored ≥ 2 (+R5 → APP_INSTALL) › live ≥ 2 › CLEAN. R1/R2 chỉ xét chữ ≤ 60 ký tự.

## Quy tắc bất di bất dịch
1. Whitelist **không chứa** OK / Đồng ý / Cho phép / Xác nhận. Popup "Liên kết email": bấm "Để sau".
2. Khi bảng **chuyển đổi tài khoản** đang hiện: chỉ nút đóng *mềm* (`để sau`, `not now`, `bỏ qua`…); tuyệt đối không bấm `đóng`/`hủy` (đó là nút X của bảng).
3. Khi hộp xác nhận **đăng xuất** đang hiện: `scannerDismiss` không làm gì (engine tự huỷ).
4. `sweep()` không bao giờ Back và không dùng từ khoá đoán — chỉ whitelist. Dùng `handleIfPresent()` (có Tier 2) chỉ ở nơi đã an toàn như vòng chính trên feed.
5. Quét tuần tự trong cùng coroutine tại các mốc; không chạy nền song song.

## Các mốc gọi `sweep()`
Vừa mở Cài đặt · mỗi lần không thấy hàng "Chuyển đổi tài khoản" trong lúc cuộn · vừa mở danh sách tài khoản · feed vừa tải xong (+ quét trễ 1,2s) · khi Back về feed · trang nhiệm vụ vừa tải (+ quét trễ). Vòng chính (nuôi acc / nhiệm vụ) vẫn gọi `handleIfPresent()` mỗi lượt.

## Thêm / sửa từ khoá
Sửa `ScanKeywords.DEFAULT`; thêm test trong `ContentScannerTest` (cả ca đúng lẫn ca "không được nhận nhầm"). Chữ nút đóng viết thường, bỏ khoảng trắng thừa (`normalize`). Nhớ cả hai kiểu dấu hỏi: `hủy` / `huỷ`.

## Gỡ lỗi
Log `POPUP: Đóng popup — bấm '…'` (hiện ở popup nổi + Nhật ký); `POPUP: <mốc> — đã đóng N popup` (dòng chi tiết). Popup lì: `WARN: Popup '…' lặp lại nhiều lần — đổi sang bấm Back`.
