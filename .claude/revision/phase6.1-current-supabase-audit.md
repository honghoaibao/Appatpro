# Phase 6.1 — Current Supabase source and production audit

**Date:** 2026-11-16  
**Branch:** `feature/supabase-completion-2026-11-16`  
**Baseline:** `main` at `69b3f49b630a6ee9073c3ca64cd65f376ccb501c`

## Scope

Read-only audit of the latest uploaded AT PRO source, connected Supabase project `AT App`, current database schema/migrations, RLS policies, function grants, Security Advisor and Performance Advisor. No production schema/data/Auth settings were changed.

## Findings

- Source version is v1.12.6 / versionCode 46.
- Auth, encrypted local session, Room-first snapshot v3, Outbox, merge/restore, CAS, acknowledgement and WorkManager worker are integrated in source. Earlier Phase 5.5/5.6 notes saying the worker is disabled are historical and superseded.
- Connected project is ACTIVE_HEALTHY on PostgreSQL 17 with five migrations.
- All six inspected public tables have RLS enabled. The normalized tables have authenticated owner-scoped policies. `user_backups` grants authenticated SELECT only and uses the `save_user_backup` CAS RPC for writes.
- The RPC derives ownership from `auth.uid()`, has an empty search_path and only grants EXECUTE to authenticated. The Advisor warning about authenticated execution of SECURITY DEFINER is expected for this intentionally narrow RPC, but remains a security-review item.
- `daily_stats` uses `security_invoker=true`.
- One remote backup row exists at revision 3 / schema v3, payload size 13,852 bytes. Payload contents were not read.
- Leaked-password protection is disabled in the Auth project settings. This was not changed; hosted email confirmation settings remain untouched.
- Unused-index notices are informational; no indexes were removed.

## Code correction in this phase

A manual-sync cooldown bug was found: the old code measured 30 seconds from the last completed worker attempt and could replace queued manual work repeatedly while offline. The completion branch now:

- persists the last accepted manual request timestamp separately;
- serializes concurrent taps with a Mutex;
- recognizes queued/running manual WorkManager work;
- applies the 30-second cooldown from request acceptance;
- preserves the ability to replace a pending automatic debounce with a manual request when allowed;
- adds pure unit tests for cooldown boundaries and clock rollback.

## Verification boundary

- Source was fetched again after writes to confirm the resulting files.
- Unit tests were added but not executed.
- No Android build or device test was run.
- No GitHub Actions workflow was dispatched.
- No PR was opened; `main` remains unchanged.

## Next phase

Phase 6.2 should focus on Auth repository/session tests, owner-binding transitions, restore/acknowledgement idempotency, and multi-installation merge tests. The final build/JUnit gate remains deferred until explicit user authorization.
