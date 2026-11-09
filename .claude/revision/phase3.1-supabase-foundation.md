# Phase 3.1 — Supabase foundation

- **Date:** 2026-11-09
- **Branch:** `feature/atpro-supabase-phase3-1-foundation`
- **Base:** `main`
- **Scope:** Additive Android Auth/network foundation only

## Changed

- Added centralized Supabase project configuration using the publishable client key.
- Added injectable `HttpURLConnection` transport with IO dispatching, timeouts, safe relative-path checks, explicit bearer-token support, and no implicit retry/logging.
- Added Auth user/session/result models and the `AuthRepository` contract.
- Added an `AuthSessionStore` boundary and Android Keystore AES-GCM implementation for local session persistence.
- Updated data/security constraints and recorded ADR-0015 to replace the former blanket prohibition on Supabase with a limited Auth/backup scope.

## Not changed

- No Room entity, DAO, migration, or `LocalRepository` changes.
- No automation, AccessibilityService, Follow4Follow, UI, or navigation changes.
- No Supabase database/schema/RLS/grant changes.
- No real Auth request or cloud data write.
- No Gradle, Kotlin, Android Gradle Plugin, or dependency version changes.
- No GitHub Actions workflow was started.

## Files

- `android/app/src/main/kotlin/com/atpro/network/supabase/SupabaseConfig.kt`
- `android/app/src/main/kotlin/com/atpro/network/supabase/SupabaseHttpClient.kt`
- `android/app/src/main/kotlin/com/atpro/auth/domain/AuthModels.kt`
- `android/app/src/main/kotlin/com/atpro/auth/domain/AuthRepository.kt`
- `android/app/src/main/kotlin/com/atpro/auth/data/AuthSessionStore.kt`
- `android/app/src/main/kotlin/com/atpro/auth/data/AndroidKeystoreAuthSessionStore.kt`
- `.claude/context/constraints.md`
- `.claude/decisions/ADR-0015-supabase-auth-cloud-data-portability.md`

## Verification and limitations

- Source was reviewed against the repository's Kotlin 1.9.25 / Android API 24+ baseline.
- No full Android compile or device test was run in this round. GitHub Actions were intentionally not started per the project workflow.
- The current Supabase project authentication provider/confirmation settings still need verification before completing signup/sign-in.

## Rollback

Revert the phase 3.1 commits on this feature branch. The changes are additive; no local DB or remote schema has been migrated.
