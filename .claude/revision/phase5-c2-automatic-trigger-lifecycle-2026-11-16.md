# Phase 5 C2 — Automatic Trigger Lifecycle

**Date:** 2026-11-16  
**Branch:** `feature/supabase-completion-2026-11-16`  
**Baseline:** v1.12.6 / versionCode 46  
**Scope:** Login/bootstrap, local mutation triggers, Earnings file trigger, unique-work coalescing and network-constrained retry.

## Implemented

- Added pure `SyncWorkSchedulingPolicy` and six JVM policy tests.
- `SyncWorkScheduler.scheduleBootstrap(context)` now enqueues a forced remote check even if the Outbox and Earnings dirty generation are clean. It is called only after the current Auth owner is verified as already bound, or immediately after explicit owner confirmation.
- `MainScreen` now performs the bootstrap check when a signed-in owner session is restored or becomes active. The owner-confirmation action also triggers bootstrap rather than merely checking pending local events.
- Existing `LocalRepository` backed mutations already called `requestBackupPreflight()` after the Room + Outbox transaction; those call sites were retained.
- Added `EarningsStore.setSyncTrigger`. A successful file-backed `EarningsStore.record()` invokes the callback after leaving its storage lock. `AtProApplication` connects the callback to the app-scoped scheduler. A crash between the durable dirty marker/write and the callback is recovered by the existing Application startup pending-data preflight.
- The scheduler uses one unique name, `atpro_backup_sync`, for automatic, bootstrap and manual work. Requests require `NetworkType.CONNECTED`, use the existing 20-second local-write debounce and exponential 30-second backoff.
- When no worker is running, a local mutation request uses `KEEP` so an already queued request captures the newest Room/Outbox state. When a worker is running, at most one follow-up is appended with `APPEND_OR_REPLACE`; additional writes coalesce into the queued follow-up.
- Bootstrap requests do not cancel a running worker. They append after it. A pending non-running non-bootstrap request may be replaced by bootstrap because the forced worker also captures pending local data. An already queued bootstrap or manual remote-check satisfies the bootstrap request.
- Manual sync retains the existing 30-second cooldown and shared unique-work name; its queue decision is serialized with automatic scheduling by the scheduler mutex.
- No ConnectivityManager callback or polling loop was added. WorkManager's CONNECTED constraint retains queued work while the device is offline.

## Tests and source inspection

- Added `SyncWorkSchedulingPolicyTest` for no-op, KEEP, one-follow-up coalescing, bootstrap deduplication, append-after-running and replace-pending behavior.
- Re-fetched all C2 implementation and test files from the feature branch.
- Source-contract inspection: 13/13 checks passed for trigger wiring, owner gate, unique-work policy, debounce, CONNECTED constraint, retry/backoff, Earnings callback, manual cooldown and tests.
- Existing `LocalRepository` preflight call sites were inspected and retained; no business mutation semantics were changed.

## Verification status

- Android compile: **NOT TESTED**.
- JUnit/Robolectric: **NOT RUN**.
- APK build: **NOT RUN**.
- Physical-device network reconnect, process-death and duplicate-worker checks: **NOT TESTED**.
- No workflow was explicitly dispatched or rerun. GitHub commit-status lookup returned no status entries; the available workflow-run lookup is limited to pull-request-triggered runs and does not establish whether a push-triggered workflow ran automatically.
- Supabase production schema, Auth settings, RPCs and user data: **UNCHANGED**.
- `main`: **UNCHANGED**.

## Follow-up

- C3 must still add robust execution/session generation isolation around logout and in-flight sync operations.
- C4 must complete state UI integration/regression verification.
- Sub-phase D must compile, run JUnit/Robolectric and build the APK after explicit permission to run verification workflows.
- RT-01 through RT-16 remain runtime verification items; source changes alone do not mark them PASS.

## Rollback

Revert the C2 scheduler, trigger, Application and MainScreen changes on this feature branch. The changes add no Room schema migration and make no Supabase production change.
