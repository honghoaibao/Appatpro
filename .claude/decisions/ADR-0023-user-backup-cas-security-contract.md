# ADR-0023 — Owner-Scoped User Backup CAS RPC

- **Status:** Implemented in the AT App Supabase project; Android remote-sync integration remains gated
- **Date:** 2026-11-10
- **Scope:** public.user_backups only
- **Related:** ADR-0018, ADR-0021, ADR-0022

## Context

The existing user_backups table already had RLS and an owner-scoped save_user_backup RPC. However, authenticated still held direct INSERT and UPDATE grants, so a client could bypass the RPC's compare-and-swap (CAS) behavior through the Data API. The RPC also attempted to cast the stored payload directly to JSONB, while the Android codec actually sends Base64-encoded GZIP data containing canonical JSON.

## Decisions

1. Keep the initial backup target as one public.user_backups row per Supabase Auth user. Do not sync the normalized operational tables in this phase.
2. Keep RLS enabled and retain the owner-scoped SELECT policy. Revoke all direct table privileges from anon, authenticated and PUBLIC, then grant SELECT only to authenticated. The existing owner-scoped INSERT/UPDATE policies remain as defense-in-depth, but authenticated lacks the table grants needed to use them directly.
3. Use public.save_user_backup(p_expected_revision, p_schema_version, p_payload, p_checksum) as the only authenticated write surface. Grant EXECUTE only to authenticated; revoke it from anon and PUBLIC. The caller cannot supply owner_id.
4. The RPC derives v_owner_id from auth.uid() and rejects a missing identity. It is SECURITY DEFINER because the caller has no direct table DML rights. The function is owned by postgres, sets an empty search_path, schema-qualifies every non-builtin object reference, contains no dynamic SQL, and only writes public.user_backups for the derived owner.
5. CAS semantics are strict: expected revision 0 may insert revision 1; if a row already exists at revision 0, that request may advance it to revision 1. A non-zero expected revision only performs UPDATE when the same owner's stored revision exactly matches. A missing row with a non-zero expected revision is a conflict, never an insert. A failed comparison raises BACKUP_REVISION_CONFLICT with SQLSTATE P0001.
6. Validate expected revision as non-null and non-negative, schema version as positive, payload as non-empty Base64 within the table's 8 MiB byte limit with a valid length/alphabet/padding shape, and checksum as lowercase 64-character hexadecimal. Set updated_at using the database clock.
7. The app encodes Base64(GZIP(canonical UTF-8 JSON)) and hashes the canonical uncompressed JSON bytes. The database validates only the Base64 envelope and checksum shape; it does not decompress GZIP or recompute the checksum. The app must continue verifying the actual checksum, exact schema version, canonical JSON, payload bounds and dataset invariants before restore.
8. Treat the snapshot updated_at as server commit time only. It is not a causal timestamp for per-account status. Room statusUpdatedAt remains a device wall-clock value; later integration must either introduce a server-authoritative per-entity ordering signal or leave uncertain status conflicts unresolved.

## Security trade-off

Supabase Security Advisor reports one warning because authenticated can execute a SECURITY DEFINER function in the exposed public schema. This warning is expected and intentional for this narrowly scoped write endpoint: authenticated has no direct table DML privileges, anon/PUBLIC cannot execute the RPC, the identity comes only from auth.uid(), the search_path is empty, and no dynamic SQL or caller-controlled owner identifier is used. Do not copy this pattern to broader privileged functions without a separate review. If the design later moves writes behind a trusted server/Edge Function, revisit whether this RPC should remain exposed.

## Consequences

- A caller cannot change the snapshot revision or payload through direct table INSERT/UPDATE.
- Concurrent writes for one owner serialize on the owner_id primary key and the revision predicate; stale clients must re-read and re-merge.
- SELECT remains owner-scoped through RLS.
- A server-side commit advances the snapshot revision, but it does not itself merge local datasets, restore Room, acknowledge Outbox entries, or resolve account status clock skew.
- Android remote upload/download remains disabled until a later phase implements and tests the merge/restore coordinator.

## Verification

- Live privilege checks: anon has no user_backups SELECT/INSERT/UPDATE and cannot execute the RPC; authenticated has SELECT, no INSERT/UPDATE/DELETE, and can execute the RPC.
- Function metadata: SECURITY DEFINER, owner postgres, search_path empty, initial-revision gate present, and no JSONB cast of the Base64 payload.
- A rolled-back transaction smoke test created synthetic Auth users and verified: first write returns revision 1; stale revision 0 is rejected; expected revision 1 advances to revision 2; and a non-zero expected revision cannot create a missing row. After ROLLBACK, both user_backups and the synthetic auth.users rows remained at zero.
- Security Advisor: one intentional warning for authenticated execution of the exposed SECURITY DEFINER RPC; no other security lint was returned.
- Not verified: Android HTTP/RPC invocation, real-account/two-device flows, Gradle build, app JUnit suite, full restore integration, or GitHub Actions.
