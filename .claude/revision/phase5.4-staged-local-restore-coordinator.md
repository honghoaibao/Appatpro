# Phase 5.4 — Staged local restore coordinator

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Base:** Phase 5.3 commit `7c0b8b0e5c858262921f722d00adbee71fcb301d`  
**Scope:** Local staged restore only; no remote transport or acknowledgement

## Implementation

- Added `BackupRestoreCoordinator` accepting an already merged, validated v3 candidate whose source installation UUID must match Room sync metadata.
- Added `BackupRestoreJournalCodec`: bounded v3 journal record, schema/journal version checks, checksum metadata validation and reuse of the canonical BackupCodec payload/checksum.
- Journal is persisted through Android `AtomicFile` under `noBackupFilesDir` before any local mutation. Startup checks for pending base/backup journal files and replays the transaction before scheduling WorkManager preflight. Recovery failure fails closed.
- Room apply is one `withTransaction`: reconcile accounts, merge contribution counters component-wise MAX, recompute saturated aggregate counters, insert missing closed sessions by UUID, update only allowlisted config keys, persist tombstones idempotently.
- Preserve active sessions, farm logs, unknown/device-local config keys, sync metadata/revision checkpoint, Outbox rows and local writes created after staging. Detect local status/config/session/tombstone changes after staging and abort instead of overwriting.
- Chunk UUID/key lookups into groups of 500 to stay below SQLite bind-variable limits.
- Schedules are serialized with `enabled=false`; existing unique schedule work is cancellation-requested only. No schedule work is enqueued. FarmWorker treats stale/missing/malformed/disabled schedule definitions as successful no-ops.
- EarningsStore merges restored/current entries by stable UUID, rejects content conflicts and malformed persisted rows, retains entries appended after staging, sorts and bounds to 25,000, and marks the dirty generation before AtomicFile replacement.
- Journal is deleted only after Room and EarningsStore complete and schedule-cancellation requests have been submitted. A crash between stores leaves the journal for idempotent startup replay.

## Tests and verification

- Added JVM tests for journal metadata parsing, journal/snapshot version rejection, malformed checksum metadata and size bounds. These tests were authored but not executed.
- The Android project build and Room/AtomicFile process-crash integration tests remain unverified. No GitHub Actions workflow was dispatched.
- No Supabase object, policy, Auth setting, backup row, HTTP/RPC/CAS transport, remote revision checkpoint, Outbox acknowledgement, tombstone acknowledgement or worker activation changed.
- No Room schema change or migration was added.
- `main` remains untouched.
