# Phase 3.4.2 — Room Outbox + Sync Metadata

**Date:** 2026-11-09  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Implemented in source; not Android-build-verified

## Implemented

- Room database upgraded from v1 to v2 with additive MIGRATION_1_2.
- Added sync_metadata singleton row with random installation UUID, nullable Auth owner, remote revision, checksum, format version, status and timestamps.
- Added sync_outbox durable journal with UUID event IDs, monotonic sequence, dataset/key, UPSERT/DELETE, optional JSON payload and retry metadata.
- Added Outbox DAO operations for ordered batch reads, pending count, exact event-ID acknowledgement, retry metadata and safe UPSERT coalescing.
- Added stable SessionEntity.syncId values. Existing session rows receive UUIDs during migration before a unique index is created.
- Account add/delete/status mutations append matching Outbox entries in the same Room transaction.
- Closing a session and updating account aggregate counters now occur in one Room transaction with closed-session and account UPSERT events.
- Backup-eligible config keys are guarded by a positive allowlist. Schedule definitions use a separate dataset; empty schedule values become DELETE tombstones.
- Fresh installs seed the metadata singleton row from the Room database callback. Existing installs seed it through the v1→v2 migration.
- Updated current focus, hard constraints and architecture status.

## Not changed

- No automation engine, scheduler execution, service lifecycle, UI, Auth implementation or Follow4Follow logic.
- No log export, earnings file changes or snapshot encoding.
- No Supabase schema/RLS/grants/settings/rows or remote request.
- No WorkManager sync worker or connectivity scheduling.
- No change to main; no GitHub Actions run.

## Important implementation boundaries

- Only explicitly allowlisted config keys and sched_* schedule definitions enter Outbox payloads. Unknown keys (including webhook settings) remain local-only.
- Auth owner remains unset until the explicit binding flow in Phase 3.4.4.
- Session payloads use stable UUID keys. Active sessions are not enqueued.
- Room cleanup retention is not represented as a user-requested DELETE tombstone.
- The Outbox is only a local journal. Nothing in this phase uploads or restores data.

## Verification and remaining work

- Source changes and migration SQL were reviewed structurally.
- Not run: Gradle/Android compile, Room migration instrumentation test, device test, Auth flow test, snapshot test, Supabase request or GitHub Actions.
- Next: Phase 3.4.3 — deterministic snapshot builder, GZIP/Base64 + SHA-256 codec, and earnings entry IDs/dirty tracking.
