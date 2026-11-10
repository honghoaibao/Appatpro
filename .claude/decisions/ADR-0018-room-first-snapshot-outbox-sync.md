# ADR-0018: Room-First Snapshot Backup and Outbox Sync

- **Status:** Accepted for the Phase 3.4 implementation plan
- **Date:** 2026-11-09
- **Scope:** Cloud backup/sync architecture only
- **Related:** ADR-0015, ADR-0016, ADR-0017

## Context

The app's source of truth is the local Room database. It also stores earnings in a separate file and schedules as JSON rows in the Room config table. AT App's Supabase project already has a public.user_backups table designed as one versioned backup row per Auth user. The existing normalized tables have cross-user uniqueness/device-key constraints that need separate review before they can safely serve as the first sync protocol.

## Decision

1. Keep Room and file-backed local storage authoritative. Cloud sync is a backup/portability layer only.
2. Use the existing user_backups row as the first remote snapshot target. Do not write to the normalized accounts, sessions, configs, farm_logs or devices tables in the first sync version.
3. Introduce a durable Room Outbox and sync metadata in a later Room migration. A required local mutation and its Outbox event must be committed atomically.
4. Serialize a deterministic, versioned backup envelope; GZIP + Base64 the canonical UTF-8 JSON for storage in the TEXT payload and calculate a 64-character SHA-256 checksum. Enforce a 7 MiB client-side payload ceiling below the database's 8 MiB constraint.
5. Use optimistic revision compare-and-swap for multi-device writes. A blind upsert is not accepted. Phase 4.1 must deliver the narrow atomic revision commit operation before live uploads begin.
6. Sync only when authenticated, connected and dirty. WorkManager is the future scheduler; there is no polling, Realtime dependency, or network call from automation.
7. Bind local sync metadata to one Auth user UUID. If a different user signs in, stop sync/restore until an explicit account-switch resolution is performed.
8. Use stable UUIDs for sessions and earnings entries, idempotent Outbox event IDs, and tombstones for user-requested deletes. Do not blindly sum aggregate account counters from two device snapshots; use a legacy baseline plus per-install contribution model.
9. Back up managed accounts, closed sessions, allowlisted non-secret configurations, disabled-on-restore schedule definitions and retained earnings. Exclude transient logs, UI theme preference, Auth sessions, Follow4Follow sessions, webhook URLs, passwords, tokens and other secrets from snapshot v1.
10. Restore by verifying checksum and schema first, staging all records, merging without destructive overwrite, and applying Room changes transactionally. Imported schedules remain disabled and never start automation automatically.

## Consequences

- The snapshot path avoids the current global accounts.username uniqueness and configs(device_id,key) issues in the normalized tables.
- The current user_backups RLS policies and authenticated INSERT/SELECT/UPDATE grants are suitable for owner-scoped read/write, but revision safety still requires a server-side atomic comparison.
- A future Room migration is required for Outbox metadata and stable local IDs.
- File-backed earnings need a backward-compatible entry ID/dirty-tracking extension.
- Multi-device aggregate counters need per-install contribution metadata; summing already-aggregated Room counters would double count restored values.
- Schedules must be reviewed and manually re-enabled after restore to prevent unexpected automatic work.

## Verification status

- Read-only inspection of the active AT App project confirmed the user_backups table, row constraints, RLS policies, grants and current zero-row state.
- Read-only inspection confirmed the normalized table constraints and current triggers.
- No SQL mutation, migration, Auth setting change, data write, Room migration, code implementation, build, test or GitHub Actions run was performed in Phase 3.4.1.
