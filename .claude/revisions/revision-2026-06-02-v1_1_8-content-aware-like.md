# Revision — v1.1.8 (2026-06-02)

## Tóm tắt
Thêm logic phát hiện loại nội dung trước khi like: không bao giờ like live,
like quảng cáo theo cài đặt người dùng, like video bình thường.
Bump versionCode 20, versionName v1.1.8.

---

## 1. Content-aware like gate (`AutomationEngine.kt`)

**Root cause / motivation:**
Trước đây bước [8] (Like) không phân biệt loại nội dung — engine có thể double-tap
vào livestream (nếu skipLive = false) hoặc quảng cáo (nếu skipAds = false).
Đây là hành vi không mong muốn và có thể gây phát hiện bot.

**Logic mới tại bước [8]:**
```
Random.nextFloat() < config.likeRate
  → detectLive(root)?  → KHÔNG like (bất kể skipLive)
  → detectAd(root)?    → chỉ like khi config.likeAdsEnabled = true
  → video thường       → like bình thường
```

Cả `detectLive()` và `detectAd()` đã được gọi tại bước [5a]/[6] để swipe qua,
nhưng khi người dùng tắt skipLive/skipAds, engine sẽ dừng lại xem nội dung đó —
content-aware gate đảm bảo like vẫn đúng loại nội dung.

**Files changed:**
- `AutomationEngine.kt` — bước [8] Like thay thế bởi content-aware block

---

## 2. Cấu hình mới: `likeAdsEnabled` (`FarmConfig.kt`)

Thêm field:
```kotlin
val likeAdsEnabled: Boolean = false
```
Default `false` — an toàn, không like quảng cáo khi nâng cấp từ version cũ.

---

## 3. LocalRepository — key `"like_ads"`

Key-value mapping mới trong `loadFarmConfig()`:
```kotlin
likeAdsEnabled = getConfigBool("like_ads", false)
```
Key-value schema không cần Room migration (thiết kế sẵn từ đầu).

---

## 4. ConfigViewModel — `ConfigUiState.likeAds`

- Thêm field `likeAds: Boolean = false` vào `ConfigUiState`
- Load từ `"like_ads"` key trong `load()`
- Save vào `"like_ads"` key trong `save()`

---

## 5. ConfigScreen — Toggle "Like quảng cáo"

Toggle mới trong card "Hành vi", ngay sau "Bỏ qua quảng cáo":
- **Bị dim (alpha 0.4) và disabled** khi `skipAds = true` (không có quảng cáo để like)
- Subtitle thay đổi theo trạng thái để hướng dẫn người dùng
- Sử dụng `enabled` parameter mới của `CfgSwitch` (thêm `Modifier.alpha` + `Switch.enabled`)

**`CfgSwitch` signature update:**
```kotlin
private fun CfgSwitch(
    label:     String,
    subtitle:  String? = null,
    value:     Boolean,
    accent:    Color   = Purple,
    enabled:   Boolean = true,   // ← MỚI
    onChanged: (Boolean) -> Unit,
)
```
Backward-compatible: tất cả call site cũ không truyền `enabled` → default `true`.

---

## 6. Version bump

| File | Thay đổi |
|------|----------|
| `android/app/build.gradle` | `versionCode 21→20`, `versionName v1.1.9→v1.1.8` |
| `.github/workflows/build.yml` | default `version 1.1.9→1.1.8`, `build 21→20` |
