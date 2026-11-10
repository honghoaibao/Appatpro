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

- CI run #264 ([run ID 42787687650](https://github.com/honghoaibao/Appatpro/actions/runs/42787687650), commit `1749494`) failed at production Kotlin compilation on two issues: restore-created `BackupAccountStatContribution` missed required counters, and JSON builder calls passed raw primitives. Fixed in `ad7be89` and `60ecf12`.
- CI run #265 ([run ID 42789289419](https://github.com/honghoaibao/Appatpro/actions/runs/42789289419)) was an old-head run on `1749494` triggered while reopening draft PR #18; it repeated the pre-fix production compile failure.
- CI run #266 ([run ID 42789290607](https://github.com/honghoaibao/Appatpro/actions/runs/42789290607), commit `09eeb2a`) passed `:app:compileDebugKotlin`, then failed `:app:compileDebugUnitTestKotlin` on stale `startFarm` test call signatures, ambiguous Room query matchers and unsupported F4F `currentTime` references. Fixed by `b173904`, `089c027`, `51dba57` and `59becd5`.
- CI run #267 ([run ID 42791353515](https://github.com/honghoaibao/Appatpro/actions/runs/42791353515), commit `167bf72`) passed production and test Kotlin compilation, then executed 348 tests with 22 failures. Failures clustered in Android framework calls under plain JVM tests (`Rect`/accessibility nodes and `Looper` in AutomationEngine tests), the obsolete expectation that `hasNavBar` needs two labels, and strict MockK test doubles in `BackupTombstoneMigrationTest`. The release APK task was skipped.
- Follow-up test harness changes are committed on the feature branch:
  - `8253694`: enable Android resources for local unit tests and add Robolectric 4.11.1.
  - `a86433e`: run `NodeTraverserTest` under Robolectric and align the single-label nav test with current implementation.
  - `f061ff8`: run `AutomationEngineTest` under Robolectric and replace the invalid empty-list expectation with a deterministic launch-retry cleanup test.
  - `5f9f7c6`: run `AutomationEngineIntegrationTest` under Robolectric.
  - `5488515`: make migration database/cursor mocks relaxed for incidental SQL/close calls.
- Latest test-harness/code-comment changes end at `c948818bad2458dc6d879505e4397cd37d875391` (including `b437fcc`, `f9c0db1`, `c948818`); later commits only synchronize project documentation. These changes are source-reviewed but **not build- or test-verified**. No CI has been triggered after them.
- Draft PR #18 and #19 are both closed unmerged. `main` remains at `e8ce98fac9c6e49b817fe04d785a400746b9b1d8`. Do not trigger additional Actions runs without explicit user authorization.
