# ADR-0015: Supabase Auth and Cloud Data Portability

- **Status:** Accepted
- **Date:** 2026-11-09
- **Scope:** Authentication and future backup/sync foundation; no automation behavior changes
- **Supersedes:** The blanket "No Supabase" constraint from the earlier local-only decision. Room-first and offline operation remain in force.

## Context

Appatpro previously kept all app data in Room and explicitly prohibited Supabase. The new product requirement is to let users sign in and preserve app data through reinstall or device changes. The existing AT App Supabase project already provides Auth and owner-scoped database tables, but its schema still needs a dedicated review and migration before any app data sync begins.

The app targets Android API 24+ and already uses Kotlin coroutines, JSON serialization, and `HttpURLConnection`. Adding a large Supabase SDK at this stage could introduce Kotlin/Gradle compatibility changes and extra dependencies without helping the narrow first step.

## Decision

1. Keep Room as the local source of truth. Core app behavior remains offline-first; Supabase must not be called from the automation engine.
2. Use the existing AT App Supabase project for AT PRO user authentication and, in later phases, remote backup/sync only.
3. Build a small, injectable Auth HTTP transport on top of `HttpURLConnection` and the existing coroutine stack. Do not add a new networking dependency or upgrade Kotlin/AGP/Gradle for this foundation.
4. Put Auth domain models and the `AuthRepository` contract in a separate package. Keep UI, Room, Follow4Follow, and automation code independent of Supabase implementation details.
5. Persist only the Auth session, never the user's password. Encrypt the session payload with AES-GCM and an Android Keystore key. Do not use the legacy XOR-based `StringEncryptor` for Supabase tokens.
6. Embed only the Supabase publishable client key. A publishable key is not a server secret and still requires correctly configured RLS; secret and `service_role` keys are forbidden in the Android client.
7. Defer registration/login implementation, UI, schema/RLS migrations, data sync, restore, conflict resolution, WorkManager, and QA to their separately approved rounds.

## Consequences

- Existing Room entities, DAO contracts, `LocalRepository`, foreground services, Follow4Follow, and automation behavior remain unchanged in this round.
- The new network and Auth packages are additive and can be removed independently if the project rolls back to local-only operation.
- The next code round can implement signup/sign-in/refresh/sign-out using the transport and encrypted session store.
- No cloud data should be written until the Supabase schema, RLS policies, table grants, and ownership keys have passed the planned review.

## Verification status

This ADR documents the architecture decision only. The 3.1 code foundation does not perform a live Auth request, write database data, or change the Supabase project.
