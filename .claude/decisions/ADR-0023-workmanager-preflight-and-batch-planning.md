# ADR-0023 — WorkManager Preflight Scheduling and Bounded Outbox Planning

- **Status:** Accepted for Phase 3.4.6
- **Date:** 2026-11-09
- **Scope:** Local scheduling, network constraints and bounded Outbox metadata planning
- **Related:** ADR-0018, ADR-0019, ADR-0021, ADR-0022

## Context

The app already depends on WorkManager 2.9.0. Local backup-eligible mutations produce durable Outbox rows, and file-backed earnings have a dirty-generation marker. A background scheduling layer is needed without introducing periodic polling, excess work, or an unsafe cloud write before the account merge and server revision contracts are ready.

## Decisions

1. Reuse the existing WorkManager dependency. No Gradle dependency upgrade or manifest component is needed for this phase.
2. Enqueue one unique, one-time request named `atpro_backup_sync` only when the Outbox or earnings dirty marker contains pending data.
3. Use `NetworkType.CONNECTED`, a 20-second initial debounce, and `ExistingWorkPolicy.KEEP`. Rapid local changes coalesce behind one pending request. Do not add periodic work, alarms, or a polling loop.
4. Check scheduling at application startup, after backup-eligible Room mutations have committed, when the Auth session changes, and after explicit first-use owner binding.
5. The worker reads the encrypted Auth session locally (no HTTP), verifies the current bound owner, checks pending counts, and reads only the Outbox head projection. The payload JSON itself stays in SQLite during preflight.
6. Batch planning preserves monotonic Outbox sequence, caps a candidate at 50 events and 512 KiB of payload bytes, and does not pass a delayed earlier event to process later sequence items.
7. Any pending account Outbox row activates the ADR-0022 merge gate for the whole per-user snapshot. The planner must not skip account rows and process other datasets as though they were an independent cloud backup.
8. **Remote execution remains disabled.** The worker has no Supabase transport dependency and does not send network requests, mutate retry metadata, acknowledge/delete Outbox rows, advance remote revision metadata, acknowledge earnings generations, download, restore, or modify local datasets.
9. `Result.success` from this worker means the local preflight check ended. It must never be presented as a successful cloud backup.
10. Later remote execution requires both the ADR-0022 account contribution model and the Phase 4.1 authenticated server-side revision CAS/RLS contract to be implemented and verified.

## Consequences

- Repeated small writes do not create an unbounded WorkManager queue.
- Network availability is a scheduling prerequisite for the future cloud worker; offline local app behavior remains unchanged.
- The worker has bounded metadata reads and does not load potentially large config payload JSON merely to decide whether a batch could be prepared.
- Pending data remains durable when signed out, unbound, on another account, conflicted, oversized, or when remote execution is disabled.
- This phase introduces no Supabase requests or server-side changes.

## Verification

Source-level review only. WorkManager execution, Android startup/provider initialization, battery/network constraint behavior, worker instantiation, JVM tests, Android build and device testing have not been run. GitHub Actions remain deferred to the one final build run.
