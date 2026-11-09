# Phase 3.4.1 — Sync architecture design

- **Date:** 2026-11-09
- **Branch:** feature/atpro-supabase-phase3-1-foundation
- **Scope:** Architecture/data-flow design only; no implementation

## Completed

- Audited the current Room entities/DAOs and LocalRepository.
- Audited file-backed earnings (EarningsStore), config-backed schedules (sched_*) and the separate Follow4Follow/Auth session stores.
- Inspected the active Supabase project AT App in read-only mode.
- Confirmed the existing per-user user_backups table, 8 MiB payload check, revision/schema/checksum columns, Auth-user foreign key, RLS policies and authenticated INSERT/SELECT/UPDATE grants.
- Confirmed the normalized tables have compatibility concerns for a first multi-device sync contract: global unique account username, device-keyed configs, non-UUID Room session IDs and the required/global unique remote device identifier.
- Defined Room-first sync invariants, data inclusion/exclusion rules, snapshot format, Outbox/metadata requirements, revision compare-and-swap, owner binding, conflict handling, restore safety and resource policy.
- Added ADR-0018 and the cloud sync architecture context document.

## Files added/updated

- .claude/context/cloud_sync_architecture.md
- .claude/decisions/ADR-0018-room-first-snapshot-outbox-sync.md
- .claude/revision/phase3.4.1-sync-architecture.md
- .claude/context/constraints.md
- .claude/context/current_focus.md

## Not changed

- No Room entity, DAO, database version or migration change.
- No SyncManager, Outbox, WorkManager or snapshot codec code.
- No Supabase DDL, RLS, grant, RPC, row or Auth setting change.
- No dashboard, automation, AccessibilityService, Follow4Follow, scheduler or existing data behavior change.
- main was not modified or merged.

## Verification and limitations

- Repository and Supabase inspection were read-only.
- The active Supabase project's current user_backups row count is zero.
- No application build, unit/device test or live Auth/backup request was performed.
- GitHub Actions were not run.
- The revision/CAS RPC and detailed Room migration are future implementation work, not present in this round.

## Next

- **3.4.2:** implement the Room Outbox and sync metadata with atomic mutation recording.
- Before any cloud write, **4.1** must implement and verify the atomic revision compare-and-swap contract and re-review RLS/grants.
