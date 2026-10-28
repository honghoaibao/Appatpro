# Revision — 2026-10-26 — Đồng bộ tài liệu với v1.3.3

Chỉ sửa tài liệu. **Không** đổi mã nguồn, `versionCode`/`versionName`, `.github/`, `.gitignore`.

## Vấn đề
- `README.md` còn mô tả cấu trúc `atpro_v1_0_9`, test 7/7/24, changelog dừng ở v1.1.8, hướng dẫn `gradle wrapper` đã lỗi thời (CI từ v1.3.3 dùng `setup-gradle@v6`).
- `context/current_focus.md` dừng ở session 14 (2026-05-25); `project_overview.md` còn mô tả lớp Flutter đã gỡ; `architecture.md` thiếu `golike/`, `UpdateChecker`, `DiscordNotifier`, `ui/services`, `ui/golike`.

## Thay đổi
| File | Thay đổi |
|------|----------|
| `README.md` | Viết lại: version v1.3.3, cấu trúc thật, stack, tính năng, setup/build local & CI, số test thực tế (61), mục `.claude/`, changelog tóm tắt v1.1.8 → v1.3.3 |
| `.claude/revisions/revision-2026-06-01-v1_1_8-daily-limit-screen-time.md` | MỚI — chuyển nguyên văn changelog v1.1.8 từ README (nội dung không đổi) |
| `.claude/context/current_focus.md` | Thêm trạng thái v1.3.3 + QA còn lại; phần session 14 giữ nguyên làm lưu trữ |
| `.claude/context/project_overview.md` | Viết lại theo kiến trúc một lớp thuần Kotlin |
| `.claude/context/architecture.md` | Cập nhật layer map, quy tắc phụ thuộc, thêm luồng task mode |
| `.claude/tasks/backlog.md` | Thêm các mục QA/CI sau v1.3.3 |

## Đã đối chiếu với mã
`versionCode 35 / versionName v1.3.3`, `GOLIKE_ENABLED = true`, số `@Test` (39/8/6/8), danh sách file `.kt`, `build.yml` (trigger, `setup-gradle@v6`, output `dist/atpro.apk`), `minSdk 24 / target 34`, dependency trong `app/build.gradle`.

## Chưa làm / lưu ý
- `constraints.md` còn dòng "Flutter SDK pinned >=3.19.0" (đã lỗi thời) và `glossary.md`, `technical_debt.md` chưa rà — để lượt sau nếu cần.
- Không compile/chạy test trong sandbox.
- Phát hiện khi đối chiếu: package `golike/` không dùng `StringEncryptor`, trong khi `constraints.md` yêu cầu token đi qua nó → cần rà cách lưu token Golike (chưa kiểm tra, chưa sửa mã).
