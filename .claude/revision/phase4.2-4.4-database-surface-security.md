# Phase 4.2–4.4 — Supabase Database Surface Security Review

**Date:** 2026-11-10  
**Project:** AT App (`sctzyxhwcatngwtjhwlf`)  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Applied and verified with live database privilege, transaction and owner-isolation smoke tests

## Objective

Review the remaining exposed database surface after Phase 4.1: public tables, policies, views, functions, triggers, role grants and default privileges. Make only security-focused changes that preserve existing app operations.

## Audit findings

- All six public base tables (`accounts`, `configs`, `devices`, `farm_logs`, `sessions`, `user_backups`) had RLS enabled.
- The five operational tables retained authenticated owner-scoped ALL policies. Each policy uses `owner_id = auth.uid()` in both USING and WITH CHECK. Their existing DML grants were left unchanged.
- `user_backups` had already become SELECT-only for authenticated in Phase 4.1. Its old INSERT/UPDATE policies were now unnecessary and were removed.
- `public.daily_stats` was already marked `security_invoker=true`, so it respects RLS on `sessions`. However, the view had unnecessary write grants.
- `public.delete_old_logs()` and `public.update_account_stats()` are trigger-only functions but had default EXECUTE access to PUBLIC/anon/authenticated.
- Default privileges for objects created by role postgres in public still granted client roles broad rights on future tables/sequences and EXECUTE on future functions. This could make future migrations unintentionally client-accessible unless each object was manually secured.

## Applied migration

Database migration: `20261109233406_tighten_public_default_privileges_and_read_views`

1. Revoked default table and sequence privileges from anon/authenticated for objects created by postgres in public.
2. Revoked the global default PUBLIC EXECUTE privilege for functions created by postgres, and revoked the postgres/public-schema default EXECUTE grants to anon/authenticated.
3. Preserved service_role defaults for trusted server-side operations.
4. Changed `public.daily_stats` to SELECT-only for authenticated; anon has no access. Kept the existing SECURITY INVOKER view option.
5. Revoked direct EXECUTE from PUBLIC/anon/authenticated on the two trigger-only functions. The triggers themselves remain enabled.
6. Dropped obsolete authenticated INSERT and UPDATE policies on `public.user_backups`; its owner-scoped SELECT policy and Phase 4.1 RPC remain.

The migration did not change existing CRUD grants or RLS policies on accounts/configs/devices/farm_logs/sessions, and did not alter Supabase-managed storage/graphql schemas or supabase_admin defaults.

## Verification

- Final privilege scan: all six base tables have RLS. The five operational tables still have authenticated owner-scoped CRUD; user_backups has authenticated SELECT only.
- Policy scan: accounts/configs/devices/farm_logs/sessions retain owner_id = auth.uid() in both USING and WITH CHECK. user_backups retains only its authenticated SELECT policy.
- View scan: daily_stats has security_invoker=true, authenticated SELECT=true, authenticated INSERT/UPDATE/DELETE=false, anon SELECT=false.
- Function scan: anon/authenticated cannot directly execute delete_old_logs or update_account_stats. save_user_backup remains the only authenticated public write RPC.
- Default-privilege smoke test: created a table with identity sequence, a separate sequence and a function in a transaction. anon/authenticated had no default privileges; service_role retained its server-side function grant. Transaction rolled back.
- Trigger + owner-isolation smoke test: two synthetic Auth users were created with separate accounts/sessions; an additional session was inserted while acting as authenticated user A after direct EXECUTE revocation. The trigger still updated A's aggregate (session count 2, likes 6); daily_stats returned exactly one account row for A, not B. Transaction rolled back.
- Post-rollback state: zero public.user_backups rows; zero synthetic Auth users from both Phase 4.1 and Phase 4.2–4.4 smoke tests.
- Security Advisor: one expected warning remains for the authenticated SECURITY DEFINER save_user_backup RPC documented in ADR-0024. No new security finding appeared.
- Health Advisor: no lints.
- Performance Advisor: 10 unused-index INFO notices remain; index removal was outside scope.
- No Gradle build or Android device test was run. No GitHub Actions workflow was run.

## Deferred finding

The existing `expire_logs` trigger is AFTER INSERT ... FOR EACH STATEMENT, but `delete_old_logs()` references `NEW.owner_id`. A statement-level trigger has no per-row NEW value, so the cleanup operation appears ineffective. This is a pre-existing behavior bug, not an access-control issue; it was documented but left untouched to avoid unrelated changes.

## Boundaries

- No Android source or app logic changed.
- No normalized operational data, backup rows, Auth settings, Storage/Realtime, or service configuration changed.
- No main branch update, merge, GitHub Actions run, remote sync, restore, Outbox acknowledgement or automatic account reassignment.
- Remote sync remains disabled until Phase 5.x implements and verifies the merge/restore coordinator and exact Outbox acknowledgement.
