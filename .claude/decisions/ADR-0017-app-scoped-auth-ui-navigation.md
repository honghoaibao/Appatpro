# ADR-0017: App-Scoped Auth UI and Protected Navigation

- **Status:** Accepted
- **Date:** 2026-11-09
- **Scope:** Login/registration UI, session state ownership, launcher routing, and local logout

## Context

The Auth repository implementation is complete, but the launcher currently opens the main dashboard without checking whether an AT PRO account session exists. The app needs a simple entry flow that does not change the dashboard's business logic or the local Room data model.

## Decision

1. Create one lazy, process-wide `AuthRepository` instance in `AtProApplication`. All activities and screens use the same repository, session StateFlow, and refresh mutex.
2. The launcher `MainActivity` restores the encrypted session before navigating. It opens `DashboardActivity` only when a usable local session is available; otherwise it opens the internal, non-exported `AuthActivity`.
3. `DashboardActivity` performs a second session check for notification or internal launches that bypass the launcher. It redirects to Auth when no session can be restored.
4. Use a Compose email/password screen with login and registration modes, client-side email/confirmation validation, password visibility toggles, loading feedback, and server error messages.
5. When signup returns a user without a session, show the email-confirmation-required state and return the form to login mode. When signup returns a session, persist it through the existing encrypted session store and open the dashboard.
6. Show the current account email and a confirmation-gated local logout row in Settings.
7. On logout, clear the encrypted local session first and emit a null session only after persistent storage has been cleared. Release the local session mutex before attempting best-effort remote revocation so a subsequent login is not blocked by a slow network.
8. Keep the dashboard, Room, automation, Follow4Follow, scheduled jobs, and notification business logic unchanged.

## Consequences

- The Auth repository remains separate from UI and feature code. UI observes its session StateFlow.
- The process-wide repository is important: multiple repository instances would have independent mutexes and in-memory session state.
- The account gate controls entry to the main dashboard, not access to local Room files at the OS level.
- Login and registration are supported. Password recovery and email confirmation deep-link handling remain future work.
- The local Room database is not cleared on logout; this is made explicit in the logout confirmation.

## Verification status

- Source and Android manifest wiring were reviewed.
- No real Auth request, signup, login, or logout was sent.
- No Android build, unit test, or device test was run in this round.
- No GitHub Actions workflow was started.
