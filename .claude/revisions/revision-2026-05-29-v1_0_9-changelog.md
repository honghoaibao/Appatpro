# Changelog — AT PRO v1.0.9

_Date: 2026-05-29_
_versionCode: 10_

---

## 🆕 Tính năng mới

### 1. Điều khiển farm từ Overlay
Floating overlay popup được thiết kế lại hoàn toàn và bổ sung khả năng điều khiển trực tiếp:
- **Nút Tạm dừng / Tiếp tục** — không cần mở app để pause/resume
- **Nút Dừng** — dừng phiên farm ngay từ overlay
- Header compact hơn: logo + status gộp trên 1 hàng
- Account ID không còn hiển thị riêng — gộp vào dòng tài khoản
- Border + rounded corners đồng bộ với design của app chính

### 2. Cập nhật tự động trong app (OTA)
- Dialog thông báo phiên bản mới hiển thị trực tiếp trên Dashboard
- Tải APK về máy và mở installer — **không còn chuyển ra browser**
- Progress bar hiển thị % tải xuống theo thời gian thực
- Báo lỗi rõ ràng nếu tải thất bại
- Hỗ trợ yêu cầu quyền "Cài ứng dụng từ nguồn khác" (Android 8+) tự động

### 3. Startup Status Dialog
- Dialog trạng thái khởi động farm được thiết kế lại: gọn, hiện đại hơn
- Hiển thị phase khởi động (1–5) overlay trong suốt
- Tích hợp nút Tạm dừng / Tiếp tục / Dừng ngay trong dialog

---

## 🐛 Sửa lỗi

### Fix: Phát hiện nhầm popup chuyển tài khoản
**Vấn đề:** Feed TikTok có `@username` trong description/duet tag bị nhận nhầm là popup "Switch Account" → farm bị gián đoạn sai.

**Fix:** Chỉ xác nhận là switch popup khi đồng thời có switch container rõ ràng (`switch_account` / `account_list` / `user_list` / text "chuyển đổi tài khoản") **và** không đang ở feed tab.

---

## 🔧 Nội bộ

- Fix 4 KDoc comment dùng `[v1.0.9]` (bracket link reference không hợp lệ → build warning)
