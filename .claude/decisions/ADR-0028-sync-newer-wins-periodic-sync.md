# ADR-0028: Giải xung đột đồng bộ bằng "mới nhất thắng" và đồng bộ định kỳ

- Trạng thái: chấp nhận (v1.12.7) — chủ app xác nhận chính sách; CHƯA kiểm chứng bằng Gradle/thiết bị.
- Bối cảnh: `BackupMergeEngine` huỷ cả lần gộp khi trạng thái tài khoản, giá trị cấu hình hoặc định nghĩa lịch khác nhau
  (ADR-0024: "không tin mốc giờ thiết bị"). Máy cài mới / hai máy gần như luôn khác nhau → đồng bộ kẹt, thống kê và
  thu nhập không lên được. Chi tiết lỗi cũng bị nuốt nên không chẩn đoán được.

## Quyết định
1. **Thu nhập và thống kê giữ nguyên**: đã cộng dồn (nhật ký thu nhập theo UUID từng sự kiện, hợp theo UUID; thống kê theo
   phần đóng góp của từng máy). Không phải chính sách mới.
2. **Trạng thái tài khoản / cấu hình / lịch**: bản có dấu thời gian mới hơn thắng; bằng nhau thì đám mây thắng.
   - Dấu thời gian mỗi bản bị chặn ở `createdAt` của snapshot chứa nó (`newerWins`) — đồng hồ lệch/giả không thắng bằng
     mốc tương lai (giữ tinh thần ADR-0024, bỏ việc huỷ cả lần gộp).
   - Tài khoản: bên chưa từng sửa trạng thái (`statusUpdatedAt <= createdAt`, tức mặc định do nhập lại sau cài mới)
     không ghi đè bên đã sửa.
   - Lịch thắng từ đám mây được nhập ở trạng thái TẮT và huỷ báo thức cũ (như lịch đến từ máy khác).
3. **Giữ là xung đột** (vẫn huỷ lần gộp): cùng máy có hai bản khác nhau (`DUPLICATE_ENTITY_IDENTITY`), xoá/tạo lại cùng
   máy, tái dùng UUID tombstone, nội dung phiên/thu nhập khác nhau cùng UUID, vượt giới hạn.
4. **`BackupRestoreCoordinator`**: bản gộp được phép khác dữ liệu cục bộ; chỉ từ chối khi dữ liệu cục bộ MỚI HƠN bản gộp
   (máy này vừa sửa sau khi gộp → gộp lại ở lần sau).
5. **Đồng bộ định kỳ** 30 phút (cần mạng) qua `INPUT_FORCE_REMOTE_CHECK`; `Mutex` tiến trình thay cho việc dùng chung tên công việc.

## Hệ quả
- `ACCOUNT_STATUS`, `CONFIG_VALUE`, `SCHEDULE_DEFINITION` không còn được tạo ra (enum giữ để đọc kết quả cũ).
- Rủi ro: một lần sửa cũ hơn bị thua mà không báo; lịch có thể bị tắt sau khi đồng bộ; hướng dẫn "gộp theo ngày" cho thu nhập
  (giữ lâu hơn 25.000 dòng) chưa làm — cần đổi định dạng snapshot.
