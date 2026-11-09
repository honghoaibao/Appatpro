# ADR-0022 — Safe Multi-device Account Conflict Model

- **Status:** Accepted as a required design gate; implementation deferred
- **Date:** 2026-11-09
- **Scope:** Account state and cumulative statistics in the Room-first backup format
- **Related:** ADR-0018, ADR-0019, ADR-0020, ADR-0021

## Context

The current local `AccountEntity` has one `updatedAt` field. Both `updateStatus()` and `addStats()` change it. The snapshot then exports that same timestamp for account status/checkpoint and for aggregate counters.

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

- A future account sync needs a small additive Room migration and versioned backup-format extension.
- Existing local aggregate totals remain intact and become the local installation's baseline contribution; they are not recalculated from truncated session history.
- Multi-device totals become additive by installation, not by copying/summing snapshots.
- The scheduler may be implemented separately, but no cloud write/ack path may bypass this merge gate.

## Verification

Design review only. No Room schema change, migration, sync merge implementation or multi-device execution test was made in Phase 3.4.5.
