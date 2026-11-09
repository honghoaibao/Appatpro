# Phase 3.4.5 — Backup Source, Security and Conflict Review

**Date:** 2026-11-09  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Source changes committed; Android build and tests not executed

## Review findings and fixes

- Centralized strict canonical UUID and metadata-state validation in `BackupMetadataRules`. Auth UUID input is normalized to lowercase, but stored metadata and backup identifiers must be canonical lowercase UUIDs.
- Metadata now fails closed for impossible combinations: owner timestamp without owner, remote revision without owner/checksum/confirmation timestamp, revision-zero rows carrying a previous checksum or sync timestamp, and inconsistent success/confirmation timestamps.
- Removed the unused Room metadata `REPLACE` upsert API, which could silently overwrite the bound owner or revision checkpoint.
- Tightened the DAO compare-and-set predicates: first binding requires both owner and owner timestamp to be NULL; revision checkpointing requires a bound owner timestamp.
- Extracted snapshot invariants into a pure JVM validator, so tests can cover the safety rules without invoking Android Base64/GZIP APIs.
- Snapshot validation now rejects non-canonical identifiers, negative dataset timestamps, closed sessions whose end precedes their start, duplicate schedule weekdays, blank schedule accounts, and invalid account/stat metadata.

## Multi-device conflict finding (blocking)

The existing `AccountEntity.updatedAt` is written by both account status changes and aggregate-stat updates. The snapshot exports that same timestamp for both account state and stats. Therefore a later session-stat update on one device can appear newer than a status change made on another device. Also, account counters are cumulative totals, not per-installation contributions; blindly summing two snapshots double-counts, while last-write-wins loses one device's increments.

No safe generic merge is possible for these fields with the current model. ADR-0022 records the required design gate: separate status/stat timestamps and per-installation cumulative contributions keyed by installation UUID. Until that model is implemented and tested, the future sync layer must not resolve account state/stat conflicts by LWW or blind summation. This phase does not implement a speculative migration.

## Changed files

- android/app/src/main/kotlin/com/atpro/sync/BackupMetadataRules.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupOwnershipManager.kt
- android/app/src/main/kotlin/com/atpro/db/dao/SyncMetadataDao.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupCodec.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupSnapshotValidator.kt
- android/app/src/test/kotlin/com/atpro/sync/BackupMetadataRulesTest.kt
- android/app/src/test/kotlin/com/atpro/sync/BackupSnapshotValidatorTest.kt
- .claude/context/cloud_sync_architecture.md
- .claude/context/constraints.md
- .claude/context/current_focus.md
- .claude/decisions/ADR-0022-account-multidevice-conflict-model.md
- .claude/revision/phase3.4.5-source-security-conflict-review.md

## Not changed

- No Supabase requests, schema, tables, rows, RLS, grants or Auth configuration.
- No restore/upload worker, WorkManager scheduling, app automation, service, or Follow4Follow logic.
- No Room migration was added.
- No changes to `main`; no GitHub Actions run.

## Verification status

- Source-level checks confirm the updated validation is called by both encode and decode, and ownership checks delegate to the stricter metadata invariant.
- Added pure unit tests for metadata invariants and snapshot integrity; tests were not executed.
- Not verified: Android/Gradle compile, JUnit execution, Room migration/device behavior, multi-device merge behavior, UI/device tests, or cloud integration.
- Next gate: define and implement the account contribution/status timestamp model before enabling any cross-device sync writes. Scheduling work must remain non-destructive and must not bypass this gate.
