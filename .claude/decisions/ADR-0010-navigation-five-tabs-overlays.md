# ADR-0010: Điều hướng 5 tab + màn hình phủ; Cài đặt một cấp; Nhật ký thành tab riêng

**Date**: 2026-10-31
**Status**: Accepted

## Context

Bố cục v1.7.0: 5 tab (Tổng quan · Dịch vụ · Thống kê · Tài khoản · Lịch), Cài đặt chỉ vào được qua biểu tượng bánh răng ở Tổng quan và là **hai tầng tab** (tab nhóm → chip mục), Nhật ký — công cụ theo dõi quan trọng nhất khi chạy tự động — bị chôn trong Cài đặt → Hệ thống → Nhật ký (ảnh chụp thực tế: hai hàng tab chiếm ~40% màn hình trước khi tới nội dung; nhãn tab bị cắt). Mỗi màn hình tự vẽ tiêu đề riêng.

## Decision

1. **Thanh dưới 5 tab theo mức độ dùng hằng ngày:** Tổng quan (bắt đầu/dừng) · Dịch vụ (chọn việc + Ví F4F) · Tài khoản · **Nhật ký** · **Cài đặt**.
2. **Thống kê và Lịch tự động là công cụ phụ** → màn hình phủ (trượt từ dưới lên, Back hệ thống đóng) mở từ lối tắt dưới nút Bắt đầu ở Tổng quan và từ tab Cài đặt. Nhờ vậy tham số `onNavigateUp` của các màn hình này được dùng thật (nút quay lại).
3. **Tab Cài đặt = danh mục phẳng một cấp** (`SettingsHubScreen`): Giao diện Sáng/Tối chỉnh tại chỗ · Công cụ (Thống kê, Lịch, Ví) · Cấu hình (mỗi hàng mở thẳng một mục) · Ứng dụng. `ConfigActivity` nhận `EXTRA_SECTION` → `ConfigScreen(initialSection)` chỉ hiện các mục **cùng nhóm** (bỏ tầng tab nhóm). Mở không kèm extra (đường cũ) vẫn dùng đủ hai tầng.
4. Mục "Nhật ký" bị gỡ khỏi `ConfigScreen`; `LogsScreen` dùng `LogsViewModel` tạo ở `DashboardActivity`.
5. **Một thanh tiêu đề chung** `AtTopBar(title, subtitle, onBack, actions)` cho Thống kê, Tài khoản, Nhật ký (và Lịch qua `TopAppBar` có `navigationIcon`) — cỡ chữ/khoảng cách thống nhất; `onBack = null` khi màn hình nằm trong tab.
6. Biểu tượng bánh răng ở Tổng quan bị bỏ (đã có tab Cài đặt).

## Consequences

- (+) Theo dõi (Nhật ký) và cấu hình (Cài đặt) ở một chạm; Cài đặt không còn hai tầng tab; mọi màn hình cùng một kiểu tiêu đề.
- (−) Thống kê/Lịch cần thêm một chạm (từ Tổng quan hoặc Cài đặt) so với trước. Chấp nhận vì dùng ít hơn Nhật ký/Tài khoản.
- (−) Chưa nhìn bố cục thật trong sandbox — cần QA trực quan trên máy.
