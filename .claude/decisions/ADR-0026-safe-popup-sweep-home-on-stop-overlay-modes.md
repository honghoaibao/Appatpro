# ADR-0026 — Quét popup an toàn theo whitelist ở mọi mốc, dừng phải về Home, popup nổi 3 trạng thái (v1.12.5)

## Bối cảnh
- Popup của TikTok hiện ở bất kỳ bước nào (mở Cài đặt, mở danh sách tài khoản, feed vừa tải, trang job vừa mở) và có khi hiện **trễ**. Cơ chế cũ chỉ xử lý ở vài điểm cố định; Tier 1 GENERIC và Tier 2 (từ khoá) cố ý **bỏ qua feed** vì từng bấm Back nhầm (FIX-WAITFEED-POPUP, FIX-POPUP-FALSE-POS) → popup đè lên feed (vd "Liên kết email") không được đóng, và `waitFeedLoad()` không dọn popup nào.
- Nhận diện quảng cáo/live cũ khớp theo từ khoá rời rạc, bỏ sót nhãn gộp dòng ("Dola · Được tài trợ").
- Máy bị bỏ lại trong TikTok/Cài đặt khi tool dừng; mọi đường dừng đều đi qua `AutomationEngine.stopInternal()`.
- `openSwitchList()` vuốt thừa: vuốt nhanh gây trôi quán tính, và luôn căn hàng vào giữa dù bấm được ngay bằng ACTION_CLICK.

## Quyết định
1. **Tách "quét an toàn" khỏi "đoán popup".** `ContentScanner` chỉ bấm nút có chữ **nguyên văn nằm trong whitelist đóng** (mềm/cứng), không bao giờ bấm nút xác nhận, không Back, không suy đoán theo từ khoá. Vì vô hại trên màn hình thường nên dùng được ở Cài đặt và feed — nơi Tier 2 bị cấm.
2. **Quét theo "mốc" thay vì chạy nền song song.** `PopupHandler.sweep()` được gọi tuần tự trong cùng coroutine tại các mốc đã biết (xem `popup_ad_live_scan.md`) → không đua với thao tác đang làm. Từ chối thiết kế "sentinel" chạy nền: nó có thể đóng nhầm màn hình tool mở có chủ đích (bảng chuyển đổi tài khoản có nút X "Đóng").
3. **Màn hình tool mở có chủ đích được bảo vệ:** hộp đăng xuất (để engine huỷ) và bảng chuyển đổi tài khoản (chỉ nút đóng *mềm* được bấm). Chống lặp vòng dùng lại `PopupLoopGuard`.
4. **Quảng cáo/live: hợp của quét mới và tầng cũ** (không thay thế) — chỉ tăng độ phủ; luật nhãn chỉ xét chữ ngắn (≤ 60) để caption dài không kích hoạt.
5. **Về Home ở `stopInternal()`**, đúng một lần cho mỗi phiên (`wasRunning`), qua `IFarmHost.pressHome()` (mặc định false để host giả trong test không vỡ). Chấp nhận hệ quả: bấm Dừng trong chính app cũng ra màn hình chính. Để "tự tắt do lỗi" cũng đi qua đường này, bước nạp cấu hình/mở popup của 7 hàm `start*` được đưa vào trong `try`, và `onUnbind` dừng engine trước khi huỷ scope.
6. **Vuốt tìm nút chuyển đổi tài khoản: ngắn + chậm + dò liên tục; hàng bấm được bằng ACTION_CLICK thì không căn giữa** (0 cú vuốt); chỉ bấm theo toạ độ mới nhích tối đa 1 lần.
7. **Popup nổi: một cửa sổ, 3 trạng thái** (Popup / Mini / Chip) giữ nguyên API công khai; dữ liệu nhật ký giữ khi thu nhỏ, View dựng lại khi mở.

## Hệ quả
- Thêm ~1,3 giây mỗi lần `waitFeedLoad()` thành công (quét trễ) và mỗi trang job; đổi lại bắt được popup hiện trễ.
- Whitelist là điểm cần bảo trì khi TikTok đổi chữ (sửa trong `ScanKeywords.DEFAULT`; thêm test). Cố ý **không** thêm "OK/Đồng ý/Cho phép".
- Popup chỉ có nút xác nhận (không có nút đóng) vẫn do Tier 1/2 và recovery xử lý như cũ.
- `IFarmHost` có thêm một hàm (mặc định) — host mock nghiêm ngặt trong test cần stub `pressHome()` nếu muốn verify.
