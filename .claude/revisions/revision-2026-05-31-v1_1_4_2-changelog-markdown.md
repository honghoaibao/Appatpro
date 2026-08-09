# Revision — v1.1.4.2 — Changelog markdown + account log

**Date:** 2026-05-31
**Version:** v1.1.4.2 (versionCode 16)

## Changes

### DashboardScreen.kt
- **Fix changelog truncation**: Bỏ giới hạn 300 ký tự; thay bằng Box cuộn `heightIn(max=220.dp)` + `verticalScroll`
- **Markdown rendering**: Thêm composable `MarkdownChangelogText` parse:
  - `# / ## / ###` → Text đậm với cỡ chữ tương ứng (14/13/12.sp)
  - `**text**` → in đậm trắng inline qua `AnnotatedString`
  - `- / *` bullet → dấu `•` màu purple + nội dung
  - Dòng trống → `Spacer(4.dp)`
- **New imports**: `rememberScrollState`, `verticalScroll`, `AnnotatedString`, `SpanStyle`, `buildAnnotatedString`, `withStyle`

### AutomationEngine.kt
- **openSwitchPopup()**: Thêm `setStatus("✓ Tìm thấy ${discovered.size} tài khoản trong popup")` sau khi đọc danh sách thành công; hiện "WARN: Không đọc được danh sách tài khoản" nếu rỗng
- **phaseDiscover()**: Thêm `setStatus("✓ ${farmList.size} tài khoản sẽ được nuôi")` sau `buildFarmList` thành công — hiển thị rõ trong StartupStatusDialog

### build.gradle
- `versionCode 15` → `versionCode 16`
- `versionName "v1.1.4.1"` → `versionName "v1.1.4.2"`

### .github/workflows/build.yml
- `default: '1.1.4.1'` → `default: '1.1.4.2'`
- `default: '15'` → `default: '16'`
- Fallback version `VER="1.1.4"` → `VER="1.1.4.2"`
