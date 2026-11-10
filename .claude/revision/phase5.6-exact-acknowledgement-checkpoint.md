# Phase 5.6 — Exact acknowledgement and local checkpoint

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Scope:** Local restore + exact post-CAS acknowledgements; remote worker stays disabled

## Implemented

- `BackupSnapshotBuildResult` now captures owner UUID, local remote-revision checkpoint, Earnings generation and the selected Outbox event identities alongside the snapshot.
- `BackupSnapshotBuilder` reads metadata, backup datasets, tombstones and the first bounded Outbox candidate projection in one Room transaction. Candidate IDs are selected through the sequence-preserving `SyncBatchPolicy` (50 max / 512 KiB / retry-due). The Outbox payload is not loaded.
- Removed the old account merge gate from `SyncBatchPolicy` and its preflight worker branch. Phase 5.3 is now the conflict gate for account status/config/schedule/session/earnings identities.
- `BackupCasCoordinator` rebuilds the full capture on each CAS attempt, checks owner binding and local checkpoint, rejects a missing/older remote revision, and returns both the captured local build and the exact server-confirmed merged candidate.
- `BackupAcknowledgementCoordinator` applies that merged candidate locally via `BackupRestoreCoordinator.restore` before any acknowledgement. If restore fails, no checkpoint or Outbox acknowledgement occurs.
- `BackupAcknowledgementPolicy` selects only the contiguous captured Outbox prefix whose live entity or tombstone exists in the committed merged snapshot. It validates event UUID, sequence order, dataset, key, operation and size.
- The Room acknowledgement transaction verifies owner + installation + local checkpoint CAS, verifies exact current Outbox identities and referenced durable tombstones, advances the local checkpoint to the server-confirmed committed revision (which may jump beyond the captured local revision), and deletes only the exact captured event IDs in the same transaction.
- Tombstone markers are verified but never deleted. A local DELETE Outbox event is acknowledged only when its exact tombstone ID and local source installation are in the committed candidate and durable Room table.
- EarningsStore acknowledges only the generation captured with the snapshot. Writes/restores newer than that generation remain dirty.
- Partial acknowledgement stops at the first unrepresented Outbox event; later events are not skipped.

## Tests added/updated

- CAS: remote revision behind local checkpoint is rejected before write; invalid Outbox identity is rejected before remote read; conflict retry re-reads and rebuilds.
- Acknowledgement policy: live account/config coverage, exact tombstone IDs with multiple markers, prior UPSERT represented by later DELETE, remote-only tombstone coverage using the merged candidate, unrepresented head blocking, DELETE without marker and invalid sequence/duplicate IDs.
- Acknowledgement coordinator: local restore failure blocks Room/Earnings ack; Room conflict blocks Earnings ack; captured Earnings generation is acknowledged exactly; owner mismatch rejects; remote revision may advance beyond local checkpoint; blocked event returns Partial.
- Sync batch policy: account events are now eligible for deterministic merge; preflight still does no remote work.

## Safety boundaries and remaining gate

- No `SyncWorker` production integration; `remote_executor_disabled` remains.
- No Outbox event outside the captured prefix is deleted. No tombstone row is deleted. No Earnings generation newer than the captured one is acknowledged.
- A `closedSessions` Outbox event whose row has aged out of the 90-day snapshot and is absent remotely remains blocked and pending. This must receive an explicit retention/remote semantics decision before worker activation in Phase 5.7.
- No Supabase schema/policy/Auth setting/remote data changes. `main` untouched. No GitHub Actions workflow dispatched.
- Room transaction, Android build and JUnit remain unverified; final single CI/build gate is deferred until Phase 5.7.
