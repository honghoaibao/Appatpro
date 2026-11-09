# ADR-0019 — Room Outbox and Sync Metadata Implementation

- **Status:** Accepted for Phase 3.4.2
- **Date:** 2026-11-09
- **Scope:** Local durable journal only; no cloud sync worker or Supabase writes
- **Related:** ADR-0018

## Context

The application uses Room as its local source of truth. Supabase backup must not make local writes wait for the network, and a process termination must not separate a backed-up local mutation from its pending sync record.

## Decision

1. Upgrade Room from schema version 1 to version 2 with an additive MIGRATION_1_2; no destructive migration or fallback-to-destructive behavior is added.
2. Add sync_metadata as a singleton row. It contains a random installation UUID, an initially-null Auth owner, remote revision/checksum and sync state. It does not use Android hardware identifiers.
3. Add sync_outbox with UUID event IDs, monotonic auto-increment sequence, dataset/entity key, UPSERT/DELETE operation, optional sanitized JSON payload, creation time and retry fields.
4. Preserve ordering by sequence, because multiple changes can share a millisecond.
5. Coalesce only older UPSERT rows for the same dataset/entity. DELETE tombstones are not removed by UPSERT coalescing.
6. Add a stable UUID to every session. The migration assigns a UUID to existing session rows before creating its unique index. Only a closed session is placed in the Outbox.
7. Wrap account add/delete/status changes, session close plus account aggregate update, and allowlisted config/schedule mutations in a single Room transaction together with their corresponding Outbox records.
8. A positive allowlist is used for configuration keys. Unknown keys, webhook settings, tokens, passwords, Auth state, Follow4Follow state and diagnostic logs are excluded.
9. Schedule changes are journaled as definitions only. This phase does not enqueue WorkManager jobs, run a worker, or contact Supabase.

## Consequences

- Offline writes remain local and durable.
- Outbox data survives process death and app restart.
- Future sync can fetch bounded ordered batches and acknowledge exact event IDs only after a confirmed cloud revision.
- The migration adds a small metadata row and indexed Outbox table; it does not copy the full Room database or add background polling.
- Snapshot encoding, earnings stable IDs/dirty tracking, owner binding workflow, network scheduling, cloud RPC and conflict handling remain later phases.

## Verification

Source-level review only. Android build, migration execution on a device, instrumentation tests and Supabase requests have not been run. GitHub Actions are intentionally deferred to the single final build phase.
