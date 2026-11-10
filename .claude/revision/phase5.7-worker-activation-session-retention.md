# Phase 5.7 — Worker Activation and Session Retention

Date: 2026-11-10

## Decisions

- Cloud closed-session history is retained long-term.
- Each device keeps only the latest 90 days locally, using `startedAt` to match the existing Room cleanup semantics.
- A closed session remains in Room while its immutable `closedSessions/UPSERT` Outbox event is pending.
- If an older app version already removed the Room row, the selected Outbox payload is the durable recovery source. It is decoded strictly, bounded to 16 KiB, and its `syncId` must exactly match the Outbox entity key.
- The merged cloud history is filtered to the local 90-day window before inserting into Room. Older records remain in the remote committed snapshot and are not repeatedly imported to local storage.
- After remote CAS, restore and exact Outbox acknowledgement, the Room acknowledgement transaction prunes expired sessions that no longer have pending UPSERT rows.

## Worker flow

1. Restore the encrypted Auth session locally; do not perform network calls during startup restoration.
2. Verify the current Auth owner is already bound to local backup metadata.
3. Inspect a bounded Outbox head. A future retry window, invalid head or oversized first event prevents a remote call.
4. Run the bounded read/merge/CAS coordinator against the authenticated Supabase PostgREST/RPC path.
5. If and only if the remote responds HTTP 401, call the serialized Auth refresh operation once. Retry CAS only when the refreshed session retains the exact same user ID and the local owner binding still matches.
6. On a committed server revision, apply the merged candidate through the staged restore journal, then acknowledge only the exact represented Outbox prefix and captured Earnings generation.
7. Remaining batches and retryable remote/local failures use WorkManager exponential backoff. A blocked unrepresented event is retained and marked with a bounded per-event retry delay. Merge conflicts and nonretryable validation/auth errors fail closed.
8. Earnings-only dirty snapshots are eligible even when the Outbox is empty.

## Safety constraints

- SyncWorker never writes directly to `user_backups`; `save_user_backup` remains the only client write path.
- No owner ID is sent as an RPC parameter; Supabase derives ownership from `auth.uid()`.
- No password, access/refresh token, Follow4Follow credential, API key or secret config is included in the snapshot.
- No tombstone row is deleted.
- A different refreshed Auth user is blocked; no owner reassignment occurs.
- No schema, RLS, RPC, remote data or Auth settings were modified in this phase.

## Verification

- The single final GitHub Actions run was triggered via draft PR #18: [run #264](https://github.com/honghoaibao/Appatpro/actions/runs/42787687650), commit `1749494b53165ab6a1e5a20a4753dc1f0092f628`.
- Gradle setup, JDK 17 setup and workflow preparation succeeded. `testDebugUnitTest` reached `:app:compileDebugKotlin` and failed on two compile issues:
  1. The restore-created `BackupAccountStatContribution` baseline omitted four required counters.
  2. `SupabaseBackupRemoteStore` passed raw Kotlin primitives to the JSON object builder instead of `JsonPrimitive`.
- The release APK build was skipped because the JUnit task failed.
- Both source issues were corrected afterward: commits `ad7be894eff746fecac22ff24b6e92ff4e925240` and `60ecf122f6eb3e6d7ba921af71740b241351c6ba`.
- At the user's later request, the draft PR was reopened to recheck. GitHub emitted two pull-request runs: run `42789289419` on the older, pre-fix commit `1749494b53165ab6a1e5a20a4753dc1f0092f628`, and run `42789290607` on the fixed source commit `09eeb2a385e7254981cd926df26afdc4e1fcec5d`. The old-head run failed as expected; the fixed-source run passed `:app:compileDebugKotlin` and then failed `:app:compileDebugUnitTestKotlin` on stale test-source call sites/types (`AutomationEngineTest`, `AutomationEngineIntegrationTest`, `BackupTombstoneMigrationTest`, `Follow4FollowProviderTest`). Release APK build was skipped.
- Test-source alignment fixes were committed in `b1739045f90b117d3c83975800ada970dc9f59c2`, `089c0271925bcd75688cada81e19f35e2e7044bc`, `51dba57cdf6fd92962458b0a8f29052144211fd6`, and `59becd5de2e8357cbb88786cb91d05c6cc74f7c4`. These corrections have not yet been verified by another build/test run.
- The draft PR was closed unmerged after the two runs appeared. Current Actions runs attributable to this attempt: one initial run, plus one old-head and one latest-head run from the PR reopen event. Do not trigger another run without explicit user authorization.
- Current source has passed application Kotlin compilation on `09eeb2a`, but the latest test fixes and the release APK remain **not build/JUnit-verified**.
