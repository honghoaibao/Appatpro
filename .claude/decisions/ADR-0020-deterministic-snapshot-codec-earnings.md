# ADR-0020 — Deterministic Snapshot Codec and Earnings Dirty Tracking

- **Status:** Accepted for Phase 3.4.3
- **Date:** 2026-11-09
- **Scope:** Local snapshot materialization and encoding only
- **Related:** ADR-0018, ADR-0019

## Context

The Room Outbox and sync metadata are in place, but there is not yet a bounded, versioned payload to send to the remote backup table. Earnings remain in a compact append-only file and need stable identities plus a durable indication that the file has changed.

## Decisions

1. Build a logical allowlisted snapshot from Room and EarningsStore; never serialize the Room database file, arbitrary configs, SharedPreferences or credential stores.
2. Build the Room portion inside a Room read transaction. Include managed accounts, account aggregate stats, closed sessions within the existing 90-day retention, positive-allowlisted config keys and stored schedule definitions.
3. Treat malformed schedule JSON as a snapshot-build error instead of silently omitting it. Keep schedule intent as recorded in the source snapshot; the later restore path must force schedules disabled and must not enqueue WorkManager work.
4. Exclude farm logs, UI theme, Auth/F4F tokens, passwords, webhook URLs, API keys and other secrets.
5. Order snapshot arrays by stable keys/time and accountStats by username. DTO property order is fixed by the versioned Kotlin serialization model.
6. Encode canonical JSON as UTF-8, then GZIP, then Base64 without line wraps. Compute lowercase SHA-256 over the canonical uncompressed JSON bytes.
7. Limit the encoded payload to 7 MiB (below the database's 8 MiB constraint) and decoded JSON to 32 MiB. The decoder verifies size, Base64/GZIP, checksum, exact version, canonical JSON and dataset invariants before returning a model.
8. Upgrade earnings records to `v2|entryUUID|timestampMs|kind|reward|account`. Legacy rows receive UUIDs and are rewritten via Android AtomicFile so the same earning keeps the same key across retries.
9. Track earnings with an AtomicFile `earnings.dirty` marker containing current and acknowledged generations. Mark dirty before appending/deleting; acknowledge only a generation included by a later confirmed remote commit. A write after snapshot capture remains dirty.
10. Do not add one Room Outbox event per earning or expand every earning into a Room row. Keep EarningsStore file-backed and include its generation with the built snapshot result.
11. Configure EarningsStore's application files directory during Application startup, before an automation service can record rewards. Keep that initialization I/O-free; initialize the durable marker and perform legacy migration lazily under the store lock on the first read/write.

## Consequences

- Snapshot generation is deterministic for a given logical input, excluding envelope timestamps that are intentionally captured at build time.
- The codec has bounded memory use during decompression and rejects oversized/invalid snapshots before any restore operation exists.
- Old earning data is upgraded once without changing the local retention policy (30,000-line threshold, retain latest 25,000).
- This phase performs no Supabase requests, cloud writes, restore, Room schema migration, WorkManager scheduling or automation changes.

## Verification

Source-level review only. No Android build, instrumentation test, device migration, codec round-trip test or GitHub Actions workflow was run. These remain part of the final integration/QA phase.
