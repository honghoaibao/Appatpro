# Phase 5 C1 — Sync Bootstrap Foundation

**Date:** 2026-11-16  
**Branch:** `feature/supabase-completion-2026-11-16`  
**Baseline:** v1.12.6 / versionCode 46  
**Scope:** Empty-local classification, cloud-first bootstrap restore, journaled remote checkpoint.

## Implemented

- Added `BackupLocalStatePolicy` to distinguish a truly empty portable backup from business rows, dirty earnings, captured Outbox events, and explicit tombstones.
- `BackupCasCoordinator` now returns a restore-only result when the local portable state is empty and a valid remote backup exists. This path runs before the checksum-based `UpToDate` shortcut, so a cleared business dataset with a retained checkpoint is not silently treated as synchronized.
- When both local portable state and remote backup are empty, the coordinator returns `EmptyLocalNoRemoteBackup`; it does not create a blank remote row.
- When the remote read fails or the remote snapshot is invalid, the coordinator fails closed. A non-zero local checkpoint with a missing remote row remains a hard failure.
- Added an optional, backward-compatible remote checkpoint to the restore journal. The checkpoint is written only after Room application, EarningsStore merge, and restored-schedule cancellation complete. Journal replay can safely finish the checkpoint update and recognizes an already-recorded checkpoint.
- Added a Room DAO compare-and-set update for the observed remote revision. It allows a revision jump from another device without pretending that this device performed a new CAS commit.
- `SyncWorker` handles restore-only, empty/no-cloud, and UpToDate results, and reads the existing `INPUT_FORCE_REMOTE_CHECK` work input.
- Added an explicit `EMPTY_READY` sync outcome so an empty device with no cloud backup is not displayed as already synchronized.

## Tests added

- `BackupLocalStatePolicyTest`: empty local state, tombstone/delete intent, pending Outbox, dirty earnings, and business rows.
- `BackupCasCoordinatorTest`: no blank upload when cloud is missing, restore-only when cloud exists, restore despite a matching checkpoint after local business-data loss, fail-closed for missing cloud with a non-zero checkpoint, remote read failure, and invalid remote snapshot.
- `BackupRestoreJournalCodecTest`: checkpoint metadata validation and backward compatibility when old journal records omit the optional field.
- `SyncStatusTest`: empty-ready status mapping, copy, and notification behavior.

## Verification status

- Source paths and branch contents re-fetched after edits.
- Static source-contract checks: 11/11 passed.
- Android compile: **NOT TESTED**.
- JUnit/Robolectric: **NOT RUN**.
- APK build: **NOT RUN**.
- GitHub Actions: **NOT RUN** (no workflow dispatch).
- Physical-device RT-07/RT-09/RT-10/RT-11/RT-13/RT-14: **NOT TESTED**.
- Supabase production schema, Auth settings, RPCs and user data: **UNCHANGED**.
- `main`: **UNCHANGED**.

## Known limitations / follow-up

- This sub-phase provides the bootstrap execution path. Login/startup/network trigger wiring and unique-work recovery are still C2.
- `INPUT_FORCE_REMOTE_CHECK` is now consumed by the worker; the scheduler does not yet enqueue that mode automatically.
- A full app-data clear/uninstall removes local owner/checkpoint/tombstone evidence. The app cannot reliably distinguish that from a new install using erased local metadata; the cloud owner and remote backup remain the recovery source after explicit account confirmation.
- Runtime verification must prove that AtomicFile replay, Room checkpoint CAS, and EarningsStore idempotency behave correctly under process interruption.
- Do not dispatch GitHub Actions or modify Supabase production resources without separate user authorization.

## Rollback

Revert the C1 implementation commits on this feature branch. No Supabase migration, Auth setting, RPC, or production record was changed. The Room change is DAO-only and introduces no schema migration.
