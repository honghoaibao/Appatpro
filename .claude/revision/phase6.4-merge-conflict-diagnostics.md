# Phase 6.4 — Merge conflict diagnostics

- Date: 2026-11-16
- Repository: `honghoaibao/Appatpro`
- Branch: `feature/supabase-completion-2026-11-16`

## Finding

`BackupMergeEngine` already returned deterministic conflict records with dataset, entity key and reason, but `SyncWorker` reduced them to a generic status code. The Settings UI could only say that two devices changed the same item, making it difficult to identify what needed to be made consistent.

## Changes

- Added `BackupMergeConflictSummary`, a pure, bounded formatter:
  - Stable dataset labels and conflict reason labels.
  - At most three items and 320 characters.
  - Entity keys stripped of control characters and truncated to 40 characters.
  - Unknown dataset labels are not echoed.
  - No entity values, configuration values, session contents or Auth material are included.
- Added `OUTPUT_CONFLICT_DETAILS` to the WorkManager result contract.
- `SyncWorker` formats details only for `BackupCasResult.Conflicted`.
- `SyncStatusStore` persists the short diagnostic locally and clears it on logout or a non-conflict terminal result.
- `SyncStatusText.detail` displays it below the existing merge-conflict explanation.
- Added five formatter tests and one status rendering test.

## Behavior

This is diagnostic only. It does not pick a winning device, overwrite local data or auto-resolve a conflict. Users can identify the logical item, make the same intended value/status/definition on the devices, then run sync again.

## Verification / boundaries

- Tests are authored but not executed. No Gradle build, JVM test run or device UI test was performed.
- No Supabase schema, Auth setting, remote row, RPC or production data was changed.
- No PR was opened, `main` remains untouched and no GitHub Actions workflow was dispatched.
