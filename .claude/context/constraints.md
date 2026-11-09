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

- **Local-first**: Room remains the source of truth for app data. Core features must continue to work offline and must not wait for Supabase.
- **Cloud data portability**: Supabase is allowed only for AT PRO account authentication and the later remote backup/sync layer. It must not become a runtime dependency of the automation engine or replace Room as the local database.
- **Sync safety**: Future sync must be authenticated, owner-scoped, retryable, and non-destructive on transient failures. Do not overwrite good local data with an incomplete or stale remote response.
- **Outbox atomicity**: Every future Room write that belongs in backup must append/update its Outbox event in the same Room transaction. Never acknowledge pending events until the remote snapshot revision is confirmed. Coalesce only older UPSERTs; preserve DELETE tombstones.
- **Outbox ordering**: Process durable changes by monotonic local sequence, not timestamp alone. Closed sessions use a stable per-session UUID; active sessions are not queued for backup.
- **Backup owner binding**: Bind local sync metadata to one Supabase Auth user UUID. If a different user signs in, block automatic restore/upload until the user explicitly resolves the local-data ownership change.
- **Backup allowlist**: Export only explicitly approved data/config fields. Never serialize the full configs table, SharedPreferences, Auth session store, Follow4Follow session store, webhook endpoints, passwords, tokens, API keys, or other secrets.
- **Snapshot safety**: Validate size (7 MiB encoded / 32 MiB decompressed), SHA-256 checksum, canonical payload format and schema version before restore. Stage and merge before applying; never replace good local data with a partial/invalid snapshot.
- **Earnings dirty tracking**: File-backed earnings use stable UUIDs and a durable generation marker. A future remote commit may acknowledge only the captured generation; newer local writes remain dirty.
- **Revision safety**: Multi-device writes must use an atomic expected-revision compare-and-swap contract. Blind upsert/last-writer-wins snapshot replacement is prohibited.
- **Schedule restore**: Back up user schedule definitions if included, but restore them disabled. Never automatically reactivate WorkManager automation on a new installation.
- **Cloud target**: The first sync format uses the per-user public.user_backups snapshot. Do not write to the normalized accounts/sessions/configs/farm_logs/devices tables until their schema/ownership contracts have passed Phase 4.1 review.
- **Supabase client key**: Android may contain only the project's publishable key. Never embed a Supabase secret key or `service_role` key in the APK or repository.
- **Auth credentials**: Never persist an AT PRO password. New Supabase access/refresh tokens must be encrypted with AES-GCM using a key held by Android Keystore; never log tokens or passwords.
- **Legacy Follow4Follow storage**: Leave its existing implementation unchanged in this scope. `StringEncryptor` uses XOR obfuscation, not cryptographic encryption; do not reuse it for Supabase or new sensitive data. A separate migration is required to improve legacy F4F token storage.
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
