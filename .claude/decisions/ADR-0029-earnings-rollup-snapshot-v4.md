# ADR-0029: Nén thu nhập cũ thành dòng tổng (snapshot v4)

- Trạng thái: chấp nhận (v1.13.0) — chủ app xác nhận hướng, cửa sổ dòng thô 60 ngày và nhánh `v1.13.0-earnings-rollup`.
  CHƯA kiểm chứng bằng Gradle/thiết bị (sandbox không chạy được).
- Bối cảnh: thu nhập là nhật ký từng sự kiện (UUID), giữ tối đa 25.000 dòng; vượt thì cắt dòng cũ nhất → mất tổng
  "Tất cả" và tổng theo tài khoản. Biểu đồ chỉ dùng ≤ 60 ngày gần nhất.

## Quyết định
1. **Dòng tổng** `EarningRollup`/`BackupEarningRollup`: (máy `origin`, ngày UTC `epochDay`, `kind`, `account`) → `coins`, `jobs`.
   `id` = UUID tất định từ (origin, ngày, kind, account); validator từ chối id không khớp công thức.
2. **Chống đếm đôi giữa các máy**: mỗi sự kiện thô mang `origin` (mã máy tạo). Dòng tổng của (máy X, ngày D) luôn đầy đủ và
   "phủ" mọi dòng thô của (X, D) → các dòng thô đó bị loại ở mọi nơi đọc (store, merge, thống kê). Máy chỉ nén sự kiện do CHÍNH nó tạo.
   Dòng cũ không có `origin` không bao giờ bị nén hay bị phủ (không thể biết chủ).
3. **Gộp**: hợp theo id; cùng id (cùng máy) → bản lớn hơn (nhiều việc hơn, rồi nhiều xu hơn); khác máy → cộng. Không bao giờ xung đột.
4. **Khi nào nén** (`EarningsRollupPolicy.compact`, gọi từ `SyncWorker` và mỗi 200 lần ghi): luôn nén ngày cũ hơn 60 ngày; nếu dòng thô > 20.000
   thì nén thêm từ ngày cũ nhất tới ≤ 15.000; không bao giờ nén 2 ngày gần nhất; mỗi (máy, ngày) nén trọn vẹn một lần; dừng ở 50.000 dòng tổng/máy.
5. **Thứ tự ghi an toàn**: ghi `earnings.rollup` TRƯỚC, bỏ dòng thô SAU; dừng giữa chừng thì dòng thô còn lại bị luật "phủ" loại → không mất, không đếm đôi.
   Tổng luôn còn trên máy dù chưa tải lên; nén đánh dấu dirty để lần đồng bộ sau gửi lên.
6. **Định dạng**: snapshot v4 (`earningRollups`, `origin`); đọc được v2/v3 qua mô hình dây đóng băng; luôn ghi v4. Server (`save_user_backup`) nhận mọi schema_version > 0 — không cần migration.
7. **Giao diện**: `summarizeEarnings(..., rollups)` — dòng tổng chỉ cộng vào "Tất cả", tổng theo loại/tài khoản và kỳ trước; biểu đồ ngày/giờ và "hôm nay" chỉ đọc dòng thô.

## Hệ quả / rủi ro
- App cũ (< v1.13.0) không đọc được v4 → cần cập nhật mọi thiết bị cùng lúc; sau đó đồng bộ bình thường.
- Dòng thu nhập cũ chưa có `origin` (trước v1.13.0) vẫn bị cắt khi vượt 30.000 dòng như trước (không nén được).
- Ngày tính theo UTC: dòng tổng lệch tối đa vài giờ so với ngày giờ địa phương — chỉ ảnh hưởng tổng, không ảnh hưởng biểu đồ.
- Số dòng tổng có trần (50.000/máy, 100.000/snapshot). Ví dụ 100 tài khoản × 2 loại × 365 ngày ≈ 73.000/năm → cần nén theo THÁNG về sau (chưa làm).
- Nếu đồng hồ máy lùi về ngày đã nén, sự kiện mới của ngày đó bị luật phủ loại — hiếm, chấp nhận.
