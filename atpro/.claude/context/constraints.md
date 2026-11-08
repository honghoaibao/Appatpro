# Constraints — Appatpro

Hard constraints that must never be violated. Check this file before any architecture decision.

## Device constraints

- **Target OS**: Android 10–14 (API 29–34)
- **Target hardware**: Mid-range Vietnamese market devices — Xiaomi Redmi, OPPO A-series, Samsung A-series
- **RAM budget**: Assume 3–4GB total, app should not exceed 300MB RSS while farming
- **Battery**: Farming runs foreground — FarmForegroundService required. Must handle Doze mode and OEM battery optimisation (MIUI, ColorOS, One UI)
- **No root**: All automation via AccessibilityService only. No ADB shell, no root-level access
- **No emulator assumption**: Must work on real devices with TikTok installed

## TikTok constraints

- TikTok version changes frequently — UI node IDs are unstable. `NodeTraverser` and `PopupHandler` must use resilient selectors (content-desc, text, class — not view-id where avoidable)
- TikTok actively detects automation. Interaction timing must include randomisation
- `FarmConfig.likeRate` and `followRate` must never exceed safe thresholds (current defaults: 30%, 15%)
- Deep links (`TikTokDeepLinks`) are the safest way to switch accounts — prefer over UI navigation

## Data constraints

- **Offline-first**: All data stored in Room (SQLite). No cloud sync, no internet required for core farming
- **No Supabase**: Removed. Do not re-introduce cloud DB
- **Encryption**: Sensitive fields (credentials, tokens) must go through `StringEncryptor` (Follow4Follow: token qua `F4fTokenCodec`; **không bao giờ lưu mật khẩu**; không log token/`x-sign`)
- **Follow4Follow**: mọi request phải ký `x-ts`/`x-sign` (`F4fSigner`); `complete-job` chỉ gọi sau ≥5s kể từ lúc mở link; không complete/skip khi `accountActive = null`
- **Màu giao diện**: chỉ lấy màu qua `AtColors.*` (getter động theo chế độ Sáng/Tối). KHÔNG sao chép vào `val` cấp file / enum / bảng khởi tạo một lần (bị đóng băng, không đổi theo chế độ) — dùng `val X get() = AtColors.Y` hoặc lambda/enum tông (ADR-0009). Hộp thoại dùng `AtDialog`, ô nhập dùng `AtTextField`; không dùng `AlertDialog`/`FilterChip` Material trực tiếp
- **Thứ tự phát hiện acc**: `detectCurrentAccount()` PHẢI chạy trước `openSwitchPopup()` (popup/Cài đặt TikTok che tab Hồ sơ) — cả nuôi acc và task mode; không bao giờ click vào acc ở vị trí 0 của switch popup (là acc đang login, no-op)
- **Rút tiền F4F**: luôn qua hộp thoại xác nhận; không bao giờ tự thử lại yêu cầu rút/nhận hoa hồng (tránh trùng)

## Build constraints

- **Single APK**: No split APKs, no dynamic delivery. Device storage may be limited

## Code constraints

- **No monolithic files**: `Entities.kt` and `Daos.kt` are current exceptions — split them when touching
- **No circular deps between modules** — see architecture.md dependency rules
- **Flutter screens hold no business logic** — all logic in Kotlin
- **FlutterBridge is the only cross-layer boundary** — no direct Android API calls from Dart

## Process constraints

- Every major decision → ADR in `.claude/decisions/`
- Revisions: keep last 10 only
- Change must be rollback-safe: prefer additive changes, avoid destructive schema migrations without migration scripts
