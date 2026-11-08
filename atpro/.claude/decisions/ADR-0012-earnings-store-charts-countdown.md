# ADR-0012 — Số liệu kiếm tiền ghi cục bộ, biểu đồ tự vẽ, thanh đếm ngược (v1.10.0)

## Bối cảnh
Thống kê chỉ có hoạt động nuôi acc (Room `sessions`); thu nhập nhiệm vụ chỉ có trên server Follow4Follow. Cần biểu đồ chuyên sâu về tiền mà không đổi schema Room (không có cách chạy test migration trong sandbox) và không thêm thư viện.

## Quyết định
1. **`EarningsStore`**: mỗi job hoàn thành ghi một dòng `thờiĐiểm|LOẠI|xu|tàiKhoản` vào `filesDir/earnings.log` (nối thêm, tự cắt ở 30.000 dòng). `record()` là no-op nếu chưa `init()` và không bao giờ ném lỗi. Tổng hợp bằng hàm thuần `summarizeEarnings()` (kiểm thử bằng JVM thường).
2. **Số liệu server bù lịch sử**: tab Kiếm tiền hiện cả xu chờ / làm hôm nay / tổng từ F4F (`walletNumbers()` gộp dashboard + earn-stats, lấy giá trị lớn hơn vì mỗi nguồn có thể thiếu).
3. **Biểu đồ tự vẽ bằng Canvas** (`StatsCharts.kt`): cột xếp chồng, vùng, donut, thanh xếp hạng; giá trị animation chỉ đọc trong phase vẽ.
4. **Thanh đếm ngược của popup là một animator thuộc tính** (`ObjectAnimator` scaleX), không đăng chữ mỗi giây; thu nhỏ/tạm dừng → huỷ animator, giữ số giây còn lại.
5. **Back về feed dựa vào thanh điều hướng dưới cùng**, không dựa vào `isOnFeedTab()` (quá khoan dung). Máy không lộ nhãn tab → giữ kiểm tra cũ để tránh Back thoát TikTok.
6. **Mỗi chức năng một màn hình riêng khi nội dung khác loại**: Quyền ≠ Thông báo; Ví F4F không có Giới thiệu.

## Hệ quả
- Dữ liệu thu nhập trên máy bắt đầu từ v1.10.0.
- `LocalRepository.log()` ghi theo lô → log mới nhất có thể trễ tới 1,2s trong màn Nhật ký (popup vẫn tức thời).
