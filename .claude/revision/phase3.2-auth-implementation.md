# Phase 3.2 — Supabase Auth implementation

- **Date:** 2026-11-09
- **Branch:** `feature/atpro-supabase-phase3-1-foundation`
- **Scope:** Implement the Android Auth repository on top of the 3.1 foundation

## Completed

- Implemented email/password signup with response-based handling for both email-confirmation-required and immediate-session outcomes.
- Implemented email/password sign-in using the Supabase password token grant.
- Implemented restore of the encrypted local session.
- Implemented refresh-token exchange and persistence of the rotated session.
- Serialized Auth/session mutations with a mutex to avoid concurrent refresh-token use inside the repository instance.
- Added a user-profile lookup for successful token responses that do not include a user object. Refresh reuses the existing user identity to avoid an extra request.
- Implemented local-first sign-out using the local scope, followed by best-effort server-side revocation.
- Added bounded Vietnamese validation/network/Auth error messages.
- Added ADR-0016 to document the session lifecycle and security decisions.

## Files changed

- `android/app/src/main/kotlin/com/atpro/auth/domain/AuthModels.kt`
- `android/app/src/main/kotlin/com/atpro/auth/domain/AuthRepository.kt`
- `android/app/src/main/kotlin/com/atpro/auth/data/SupabaseAuthRepository.kt`
- `.claude/decisions/ADR-0016-supabase-auth-session-lifecycle.md`
- `.claude/revision/phase3.2-auth-implementation.md`

## Not changed

- No UI, navigation, or application startup wiring.
- No Room entities, DAOs, migrations, or LocalRepository behavior.
- No automation, AccessibilityService, Follow4Follow, earnings, scheduler, or notification logic.
- No dependency, Gradle, Kotlin, or Android Manifest changes.
- No Supabase dashboard settings, database schema, RLS policies, or data rows.

## Verification and limitations

- Reviewed the implementation against the current Supabase Auth REST endpoint documentation.
- Confirmed the existing Android Manifest already declares Internet permission.
- No live Auth request was sent; no test account was created.
- No Android compilation or unit/device test was run in this round. The source is not yet build-verified.
- No GitHub Actions workflow was started, per the project rule to run the workflow only once after all implementation and review work is complete.
- The repository is not yet wired into an application-wide singleton; that belongs to the Auth UI/application integration round.

## Follow-up

- Phase 3.3: Auth UI, account state, app-scoped repository wiring, and navigation.
- Phase 3.7: full source/security review and fixes.
- Phase 6: Auth QA, including confirmation-enabled and confirmation-disabled signup outcomes.
