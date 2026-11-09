# Phase 3.3 — Auth UI and protected navigation

- **Date:** 2026-11-09
- **Branch:** `feature/atpro-supabase-phase3-1-foundation`
- **Scope:** Auth screen, process-wide repository wiring, entry routing, and account logout affordance

## Completed

- Added a lazy process-wide AuthRepository to `AtProApplication`, backed by the existing Android Keystore session store.
- Added `AuthViewModel` with login/register form state, email/password validation, confirmation-password matching, and one-shot navigation events.
- Added a Compose `AuthScreen` for email/password login and registration. Includes password visibility toggles, loading feedback, and error/information messages.
- Added a non-exported `AuthActivity`.
- Updated `MainActivity` to restore the encrypted session and route to Dashboard or Auth.
- Added a second session gate to `DashboardActivity` to protect notification/internal launches.
- Added the current signed-in email and a confirmation-gated sign-out option to Settings.
- Refined logout ordering so the encrypted local session is cleared before the UI leaves Dashboard; best-effort remote revocation runs after the local mutex is released.
- Added ADR-0017 to record the Auth UI and navigation architecture.

## Files changed in 3.3

- `android/app/src/main/kotlin/com/atpro/AtProApplication.kt`
- `android/app/src/main/kotlin/com/atpro/MainActivity.kt`
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardActivity.kt`
- `android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt`
- `android/app/src/main/kotlin/com/atpro/ui/settings/SettingsHubScreen.kt`
- `android/app/src/main/kotlin/com/atpro/auth/data/SupabaseAuthRepository.kt`
- `android/app/src/main/kotlin/com/atpro/auth/ui/AuthViewModel.kt`
- `android/app/src/main/kotlin/com/atpro/auth/ui/AuthScreen.kt`
- `android/app/src/main/kotlin/com/atpro/auth/ui/AuthActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `.claude/decisions/ADR-0017-app-scoped-auth-ui-navigation.md`
- `.claude/revision/phase3.3-auth-ui-navigation.md`

## Not changed

- No Room entity, DAO, migration, or `LocalRepository` changes.
- No Dashboard screen business logic, automation engine, AccessibilityService, Follow4Follow, scheduler, earnings, or notification behavior changes.
- No new Gradle dependencies, version upgrades, or permissions.
- No Supabase dashboard setting, database schema, RLS policy, or cloud data change.
- `main` was not modified or merged.

## Verification and limitations

- Reviewed source call sites, activity routing, Auth screen state, manifest declaration, and app-scoped repository wiring.
- No Android build or Kotlin compilation was run. This implementation is not build-verified.
- No unit test, device test, or live Supabase Auth request was run.
- GitHub Actions were intentionally not started; the project's one-time CI run remains reserved for the final build phase.
- Password recovery and email-confirmation deep-link handling are not included.
