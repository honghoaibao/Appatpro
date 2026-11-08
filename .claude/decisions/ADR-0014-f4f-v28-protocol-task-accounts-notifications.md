# ADR-0014 — Port giao thức `f4f.py` v2.8, chọn tài khoản nhiệm vụ riêng, thông báo theo nhóm (v1.12.0)

## Bối cảnh
`f4f.py` v2.7/v2.8 đo lại build web mới của Follow4Follow: không gọi `earn-coin` khi chưa biết acc, đổi acc là phía client (`uid` + `refresh`), `POST quick-switch` cũ bị từ chối, danh sách acc lấy từ `GET quick-switch`, token chết sau vài giờ. App theo giao thức cũ nên đổi acc dễ lỗi. Đồng thời lựa chọn tài khoản nhiệm vụ dùng chung ô gõ tay của chế độ Nuôi và thông báo chỉ phục vụ chế độ nuôi.

## Quyết định
1. **Port hành vi của `f4f.py`, không chạy Python trong app.** `f4f.py` là bản tham chiếu hành vi (đã field-test); Kotlin mirror từng bước (`earnOnce`, `forceActivate` S1–S5, V2). Tài liệu + bản `f4f.py` đi cùng repo (`.claude/context/follow4follow/`).
2. **Provider không bao giờ dùng jobs của acc khác `accountActive`** và dừng thang khi 401/mất mạng — an toàn hơn tốc độ.
3. **Phát trace từng bước** qua `TaskProvider.setTraceListener` (mặc định no-op) thay vì cho provider biết về logger của engine; trace không chứa token. Engine ghi dưới tag `F4F` (dòng chi tiết).
4. **Không lưu mật khẩu để tự đăng nhập lại khi 401** (khác `f4f.py` opt-in): giữ ràng buộc `constraints.md`. 401 → dừng cả lượt + thông báo. Muốn đổi cần ADR bảo mật.
5. **Lựa chọn tài khoản nhiệm vụ tách khỏi Nuôi TikTok** (`task_account_mode`/`task_selected_accounts`); luật ở hàm thuần `TaskAccountSelector` (test được); UI chọn từ danh sách F4F (không gõ tay); engine báo lý do bỏ acc.
6. **Thông báo theo nhóm**: lõi quyết định/câu chữ thuần Kotlin (`NotificationPolicy`), vỏ Android mỏng (`AtProNotificationManager`). Giờ yên tĩnh làm im lặng chứ không xoá thông báo; cảnh báo chống lặp 10 phút. Kênh cảnh báo đổi ID (`atpro_alert2`) vì Android không cho nâng mức quan trọng của kênh đã tạo.

## Hệ quả
- Danh sách "gõ tay" ở Dashboard không còn tác dụng cho nhiệm vụ (mặc định Tự động).
- `Follow4FollowProviderTest` gắn với giao thức v2.8; khi Follow4Follow đổi giao thức nữa cần cập nhật `f4f.py` + test + `Follow4FollowAPI_Documentation.md` cùng lúc.
- Người dùng nâng cấp mất tuỳ chọn riêng của kênh `atpro_alert` (đã xoá) — cần chỉnh lại trên kênh "Cảnh báo".
