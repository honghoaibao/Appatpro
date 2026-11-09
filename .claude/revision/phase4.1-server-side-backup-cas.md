# Phase 4.1 — Supabase Server-Side Backup CAS/RLS Contract

**Date:** 2026-11-10  
**Project:** AT App (sctzyxhwcatngwtjhwlf)  
**Branch:** feature/atpro-supabase-phase3-1-foundation  
**Status:** Applied to Supabase and transaction-smoke-tested; client remote sync remains disabled

## Objective

Make the existing per-user backup snapshot write path enforce server-side revision CAS, prevent direct Data API writes from bypassing CAS, and align server input validation with the Android Base64/GZIP/JSON codec.

## Initial findings

- public.user_backups already had RLS and owner-scoped SELECT/INSERT/UPDATE policies, but authenticated still had direct INSERT and UPDATE table grants.
- save_user_backup already checked auth.uid() and compared revisions for ON CONFLICT updates, but it was SECURITY INVOKER and therefore required those direct table grants.
- Its first-write path accepted any expected revision when no row existed, because the INSERT branch did not require expected revision 0.
- It cast p_payload to JSONB, although the Android BackupCodec stores Base64(GZIP(canonical JSON)) as text. This would reject valid app payloads.
- The project contained zero user_backups rows before the migration.

## Applied database changes

Migration 20261109230837 — secure_user_backup_cas_write_path:

- Revoked all direct table privileges on public.user_backups from anon, authenticated and PUBLIC; granted SELECT only to authenticated.
- Replaced save_user_backup with a narrow SECURITY DEFINER RPC using an empty search_path, schema-qualified names, and owner identity derived from auth.uid().
- Restricted function execution to authenticated; revoked from anon and PUBLIC.
- Added strict expected-revision branching, input-shape checks, server timestamping and a documented function comment.

Migration 20261109231229 — fix_user_backup_cas_returning_alias:

- The first live transaction smoke test exposed a PostgreSQL alias issue in INSERT ... ON CONFLICT ... RETURNING. The statement had to return current_backup.revision because the target table had been aliased.
- Applied a follow-up migration correcting the target alias. No backup row or synthetic user was committed during the failed test.

## Final access contract

| Surface | anon | authenticated |
|---|---:|---:|
| SELECT public.user_backups | Denied | Allowed by owner-scoped RLS |
| INSERT public.user_backups | Denied | Denied |
| UPDATE public.user_backups | Denied | Denied |
| DELETE public.user_backups | Denied | Denied |
| EXECUTE save_user_backup | Denied | Allowed |

RPC semantics:

- expected revision 0: insert revision 1, or update only a pre-existing revision-0 row.
- expected revision greater than 0: update only the current user's row when its revision exactly matches; never insert a missing row.
- mismatch or missing row: BACKUP_REVISION_CONFLICT (SQLSTATE P0001).
- invalid input: INVALID_BACKUP_PAYLOAD (SQLSTATE 22023).
- missing auth identity: AUTHENTICATION_REQUIRED (SQLSTATE 42501).

Payload checks:

- schema version must be positive.
- payload must be non-empty Base64, no larger than 8 MiB, with valid length/alphabet/padding shape.
- checksum must be 64 lowercase hexadecimal characters.
- The database does not decompress GZIP or verify the checksum against uncompressed JSON. The app's bounded restore pipeline remains responsible for content integrity and dataset validation.

## Verification

- Live SQL privilege/metadata verification passed: authenticated SELECT=true; direct INSERT/UPDATE/DELETE=false; authenticated RPC EXECUTE=true; anon RPC EXECUTE=false; SECURITY DEFINER=true; owner=postgres; search_path empty; initial-revision gate present; old JSONB payload cast absent.
- Rolled-back functional smoke test passed: initial revision 0 -> 1; stale revision 0 rejected; expected revision 1 -> 2; non-zero expected revision against a missing owner row rejected.
- Post-rollback verification: user_backups row count = 0; synthetic Auth user count = 0.
- Supabase Security Advisor reports one expected warning for the exposed SECURITY DEFINER RPC executable by authenticated. It is an intentional exception documented in ADR-0023 and bounded by the function's explicit owner checks and narrow scope.
- Performance Advisor's previously reported unused-index notices were not changed; they are outside this phase.

## Scope boundaries

- No Android source, Supabase HTTP/RPC client, sync worker, download, restore, merge coordinator, Outbox acknowledgement, retry mutation or account-owner reassignment was implemented.
- No normalized operational table, device data, backup row, Auth setting, email setting or unrelated automation logic was changed.
- The repo did not contain the pre-existing Supabase migration files for the earlier production schema. This phase adds the two exact migration scripts applied in Phase 4.1; it does not invent or reconstruct the older baseline migrations. A fresh-environment bootstrap still needs that separate baseline migration work.
- No GitHub Actions workflow was run. main remains unchanged and unmerged.

## Next gate

Proceed to Phase 4.2–4.4 only after reviewing the remaining tables, views, triggers, default privileges and RLS policies. Keep findings scoped; do not modify unrelated operational tables automatically. Remote sync remains disabled until a later approved phase implements and tests merge/restore and exact Outbox acknowledgement.
