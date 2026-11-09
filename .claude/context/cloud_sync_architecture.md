# Cloud Sync Architecture — Appatpro

- **Status:** Architecture approved; Phase 3.4.2 Room journal implementation added (source-only, not build-verified)
- **Date:** 2026-11-09
- **Scope:** Phase 3.4 design baseline
- **Related ADRs:** ADR-0015, ADR-0016, ADR-0017, ADR-0018

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
- RLS is enabled. The current policies allow authenticated users to SELECT, INSERT and UPDATE only their own row using auth.uid() = owner_id.
- The authenticated role currently has SELECT, INSERT and UPDATE privileges; there is no authenticated DELETE policy/privilege.
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
  "formatVersion": 1,
  "createdAt": 0,
  "appVersion": "1.12.0",
  "sourceInstallId": "uuid",
  "datasets": {
    "accounts": [],
    "closedSessions": [],
    "safeConfigs": [],
    "schedules": [],
    "earnings": [],
    "accountStats": {}
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

Do not serialize an entire Room database file, Android SharedPreferences file, token store, or arbitrary object graph.

## 5. Local sync metadata and Outbox

The next Room migration (planned as a separate implementation task) adds:

### sync_metadata

One local metadata row containing:
- Bound Supabase owner_id.
- Random app-installation UUID (not Settings.Secure.ANDROID_ID), persisted across app restarts.
- Last confirmed remote revision.
- Last successful sync timestamp.
- Last acknowledged snapshot checksum.
- Local snapshot format version and sync status.

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
- The current EarningsStore file needs a format-compatible stable entry ID and a durable dirty marker before it is included. That file integration is part of the Outbox implementation design, not part of this architecture-only round.

Do not create one Outbox event for every diagnostic log line. Logs are local-only in snapshot format v1.

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
| SyncWorker | Future WorkManager adapter; runs only with network connectivity and delegates to SyncManager |

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

Because the current REST transport deliberately does not implement HTTP PATCH, Phase 4.1 must decide and implement an atomic revision-check operation (preferred: a narrow Postgres RPC using SECURITY INVOKER, auth.uid(), a fixed safe search path and grants only to authenticated). Do not replace this with blind upsert. Do not use SECURITY DEFINER to bypass RLS.

## 8. Merge and conflict policy

- **Accounts:** key by normalized username. Status/checkpoint use the most recent updatedAt; aggregate counters must not be blindly summed from two snapshots. Preserve the pre-sync totals as a legacy baseline and track post-sync session increments as idempotent per-install contributions, then derive the displayed total. This avoids double counting a restored aggregate when another device logs in.
- **Sessions:** merge as a set by stable session UUID; only closed sessions are uploaded. The same UUID is an update to one logical session, never a duplicate insert.
- **Configs:** merge by allowlisted key and updatedAt; unknown keys are ignored. Device-local preferences and credentials never enter the shared snapshot.
- **Schedules:** preserve their definitions, but import with enabled = false; require the user to review and manually enable them on a target installation.
- **Earnings:** merge by stable earning-entry UUID; preserve only entries still retained by the local EarningsStore policy.
- **Deletes:** account/config/schedule deletions use tombstones so a stale device cannot resurrect a deleted item.
- **Logs:** excluded from v1 snapshot; remain on the originating device.
- **Unresolvable conflict:** preserve both the local pending changes and the latest remote snapshot; do not silently discard either side.

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

- A future WorkManager request uses a CONNECTED network constraint and unique work name to coalesce duplicate requests.
- Request sync only when there are pending changes or an explicit restore/repair is needed.
- Use a short debounce after local writes; never sync on every small write or from a tight loop.
- Use bounded batches and compression; do not send logs on every sync.
- Use exponential backoff for transient network/server failures. Do not retry authentication/validation failures indefinitely.
- No Supabase Realtime subscription or continuous polling is required for v1 snapshot sync.
- Network requests never run on the main thread and never block an automation session.

## 12. Phase boundaries

- **3.4.1 (this document):** architecture and data policy only.
- **3.4.2:** ✅ Room v2 Outbox/metadata entities, additive migration, stable session UUIDs and atomic local mutation recording (not build-verified).
- **3.4.3:** safe snapshot builder, codec and file-backed earnings integration.
- **3.4.4:** installation identity, owner binding and revision metadata.
- **3.4.5:** source/security/conflict review and fixes.
- **3.4.6:** WorkManager/network scheduling and batch sync adapter.
- **4.1 onward:** Supabase RPC/schema/revision write contract, RLS/grants review, and cloud integration.

No Supabase schema, RLS, grants, rows, Auth settings, Room schema, Gradle files or application code are changed by Phase 3.4.1.
