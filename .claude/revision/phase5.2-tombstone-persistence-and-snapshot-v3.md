# Phase 5.2 — Durable Tombstones and Snapshot v3

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Base commit:** `501d022aaa32a9de52842a653403f5c20b6c4461`  
**Scope:** Local Room persistence, snapshot wire format and compatibility validation only

## Implemented

- Completed the existing Room schema v5 tombstone table and DAO without duplicating the already-present entity.
- Extended migration 4→5 to backfill every still-pending supported Outbox DELETE for `accounts`, `safeConfigs` and `schedules`. The existing Outbox event UUID is reused as the tombstone ID; installation UUID and deletion time are taken from sync metadata and the Outbox row. Migration fails closed if the number inserted differs from the number of eligible pending DELETE events.
- Added a bounded deterministic tombstone query to the existing DAO.
- Updated new delete operations so the durable tombstone and matching Outbox DELETE share one UUID and are inserted in the same Room transaction. Missing rows do not create redundant markers.
- Separated WorkManager schedule cancellation from logical schedule deletion. Updating a schedule no longer creates a false tombstone; explicit cancel/disable uses the repository delete path.
- Changed new snapshots to format v3 and included tombstones sorted by dataset, entity key and tombstone ID. Builder reads them in the Room transaction, with a 100,000-row bound.
- Added strict validation for supported datasets, safe config keys, normalized entity keys, canonical tombstone/source-install UUIDs, non-negative deletion time, unique tombstone IDs and maximum retained tombstones.
- Added the exact legacy v2 wire model. Canonical v2 payloads are validated with the legacy model and byte-for-byte canonical check, then converted to a v3 staging model with an empty tombstone list. New encoding is v3 only; unknown future versions and schema/payload mismatches are rejected.
- Added pure JVM tests for tombstone invariants and v2 conversion, plus mocked SQLite migration-contract tests.

## Safety boundaries

- No Supabase schema, policies, Auth setting or backup row changed.
- No HTTP/RPC transport, remote worker activation, restore apply, Outbox acknowledgement, tombstone acknowledgement or tombstone garbage collection was added.
- Existing worker behavior remains preflight-only.
- `main` was not modified.
- No GitHub Actions workflow was dispatched. The Android build workflow does not watch push events on this feature branch.

## Verification

- Implementation was based on the current Room v5/tombstone commit and the existing Outbox, schedule manager, snapshot builder, v2 codec and validator.
- Tests are authored but were not executed. Android build/JUnit verification remains required at the single final build gate.
