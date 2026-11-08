# ADR-0011 — Gom UI một lối vào, bỏ màn hình/khung chết (v1.9.0)

## Bối cảnh
Sau ADR-0010 (5 tab + màn hình phủ) vẫn còn: bốn `*Activity` đứng riêng không ai gọi, `SplashActivity` chưa từng có trong manifest, `PermissionScreen` không ai gọi, và nhiều khung trùng chức năng (hộp thoại khởi động + popup nổi; lối tắt Dashboard + hàng Công cụ; Ví ở Dịch vụ + Cài đặt; "Giao diện" ở hub + trong ConfigScreen).

## Quyết định
1. **Mỗi chức năng đúng một lối vào.** Thống kê/Lịch: chỉ từ tab Cài đặt → Công cụ. Ví F4F: chỉ từ thẻ ở tab Dịch vụ. Sáng/Tối: chỉ ở tab Cài đặt. Trạng thái khi đang chạy: popup nổi + một dòng trong màn hình đang chạy (không hộp thoại chặn).
2. **Mã không có đường vào thì xoá**, không để "phòng khi cần" (lấy lại từ git khi cần).
3. **Mọi màn hình dùng `AtTopBar`/`AtButton`**; không còn `TopAppBar` Material riêng.
4. Popup nổi (`OverlayFarmMonitor`) là nơi duy nhất hiển thị nhật ký thời gian thực; luôn tối (ADR-0009). Mỗi chế độ (nuôi acc / nhiệm vụ) chỉ hiện khối tóm tắt của mình.

## Hệ quả
- `ConfigActivity` luôn mở kèm tên mục → không còn tầng tab nhóm; chỉ chip các mục cùng nhóm.
- Dashboard ở chế độ nhiệm vụ đo tiến độ theo job (`LiveFarmStats.jobsDone/jobsTarget`), không theo acc.
