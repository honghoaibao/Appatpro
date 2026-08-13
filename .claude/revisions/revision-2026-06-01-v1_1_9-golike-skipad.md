# Revision — v1.1.9 (2026-06-01)

## Tóm tắt
Bỏ qua quảng cáo TikTok, tích hợp đăng nhập Golike + thống kê sơ trên Dashboard,
tích hợp nhiệm vụ Golike TikTok sơ trong Settings, bump versionCode 20→21 / versionName v1.1.8→v1.1.9.

---

## 1. Golike API layer mới (`com.atpro.golike/`)

**Tài liệu API:** Đã đưa vào `.claude/context/golike_api.md` từ file `GolikeAPI_Documentation.md`
(reverse-engineered từ APK smali). Bao gồm: auth, `/api/users/me`, `/api/statistics/report`,
`/api/tiktok-account`, get-jobs, complete-jobs TikTok.

### Files mới tạo

| File | Chức năng |
|---|---|
| `GolikeModels.kt` | Tất cả `@Serializable` data class: Login, User, TikTokAccount, TikTokJob, Statistics, GolikeResult |
| `GolikeApi.kt` | HTTP client dùng `HttpURLConnection` + `kotlinx.serialization`, không phụ thuộc OkHttp |
| `GolikeRepository.kt` | Business logic + credential storage dùng `LocalRepository` config key-value |

**Token storage keys:**
- `golike_token` — JWT bearer token
- `golike_username` — username đã lưu (pre-fill form)

---

## 2. GolikeViewModel (`com.atpro.ui.golike/GolikeViewModel.kt`)

Mới hoàn toàn. `GolikeUiState` chứa:
- `isLoggedIn`, `user`, `stats`, `savedUsername`, `loginError`
- `tikTokAccounts: List<TikTokAccountDto>`
- `tikTokJobs: Map<String, List<TikTokJobDto>>`

Computed props: `coin`, `rankName`, `tiktokHold`, `tiktokPending`, `totalJobCount`, `displayName`.

Auto-restore session: nếu token đã lưu trong LocalRepository → gọi `/api/users/me` khi init.
Auto-logout khi nhận HTTP 401.

---

## 3. Skip Ads (`FarmConfig.kt`, `LocalRepository.kt`, `ConfigViewModel.kt`, `ConfigScreen.kt`)

**Thêm field `skipAds: Boolean = true`** vào `FarmConfig`.

| File | Thay đổi |
|---|---|
| `FarmConfig.kt` | `val skipAds: Boolean = true` ngay sau `skipLive` |
| `LocalRepository.loadFarmConfig()` | Load từ key `"skip_ads"` default `true` |
| `ConfigViewModel` `ConfigUiState` | Field `skipAds: Boolean = true` |
| `ConfigViewModel.load()` | `getConfigBool("skip_ads", true)` |
| `ConfigViewModel.save()` | `setConfig("skip_ads", ...)` |
| `ConfigScreen.ActionsSection` | Switch "Bỏ qua quảng cáo" (Amber) sau switch "Bỏ qua video trực tiếp" |

> ⚠️ `AutomationEngine` / `PopupHandler` chưa tiêu thụ `skipAds` trong release này — field có mặt
> trong config, UI lưu/load đúng, nhưng logic detect+skip quảng cáo sẽ implement ở v1.2.x.

---

## 4. EarnGolikeSection thay thế EarnGolikePlaceholder (`ConfigScreen.kt`)

`Section.EARN_GOLIKE` trước đây render placeholder tĩnh. Từ v1.1.9 render `EarnGolikeSection(golikeVm)`.

**Sub-composables:**

| Composable | Khi nào hiển thị |
|---|---|
| `GolikeLoginCard` | Chưa đăng nhập — form username + password, nút Login |
| `GolikeUserCard` | Đã đăng nhập — avatar box, tên, rank, coin/hold/pending row, nút Logout |
| `GolikeTikTokJobsCard` | Đã đăng nhập + có tài khoản TikTok — list jobs theo account |
| `GolikeTikTokAccountRow` | 1 TikTok account + tối đa 3 jobs preview (type badge + link rút gọn + coin) |
| `InfoBanner` | Không có TikTok account; tái dụng composable đã có |

**Signature thay đổi:**
- `ConfigScreen(vm, golikeVm)` — thêm `golikeVm: GolikeViewModel`
- `SettingsLayout(state, onSet, golikeVm, ...)` — pass-through
- `ConfigActivity` tạo thêm `golikeVm: GolikeViewModel by viewModels { GolikeViewModel.Factory(...) }`

---

## 5. GolikeSummaryCard trên Dashboard (`DashboardScreen.kt`)

Card mới compact hiển thị dưới nút Start khi ở trạng thái idle.

- **Chưa đăng nhập:** icon + "Chưa đăng nhập — bấm để cài đặt" + nút TextButton → ConfigActivity
- **Đã đăng nhập:** avatar box, tên, rank, coin to rõ, tiktokHold (+X coin), số job còn lại
- Border màu GolikeGold khi logged in, BorderDark khi chưa
- Ẩn hoàn toàn khi `state.isLoading == true` (không flash empty card lúc khởi động)

**Propagation chain:**
```
DashboardActivity → golikeVm: GolikeViewModel
DashboardActivity.setContent → MainScreen(golikeVm)
MainScreen → DashboardScreen(vm, golikeVm)
DashboardScreen → IdleView(state, vm, golikeState)
IdleView → GolikeSummaryCard(golikeState, onSetup)
```

---

## 6. Version bump

| File | Trước | Sau |
|---|---|---|
| `android/app/build.gradle` | `versionCode 20`, `versionName "v1.1.8"` | `versionCode 21`, `versionName "v1.1.9"` |
| `.github/workflows/build.yml` | default version `1.1.7`, build `19` | default version `1.1.9`, build `21` |

---

## Files thay đổi (tóm tắt)

**Mới:**
- `android/app/src/main/kotlin/com/atpro/golike/GolikeModels.kt`
- `android/app/src/main/kotlin/com/atpro/golike/GolikeApi.kt`
- `android/app/src/main/kotlin/com/atpro/golike/GolikeRepository.kt`
- `android/app/src/main/kotlin/com/atpro/ui/golike/GolikeViewModel.kt`
- `.claude/context/golike_api.md`

**Sửa:**
- `android/app/src/main/kotlin/com/atpro/data/FarmConfig.kt`
- `android/app/src/main/kotlin/com/atpro/data/LocalRepository.kt`
- `android/app/src/main/kotlin/com/atpro/ui/config/ConfigViewModel.kt`
- `android/app/src/main/kotlin/com/atpro/ui/config/ConfigScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/config/ConfigActivity.kt`
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardActivity.kt`
- `android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt`
- `android/app/build.gradle`
- `.github/workflows/build.yml`

---

## Rollback

```bash
git diff HEAD -- android/app/src/main/kotlin/com/atpro/golike/ \
                 android/app/src/main/kotlin/com/atpro/ui/golike/ \
                 android/app/src/main/kotlin/com/atpro/data/FarmConfig.kt \
                 android/app/src/main/kotlin/com/atpro/ui/config/ \
                 android/app/src/main/kotlin/com/atpro/ui/dashboard/ \
                 android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt \
                 android/app/build.gradle .github/workflows/build.yml
```

Xóa 4 file mới trong `golike/` và `ui/golike/`, revert config/dashboard/main theo diff.

---

## Blockers / TODO

- `skipAds` field đã có trong `FarmConfig` + UI nhưng `AutomationEngine`/`PopupHandler` chưa có
  logic detect quảng cáo TikTok → cần implement ở v1.2.x (xem `technical_debt.md`)
- `completeJob()` trong `GolikeViewModel` gọi API complete nhưng chưa trigger thực tế từ
  `AutomationEngine` — tích hợp deep vào automation loop sẽ ở giai đoạn kế tiếp
