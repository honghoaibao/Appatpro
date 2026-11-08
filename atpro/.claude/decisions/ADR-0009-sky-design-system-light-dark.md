# ADR-0009: Hệ thiết kế "Sky" — bảng màu xanh da trời · trắng · xanh dương · đen, hai chế độ Sáng/Tối

**Date**: 2026-10-30
**Status**: Accepted

## Context

v1.5.0 gom giao diện về `AtTheme` nhưng chỉ một bảng màu tối (đen/xám + chữ trắng) — người dùng đánh giá "đen trắng, đơn sơ" và yêu cầu bộ màu **xanh da trời, trắng, xanh dương, đen**, nâng cấp từ UI lớn tới nhỏ (popup, cài đặt…).
Các màn hình khai báo bí danh màu cấp file (`private val TextPrim = AtColors.TextPrim`) nên màu bị đóng băng lúc nạp class — không thể đổi theo chế độ lúc chạy.

## Decision

1. **Hai chế độ từ cùng một bảng màu gốc** (`AtPalettes.Light/Dark`): Sáng = nền trắng ngả xanh nhạt, thẻ trắng, chữ đen, nhấn xanh dương, điểm xuyết xanh da trời; Tối = nền đen ngả xanh, chữ trắng, nhấn xanh dương sáng. Mặc định **Sáng**; lưu `SharedPreferences("at_ui")`; chuyển ngay ở Cài đặt → Giao diện.
2. `AtColors` là các **getter** đọc `AtThemeState.palette` (state Compose) → đổi chế độ vẽ lại toàn UI. Mọi bí danh cấp file chuyển thành `private val X get() = AtColors.Y` (84 chỗ). **Quy tắc:** không bao giờ sao chép `AtColors.*` vào `val` cấp file / enum / bảng khởi tạo một lần; cần màu "theo tông" thì giữ lambda/enum tông (`LogTone`, `Section.accentOf`).
3. Màu `Blue` (nền nút/chip — chữ trắng đọc rõ ở cả hai chế độ) tách khỏi `Accent` (chữ/biểu tượng nhấn trên bề mặt, sáng hơn ở chế độ tối). Gradient thương hiệu `AtBrush.Hero` (xanh dương → xanh da trời) chỉ dùng cho thẻ nổi bật (số dư, Dashboard) — MỘT điểm nhấn mỗi màn hình.
4. **Thành phần chuẩn thay mọi chỗ tự làm:** `AtDialog` (thay `AlertDialog`), `AtTextField`, `AtChip` (thay `FilterChip`), `AtSwitch`, `AtBadge`, `AtListRow`, `AtEmptyState`, `AtStatTile`, `AtHeroCard`; `AtCard`/`AtSegmented` có bóng xanh nhẹ ở chế độ Sáng.
5. Màu thương hiệu mạng xã hội (TikTok, Facebook, …) giữ cố định. Overlay nổi trên TikTok và Splash **không** đổi theo chế độ (luôn tối / luôn gradient thương hiệu) vì cần đọc rõ trên mọi nền.
6. `AtTheme` đồng bộ biểu tượng thanh trạng thái/điều hướng (sáng/tối) và nền cửa sổ; `AtWindowBackground` đặt nền cửa sổ ngay khi Activity tạo để không nháy `bg_dark`.

## Consequences

- (+) Một nguồn màu; đổi chế độ tức thì; thêm chế độ/bảng màu mới chỉ cần thêm `AtPalette`.
- (+) Tương phản đã tính WCAG cho các cặp chữ/nền chính ở cả hai chế độ (≥ 4.4 : 1 mọi trường hợp; chi tiết `revision/v1.7.0.md`).
- (−) Chưa có chế độ "Theo hệ thống" (cần ghi state trong composition); thêm sau nếu cần.
- (−) Chưa nhìn được giao diện thật trong sandbox — cần QA trực quan trên máy (đặc biệt chữ trắng trên gradient, biểu tượng thanh hệ thống).

## Alternatives considered

- Chỉ đổi giá trị `AtColors` (một chế độ tối xanh): ít rủi ro nhưng không có chế độ "trắng" như bảng màu yêu cầu.
- `CompositionLocal` palette: đúng chuẩn Compose nhưng đòi sửa mọi chỗ truy cập màu thành `@Composable` — quá rủi ro khi không có trình biên dịch Compose trong sandbox.
