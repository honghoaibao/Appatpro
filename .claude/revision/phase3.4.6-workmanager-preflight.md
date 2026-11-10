# Phase 3.4.6 — WorkManager Scheduling and Bounded Batch Preflight

**Date:** 2026-11-09  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Implemented in source; Android build and tests not executed

## Implemented

- Reused the existing `androidx.work:work-runtime-ktx:2.9.0` dependency; no Gradle or manifest changes were required.
- Added a unique one-time WorkManager request with CONNECTED network constraint, a 20-second debounce and KEEP coalescing. No periodic work or polling is added.
- Added scheduling checks at app startup, after committed account/session/config mutations, when the Auth user changes, and after explicit first-use owner binding.
- Added a metadata-only Outbox head query that calculates payload byte length in SQLite rather than loading potentially large JSON values into memory.
- Added dataset-level pending count so the worker can detect an account event anywhere in the Outbox, not only in the inspected head.
- Added bounded batch planning: maximum 50 events and 512 KiB payload bytes, monotonic sequence order, retry-window barrier, and oversized/invalid event detection.
- Added a CoroutineWorker local preflight: local encrypted session restore, owner-bound check, pending data/earnings check, and batch candidate evaluation.
- The worker has no Supabase HTTP/repository dependency and does not mutate Outbox, retry state, local revision, earnings generation or backup data.

## Safety boundaries

- Any pending account event blocks the snapshot under ADR-0022.
- Even when a non-account candidate batch is found, the worker returns `remote_executor_disabled`; it does not upload or acknowledge anything.
- A successful WorkManager result means only that preflight completed, not that the cloud backup succeeded.
- Room remains the source of truth and offline behavior is unchanged.

## Changed files

- android/app/src/main/kotlin/com/atpro/db/dao/SyncOutboxDao.kt
- android/app/src/main/kotlin/com/atpro/sync/SyncBatchPolicy.kt
- android/app/src/main/kotlin/com/atpro/sync/SyncWorkScheduler.kt
- android/app/src/main/kotlin/com/atpro/sync/SyncWorker.kt
- android/app/src/main/kotlin/com/atpro/data/LocalRepository.kt
- android/app/src/main/kotlin/com/atpro/AtProApplication.kt
- android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt
- android/app/src/test/kotlin/com/atpro/sync/SyncBatchPolicyTest.kt
- .claude/context/cloud_sync_architecture.md
- .claude/context/current_focus.md
- .claude/decisions/ADR-0023-workmanager-preflight-and-batch-planning.md
- .claude/revision/phase3.4.6-workmanager-preflight.md

## Not changed

- No Supabase HTTP request, RPC, schema, table, row, RLS, grant or Auth setting.
- No upload, download, restore, remote revision update or Outbox acknowledgement.
- No account merge logic or Room migration; ADR-0022 remains the required next gate.
- No automation engine, AccessibilityService, scheduler definitions, FarmForegroundService or Follow4Follow implementation changes.
- No GitHub Actions run; `main` remains unchanged.

## Verification status

- Reviewed the source wiring and static invariants: unique one-time work, CONNECTED constraint, no periodic request, bounded metadata-only Outbox query, sequence/retry barriers, account conflict gate, and no remote transport/acknowledgement path.
- Added pure JVM tests for empty queues, sequence order, batch count/byte limits, retry blocking and account gating; tests have not been executed.
- Not verified: Gradle compile, JUnit execution, WorkManager runtime initialization/worker scheduling, real network constraints, device background execution, battery behavior or APK build.
- Next required gate: Phase 3.4.7 — implement and test the ADR-0022 account status/stat timestamp and per-installation contribution model before any snapshot write or event acknowledgement is enabled.
