# Phase 5 C3 — Session Isolation & Conflict

Date: 2026-11-16  
Branch: `feature/supabase-completion-2026-11-16`  
Baseline: v1.12.6 / versionCode 46

## Scope

Implement a process-wide session-generation fence between Supabase Auth and automatic backup workers. C3 is source implementation only; it does not change Supabase production resources, Room schema, migrations, the main branch, or GitHub Actions dispatch state.

## Changes

- Added `SyncSessionGuard` and immutable `SyncExecutionContext(ownerUserId, generation, installationId)`.
- Auth session restore/sign-in/refresh activates the current owner generation. Explicit logout invalidates immediately and invalidates again while holding the Auth repository mutex, covering an in-flight sign-in/refresh that could otherwise reactivate the old generation during logout. If local session deletion fails, the still-valid session is reactivated under a new generation; old worker contexts remain invalid.
- The application injects the same guard into `SupabaseAuthRepository` and `SyncWorker`.
- Each worker captures its owner + installation + generation after restoring the encrypted Auth session and checking the durable Room owner binding.
- CAS checks the context before/after remote reads and runs the RPC commit through a guard lease. Logout waits for an already-started CAS lease; after invalidation no old context can start another CAS.
- Restore and the full local acknowledgement pipeline (journal replay/staging, Room checkpoint + exact Outbox acknowledgement, Earnings generation acknowledgement) execute only while the captured context is protected by the guard lease.
- Outbox retry metadata writes are guarded. Stale workers do not report old outcomes or notifications over a newer session's status.
- No database migration or Supabase schema/Auth/RPC change.

## Tests added

- `SyncSessionGuardTest`: same-owner logout/login generation renewal, different-owner capture rejection, stale-context block rejection, and logout waiting for an already-running protected operation.
- `SupabaseAuthRepositoryTest`: logout invalidates an existing worker context; a failed local clear can reactivate the live session only under a fresh generation.
- `BackupCasCoordinatorTest`: invalidation after remote read prevents the CAS commit.
- `BackupAcknowledgementCoordinatorTest`: invalidated context cannot run restore, Room acknowledgement, or Earnings acknowledgement.

## Verification

- Source-level review/contract checks only.
- Android compile: NOT RUN.
- JUnit/Robolectric: NOT RUN.
- APK build: NOT RUN.
- Physical-device race testing: NOT RUN.
- GitHub Actions: no manual dispatch or rerun requested/performed. Automatic push-triggered workflow behavior is not asserted.
- Supabase production resources: unchanged.
- `main`: unchanged.

## Runtime limitation and recovery

The guard is process-local by design; it does not add persistent session metadata or identify hardware. If the process dies, the old coroutine/worker instance dies with it. A restarted worker must restore the encrypted local Auth session and capture a new context. A remote CAS already in flight is allowed to finish before logout can pass the shared guard lease; once logout completes, the old context cannot start a further remote CAS or local restore/ack. If a server-side commit succeeds but the client loses its response, the next valid session's read/merge/CAS cycle reconciles the remote revision without replaying the old local acknowledgement.
