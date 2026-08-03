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
- **Encryption**: Sensitive fields (credentials, tokens) must go through `StringEncryptor`

## Build constraints

- **Single APK**: No split APKs, no dynamic delivery. Device storage may be limited
- **Flutter SDK**: Pinned to `>=3.19.0`. Do not upgrade without testing on target devices
- **Dart SDK**: `>=3.2.0 <4.0.0`
- **flutter_riverpod**: `^2.4.9` — do not upgrade major without Dart version check
- `share_plus`: pinned `^7.2.2` (v9 does not exist)
- `flutter_lints`: pinned `^3.0.1` (v4 requires Dart 3.3+)

## Code constraints

- **No monolithic files**: `Entities.kt` and `Daos.kt` are current exceptions — split them when touching
- **No circular deps between modules** — see architecture.md dependency rules
- **Flutter screens hold no business logic** — all logic in Kotlin
- **FlutterBridge is the only cross-layer boundary** — no direct Android API calls from Dart

## Process constraints

- Every major decision → ADR in `.claude/decisions/`
- Revisions: keep last 10 only
- Change must be rollback-safe: prefer additive changes, avoid destructive schema migrations without migration scripts
