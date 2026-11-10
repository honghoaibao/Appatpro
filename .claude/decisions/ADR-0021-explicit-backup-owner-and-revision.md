# ADR-0021 — Explicit Backup Owner Binding and Revision Checkpoint

- **Status:** Accepted for Phase 3.4.4
- **Date:** 2026-11-09
- **Scope:** Local installation identity, Auth ownership boundary and post-commit revision metadata
- **Related:** ADR-0018, ADR-0019, ADR-0020

## Context

Room data survives sign-out and is shared by the local installation. A device can therefore contain data before the first AT PRO login, and a later login can belong to a different Supabase Auth user. The app must not silently attach existing local data to whichever account happens to be signed in. Revision metadata also needs protection against delayed responses from older sync attempts.

## Decisions

1. Keep the existing random installation UUID in the Room singleton metadata row. It is an app-installation identifier and must never be replaced with Settings.Secure.ANDROID_ID or another hardware identifier.
2. Upgrade Room from version 2 to version 3 using additive MIGRATION_2_3, adding nullable ownerBoundAt and lastRevisionConfirmedAt. Preserve the existing v1→v2 migration in the migration chain.
3. The Auth owner key is the canonical UUID from the active AuthSession.user.id; invalid/non-UUID identities fail closed.
4. An unbound installation (ownerUserId == NULL) can be linked only after an explicit confirmation in Settings. The dialog explains that future backup ownership is tied to the current AT PRO account and that the action does not upload, download, or delete data.
5. Binding is a one-way first-use operation. The DAO update is conditional on ownerUserId IS NULL. There is no automatic unbind/reassign method. Sign-out does not clear the owner binding or local data.
6. On later login:
   - same Auth UUID → ownership state is linked;
   - different Auth UUID → show a blocked state and refuse future upload/restore;
   - signed out → no cloud operation;
   - corrupt or incomplete metadata → fail closed.
7. Expose a fresh isBoundToCurrentUser() check for future sync entry points. UI state is informational and must not be used as the sole authorization decision.
8. A remote revision is recorded locally only after the future server RPC confirms an atomic R→R+1 commit. Validate canonical Auth owner UUID, exact next revision, lowercase 64-character SHA-256 checksum and positive schema version.
9. Persist the revision with a local compare-and-set requiring both the same owner UUID and the expected local revision R. Store checksum/schema version and confirmation/success timestamps atomically. Late responses and stale local revisions are rejected.
10. This phase does not call Supabase, update server schema/RLS/grants, upload/restore snapshots, or schedule background work.

## Consequences

- Local data has a durable and explicit owner boundary before any future cloud transfer.
- Reinstall identity remains independent of physical device identifiers.
- Account switching cannot silently expose or reassign local data.
- A future SyncManager must call isBoundToCurrentUser() immediately before cloud operations and acknowledge Outbox events only after server revision confirmation.
- The UI can explain ownership and show revision state without claiming that cloud backup is already active.

## Verification

Source-level review only. Room v2→v3 migration execution, Android build, UI interaction test, owner-switch instrumentation test, and remote RPC verification have not been run. GitHub Actions are intentionally deferred to the single final build phase.
