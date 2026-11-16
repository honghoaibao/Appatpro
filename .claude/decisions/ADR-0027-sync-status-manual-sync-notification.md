# ADR-0027: Trạng thái đồng bộ, đồng bộ thủ công và nhóm thông báo Đồng bộ

- Trạng thái: chấp nhận (v1.12.6)
- Bối cảnh: `SyncWorker` (ADR-0018…0024) chạy nền nhưng người dùng không thấy kết quả; máy mới không có thay đổi chờ gửi thì không bao giờ kéo dữ liệu về; sự cố (hết phiên, đổi chủ dữ liệu, xung đột) chỉ nằm trong mã trả về của worker.

## Quyết định
1. **Trạng thái hiển thị tách khỏi logic đồng bộ.** `SyncStatusCodes` + `SyncOutcome` (thuần Kotlin) là hợp đồng giữa worker và giao diện. Worker không biết giao diện; `SyncStatusStore` chỉ lưu kết quả, mốc thời gian, số thay đổi chờ. Lỗi tạm (`Result.retry()`) là cờ `retrying`, không phải một `SyncOutcome` — kết quả cuối cùng đã biết được giữ nguyên.
2. **\"Đồng bộ ngay\" dùng chung tên công việc với lần chạy nền** (`atpro_backup_sync`). Đang RUNNING thì từ chối; đang chờ debounce thì `REPLACE`. Hai vòng đọc–gộp–gửi không bao giờ chạy song song (giữ bất biến của ADR-0023/0024). `manual=true` bỏ qua bước thoát sớm \"không có gì chờ gửi\"; vẫn đi qua đủ kiểm tra chủ sở hữu, CAS và acknowledge. Mỗi vòng thành công tăng revision máy chủ nên giới hạn ≥ 30 giây giữa hai lần bấm.
3. **Thông báo chỉ cho việc cần người dùng làm.** Chạy nền báo khi *vào* trạng thái `needsAttention` và một lần khi hết sự cố; cùng loại sự cố nhắc lại sau ≥ 6 giờ. Bấm tay luôn có phản hồi. Thành công im lặng, tự gỡ sau 20 giây. Tuân theo công tắc nhóm và giờ yên tĩnh của hệ thống thông báo (ADR-0014).
4. **Đăng xuất bỏ trạng thái và lần chạy đang chờ** của tài khoản cũ, chạy ở `appScope`.

## Hệ quả
- Không đổi hợp đồng bảo mật server (RLS, `save_user_backup`, chặn đổi chủ).
- Thêm một nhóm thông báo/kênh/công tắc (`notif_sync`).
- Giao diện không còn khẳng định \"đồng bộ chưa bật\".
- Chưa đối chiếu được với dự án Supabase thật (connector chưa hoàn tất kết nối) — xem `revision/v1.12.6.md`.
