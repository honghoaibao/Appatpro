# ADR-0022 — Safe Multi-device Account Conflict Model

- **Status:** Implemented locally in Phase 3.4.7; remote merge integration remains gated
- **Date:** 2026-11-09
- **Scope:** Account state and cumulative statistics in the Room-first backup format
- **Related:** ADR-0018, ADR-0019, ADR-0020, ADR-0021

## Context

The original local `AccountEntity` had one `updatedAt` field shared by status and aggregate-stat changes. The snapshot exported that same timestamp for account status/checkpoint and counters.

Across two installations, a session completed later on device B can advance `updatedAt` and make device B's older status appear newer than a status change made earlier on device A. A generic last-write-wins merge can therefore undo a meaningful status change.

The counters (`sessionsCount`, `totalLikes`, `totalFollows`, `totalVideos`) are cumulative local totals. Summing two device snapshots duplicates historical baseline values; choosing the latest snapshot loses increments made on the other device. Stable closed-session UUIDs alone do not solve lifetime aggregate counters because the local session history is retained for only 90 days.

## Decisions

1. Do not resolve account status/checkpoint by the current shared `updatedAt` field.
2. Introduce independent `statusUpdatedAt` and `statsUpdatedAt` values before enabling account-state LWW. Migrate legacy rows by initializing both from the existing `updatedAt`.
3. Represent cumulative counters as monotonic per-installation contributions, keyed by (normalized account key, installationId). On first adoption, each installation contributes its existing local baseline exactly once; subsequent sessions update only that installation's contribution.
4. Derive the displayed aggregate by summing the distinct installation contributions with overflow-safe arithmetic. Never sum two complete snapshot totals and never overwrite the contribution of another installation.
5. Continue merging closed session history by stable session UUID; do not infer lifetime aggregate totals solely from the retained 90-day session list.
6. Until these fields and migration/tests exist, a future merge engine must preserve account conflicts as unresolved and must not apply LWW or blind summation to account counters. It must leave the local Outbox pending rather than acknowledge an unresolvable account batch.
7. The conflict policy does not change the owner-binding boundary or server-side revision CAS requirement.

## Consequences

- Account sync now uses Room v4 and backup format v2; remote merge/restore integration remains a later, separately verified phase.
- Existing local aggregate totals remain intact and become the local installation's baseline contribution; they are not recalculated from truncated session history.
- Multi-device totals become additive by installation, not by copying/summing snapshots.
- The scheduler may be implemented separately, but no cloud write/ack path may bypass this merge gate.
- `statusUpdatedAt` is currently a device wall-clock timestamp, not a causal clock. Material clock skew can misorder updates across devices. Before enabling automatic remote status LWW, the Phase 4.1/integration design must decide how to use a server-authoritative timestamp or surface uncertain ordering as a user-resolvable conflict.

## Phase 3.4.7 implementation

- Room database is now version 4. `MIGRATION_3_4` adds `statusUpdatedAt` and `statsUpdatedAt`, initializes both from legacy `updatedAt`, creates `account_stat_contributions`, and seeds exactly one baseline row per existing account using the existing random installation UUID.
- The contribution table is keyed by `(accountId, installationId)`, uses Long counters, and cascades on account deletion. Account DAO upserts now use Room `@Upsert` rather than REPLACE so updating an account cannot delete contribution rows through FK cascade.
- New account creation inserts only if absent and creates the installation baseline. Closing a session increments only the current installation contribution in the same Room transaction, then derives the existing Int display fields from all distinct contribution rows with saturating Long arithmetic.
- Status changes update only `statusUpdatedAt`; contribution/stat changes update `statsUpdatedAt`. The generic legacy `updatedAt` is retained for existing app compatibility but is not used by the multi-device status merge policy.
- Account Outbox payloads now contain status timestamps and per-installation contribution rows, not a pair of whole-snapshot aggregate counters.
- Snapshot format is version 2 and contains `statusUpdatedAt`, `statsUpdatedAt`, and the per-installation contribution list. The validator rejects duplicate (account, installation) keys, invalid installation UUIDs, negative counters, unknown account references, and missing contributions.
- `AccountStatContributionPolicy` resolves status by its own timestamp (equal-time disagreement remains unresolved), merges the same installation by component-wise MAX, retains distinct installation contributions, and derives aggregate totals with saturating addition.

## Verification

- The migration SQL was exercised against a synthetic SQLite v3-shaped schema: timestamp initialization, baseline insertion, composite primary key, FK cascade declaration and index creation succeeded. This is a SQL smoke check only, not a Room migration/device test.
- Added pure JVM tests for status timestamp resolution/tie conflicts, idempotent same-installation merge, distinct-installation aggregation, overflow saturation, snapshot v2 validation and malformed contribution rejection. Tests have not been executed.
- Not verified: Room schema validation, Gradle compile, JUnit execution, actual v3→v4 migration on an app database, two-device execution or cloud merge.
- No remote sync, Supabase call, Outbox acknowledgement, server schema or RLS change is part of this phase.
