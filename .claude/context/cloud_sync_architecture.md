# Cloud Sync Architecture — Appatpro

- **Status:** Phases 3.4.2–3.4.7 and 5.2 implemented in source (not Android-build-verified); Phases 4.1–4.4 database backup CAS/RLS and public-schema hardening applied and verified in AT App; Android remote sync remains gated
- **Date:** 2026-11-10
- **Scope:** Room-first backup architecture and Supabase security contract
- **Related ADRs:** ADR-0015, ADR-0016, ADR-0017, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0025

## 1. Goals and invariants

1. Room remains the local source of truth. Screens, the scheduler, and the automation engine continue reading and writing local data without waiting for Supabase.
2. Supabase is a portability/backup destination, not a replacement database and not a runtime dependency of farming or automation.
3. A sync is attempted only when an authenticated user is available, the device is connected, and there are durable local changes or a restore is explicitly needed.
4. Every local change that must be backed up is recorded durably in an Outbox in the same Room transaction as the corresponding Room write.
5. Pending Outbox items are acknowledged only after the remote snapshot revision has been committed successfully.
6. Transient network errors, app termination, authentication expiry, malformed remote data, unsupported schema versions, or revision conflicts must never cause a destructive local overwrite or silent deletion of queued changes.
7. Backup ownership is bound to the Supabase Auth user. The client must never trust a caller-supplied owner_id; ownership is derived from the authenticated JWT and enforced by RLS/server logic.
8. No password, Supabase access/refresh token, Follow4Follow token, webhook URL, API key, or other credential is included in a cloud backup.

## 2. Current local data inventory

| Local source | Current storage | Existing retention/behavior | Initial backup decision |
|---|---|---|---|
| Managed accounts | Room accounts | Retained until user deletes | Include username, status/checkpoint, created/updated timestamps and aggregate stats |
| Farm sessions | Room sessions | Cleanup removes rows older than 90 days | Include closed sessions within the local retention window; active sessions are excluded |
| Tool configuration | Room configs | Key-value, updated in place | Include only an explicit safe-key allowlist |
| Scheduled farm definitions | Config keys prefixed sched_ | JSON definitions; WorkManager jobs are separately scheduled | Back up definitions, but restore them disabled; never silently reactivate automation |
| Earnings | filesDir/earnings.log via EarningsStore | Append-only file; trims to the latest 25,000 lines after crossing 30,000 | Include retained entries after adding stable entry IDs and durable dirty tracking |
| Farm logs | Room farm_logs | Local cleanup removes rows older than 7 days; batched writes | Keep local-only in the first backup format; diagnostic messages can be noisy and may contain user-specific text |
| UI theme preference | SharedPreferences at_ui | Device-local preference | Keep device-local initially |
| Follow4Follow session | SharedPreferences f4f_session and token codec | Separate provider authentication | Never back up or transfer; user signs in to the provider again on another install |
| AT PRO Auth session | Android Keystore AES-GCM store | Device-bound key and encrypted session | Never include in backup; each device signs in independently |
| Webhook/notification secrets | Config or preference storage | May contain endpoint credentials | Exclude; user re-enters on the target install |

The configuration exporter must use a **positive allowlist** of known safe configuration keys. A generic “export every row from configs” implementation is prohibited. Keys that contain or represent secrets (including discord_webhook_url) are always excluded.

## 3. Remote persistence target

The initial snapshot target is the existing public.user_backups table in the active Supabase project AT App.

Current verified structure:

- Primary key: owner_id (UUID, references auth.users(id), cascade on Auth-user deletion).
- revision BIGINT NOT NULL DEFAULT 0, constrained to non-negative values.
- schema_version INTEGER NOT NULL, constrained to positive values.
- payload TEXT NOT NULL, constrained by octet_length(payload) <= 8,388,608.
- checksum TEXT NOT NULL, constrained to exactly 64 characters.
- updated_at TIMESTAMPTZ NOT NULL DEFAULT now().
- RLS is enabled. The owner-scoped SELECT policy remains active; authenticated users can read only the row whose owner_id equals auth.uid().
- After Phase 4.1, authenticated has SELECT only. Direct INSERT, UPDATE and DELETE privileges are revoked; anon has no table privileges. The older owner-scoped INSERT/UPDATE policies remain as defense-in-depth but cannot be used without table grants.
- Authenticated snapshot writes must use public.save_user_backup(p_expected_revision, p_schema_version, p_payload, p_checksum). Only authenticated can execute it; anon and PUBLIC cannot.
- The RPC is SECURITY DEFINER with an empty search_path, fully qualified object references and no caller-supplied owner_id. It derives ownership from auth.uid() and only writes that user's row.
- The table currently has zero rows.

The normalized accounts, sessions, configs, farm_logs and devices tables are **not** the initial sync write target. Their current schema has integration concerns that require separate migration review first:

- accounts.username has a global unique constraint rather than a user-scoped unique constraint.
- sessions.account_id references accounts.username; the local Room session has only an auto-increment integer ID and currently has no stable sync_id.
- configs is keyed by (device_id, key), so it is device-scoped rather than a simple per-user key-value set.
- devices.android_id is globally unique and required; the app must not upload a hardware Android ID as its device identity.
- sessions has insert/update triggers that recompute remote account aggregates. Directly syncing both local aggregate totals and session rows would need an explicit reconciliation contract.

A single per-user snapshot in user_backups avoids writing into these partially mismatched row-level contracts in the first sync version.

## 4. Snapshot format

The payload contains a versioned envelope. The row's schema_version and the payload's formatVersion must agree.

Logical envelope:

~~~json
{
  "formatVersion": 3,
  "createdAt": 0,
  "appVersion": "1.12.0",
  "sourceInstallId": "uuid",
  "datasets": {
    "accounts": [],
    "closedSessions": [],
    "safeConfigs": [],
    "schedules": [],
    "earnings": [],
    "accountStatContributions": [],
    "tombstones": []
  }
}
~~~

Encoding contract:

1. Build deterministic JSON (stable key ordering and stable ordering within datasets).
2. Encode JSON as UTF-8.
3. GZIP the UTF-8 bytes and Base64-encode the compressed bytes for the TEXT column.
4. Store a lowercase 64-character SHA-256 hex checksum of the canonical, uncompressed UTF-8 JSON bytes.
5. On restore, Base64-decode, GZIP-decompress, verify the checksum, verify the envelope, then parse datasets.
6. Enforce an app-side maximum encoded payload of **7 MiB**, leaving headroom below the database's 8 MiB constraint. If the backup exceeds this limit, keep the local data and Outbox unchanged and report a bounded “backup too large” result.
7. The codec also caps uncompressed JSON at **32 MiB** before encoding and during decoding. Decoder validation checks the checksum, exact schema version, canonical JSON, required identifiers, safe config allowlist and dataset-level invariants before returning a snapshot model.

Do not serialize an entire Room database file, Android SharedPreferences file, token store, or arbitrary object graph.

## 5. Local sync metadata and Outbox

The next Room migration (planned as a separate implementation task) adds:

### sync_metadata (Room schema v3)

One local metadata row containing:
- Bound Supabase Auth user UUID, initially NULL.
- `ownerBoundAt`: local timestamp of the explicit first-use owner confirmation.
- Random app-installation UUID (not Settings.Secure.ANDROID_ID), persisted across app restarts.
- Last confirmed remote revision.
- Last successful sync timestamp and revision-confirmation timestamp.
- Last confirmed snapshot checksum.
- Snapshot format version and local sync status.

Room migration 2→3 adds only the two nullable timestamps `ownerBoundAt` and `lastRevisionConfirmedAt`; the v1→v2 migration remains in the chain. No user rows are rewritten or deleted.

The installation UUID identifies an app installation, not a physical device or person. A reinstall may create a new UUID; the restored cloud backup remains associated with the Auth user.

### sync_outbox

A durable local journal with:
- UUID event ID.
- Dataset/entity type.
- Stable entity key.
- Operation (UPSERT or DELETE).
- Sanitized JSON change payload for the allowed fields only.
- Creation timestamp and retry metadata.

Rules:
- Write the domain mutation and its Outbox event in one Room transaction.
- Coalesce pending UPSERT events for the same logical entity when safe; never drop a later DELETE tombstone.
- Closed sessions and earnings entries use stable UUIDs so retries and multi-device merges are idempotent.
- A session receives its stable sync UUID when created; closing it changes the same logical record rather than creating a second session.
- Account/config/schedule deletes are represented by tombstones until a committed remote snapshot acknowledges them.
- Room cleanup of records older than the existing retention window is handled by snapshot retention policy; it is not interpreted as a user-requested delete of the entire account.
- Phase 3.4.3 adds a versioned line format (`v2|entryUUID|timestampMs|kind|reward|account`), atomically upgrades legacy rows with stable UUIDs, and persists a generation/acknowledged-generation marker in `earnings.dirty`. The backup builder captures the earnings rows and generation together under the file lock. A future sync may acknowledge only the captured generation after the remote snapshot commit; any later append leaves the store dirty.

Do not create one Outbox event for every diagnostic log line. Logs are local-only in snapshot format v1.

### backup_tombstones (Room schema v5)

An append-only local delete-marker table keyed by a stable tombstone UUID. Each row stores one supported dataset (`accounts`, `safeConfigs` or `schedules`), its normalized logical entity key, source installation UUID and deletion timestamp. The table has no foreign key to the deleted row, so deleting an account/config cannot cascade away its marker.

Migration 4→5 backfills supported pending Outbox DELETE rows using the existing Outbox event ID, installation UUID and creation timestamp. New local deletes write a durable tombstone and matching Outbox DELETE in the same Room transaction; the two share one stable UUID. Tombstones are read into snapshot v3 in deterministic order, have a bounded snapshot read, and are retained without garbage collection until a safe multi-device causal/watermark protocol exists.

### Owner binding state machine

The app derives the local ownership state from the signed-in Supabase Auth user UUID and the singleton metadata row:
- Signed out → no cloud operation.
- Owner is NULL → show a first-use confirmation; do not upload or restore.
- Owner matches the current Auth UUID → eligible for a future sync layer to evaluate other gates.
- Owner belongs to another Auth UUID → block upload and restore; no automatic reassignment path exists.
- Invalid UUID or incomplete metadata → fail closed and preserve all local data.

The Settings screen confirmation binds only the current local installation data to the account shown in the active Auth session. It does not transfer data, upload a snapshot or change the server. Sign-out retains the bound owner. An account-switch/export/reassign workflow is intentionally not part of this phase.

### Remote revision checkpoint lifecycle

The local checkpoint is advanced only after a future server operation reports a confirmed atomic compare-and-swap from revision R to R+1. The client then persists revision, checksum, schema version, successful-sync timestamp and confirmation timestamp with an owner-scoped + expected-local-revision SQL update. A late response, wrong owner, invalid checksum, stale local revision or non-consecutive revision is rejected. This phase adds the local checkpoint contract only; it does not call the server or define the RPC itself.

## 6. Sync components and responsibilities

| Component | Responsibility |
|---|---|
| SyncManager | Orchestrates one sync cycle; owns a process mutex and checks user/owner/network/dirty state |
| SyncOutboxDao | Reads, coalesces and acknowledges durable pending changes |
| BackupSnapshotBuilder | Reads allowlisted Room/file data and builds the versioned envelope |
| BackupCodec | Deterministic JSON, GZIP/Base64, SHA-256 and size validation |
| CloudBackupRepository | Reads the user's user_backups row and commits a revision-checked snapshot |
| BackupMergeEngine | Merges pending local changes with the latest remote snapshot without losing unrelated-device changes |
| BackupRestoreCoordinator | Validates and stages the remote payload, then applies it atomically to Room and safely to file-backed earnings |
| SyncWorker | Phase 3.4.6 local-only preflight; verifies local Auth/owner gates and prepares bounded Outbox metadata candidates. Remote executor remains disabled. |

The automation engine, AccessibilityService, dashboard state machine and Follow4Follow provider do not depend on or call SyncManager.

## 7. Revision safety and multi-device writes

A plain “last writer wins” upsert to user_backups is not acceptable because two devices could overwrite a newer snapshot with an older one.

Required write flow:

1. Read the latest snapshot and server revision R.
2. Verify the remote checksum/schema before using it.
3. Merge pending Outbox changes into that latest snapshot.
4. Commit only when the server revision is still R; the server atomically stores the new payload and advances the revision.
5. If the revision changed, re-read and re-merge. Retry a small bounded number of times; otherwise leave the Outbox pending for a later run.
6. Acknowledge only the exact Outbox event IDs included in the successful revision. If the process crashes after cloud commit but before local acknowledgement, the same event IDs are replayed idempotently.

Because the current REST transport deliberately does not implement HTTP PATCH, Phase 4.1 implemented a narrow Postgres RPC rather than a blind upsert. The RPC is callable only by authenticated users and direct table INSERT/UPDATE/DELETE grants are revoked.

### Supabase server-side write boundary (Phase 4.1)

- Function: public.save_user_backup(p_expected_revision bigint, p_schema_version integer, p_payload text, p_checksum text). The owner is derived only from auth.uid(); owner_id is not an RPC argument.
- The function is SECURITY DEFINER because authenticated clients intentionally have no direct table DML privileges. It is owned by postgres, has an empty search_path, uses schema-qualified references, contains no dynamic SQL, validates auth.uid() and touches only public.user_backups.
- expected revision 0 can create revision 1, or update an existing revision-0 row. A non-zero expected revision can only update an existing row whose owner_id and revision both match. A missing row or stale revision raises BACKUP_REVISION_CONFLICT (SQLSTATE P0001); it never creates a row from a non-zero expected revision.
- Input checks require a non-negative, non-null expected revision; positive schema version; non-empty Base64 payload no larger than 8 MiB, with Base64 length/alphabet/padding checks; and a lowercase 64-character hexadecimal SHA-256 field. updated_at is assigned by the database clock.
- The app stores Base64(GZIP(canonical UTF-8 JSON)); the database does not decompress the payload or recompute the checksum. The app's bounded restore pipeline remains responsible for GZIP, checksum-content, canonical JSON, format-version and dataset validation.
- RLS remains enabled. The authenticated owner-scoped SELECT policy is active. The former INSERT/UPDATE owner policies remain, but authenticated has no grants to invoke them directly.
- Supabase Security Advisor reports one expected warning because this authenticated RPC is a SECURITY DEFINER function in the exposed public schema. This is an intentional, narrowly scoped exception: its body derives identity from auth.uid(), has a fixed empty search_path, has no dynamic SQL or caller-supplied owner, and is the sole authenticated write path. Any future expansion of the function must be reviewed again.
- The RPC's server updated_at orders snapshot commits only. It does not solve cross-device ordering of account statusUpdatedAt values, which remain device wall-clock values. The later merge/restore integration must use a server-authoritative per-entity ordering signal or preserve uncertain status conflicts for user resolution.

Do not replace this contract with a blind upsert or grant authenticated direct table writes. The existence of the RPC does not enable app-side remote sync or Outbox acknowledgement.

## 8. Merge and conflict policy

- **Accounts:** key by normalized username. Room v4 stores independent `statusUpdatedAt` and `statsUpdatedAt` fields. Status/checkpoint resolves by `statusUpdatedAt`; equal timestamps with different status values are unresolved. Note that this is currently a device wall-clock timestamp, not a causal clock: material clock skew can misorder cross-device changes. Before automatic remote status LWW is enabled, the server/integration design must provide a server-authoritative ordering signal or surface uncertain ordering for user resolution. Cumulative counters are represented by `accountStatContributions`, keyed by (accountId, installationId), with Long counters and per-installation updatedAt. For the same installation, merge each counter by component-wise MAX (idempotent replay); across distinct installations, sum the contributions using saturating arithmetic. Display values clamp to the existing Int UI range. Never sum two whole snapshots or apply generic LWW to counters. See ADR-0022.
- **Sessions:** merge as a set by stable session UUID; only closed sessions are uploaded. The same UUID is an update to one logical session, never a duplicate insert.
- **Configs:** merge by allowlisted key and updatedAt; unknown keys are ignored. Device-local preferences and credentials never enter the shared snapshot.
- **Schedules:** preserve their definitions, but import with enabled = false; require the user to review and manually enable them on a target installation.
- **Earnings:** merge by stable earning-entry UUID; preserve only entries still retained by the local EarningsStore policy.
- **Deletes:** account/config/schedule deletions use tombstones so a stale device cannot resurrect a deleted item.
- **Logs:** excluded from v1 snapshot; remain on the originating device.
- **Unresolvable conflict:** preserve both the local pending changes and the latest remote snapshot; do not silently discard either side. For unresolved account status/stat conflicts, leave the affected Outbox batch pending and do not apply last-write-wins or blind summation.

### Account merge gate

The local account conflict model in ADR-0022 is implemented in Phase 3.4.7: Room v4 additive migration, legacy baseline seeding, independent timestamps, per-installation contribution storage, pure merge/aggregate rules and tests. This removes the earlier local-model gate, but does **not** enable cloud writes. A worker must still not upload or acknowledge account events until Phase 4.1 provides a verified server-side revision CAS/RLS contract and a later integration phase implements a tested merge/restore coordinator.

## 9. Auth ownership boundary

The Room database currently survives sign-out and is not partitioned by Supabase user. Therefore:

- On first sync, bind local data to the current Auth user's stable UUID only after an explicit first-use confirmation.
- Store the bound owner ID in local sync metadata.
- If another Supabase user signs in while local metadata is bound to a different owner, block automatic upload and restore. Do not attach the existing local data to the new user by default.
- Provide a later explicit account-switch flow to export, clear, or deliberately reassign local data; never silently reassign it.
- On sign-out, keep Room and Outbox data. Stop cloud sync until an account is authenticated again.
- owner_id sent to the server is checked against auth.uid(); the server remains authoritative.

## 10. Restore safety

1. Authenticate and read the remote row.
2. Verify payload size, checksum, schema version, format version and required fields.
3. Parse into an isolated staging model; reject the whole snapshot if required sections are malformed or unsupported.
4. If local data already exists, merge rather than replace it.
5. Apply Room datasets in a single Room transaction; restore the earnings file through a temporary file + atomic rename.
6. Restore schedules as disabled definitions; do not enqueue WorkManager jobs or start any service automatically.
7. Update last revision/checksum and clear only those Outbox items proven to be included in the committed state.

## 11. Scheduling and resource policy

- Phase 3.4.6 uses an existing WorkManager 2.9.0 dependency; no additional library is required.
- Schedule one unique one-time request only when the Outbox or earnings dirty marker indicates pending backup data. The request requires `NetworkType.CONNECTED`, waits 20 seconds, and uses `ExistingWorkPolicy.KEEP` to coalesce a burst of local writes. It is not periodic polling.
- Re-check at application startup, after committed backup-eligible Room mutations, when the Auth session changes, and after explicit owner binding.
- The worker restores the encrypted Auth session locally, verifies the bound owner, reads only a bounded metadata projection of the Outbox head, and checks whether earnings are dirty. It never loads Outbox JSON payloads during preflight.
- Batch planning is capped at 50 events and 512 KiB of payload bytes. It preserves monotonic sequence order and will not skip an earlier event whose retry window has not opened.
- Any pending account event activates the ADR-0022 merge gate for the entire snapshot. The worker does not bypass it by selecting other datasets.
- **Remote execution is disabled in this phase:** no Supabase HTTP/RPC, upload/download, Outbox acknowledgement, revision update, earnings acknowledgement, retry mutation, or restore is performed. A successful WorkManager result means only that local preflight completed.
- Once remote execution is implemented, transient errors may use bounded exponential backoff; authentication/validation errors must not retry indefinitely. Do not use Realtime or continuous polling.
- No Supabase Realtime subscription or continuous polling is required for v1 snapshot sync.
- Network requests never run on the main thread and never block an automation session.

## 12. Phase boundaries

- **3.4.1 (this document):** architecture and data policy only.
- **3.4.2:** ✅ Room v2 Outbox/metadata entities, additive migration, stable session UUIDs and atomic local mutation recording (not build-verified).
- **3.4.3:** ✅ Local snapshot builder, deterministic JSON/GZIP/Base64 + SHA-256 codec, bounded decode validation, stable earnings IDs and durable dirty-generation tracking (source-only; no cloud writes).
- **3.4.4:** ✅ Room v3 owner-binding timestamps, explicit Settings confirmation, signed-in-owner matching/blocking, and local CAS revision-checkpoint contract (source-only; no cloud calls).
- **3.4.5:** ✅ source/security/conflict review; strict local metadata and snapshot validation added. ADR-0022 documents the unresolved account merge gate (source-only, not build-verified).
- **3.4.6:** ✅ unique connected-network WorkManager preflight, mutation/startup/auth triggers, metadata-only Outbox head projection and bounded 50-event / 512 KiB planner. No cloud operation or Outbox acknowledgement (source-only, not build-verified).
- **3.4.7:** ✅ Room v4 account conflict model: independent status/stat timestamps, migration 3→4 with exactly-once legacy baseline contribution, per-installation Long counters, component-wise MAX merge policy, saturating aggregate and snapshot format v2. Pure tests added; not build-verified.
- **4.1:** ✅ Applied the authenticated server-side revision CAS/RLS contract to AT App. Authenticated table access is SELECT-only; writes go through the owner-scoped save_user_backup RPC. Transaction smoke tests passed with all synthetic rows rolled back. Android remote writes remain disabled. See ADR-0024.
- **4.2–4.4:** ✅ Audited the exposed database surface. All six public base tables have RLS; five operational tables retain their owner-scoped CRUD policies. `daily_stats` is SECURITY INVOKER and SELECT-only. Removed client EXECUTE from trigger-only functions, removed obsolete user_backups write policies, and tightened `postgres` default privileges so new public tables/sequences/functions are not automatically granted to anon/authenticated. Transaction smoke tests confirmed defaults, view isolation and trigger execution.
- **5.1:** ✅ Merge/Restore domain contract and gap inventory documented.
- **5.2:** ✅ Local durable tombstones, pending DELETE migration backfill, snapshot v3 and strict v2→v3 compatibility/validation implemented in source; build/JUnit remain unverified. No Supabase or remote executor changes.
- **5.3–5.7:** Separately implement and test deterministic merge/conflicts, staged restore, bounded CAS transport, exact acknowledgement and only then remote worker activation. Do not enable app-side remote writes before every gate is verified.

Phase 3.4.1 itself changed architecture documentation only. Phases 3.4.2–3.4.7 changed local Room/application code, validation, preflight scheduling, account conflict handling and documentation only. Phase 4.1 changed only the user_backups write function and its table/function grants. Phases 4.2–4.4 changed only public-schema default privileges, the daily_stats view grants/comment, EXECUTE grants on two trigger functions, obsolete user_backups write policies and documentation. No backup rows, normalized operational rows, Auth settings, Android source or automation behavior were changed.


## 13. Phase 5.1 — Merge/Restore domain contract (2026-11-10)

The detailed contract and gap inventory are recorded in `.claude/revision/phase5.1-merge-restore-domain-contract.md`.

Important implementation findings:

- The current Room schema has no durable tombstone table/DAO, and snapshot format v2 has no tombstone dataset. Outbox DELETE rows alone cannot prevent resurrection after acknowledgement. A tombstone schema + snapshot version step is mandatory before remote merge/acknowledgement.
- Account `statusUpdatedAt` is a device wall-clock value. Cross-device status LWW is disabled until a server-authoritative per-entity version exists; differing status/checkpoint values remain unresolved.
- Session/earning same-ID content mismatches are integrity conflicts. Config/schedule conflicts must not be resolved by arrival order; device timestamps are not causal proof.
- Restore must stage and validate the merged model before any mutation. The coordinator should commit the candidate with server CAS first, then apply the already validated model locally; if local apply fails after cloud commit, leave local acknowledgements pending and re-apply idempotently from the committed remote snapshot.
- CAS retry target is bounded to 3 total attempts per worker run. On conflict, re-read and re-merge; on exhaustion, preserve Outbox and dirty generations.
- Exact Outbox event IDs, earnings generation and tombstone IDs/generation are acknowledged only after confirmed CAS success. Newer local writes remain pending.
- Imported schedules are always disabled; restore never starts automation or enqueues schedule work.

Phase 5.1 is documentation-only. No remote executor, network call, local schema change, Supabase change, data mutation or GitHub Actions run is enabled by this contract.

### Phase 5 implementation gates

- **5.1:** ✅ Domain contract and gap inventory documented.
- **5.2:** Room tombstone persistence + snapshot v3 compatibility/validation; local-only.
- **5.3:** Pure deterministic merge engine + conflict model; no mutation/network.
- **5.4:** Staged restore coordinator + atomic Room/Earnings apply; no remote write.
- **5.5:** Supabase read/RPC transport + bounded CAS coordinator; fake transport tests first.
- **5.6:** Exact Outbox/tombstone/earnings acknowledgement + owner/revision checkpoint + crash tests.
- **5.7:** Enable remote worker only after all prior gates pass; one final GitHub Actions run after code completion.

## 14. Phase 5.2 — Durable tombstones and snapshot v3 (2026-11-10)

Phase 5.2 completes the Room tombstone work and adds snapshot format v3. Room schema v5 uses the existing `backup_tombstones` entity/table with indexes on dataset/entity key, source installation and deletion timestamp. Migration 4→5 now backfills every still-pending supported Outbox DELETE (`accounts`, `safeConfigs`, `schedules`) by preserving its Outbox event UUID as the tombstone ID, taking the source installation from `sync_metadata` and the deletion time from the Outbox row. The migration compares eligible and inserted row counts and fails closed if any eligible DELETE cannot be preserved.

New local account/config/schedule deletes record a tombstone and a matching Outbox DELETE in one Room transaction. The tombstone ID is reused as the Outbox event ID. Nonexistent rows do not create redundant markers. Schedule WorkManager cancellation is separated from logical schedule deletion so replacing an enabled schedule does not create a false delete tombstone.

Snapshot writers emit v3 and include a deterministic, bounded tombstone dataset. Validation permits only the three supported datasets, requires canonical tombstone/source-install UUIDs, normalized non-empty keys, safe-config allowlist membership, non-negative deletion timestamps, unique IDs and at most 100,000 retained tombstones. Multiple tombstones for the same key remain valid; causal merge and recreation resolution belong to Phase 5.3.

The decoder preserves the exact v2 wire model. It validates legacy v2 data with the old dataset shape, confirms canonical JSON byte-for-byte, then converts it to a v3 staging model with an empty tombstone list. New encoding is v3 only; unknown future formats, expected-version mismatches and non-canonical JSON fail closed.

Phase 5.2 is local-only. No Supabase schema, policies, Auth settings, data, HTTP/RPC transport, remote worker activation, restore application, Outbox acknowledgement, tombstone acknowledgement or tombstone garbage collection was added. GitHub Actions were not dispatched. The APK build workflow only triggers push for `main`, `dev`, `release/*`, tags and manual dispatch; the feature branch is outside its branch push filter. Android/JUnit execution remains unverified and is reserved for the final build gate.
