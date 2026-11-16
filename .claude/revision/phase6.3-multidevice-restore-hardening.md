# Phase 6.3 — Multi-device restore hardening

- Date: 2026-11-16
- Repository: `honghoaibao/Appatpro`
- Branch: `feature/supabase-completion-2026-11-16`
- Scope: local schedule preservation during remote snapshot restore

## Finding

The restore coordinator previously:
1. Added every local schedule ID to `scheduleIdsToCancel`, even when the restore snapshot contained the same schedule and there was no deletion.
2. Rewrote matching existing schedules using `scheduleConfig(incoming)`, which always serializes `enabled = false`.
3. Also disabled a local schedule absent from the staged snapshot, even though it could have been created locally after snapshot capture.

This meant a successful ordinary cloud sync could disable schedules the user had already enabled on the current installation.

## Changes

- Added `BackupScheduleRestorePolicy` with explicit decisions:
  - `PRESERVE_LOCAL`: a schedule already on this installation retains its local config and activation state.
  - `IMPORT_DISABLED`: an incoming schedule with no local counterpart is imported disabled.
  - `REMOVE_LOCAL`: a durable tombstone removes the schedule and cancels its WorkManager work.
  - `NO_OP`: no local or incoming schedule exists.
- Removed unconditional cancellation of every local schedule during restore.
- For a matching pre-existing local schedule, the restore still compares the definition against the staged snapshot and still rejects concurrent definition edits, but updates only the metadata timestamp while preserving the exact local config value (including `enabled`).
- A local schedule created after snapshot capture and absent from the incoming candidate is preserved unchanged.
- A cloud-only schedule is still created with `enabled = false`; cancellation is scoped to that imported schedule ID.
- Tombstone precedence and same-installation delete/recreate conflict checks remain fail-closed.
- Added `BackupScheduleRestorePolicyTest` with five tests for the policy branches.

## Files changed

- `android/app/src/main/kotlin/com/atpro/sync/BackupRestoreCoordinator.kt`
- `android/app/src/main/kotlin/com/atpro/sync/BackupScheduleRestorePolicy.kt` (new)
- `android/app/src/test/kotlin/com/atpro/sync/BackupScheduleRestorePolicyTest.kt` (new)
- `.claude/context/cloud_sync_architecture.md`
- `.claude/revision/phase6.3-multidevice-restore-hardening.md` (this report)

## Verification / boundaries

- The current branch head was re-read before editing and the branch update uses an expected-SHA lease.
- Policy tests are authored but not executed. No Gradle build, JVM test run, Android device test or WorkManager integration test was performed.
- The current-source audit still shows that Room restore is transactional and the restore journal is replayed during application startup. These properties were not runtime-tested in this phase.
- No production Supabase SQL, table, row, RPC, RLS policy, Auth setting or user data was changed.
- No GitHub Actions workflow was dispatched. No PR was created. `main` remains untouched.
- This phase is not a release-readiness or production-verification sign-off.
