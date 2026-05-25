# ADR-0002: Room (local SQLite) as the only database — no cloud sync

**Date**: 2026-05-21
**Status**: Accepted

## Context

An earlier version used Supabase (cloud Postgres) for storing accounts, sessions, and logs. This created a dependency on internet connectivity and introduced privacy concerns around farming data leaving the device.

## Decision

All data is stored in Room (SQLite) on-device only. Supabase is removed. No cloud sync will be introduced without explicit ADR.

## Rationale

- Farming is a local, single-device operation. Cloud sync adds no functional value
- Internet requirement for core features is a dealbreaker for the target user (offline-capable farming)
- Privacy: account data and interaction logs must not leave the device
- Simplicity: no auth, no network failure handling, no sync conflicts

## Consequences

- Export feature (`export_screen.dart`) handles data portability via file export
- Multi-device coordination uses `LanWebSocketServer` (LAN-only, real-time) — not cloud sync
- Schema migrations require explicit Room migration scripts (no destructive migration in production)

## Alternatives considered

- **Supabase retained**: rejected — internet dependency, privacy risk
- **Firebase**: rejected — same reasons as Supabase
- **DataStore for prefs + Room for records**: partially adopted — FarmConfig could move to DataStore in future (see backlog)

## Rollback

Re-introducing cloud: add a sync module that reads Room and pushes to cloud — do not replace Room.
