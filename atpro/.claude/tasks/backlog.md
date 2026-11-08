# Backlog

## Việc tồn đọng sau v1.8.0

- [ ] CI-005 — Build Debug thật cho v1.8.0 (Termux/CI); gửi log để xử lý nốt icon deprecated còn lại (AssignmentTurnedIn, ArrowBackIosNew… nếu bị cảnh báo)
- [ ] ENG-001 — QA máy thật: task mode đọc acc hiện tại TRƯỚC popup; chuẩn hoá acc đầu chỉ mở Hồ sơ (kiểm `isNormalizing` không bị tắt giữa chừng)
- [ ] CI-004 — Biên dịch toàn dự án lần đầu cho v1.7.0 (Compose: theme mới, Ví F4F, màn hình đã đổi; engine task mode)
- [ ] F4F-007 — QA rút tiền bằng số coin nhỏ từng kênh (xác minh `bank_name` ZaloPay, hạn mức, lỗi 422)
- [ ] F4F-008 — Xác minh schema `/jobs` (nhận thưởng hằng ngày), `transaction/list`, đánh dấu thông báo đã đọc rồi mới thêm vào Ví
- [ ] UI-003 — Thêm chế độ giao diện "Theo hệ thống"; thiết kế lại bố cục Thống kê/Nhật ký (hiện mới đồng bộ màu + token)
- [ ] F4F-002 — QA thiết bị: đăng nhập thật → lấy acc → chạy trọn `taskOneAccount()` (job Follow và Like)
- [ ] F4F-003 — Xác nhận `mark-start` + đợi ≥5s khiến `complete-job` được chấp nhận; theo dõi tỉ lệ "chưa làm job này"
- [ ] F4F-004 — Nhiều acc: xác nhận `quick-switch` đổi đúng acc active, không gọi thừa khi đã active
- [ ] F4F-005 — Hết lượt ngày (`reached`): engine dừng acc và chuyển acc kế tiếp, log có lý do
- [ ] F4F-006 — Cân nhắc nối `report-job` (job hỏng/video đã xoá) thay vì `skip`; cân nhắc luồng liên kết acc TikTok trong app
- [ ] UI-001 — QA giao diện Sáng/Tối trên máy thật (độ tương phản, kích thước chạm, hiệu ứng mượt, thanh điều hướng + gesture bar, bàn phím che ô nhập, hộp thoại đăng nhập F4F)
- [ ] QA-014 — Xác nhận double-tap dự phòng trong `doLikeTask()` không toggle unlike
- [ ] QA-017 — Kiểm chứng 6 cụm từ quyền Android 13 trong `PopupHandler.scanKeywords`
- [ ] QA-018 — Đối chiếu tỉ lệ like/follow thực tế trong log với cấu hình (RateGate)
- [ ] CI-001 — Theo dõi lần chạy CI đầu tiên với `setup-gradle@v6`
- [ ] TD-NEW-003 — Rà `findHomeTab()` / `findInboxTab()` (nguy cơ false-positive substring ngắn, giống `findProfileTab()` v1.3.2)

---

## Đã xong (removed từ backlog)

- F4F-001 ✅ — `Follow4FollowProvider` + đăng nhập trong app, bật `TASKS_ENABLED` (v1.6.0, 2026-10-29)
- TD-NEW-001 ✅ — Refactor FlutterBridge callsites + xóa bridge/ (2026-05-22)
- TD-NEW-002 ✅ — Xóa FeatureFlags.kt (2026-05-22)
