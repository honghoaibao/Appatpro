# Phase 5.1 — Merge/Restore Domain Contract

**Date:** 2026-11-10  
**Branch:** `feature/atpro-supabase-phase3-1-foundation`  
**Status:** Domain contract defined; no application code, database schema, Supabase settings or remote sync behavior changed

## Objective

Define a conservative, deterministic merge and restore contract before implementing any remote upload/download. This phase is documentation and design only. It must expose unresolved data-model gaps rather than silently choosing a winner.

## Non-negotiable safety gates

1. Room remains the local source of truth. No network operation may block automation or the UI.
2. A remote snapshot is untrusted input until payload bounds, checksum, schema version, format version, canonical JSON and all dataset invariants pass.
3. Never replace a populated local database with a remote snapshot. Restore is a merge into a staged model, followed by one atomic Room transaction and an atomic file replacement for EarningsStore.
4. Never acknowledge an Outbox event or earnings generation before the exact merged snapshot revision is confirmed by the CAS RPC.
5. Never acknowledge event IDs that were not captured in the committed snapshot attempt.
6. Auth owner mismatch, missing owner binding, invalid metadata, malformed snapshot, unresolved conflict, cancellation or CAS exhaustion leaves local data and pending work intact.
7. Remote sync remains disabled until the later implementation and test gates are complete.

## Merge identity and policies

### Accounts

- Identity is the normalized username used by the existing Room primary key.
- Account status and checkpoint are one logical value; do not merge them independently.
- The current `statusUpdatedAt` is a device wall-clock timestamp, not a causal or server-authoritative clock. Therefore, a difference in status/checkpoint across two sources is **unresolved**, even if one device timestamp is numerically greater. Do not apply cross-device status LWW until a server-authoritative per-entity ordering/version contract exists.
- If status/checkpoint agree, preserve that value and merge independent statistic contributions.
- `accountStatContributions` are keyed by (normalized account, installation UUID). For a shared key, merge each counter by component-wise MAX; for distinct keys, retain both. Derive displayed aggregates using saturating Long addition and clamp only at the existing Int UI boundary. Never sum snapshot aggregate totals.
- A status conflict blocks the affected merge transaction; preserve the local pending changes and remote snapshot for a later explicit resolution.

### Sessions

- Identity is stable `syncId`; only closed sessions are eligible.
- Identical rows with the same ID are idempotent.
- If the same ID has different immutable session contents and no trustworthy version exists, report a conflict; do not choose by local row ID or arrival order.
- Active sessions remain device-local and are never restored as running sessions.

### Safe configurations

- Only keys accepted by `BackupConfigPolicy` may enter the merge.
- Unknown keys, device-local preferences and credentials are discarded from the candidate and must never be applied.
- Different values for the same key require a deterministic conflict policy. `updatedAt` is device-clock time; it may be used only as a provisional ordering hint, not proof of causality. Equal-time disagreement is unresolved. The implementation must not silently overwrite a local value when ordering is uncertain.

### Schedules

- Identity is stable `scheduleId`.
- Conflicting definitions require resolution rather than arrival-order selection.
- Every imported schedule is forced disabled, regardless of `enabledAtSource`.
- Restore must not enqueue WorkManager work, start services or resume automation. User review and a separate manual enable action are required.

### Earnings

- Identity is stable earning-entry UUID.
- Identical rows are idempotent.
- Same UUID with different content is a data-integrity conflict; do not overwrite either copy.
- Keep only entries allowed by the existing EarningsStore retention policy. Capture the dirty generation together with the entries; acknowledge only that captured generation after the corresponding snapshot commit.

### Deletes and tombstones

- The current design requires account/config/schedule tombstones, but the inspected Room schema has no durable tombstone table and snapshot format v2 has no tombstone dataset. Outbox DELETE rows alone are insufficient once acknowledged: a later device snapshot could resurrect the deleted entity.
- A follow-up schema step must add a durable Room tombstone store and a versioned snapshot tombstone dataset before remote merge or Outbox acknowledgement is enabled.
- Tombstones must identify dataset + normalized entity key + stable tombstone ID + source installation + deletion metadata. They remain durable until the merged snapshot containing them is committed and the exact local tombstone/outbox generation is acknowledged.
- Conservative rule: a tombstone wins over an existing entity on another installation. Re-creation of the same key requires an explicit user-visible resolution that creates a new logical generation; a stale snapshot must not silently resurrect it.
- Retention/cleanup of tombstones is not part of the first implementation. Do not garbage-collect them until a safe multi-device watermark/causal protocol exists.

## Snapshot format evolution

- Current snapshot format is v2 and contains no tombstones.
- The first tombstone-capable format must be versioned (planned v3); do not silently add required fields to v2.
- Implement a dedicated v2-to-staging-model compatibility path before accepting v2 remote backups into a v3 merge. Preserve the original remote row until a v3 CAS commit succeeds.
- Reject unknown future format versions. Do not downgrade a newer remote snapshot.
- Any new required dataset must have explicit empty/default migration behavior and tests.

## Restore transaction contract

1. Read the authenticated owner's backup row and its server revision.
2. Validate the row metadata and decode the bounded payload into an isolated staging model.
3. Verify local owner binding again immediately before merge/apply.
4. Build the local snapshot and merge local + remote + captured pending changes without mutating Room.
5. If any required dataset has an unresolved conflict, abort the whole apply/commit attempt; retain both source states and pending work.
6. Validate the merged candidate again, encode it canonically, and enforce the app's 7 MiB encoded / 32 MiB uncompressed limits.
7. Apply the merged datasets in a single Room transaction. Apply earnings using a temporary file and atomic rename. Do not start schedules or services.
8. Upload the exact merged snapshot with expected server revision R through `save_user_backup`.
9. Only after server-confirmed R→R+1 may the app persist the local revision/checksum, acknowledge the exact captured Outbox IDs, acknowledge the captured earnings generation, and acknowledge the included tombstone generation.
10. If the remote commit fails after local merge application, the local merge remains valid but all captured Outbox/tombstone/earnings dirty markers remain pending; the next attempt re-reads the latest remote snapshot and re-merges idempotently. Never roll back unrelated local writes.

**Implementation refinement:** to avoid applying data locally before CAS and then needing rollback, the coordinator should stage the merged model first, perform the CAS commit, and only then apply the already validated committed model locally. If local application fails after cloud commit, do not acknowledge local events; retry by reading the committed remote snapshot and applying it idempotently. The exact ordering must be tested with crash injection.

## CAS retry contract

- A cycle captures the local Outbox head and dirty generations once per attempt.
- On `BACKUP_REVISION_CONFLICT`, re-read the latest row, revalidate, and rebuild the merge from the unchanged local source plus the new remote source.
- Maximum retry count is bounded (initial implementation target: 3 total CAS attempts per worker run).
- Use capped exponential delay only between future WorkManager runs; do not sleep/block a worker thread for long periods.
- Do not mutate `attempts` or `nextAttemptAt` for every event until the retry classification is known.
- On exhausted conflicts, transient network errors or cancellation, leave all captured events and dirty generations pending.
- Authentication, owner mismatch, unsupported schema and validation errors are non-retryable until user/session/data state changes.

## Exact acknowledgement contract

- The candidate stores the exact ordered Outbox event IDs and sequence range captured before building the merged snapshot.
- After CAS success, delete/acknowledge only those event IDs, using a transaction and exact ID predicate. Newer events created during the network call remain pending.
- Acknowledgement must be idempotent: if the app crashes after server commit but before local acknowledgement, a later read of the committed snapshot must recognize the included logical changes and safely replay the same event IDs.
- Earnings acknowledgement uses the captured generation, never the current generation.
- Tombstone acknowledgement uses exact tombstone IDs/generation captured in the committed snapshot.
- A partial acknowledgement is not reported as a fully clean sync.

## Known implementation gaps found during Phase 5.1 inspection

1. Room has no tombstone entity/table or DAO, despite the architecture requiring durable tombstones.
2. Snapshot format v2 has no tombstone dataset; safe delete propagation cannot currently survive acknowledgement and later full-snapshot rebuilds.
3. Cross-device account status timestamps are device wall-clock values; numerical LWW is unsafe under clock skew.
4. Session, config, schedule and earnings models do not all carry a trustworthy cross-device causal version. Conflicting same-key changes must be preserved or explicitly resolved, not selected by arrival order.
5. The existing worker is preflight-only and has no transport dependency. Keep this separation until the merge/restore core is implemented and tested.
6. Android source and pure tests from earlier phases have not had a complete Android build/JUnit verification.

## Phase breakdown after this contract

- **5.1:** This domain contract and gap inventory. Documentation only.
- **5.2:** Room tombstone persistence, migration, snapshot v3 model/codec compatibility and validation tests. No network calls.
- **5.3:** Pure deterministic merge engine and conflict result model, including all dataset policies and tests. No Room mutation or network.
- **5.4:** Staged Room/Earnings restore coordinator with atomicity, disabled schedules and crash/idempotency tests. No remote write.
- **5.5:** Supabase read/RPC transport and bounded CAS coordinator using fake transport tests first. No worker activation yet.
- **5.6:** Exact Outbox/tombstone/earnings acknowledgement and owner/revision checkpoint integration, with crash-injection tests.
- **5.7:** Enable remote worker execution only after all prior gates pass; final single GitHub Actions run after source completion. Device QA remains a separate gate.

## Verification

- Read the active branch's sync worker, batch planner, ownership manager, snapshot model/builder/validator/codec, Room entities/DAOs and architecture/ADR documents.
- Confirmed by source inspection that the worker currently returns `remote_executor_disabled` and performs no Supabase network I/O or acknowledgement.
- Confirmed the current v2 snapshot has no tombstone dataset and the Room schema has no tombstone entity/DAO.
- No Android source, Room schema, Supabase object, Auth setting, user data, Outbox row, remote backup row or GitHub Actions workflow was changed in Phase 5.1.
