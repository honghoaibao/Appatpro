# ADR-0016: Supabase Auth Session Lifecycle

- **Status:** Accepted
- **Date:** 2026-11-09
- **Scope:** Android Auth repository implementation only
- **Related:** ADR-0015

## Context

The 3.1 foundation provides a small HTTP transport, Auth domain models, and an encrypted Android Keystore session store. The next step is to implement the Auth lifecycle without adding a Supabase SDK or changing the rest of the application.

Supabase access tokens are short-lived and refresh tokens are rotated. The Auth token endpoint may return token fields without a full user object, so the app cannot assume every token response contains user profile data.

## Decision

1. Implement email/password signup at `POST /auth/v1/signup`.
2. Implement password sign-in at `POST /auth/v1/token?grant_type=password`.
3. Implement refresh at `POST /auth/v1/token?grant_type=refresh_token`.
4. When a successful token response does not contain a user, retrieve the authenticated user from `GET /auth/v1/user`. During refresh, reuse the current session's user identity because refresh does not change the account.
5. Determine the signup outcome from the response itself. When tokens are returned, persist the encrypted session and report that confirmation is not pending. When only a user is returned, report that email confirmation is required. Do not hard-code the project's current Confirm Email setting.
6. Serialize signup, sign-in, restore, refresh, and sign-out through one repository mutex to prevent concurrent refresh-token rotation within the repository instance.
7. Persist only Auth sessions using the existing AES-GCM Android Keystore store. Never persist or log passwords, access tokens, or refresh tokens in plaintext.
8. Implement sign-out with `scope=local`. Clear this device's local session first and emit a null session only after persistent storage has been cleared. Release the repository mutex before best-effort server revocation, so network latency does not block a subsequent sign-in. A network failure must not leave the user signed in locally or affect other devices.
9. Keep HTTP error mapping bounded and avoid implicit request retries. Preserve the saved session after transient refresh failures; clear it when Supabase rejects the refresh token as invalid or unauthorized.

## Consequences

- The implementation uses the existing `HttpURLConnection`, coroutines, and `org.json`; no dependency or Gradle changes are needed.
- Auth remains separate from UI, Room, Follow4Follow, and automation logic.
- The application layer must provide a single shared AuthRepository instance so the refresh mutex protects the app's active session lifecycle.
- A local sign-out can complete while offline. If server revocation fails, the device no longer retains the refresh token, but Supabase may retain the server-side session until it is explicitly revoked or a configured session-lifetime/inactivity policy terminates it. Refresh tokens do not expire by default.
- Email confirmation, password recovery, deep-link confirmation handling, UI, and app startup wiring remain separate follow-up work.

## Verification status

- Source was reviewed against the current Supabase Auth REST endpoint documentation.
- No live signup, login, refresh, or logout request was sent.
- No Android build, unit test, device test, or GitHub Actions workflow was run in this round.
- No Supabase project setting, database schema, RLS policy, or remote data was changed.
