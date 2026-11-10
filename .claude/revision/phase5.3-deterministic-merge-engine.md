# Phase 5.3 — Pure deterministic merge engine

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Base commit:** `81c3008c2600e67c8b56a91927e910603f6bb3ec`  
**Scope:** Pure Kotlin merge model, conflict results and JVM tests only

## Implemented

- Added `BackupMergeEngine`, with validated v3 snapshot inputs and in-memory-only result construction.
- Added stable `BackupMergeResult`, `BackupMergeConflict` and conflict reason codes. Conflicts are deduplicated and sorted by dataset/key/reason.
- Accounts merge by normalized username. Status + checkpoint disagreement always conflicts regardless of device timestamp. Creation-time disagreement conflicts. Matching statuses retain the max timestamp only as metadata.
- Contributions merge by normalized (account, installation) identity with component-wise MAX. Remote-only accounts receive a zero-count local installation baseline to satisfy the existing v3 local-source invariant without copying aggregate totals.
- Closed sessions merge by `syncId`; same-ID differing content conflicts. The 100,000-session bound fails as a conflict rather than truncating history.
- Safe configurations accept only input snapshots that pass the existing allowlist validator. Same-key different values conflict regardless of clock; identical values are idempotent.
- Schedules conflict when definitions differ; matching definitions merge and are always returned disabled.
- Earnings merge by stable UUID; same-ID differing content conflicts; candidate retention is bounded to 25,000 entries with deterministic timestamp/ID ordering.
- Tombstones merge by UUID. A marker from another installation suppresses a stale matching entity. A marker and entity from the same installation require explicit resolution because snapshot v3 has no recreation generation field. No tombstone garbage collection.
- Output dataset ordering is deterministic and every successful candidate is run through the v3 snapshot validator.
- Updated `AccountStatContributionPolicy.mergeStatus` to remove timestamp-based status LWW; timestamps remain metadata only when status/checkpoint agree.
- Added dedicated tests for successful merge, deterministic ordering, status/config clock-skew conflicts, creation metadata, component-wise stats, schedule disabling/conflicts, session/earning identity, tombstone delete/recreate/ID reuse, remote-only contribution baseline and earnings bound.

## Safety boundaries

- No Room write or schema changes.
- No EarningsStore file write.
- No Supabase schema, policies, Auth settings, backup row or credentials changed.
- No transport, RPC, Outbox acknowledgement, tombstone acknowledgement or WorkManager activation.
- Existing SyncWorker remains preflight-only; `remote_executor_disabled` is unchanged.
- `main` was not modified.
- No GitHub Actions workflow was dispatched.

## Verification

Tests were authored but not run. Android build/JUnit verification remains pending for the single final build gate. The candidate merge is source-reviewed only; it must not be wired into a restore/remote path until Phase 5.4+ gates are completed and final verification passes.
