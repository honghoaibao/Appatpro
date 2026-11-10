# Phase 3.4.7 — Account Multi-device Conflict Model

**Date:** 2026-11-10  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Implemented in source; Android build and JUnit tests not executed

## Objective

Implement ADR-0022 locally before any cloud account merge or acknowledgement is enabled. Separate account status time from statistics time and replace copied aggregate counters with per-installation monotonic contributions.

## Implemented

### Room v4 and additive migration

- Added `statusUpdatedAt` and `statsUpdatedAt` to `AccountEntity`, each with a Room schema default matching the migration.
- Added `MIGRATION_3_4`; previous v1→v2 and v2→v3 migrations remain registered.
- Migration initializes both timestamps from the existing `updatedAt`, creates `account_stat_contributions` with composite primary key `(accountId, installationId)`, and seeds each existing account's current aggregate exactly once under the existing random installation UUID.
- Contribution rows use Long counters, carry a per-installation `updatedAt`, and cascade when an account is deleted.
- Account DAO upserts use Room `@Upsert` rather than REPLACE, avoiding delete/reinsert behavior that could cascade-delete contribution rows.

### Local write path

- New account creation is insert-if-absent, then ensures a baseline contribution without resetting an existing account.
- Status changes advance only `statusUpdatedAt`; timestamp update is monotonic within the local database. This remains a device wall-clock timestamp, not a causal clock; material cross-device clock skew must be handled by the future server/integration layer rather than assumed away.
- Closing a session increments only the current installation's contribution inside the same Room transaction as the session close and Outbox writes.
- Per-installation increments use saturating SQLite arithmetic. Display aggregates are derived from contribution rows with saturating Long addition and clamped to the existing Int UI range.
- `statsUpdatedAt` is derived from the latest contribution timestamp; it is not used to resolve status.
- Account Outbox payloads carry separate timestamps and contribution rows, not whole-snapshot aggregate totals.

### Snapshot and merge policy

- Snapshot format advances to version 2. Account rows carry `statusUpdatedAt` and `statsUpdatedAt`; `accountStatContributions` stores the (account, installation) contribution vectors.
- Snapshot validation rejects unsupported format versions, duplicate contribution keys, noncanonical installation UUIDs, unknown account references, negative counters/timestamps, accounts without contributions, missing source-installation baselines, and a stats watermark inconsistent with the latest contribution timestamp.
- `AccountStatContributionPolicy` implements:
  - status resolution by `statusUpdatedAt`;
  - unresolved conflict when equal timestamps disagree on status/checkpoint;
  - component-wise MAX for the same installation key, making replay idempotent;
  - union of distinct installation keys;
  - overflow-safe aggregate totals.

## Changed files

- android/app/src/main/kotlin/com/atpro/db/entity/AccountEntity.kt
- android/app/src/main/kotlin/com/atpro/db/entity/AccountStatContributionEntity.kt
- android/app/src/main/kotlin/com/atpro/db/dao/AccountDao.kt
- android/app/src/main/kotlin/com/atpro/db/dao/AccountStatContributionDao.kt
- android/app/src/main/kotlin/com/atpro/db/AtProDatabase.kt
- android/app/src/main/kotlin/com/atpro/data/LocalRepository.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupSnapshot.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupSnapshotBuilder.kt
- android/app/src/main/kotlin/com/atpro/sync/BackupSnapshotValidator.kt
- android/app/src/main/kotlin/com/atpro/sync/AccountStatContributionPolicy.kt
- android/app/src/test/kotlin/com/atpro/sync/BackupSnapshotValidatorTest.kt
- android/app/src/test/kotlin/com/atpro/sync/AccountStatContributionPolicyTest.kt
- .claude/context/cloud_sync_architecture.md
- .claude/context/current_focus.md
- .claude/decisions/ADR-0022-account-multidevice-conflict-model.md
- .claude/revision/phase3.4.7-account-multidevice-conflict-model.md

## Safety boundaries

- No Supabase HTTP/RPC, schema, table, row, RLS, grant or Auth setting changes.
- No cloud merge, upload, download, restore or Outbox acknowledgement.
- Phase 3.4.6 worker remains local-only preflight and continues to gate account events.
- No automation/F4F/AccessibilityService or unrelated app logic changes.
- No GitHub Actions run; main remains unchanged.

## Verification

- Ran a SQLite smoke check against a synthetic v3-shaped schema for the exact migration operations: new timestamps, contribution table, index, foreign key and legacy baseline seed all succeeded.
- Ran a SQLite smoke check of the contribution increment SQL, including saturation at Long.MAX_VALUE.
- Added pure JVM tests for status timestamp resolution/tie conflicts, same-install idempotent merge, distinct-install aggregation, overflow saturation, snapshot format v2, invalid contributions and watermark consistency. Tests were added but not executed.
- Not verified: Room-generated schema validation, actual v3→v4 migration on a real app database, Gradle compile, JUnit execution, device behavior, or two-device/cloud integration.

## Next gate

Phase 4.1 may now design and review the authenticated server-side revision CAS/RLS contract. It must not enable remote writes by itself. A later integration phase still needs a tested snapshot merge/restore coordinator and exact Outbox acknowledgement after confirmed server commit.
