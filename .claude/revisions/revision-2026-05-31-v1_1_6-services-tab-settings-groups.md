# Revision: v1.1.6 — Tab Dịch vụ + Settings 3 nhóm

**Date:** 2026-05-31
**Version:** v1.1.6 (versionCode 18)

## Thay đổi

### 1. Tab Dịch vụ mới (`ui/services/ServicesScreen.kt`)
- Thêm tab **"Dịch vụ"** vào bottom navigation bar (vị trí thứ 2, sau Dashboard)
- **Nhóm "Dịch vụ nuôi tài khoản":**
  - Card **"Nuôi tài khoản TikTok"** — bo góc 16dp, gradient ngang từ TikTok đen sang cyan/đỏ mờ dần bên phải; badge "Hoạt động"; chip tính năng (Xem video, Like, Follow)
- **Nhóm "Dịch vụ kiếm tiền":**
  - Card **"Kiếm tiền Golike - TikTok"** — thiết kế tương tự, màu vàng/amber Golike, badge "Đang phát triển" + icon Build
- Press animation (scale 0.97) trên card TikTok

### 2. MainScreen — thêm Tab.SERVICES (`ui/MainScreen.kt`)
- Enum `Tab` tăng từ 4 lên 5 mục: DASHBOARD · **SERVICES** · STATS · ACCOUNTS · LOGS
- Icon: `Icons.Rounded.GridView`
- Label: `"Dịch vụ"`

### 3. Settings — cấu trúc 3 nhóm mở rộng (`ui/config/ConfigScreen.kt`)
- `Section` enum giữ nguyên content, thêm `EARN_GOLIKE` placeholder
- Thêm `SettingsGroup` data class và `SETTINGS_GROUPS` list:
  - **Hệ thống** (⚙️ xanh lá): Quyền · Thông báo · TikTok
  - **Nuôi TikTok** (👤 tím): Thời gian · Hành động
  - **Kiếm tiền** (💰 vàng): Golike *(đang phát triển)*
- Sidebar mới: mỗi nhóm có chevron expand/collapse; tap header mở nhóm & auto-select section đầu tiên
- `EarnGolikePlaceholder()` composable — màn hình placeholder cho kiếm tiền

### 4. Build
- `versionCode 17 → 18`
- `versionName "v1.1.5" → "v1.1.6"`
- `build.yml` default inputs: `1.1.6` / `18`

## Files thay đổi
- `android/app/build.gradle`
- `.github/workflows/build.yml`
- `android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/config/ConfigScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/services/ServicesScreen.kt` *(mới)*
