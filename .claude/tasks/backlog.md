# Backlog

## Việc tồn đọng sau v1.5.0

- [ ] UI-001 — QA giao diện v1.5.0 trên máy thật (độ tương phản, kích thước chạm, hiệu ứng mượt, thanh điều hướng + gesture bar)
- [ ] UI-002 — Thiết kế lại bố cục Tài khoản, Thống kê, Lịch, Nhật ký, Cài đặt, overlay nổi (hiện mới đồng bộ màu + phẳng hoá)
- [ ] CI-003 — Biên dịch toàn dự án lần đầu cho v1.5.0

## Việc tồn đọng sau v1.4.0

- [ ] F4F-001 — Viết `Follow4FollowProvider` (cần tài liệu API từ https://follow4follow.net/api-docs); bật `TASKS_ENABLED`
- [ ] CI-002 — Biên dịch toàn dự án lần đầu cho v1.4.0
- [ ] QA-017 — Kiểm chứng 6 cụm từ quyền Android 13 trong `PopupHandler.scanKeywords`
- [ ] QA-018 — Đối chiếu tỉ lệ like/follow thực tế trong log với cấu hình (RateGate)

## QA tồn đọng sau v1.3.3 (chỉ còn hiệu lực khi có provider) (cần thiết bị thật — chi tiết ở `context/current_focus.md`)

- [ ] QA-014 — Xác nhận double-tap dự phòng trong `doLikeTask()` không toggle unlike
- [ ] QA-015 — Chạy thật toàn bộ `taskOneAccount()` với `GOLIKE_ENABLED = true`
- [ ] QA-016 — Xác nhận lock job Golike (~120s) không bị vượt khi cộng `taskJobDelaySecs` + thời gian làm job
- [ ] CI-001 — Theo dõi lần chạy CI đầu tiên với `setup-gradle@v6`
- [ ] TD-NEW-003 — Rà `findHomeTab()` / `findInboxTab()` (nguy cơ false-positive substring ngắn, giống `findProfileTab()` v1.3.2)

---

## Đã xong (removed từ backlog)

- TD-NEW-001 ✅ — Refactor FlutterBridge callsites + xóa bridge/ (2026-05-22)
- TD-NEW-002 ✅ — Xóa FeatureFlags.kt (2026-05-22)
