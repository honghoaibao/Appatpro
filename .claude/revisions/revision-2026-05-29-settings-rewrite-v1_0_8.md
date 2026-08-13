# Revision — v1.0.8: Settings UI Rewrite

_Date: 2026-05-29_
_Files changed: 2_

---

## Mục tiêu

Viết lại toàn bộ phần Settings (`ConfigScreen.kt`) để cải thiện UX và tính trực quan.

---

## Thay đổi chính

### 1. Navigation: Pill tabs → Vertical sidebar

**Trước:** 5 pill tabs nhỏ ngang (72px x 5), chữ 9.5sp, icon 14dp, bị cắt trên màn hình nhỏ.

**Sau:** Sidebar dọc 72dp ở trái, mỗi mục có icon 20dp + label 8.5sp, có indicator badge đỏ
khi thiếu quyền Accessibility.

**Lý do:** Sidebar tiêu tốn ít chiều rộng hơn, tên mục không bị truncate, và dễ chạm hơn trên mobile.

### 2. Layout content: Tabs → Named Sections

Mỗi section (Timing, Actions, Notifications, TikTok, Permissions) là một `@Composable` riêng
với `SectionTitle` header rõ ràng ở đầu, thay vì ẩn trong `CfgSectionHeader` bên trong card.

### 3. Permission cards: Rows trong 1 card → Mỗi quyền 1 card riêng

**Trước:** 3 permission items xếp trong 1 `CfgCard`, dùng `CfgDividerThin` ngăn cách.

**Sau:** Mỗi `PermissionCard` tự có border/background riêng animate theo trạng thái `granted`.
- Granted: border xanh + bg xanh mờ
- Not granted: border tối bình thường

### 4. Widget mới

- `RangePreview` — thanh mini trực quan hoá khoảng watchMin–watchMax so với 30s
- `RateRow` — header cho slider tỉ lệ (icon + label + % value)
- `StatusBadge` — badge trạng thái TikTok
- `InfoBanner` — banner thông tin với màu dynamic (thay thế hardcoded layout)
- `CardLabel` — header nhỏ bên trong card (icon + text)
- `SectionTitle` — tiêu đề mỗi section (icon box + bold text)

### 5. Animation transition

Tab transition cũ: slideInHorizontally (trái/phải).
Section transition mới: slideInVertically (trên/dưới) theo chiều scroll navigation sidebar.

### 6. Không thay đổi

- `ConfigViewModel.kt` — không chạm
- `ConfigUiState` — không thay đổi
- Tất cả keys `repo.setConfig(...)` — không thay đổi
- `ConfigActivity.kt` — không thay đổi
- Logic validation watchMin/watchMax (giữ nguyên guards)

---

## Files changed

| File | Action |
|------|--------|
| `ui/config/ConfigScreen.kt` | Rewrite (1185 lines) |
| `android/app/build.gradle`  | versionCode 8→9, versionName v1.0.7→v1.0.8 |
