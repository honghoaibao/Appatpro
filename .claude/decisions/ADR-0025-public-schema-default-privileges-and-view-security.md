# ADR-0025 — Public Schema Default Privileges and View Security

- **Status:** Applied to the AT App Supabase project and transaction-smoke-tested
- **Date:** 2026-11-10
- **Scope:** App-owned objects in the public schema, public.daily_stats and public trigger functions
- **Related:** ADR-0023, ADR-0024

## Context

The database review found that the existing public base tables already had RLS and owner-scoped policies, but default privileges for objects created by the postgres migration role still granted broad client privileges to anon/authenticated. The public.daily_stats aggregate view was already SECURITY INVOKER but also had unnecessary write privileges. Two trigger-only functions were directly executable by client roles even though they were not RPC entry points.

## Decisions

1. Preserve the existing owner-scoped RLS policies on accounts, configs, devices, farm_logs and sessions. Both USING and WITH CHECK compare owner_id with auth.uid(). Do not change their operational DML grants in this phase.
2. Keep user_backups authenticated SELECT under its owner policy. Its only authenticated write path remains the ADR-0024 save_user_backup CAS RPC. Drop the now-obsolete INSERT and UPDATE RLS policies as defense-in-depth.
3. Keep public.daily_stats as SECURITY INVOKER and grant SELECT only to authenticated. Revoke all client DML privileges and keep anon without access. The view therefore respects RLS on public.sessions.
4. For objects created by postgres in public, revoke anon/authenticated default privileges on tables and sequences. New tables remain inaccessible to client roles until an explicit least-privilege GRANT is added.
5. Revoke the default PUBLIC EXECUTE privilege for functions created by postgres; also revoke the public-schema default EXECUTE grants to anon/authenticated. New RPCs must explicitly grant EXECUTE only to their intended role after review.
6. Revoke direct EXECUTE from PUBLIC, anon and authenticated on public.delete_old_logs() and public.update_account_stats(). They remain trigger functions; authenticated table writes still invoke their triggers through PostgreSQL's trigger mechanism.
7. Preserve service_role default privileges for trusted server-side operations. The service_role key must never be embedded in the Android app. Do not change supabase_admin defaults or Supabase-managed storage/graphql schemas in this phase.

## Consequences

- Public database objects created by the postgres migration role fail closed for client roles unless migrations explicitly grant access.
- Existing operational tables retain their current owner-scoped DML behavior.
- daily_stats is a read-only API surface for authenticated owners.
- Trigger functions no longer advertise a direct client EXECUTE surface.
- Future database migrations must state explicit grants and RLS policies for any new app table or RPC.
- The default EXECUTE revocation for postgres-created functions applies across schemas for that owner role; existing functions and objects are not altered by the default-privilege change.

## Deferred finding

The existing expire_logs trigger is defined as AFTER INSERT ... FOR EACH STATEMENT, while public.delete_old_logs() references NEW.owner_id. A statement-level trigger has no row-level NEW record, so this cleanup predicate appears unable to target the inserted owner's logs. This is a pre-existing functional issue, not a data-access escalation. It was not changed here to avoid altering unrelated log-retention behavior; review it in a separately approved bug-fix phase.

## Verification

- All six public base tables have RLS enabled.
- accounts, configs, devices, farm_logs and sessions retain their existing authenticated ALL policies with owner_id = auth.uid() in both USING and WITH CHECK.
- user_backups retains only its authenticated owner-scoped SELECT policy. Authenticated INSERT/UPDATE/DELETE privileges are false; save_user_backup remains the only authenticated write RPC.
- daily_stats has security_invoker=true, authenticated SELECT=true and authenticated INSERT/UPDATE/DELETE=false; anon SELECT=false.
- anon/authenticated cannot execute either trigger function directly. A transaction smoke test inserted a session as authenticated after revoking EXECUTE and confirmed the trigger still updated the account aggregate.
- An owner-isolation smoke test created two synthetic users with separate accounts/sessions, queried daily_stats as one authenticated user and observed only that user's aggregate row.
- A default-privilege smoke test created a table, identity sequence, sequence and function inside a transaction. anon/authenticated received no default table/sequence/function privileges; service_role retained the intended server-side function execution grant. All test objects were rolled back.
- Supabase Security Advisor reports only the previously documented intentional save_user_backup SECURITY DEFINER warning. Health Advisor reports no lints. Performance Advisor still reports 10 unused-index INFO notices; they were not modified.
- All synthetic Auth users and backup rows remained at zero after rollback. No live user data was inserted or modified.

## Scope boundaries

- No Android source, Auth settings, normalized operational data, backup rows, service workflow, automation engine or AccessibilityService behavior was changed.
- No Supabase Storage, Realtime, Auth, GraphQL system schema or supabase_admin defaults were changed.
- No Gradle build, device test or GitHub Actions run was performed; main remains unchanged and unmerged.
- App-side remote upload/download, restore, merge and Outbox acknowledgement remain disabled until Phase 5.x.
