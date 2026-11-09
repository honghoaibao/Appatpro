# Current Focus

## Supabase portability track (updated 2026-11-09)

- Active branch: feature/atpro-supabase-phase3-1-foundation. main is unchanged and not merged.
- Phases 3.1–3.3: Auth foundation, lifecycle, UI and protected routing implemented in source; not Android-build-verified or device-tested.
- Phase 3.4.1: Room-first snapshot architecture/data policy documented in context/cloud_sync_architecture.md and ADR-0018.
- Phase 3.4.2: Room v2 Outbox + Sync Metadata, additive migration, stable session UUIDs and atomic local mutation recording implemented; not build-verified.
- Phase 3.4.3: local snapshot builder, JSON/GZIP/Base64 + SHA-256 codec, bounded decode validation, stable earnings IDs and durable dirty generations implemented in source; not build-verified.
- Phase 3.4.4: Room v3 owner/revision timestamps, explicit owner confirmation in Settings, mismatch blocking and local expected-revision checkpoint API implemented in source; not build-verified.
- Phase 3.4.5: strict metadata/snapshot validation and DAO hardening implemented in source; ADR-0022 records the account multi-device conflict gate. Not build-verified.
- Next step: **3.4.6 — WorkManager/network scheduling shell only**. No account cloud writes or Outbox acknowledgements until ADR-0022 and Phase 4.1 are complete.
- Constraints: Room remains local-first; never upload credentials/tokens; another Auth owner must be blocked with no automatic reassignment; account status/stats must not use LWW or blind summation; do not run GitHub Actions until the single final build run; do not modify main.

## Trạng thái: v1.12.0 — nhận nhiệm vụ theo `f4f.py` v2.8, chọn tài khoản nhiệm vụ riêng, thông báo nâng cấp (trên nền v1.11.0); chờ build thật + QA thiết bị

v1.12.0 (`revision/v1.12.0.md`, ADR-0014): (1) `Follow4FollowProvider` port giao thức `f4f.py` v2.8 — danh sách acc `GET quick-switch`, `earn-coin` luôn kèm `uid`, thang đổi acc S1–S5, 429 chờ-thử-lại, `jobGone`, trace `F4F:`; 401 giữa phiên → dừng cả lượt (KHÔNG lưu mật khẩu — `constraints.md`); (2) `TaskAccountSelector` + tab Cài đặt → Nhiệm vụ → **Tài khoản** (Tự động / Chọn tay), Dashboard hiện tóm tắt; (3) `notification/`: 4 nhóm + giờ yên tĩnh + thông báo nhiệm vụ (tổng kết xu/số dư) + tiến độ trên thông báo đang chạy + mở đúng tab.

**QA máy thật cho v1.12.0 (làm trước):** chạy nhiệm vụ ≥ 2 acc với F4F thật, xem dòng `F4F:` ở Nhật ký "Chi tiết" (kỳ vọng khớp ngay ở bước "Lấy job"); Chọn tay + acc "Chưa có trên máy"; token hết hạn → dừng + thông báo; thông báo trên Android 13+ (quyền), giờ yên tĩnh, chạm thông báo mở đúng tab.

### (Trước đó) v1.11.0

v1.11.0 (`revision/v1.11.0.md`, ADR-0013): (1) **bấm nhầm Đăng xuất** — 3 nguyên nhân: `contains("x")` trúng "Đăng xuất", `detectPopup` đòi ≥ 2 username, `clickInPopup` luôn `drop(1)`; sửa ở `NodeTraverser.isLogoutLabel`, `openSwitchList()`, `pickSwitchEntry`; (2) `AtSlider`/`AtRangeSlider` thay mọi thanh kéo; nuôi lâu mỗi N job ≤ 50, thời lượng ≥ 30s; (3) tab **Nuôi acc** trong Nhiệm vụ kiếm tiền (`taskFarm*`); (4) ngưỡng số tim (`minLikesToLike`, `taskFarmMinLikesToLike`) + `VideoScanner`/`ContentMatcher`; (5) `LogPolish` + chip "Chi tiết", popup/nhật ký gọn hơn, thêm hiệu ứng.

**QA máy thật cho v1.11.0 (làm trước):** chuyển acc nhiều lần liên tiếp (nhất là khi danh sách chỉ hiện 1 acc) không còn bấm Đăng xuất; ngưỡng số tim đọc đúng (log "Chi tiết"); thanh kéo trong trang cuộn dọc, cỡ chữ lớn.

### (Trước đó) v1.10.0

v1.10.0 (`revision/v1.10.0.md`, ADR-0012): (1) sau báo cáo job **Back về feed thật** (`NodeTraverser.classifyFeed` + `ensureRealFeed`); (2) bảng xếp hạng F4F hiện xu (`parseCoinText`, nhiều key); (3) F4F làm lại, bỏ Giới thiệu, ô Xu chờ · Làm hôm nay · Tổng xu; (4) Thống kê 2 tab (Nuôi acc / Kiếm tiền) với biểu đồ Canvas, dữ liệu `EarningsStore`; (5) Quyền & Thông báo tách riêng, thẻ quyền bố cục dọc, chặn cỡ chữ hệ thống; (6) popup 216dp + thanh đếm ngược; (7) tối ưu pin; (8) bỏ chữ thừa.

**QA máy thật cho v1.10.0 (làm trước):** log `TASK: Đã về feed (Back ×n)` sau mỗi job; bảng xếp hạng F4F có xu; màn Quyền với cỡ chữ lớn; thanh đếm ngược khi tạm dừng/thu nhỏ.

### (Trước đó) v1.9.0

v1.9.0: (1) **không tìm thấy nút Follow** — viết lại bộ dò (`NodeTraverser.probeFollow/scanProfile`), `performProfileFollow()` chờ tải/bấm/xác nhận/chạm dự phòng, bật `flagIncludeNotImportantViews` (xem `revision/v1.9.0.md` mục A); (2) tối đa **150 job/acc**, nuôi trước job tối thiểu **10s**; (3) popup nổi viết lại với **nhật ký thời gian thực** và dữ liệu nhiệm vụ thật (`updateTask` trước đây không được gọi); (4) bỏ 6 màn hình/khung dư thừa, `AtTopBar` cho Config/Lịch (ADR-0011).

**QA máy thật cho v1.9.0 (làm trước):** chạy nhiệm vụ Follow trên 3 loại hồ sơ (cửa hàng, người nổi tiếng, người thường) — log mong đợi `TASK-FOLLOW: Thấy nút "Follow" [nhãn:hàng-nút] ... Follow thành công ✓`. Nếu vẫn `không tìm thấy nút Follow`, lấy các dòng `DEV: ... cây hồ sơ` để chỉnh. Sau khi cập nhật app nên tắt/bật lại dịch vụ Trợ năng một lần.

### (Trước đó) v1.8.0

v1.8.0: (1) **lỗi không bắt đầu được nhiệm vụ** — task mode đọc acc hiện tại sau khi mở popup chuyển tài khoản (xem `revision/v1.8.0.md` mục B); (2) **chuẩn hoá acc đầu chỉ mở Hồ sơ** (mục C); (3) lỗi build `animateFloat` + dọn cảnh báo Kotlin; (4) bố cục 5 tab (ADR-0010); (5) logo mới; (6) API v2.7 (`coin_received`).

(Trước đó) v1.7.0 — Giao diện Sky (Sáng/Tối) + Ví Follow4Follow

Task mode đi qua `TaskProvider` → `Follow4FollowProvider` (`TASKS_ENABLED = true`): đăng nhập trong app,
lấy acc đã liên kết, lấy job Like/Follow, `quick-switch` acc, `mark-start` (Like), complete (đợi ≥5s) / skip,
xử lý 429 / hết lượt ngày / 401 / lệch đồng hồ. Tài liệu: `context/Follow4FollowAPI_Documentation.md`.

v1.7.0: bảng màu xanh da trời · trắng · xanh dương · đen với chế độ Sáng/Tối (ADR-0009); thành phần chuẩn thay `AlertDialog`/ô nhập rời rạc; thiết kế lại thanh điều hướng, Dashboard, hộp thoại, Splash, overlay; **Ví Follow4Follow** (tổng quan, xếp hạng, rút tiền 5 kênh, lịch sử, giới thiệu, thông báo — `Follow4FollowAPI_Documentation.md` mục 9).

Sandbox không có Android SDK nên toàn dự án chưa được biên dịch. Đã biên dịch + chạy test riêng cho phần
thuần Kotlin: `FarmBehavior`, `PopupLoopGuard`, `taskPostJobNurtureSecs` và **toàn bộ `tasks/f4f/` (81 test, transport giả)**; toàn dự án đã được quét cú pháp bằng kotlinc (không lỗi cú pháp).
Phần chưa được compile trong sandbox: toàn bộ Compose (`ui/theme/AtTheme.kt`, `ui/f4f/*`, mọi màn hình), `AutomationEngine` (task mode), `F4fAuth.kt` (phần `SharedPreferences`), `F4fHubViewModel`.
**CI là nguồn xác nhận cuối cùng.**

## Phiên bản gần đây

| Bản | Nội dung | Ghi chú chi tiết |
|-----|----------|------------------|
| v1.12.0 (44) | Port `f4f.py` v2.8; chọn tài khoản nhiệm vụ riêng; thông báo nâng cấp | `revision/v1.12.0.md`, ADR-0014 |
| v1.11.0 (43) | Sửa bấm nhầm Đăng xuất; AtSlider; tab Nuôi acc cho kiếm tiền; số tim tối thiểu; LogPolish | `revision/v1.11.0.md`, ADR-0013 |
| v1.10.0 (42) | Back về feed; xu F4F; thống kê kiếm tiền; popup nhỏ + đếm ngược; tối ưu pin; bỏ chữ thừa | `revision/v1.10.0.md`, ADR-0012 |
| v1.9.0 (41) | Sửa nút Follow; 150 job/acc, nuôi trước job ≥10s; popup log thời gian thực; dọn UI | `revision/v1.9.0.md`, ADR-0011 |
| v1.8.0 (40) | Sửa lỗi nhiệm vụ/chuẩn hoá; bố cục 5 tab; logo mới; API v2.7; dọn cảnh báo | `revision/v1.8.0.md`, ADR-0010 |
| v1.7.0 (39) | Giao diện Sky Sáng/Tối; thành phần chuẩn; Ví Follow4Follow (xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo) | `revision/v1.7.0.md`, ADR-0009 |
| v1.6.0 (38) | Tích hợp Follow4Follow (đăng nhập, acc, job, complete/skip); `TaskProvider.nextJob(account, kinds)` + `onJobOpening` + `noJobReason`; engine báo lại sau 429 và skip khi lỗi nghiệp vụ; dọn tài liệu cũ | `revision/v1.6.0.md` |
| v1.5.0 (37) | Thiết kế lại giao diện (`ui/theme/AtTheme.kt`); Services/Dashboard/hộp thoại/thanh điều hướng viết lại | `revision/v1.5.0.md` |
| v1.4.0 (36) | Gỡ Golike+WebView; `TaskProvider`; `RateGate`/`WatchPlan`; `PopupLoopGuard`; thẻ nhiệm vụ mới | `revision/v1.4.0.md` |
| v1.3.3 (35) | Bật Golike; nuôi acc ngắn/lâu giữa các job; delay trước khi lấy job; `doLikeTask()` double-tap dự phòng; CI dùng `setup-gradle@v6` | `revision/v1.3.3.md` |
| v1.3.2 (34) | Fix `findProfileTab()` bấm nhầm tab; popup cho Golike | `revision/v1.3.2.md` |
| v1.3.1 (33) | `getRootNode()` → `suspend`, `safeScreenRead()`; `ACCOUNT_UPDATE_PROMPT`; Facebook thoát bottom sheet | `revision/v1.3.1.md` |

## Next actions

0. **QA máy thật cho hai lỗi vừa sửa:** (a) chế độ Nhiệm vụ với acc đang login — log phải có `DEV: Acc đang active: @...` TRƯỚC `SCAN`; không còn `(hiện tại: @?)`; (b) acc đầu cần chuẩn hoá — log `FIX: ... chỉ mở Hồ sơ`, không còn thao tác bấm vào acc đang đăng nhập.
1. **Build thật (Termux/CI) cho v1.8.0 + gửi lại log cảnh báo:** các icon deprecated chưa liệt kê hết cần tên chính xác từ log. **Trước đó (v1.7.0):** biên dịch toàn dự án. Điểm rủi ro: `AtTheme.kt` (Modifier.shadow với màu bóng, `OutlinedTextFieldDefaults.colors`, `SwitchDefaults.colors`), `F4fHubScreen`/`F4fWithdrawPanel` (ngữ pháp lambda @Composable), `MainScreen` (Box bao Scaffold + overlay).
2. **QA trực quan Sáng/Tối trên máy thật:** chữ trắng trên gradient hero, biểu tượng thanh trạng thái/điều hướng, bóng đổ thẻ, hộp thoại, bàn phím che ô nhập (đăng nhập, rút tiền).
3. **QA rút tiền bằng số coin nhỏ:** xác minh `bank_name` ZaloPay, hạn mức từng kênh, thông báo lỗi 422.
4. **QA thiết bị với tài khoản F4F thật** (xem `tasks/backlog.md`: F4F-002…F4F-005): chạy trọn `taskOneAccount()`, xác nhận mark-start + đợi 5s, quick-switch khi có nhiều acc, hành vi khi hết lượt ngày.
5. **QA thiết bị khác:** hiệu quả `RateGate` thực tế, `PopupLoopGuard` khi popup cứng đầu, 6 cụm từ quyền Android 13.

## Màn hình native

| Screen    | Activity           | Trạng thái |
|-----------|--------------------|------------|
| Dashboard | DashboardActivity  | ✅ Active  |
| Accounts  | Tab trong MainScreen (AccountsScreen) | ✅ Active |
| Logs      | Tab trong MainScreen (LogsScreen)     | ✅ Active |
| Stats     | Màn hình phủ từ Cài đặt → Công cụ     | ✅ Active |
| Config    | ConfigActivity (mở thẳng mục từ tab Cài đặt) | ✅ Active |
| Schedule  | Màn hình phủ từ Cài đặt → Công cụ     | ✅ Active |
| Services  | ServicesScreen (tab trong MainScreen) | ✅ Active |
| Nhiệm vụ kiếm tiền | Hàng trong ServicesScreen + `F4fAccountCard` trong Cài đặt → Nhiệm vụ | ✅ Active (v1.6.0, gate bởi `TASKS_ENABLED`) |

Flutter: đã gỡ hoàn toàn ✅
