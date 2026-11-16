# Phase 6.2 — Auth and Session Hardening

**Date:** 2026-11-16  
**Branch:** `feature/supabase-completion-2026-11-16`  
**Base:** `main` at `69b3f49b630a6ee9073c3ca64cd65f376ccb501c`

## Implemented

1. **Refresh rejection classification** — the repository clears a saved session only when Supabase returns recognized invalid-refresh/session codes or messages. Unrelated 400/401 responses (for example invalid API key) preserve the existing encrypted session.
2. **Rotated token persistence** — a successful refresh's new token pair is persisted in a non-cancellable section. If storage fails after refresh-token rotation, the potentially consumed old refresh token is cleared best-effort rather than being reused.
3. **Expiry parsing** — only positive absolute/relative expiry fields are accepted; missing or malformed `expires_in` falls back to 3,600 seconds. Overflow is saturated, and an invalid token_type falls back to `bearer`.
4. **HTTP cache controls** — Supabase transport requests add `Cache-Control: no-store` and `Pragma: no-cache`. Existing request/response bounds and HTTPS/redirect constraints remain.
5. **Keystore invalidation recovery** — a permanently invalidated AES-GCM key and its unreadable session payload are removed so the next sign-in can create a new key. The previous session is not recoverable after permanent key invalidation.
6. **Session-driven navigation** — DashboardActivity observes AuthRepository.session. When a background refresh clears the local session, it redirects to Auth and prevents duplicate redirects during sign-out.
7. **13 Auth repository tests added** — signup confirmation flow, token/user lookup, refresh rotation, expiry fallback, refresh error classification, transient failures, refresh persistence failure, local restore, logout scope/order, clear failure, and concurrent refresh serialization.

## Supabase behavior references

- Supabase refresh tokens are rotated; a refresh token is generally single-use, with a short reuse interval intended to mitigate legitimate race/retry scenarios.
- Supabase sign-out supports `local` scope to revoke only the current device session; this app continues using that scope.
- The app retains local-only startup restore and only refreshes during an explicit cloud request/401 recovery. No email-confirmation setting was changed.

## Files changed

- `android/app/src/main/kotlin/com/atpro/auth/data/SupabaseAuthRepository.kt`
- `android/app/src/main/kotlin/com/atpro/auth/data/AndroidKeystoreAuthSessionStore.kt`
- `android/app/src/main/kotlin/com/atpro/network/supabase/SupabaseHttpClient.kt`
- `android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardActivity.kt`
- `android/app/src/test/kotlin/com/atpro/auth/data/SupabaseAuthRepositoryTest.kt`
- `.claude/context/cloud_sync_architecture.md`

## Verification and safety boundaries

- Read back changed source files from the feature branch after edits.
- Tests were authored but **not executed**. Android/Kotlin compilation, real Android Keystore behavior and device navigation remain unverified.
- No live signup/login/refresh/logout was performed.
- No Supabase project settings, keys, schema, RLS, RPC or production records were modified.
- No GitHub Actions workflow was dispatched. No PR was created. `main` remains unchanged.

## Next

Phase 6.3 should review account binding and the complete sync lifecycle under two-device scenarios, especially restore idempotency, stale owner protection, pending Outbox continuity, and local/cloud reconciliation. The single build/JUnit gate remains deferred until the user explicitly authorizes it.
