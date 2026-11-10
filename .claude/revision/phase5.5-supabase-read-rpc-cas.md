# Phase 5.5 — Supabase read/RPC transport and bounded CAS coordinator

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Scope:** Remote read + revision-CAS RPC adapter and deterministic coordinator; worker remains disabled

## Implementation

- Added `SupabaseBackupRemoteStore` over the existing injectable `SupabaseHttpClient`.
- GET reads only `revision,schema_version,payload,checksum,updated_at` from `/rest/v1/user_backups`, with canonical owner UUID filter, current JWT and an 8 MiB response cap. Empty array is Missing; exactly one validated row is Found. Duplicate/malformed/unsupported/oversized rows fail closed.
- Supports legacy remote schema v2 and current v3. The existing `BackupCodec` remains responsible for decompression, checksum, canonical JSON, dataset validation and v2→v3 staging conversion.
- POST calls only `/rest/v1/rpc/save_user_backup`. The JSON body contains the four SQL arguments: expected revision, schema version, Base64 payload and checksum. No owner ID is sent; SQL derives it from `auth.uid()`.
- New commits require schema v3 and validate encoded/uncompressed size bounds, Base64 envelope, checksum and revision overflow. RPC success is accepted only for a single returned row with exactly expected revision + 1.
- Recognizes only the exact `P0001 / BACKUP_REVISION_CONFLICT` server error as a CAS conflict. Other auth/permission/validation failures are returned as typed failures; 408/429/5xx are marked retryable for a later policy, not retried inside the transport.
- Extended `SupabaseHttpRequest` with a per-request response cap (1 MiB default, hard maximum 8 MiB); the URLConnection transport streams into a bounded buffer and never reads an unlimited response body.
- Added `BackupCasCoordinator`, which accepts an AuthRepository-issued `AuthSession`, verifies canonical user identity and calls a required local owner-binding validator before network access.
- Each CAS attempt rebuilds the local snapshot, reads the latest remote row, decodes/validates it, merges via Phase 5.3, encodes v3 and invokes the CAS RPC. A missing row uses expected revision 0.
- Exactly three attempts maximum. Only a confirmed CAS conflict retries; every retry re-reads remote and rebuilds local. Merge conflicts stop before write. Success returns candidate + committed revision/checksum/attempt count. Exhaustion returns RetryLater.

## Tests added

- `SupabaseBackupRemoteStoreTest`: owner filter/JWT, response bound, empty row, duplicate row, unsupported schema, exact RPC path/body, absence of owner_id in RPC, exact P0001 conflict, 401 classification and oversize input.
- `BackupCasCoordinatorTest`: first remote creation, conflict re-read/rebuild/re-merge, three-conflict exhaustion, merge conflict prevents write, transient read failure does not blindly retry, invalid Auth UUID and different local owner are rejected before remote read.

## Safety boundaries

- No production caller invokes `BackupCasCoordinator`; `SyncWorker` remains preflight-only with `remote_executor_disabled`.
- No Outbox, tombstone or Earnings acknowledgement; no local owner/revision checkpoint update; no retry metadata mutation.
- No Room/Earnings restore application, WorkManager activation, schedule enqueue or automation start.
- No Supabase SQL migration, RLS/grant/Auth setting or remote row changed.
- `main` remains untouched. No GitHub Actions workflow was dispatched.

## Verification

Source and test files were reviewed on the feature branch, but tests were authored and not executed. Android build/JUnit verification remains pending for the single final build gate.
