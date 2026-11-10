# Phase 3.4.4 — Installation Identity, Auth Owner Binding and Remote Revision Metadata

**Date:** 2026-11-09  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Implemented in source; not Android-build-verified

## Implemented

- Kept the existing random installation UUID as the durable installation identity; it remains unrelated to hardware Android IDs.
- Upgraded Room from v2 to v3 with additive MIGRATION_2_3, adding nullable ownerBoundAt and lastRevisionConfirmedAt. The existing v1→v2 migration remains registered.
- Extended SyncMetadataEntity and SyncMetadataDao with an owner-binding conditional update and an owner-scoped expected-revision update.
- Added BackupMetadataRules to canonicalize/validate Auth UUIDs, lowercase SHA-256 checksums and exact R→R+1 revision transitions.
- Added BackupOwnershipManager with local ownership state, explicit confirmation requirement, fresh ownership checks, different-account blocking and a post-server-commit local revision checkpoint.
- Added Settings UI for first-use owner confirmation. It states clearly that confirmation does not upload, download or delete local data.
- Bound owner confirmation to the current AuthSession.user.id; sign-out retains the owner ID and local data.
- Different Auth UUID, malformed identity or incomplete metadata fails closed. No unbind or silent reassignment path is provided.
- Wired the ownership state into MainScreen and exposed the manager from AtProApplication.
- Added pure unit tests for UUID normalization, checksum format and exact next-revision validation.

## Changed files

- android/app/src/main/kotlin/com/atpro/db/AtProDatabase.kt
- android/app/src/main/kotlin/com/atpro/db/entity/SyncMetadataEntity.kt
- android/app/src/main/kotlin/com/atpro/db/dao/SyncMetadataDao.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupMetadataRules.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupOwnershipManager.kt
- android/app/src/main/kotlin/com/atpro/AtProApplication.kt
- android/app/src/main/kotlin/com/atpro/ui/MainScreen.kt
- android/app/src/main/kotlin/com/atpro/ui/dashboard/DashboardActivity.kt
- android/app/src/main/kotlin/com/atpro/ui/settings/SettingsHubScreen.kt
- android/app/src/test/kotlin/com/atpro/sync/BackupMetadataRulesTest.kt
- .claude/context/cloud_sync_architecture.md
- .claude/context/current_focus.md
- .claude/context/constraints.md
- .claude/decisions/ADR-0021-explicit-backup-owner-and-revision.md
- .claude/revision/phase3.4.4-owner-binding-revision.md

## Ownership behavior

- Unbound + active Auth user: require explicit confirmation before binding.
- Same Auth UUID: show linked state.
- Different Auth UUID: block future cloud upload/restore; do not change the stored owner.
- Signed out: retain Room data and owner binding; no cloud operation.
- Invalid UUID or corrupt metadata: fail closed and preserve local data.

## Remote revision behavior

- Local metadata advances only after a future server operation confirms a successful R→R+1 compare-and-swap.
- The local DAO update requires the same owner and the expected old revision. It persists new revision, checksum, schema version and timestamps in one statement.
- Invalid checksum/schema, skipped revision, wrong owner or stale local revision is rejected.
- This is only the local checkpoint contract. The Supabase RPC and network call remain Phase 4.1.

## Not changed

- No Supabase request, database schema, RLS, grants, Auth settings or remote rows.
- No snapshot upload/restore, WorkManager sync, polling, automation/service or F4F behavior.
- No destructive Room migration, credential/token movement or hardware-ID collection.
- No change to main; no GitHub Actions run.

## Verification and remaining work

- Reviewed migration chain, owner binding predicates, state transitions and revision compare-and-set contract in source.
- Added unit tests but did not execute them.
- Not run: Android/Gradle compile, Room migration/device test, UI interaction test, two-account owner-switch test, remote RPC or GitHub Actions.
- Next: Phase 3.4.5 — source/security/conflict review and fixes.
