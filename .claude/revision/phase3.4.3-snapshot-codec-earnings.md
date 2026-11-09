# Phase 3.4.3 — Snapshot Builder, Backup Codec and Earnings Tracking

**Date:** 2026-11-09  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Implemented in source; not Android-build-verified

## Implemented

- Added versioned serializable snapshot DTOs for accounts, account stats, retained closed sessions, safe configs, schedules and earnings.
- Added `BackupSnapshotBuilder`: reads Room data under a Room transaction, pages retained closed sessions in bounded batches, validates installation UUID and schedule definitions, sorts datasets deterministically, and captures earnings generation metadata.
- Snapshot coverage follows the approved allowlist. Farm logs, UI theme, Auth/F4F tokens, passwords, webhook URLs, API keys and unknown config keys are omitted.
- Schedule definitions retain the source enabled preference in the backup representation; the future restore implementation must force `enabled = false` and must not schedule WorkManager jobs.
- Added `BackupCodec`: canonical JSON → UTF-8 → GZIP → Base64 (NO_WRAP); SHA-256 is computed over canonical uncompressed JSON.
- Added 7 MiB encoded and 32 MiB decompressed limits. Decode validates Base64/GZIP, checksum, exact schema version, canonical JSON, UUIDs, safe-config keys, duplicate identifiers and basic dataset invariants.
- Added stable UUID IDs to `EarningEntry`. Legacy `timestamp|kind|reward|account` rows are upgraded to `v2|entryUUID|timestampMs|kind|reward|account` through Android AtomicFile.
- Added durable `earnings.dirty` generation/acknowledgement marker. Writes mark dirty before append; a captured generation can be acknowledged later without clearing dirtiness from newer writes.
- Earnings remain file-backed with the original 30,000/25,000 retention behavior; no per-earning Room rows or Outbox events were added.

## Changed files

- `android/app/src/main/kotlin/com/atpro/db/dao/SessionDao.kt`
- `android/app/src/main/kotlin/com/atpro/data/EarningsStore.kt`
- `android/app/src/main/kotlin/com/atpro/sync/BackupSnapshot.kt`
- `android/app/src/main/kotlin/com/atpro/sync/BackupSnapshotBuilder.kt`
- `android/app/src/main/kotlin/com/atpro/sync/BackupCodec.kt`
- `.claude/context/cloud_sync_architecture.md`
- `.claude/context/current_focus.md`
- `.claude/context/constraints.md`
- `.claude/decisions/ADR-0020-deterministic-snapshot-codec-earnings.md`
- `.claude/revision/phase3.4.3-snapshot-codec-earnings.md`

## Not changed

- No Supabase requests, cloud writes, RLS/grants/schema/Auth setting changes or remote restore.
- No WorkManager sync worker, network scheduling or automation/service/UI logic.
- No additional Room schema migration or Gradle dependency change.
- No change to `main`; no GitHub Actions workflow run.

## Verification and remaining work

- Source and SQL/query shapes reviewed structurally; Room dependency `room-ktx 2.6.1` and Kotlin serialization JSON dependency are already present.
- Not run: Android/Gradle compile, codec round-trip tests, legacy earnings file migration test on device, snapshot-size test, Auth-owner test or Supabase integration.
- Next: **Phase 3.4.4 — installation identity, explicit Auth owner binding and remote revision metadata lifecycle**.
