# Phase 5 C4 — State UI Integration & Regression

Date: 2026-11-16  
Branch: `feature/supabase-completion-2026-11-16`  
Baseline: v1.12.6 / versionCode 46

## Scope

Complete the backup ownership and sync-status UI integration after C1–C3. This sub-phase is source implementation and regression-test preparation; it does not run Android build/test workflows or change Supabase production resources.

## Findings

- `MainScreen` rendered the last `BackupOwnershipManager.state` immediately while a new Auth user ID was being refreshed. This could briefly show the prior owner's Linked/NeedsConfirmation state during logout or account transition.
- `SyncWorker.doWork()` marked the status RUNNING before Auth restore and owner/generation validation. A stale or unbound worker could therefore produce a misleading spinner.
- `SyncWorkScheduler.requestManual()` marked RUNNING at enqueue time, including while WorkManager was still waiting for connectivity/constraints.
- C3 introduced `AUTH_SESSION_INVALIDATED`; its mapping to `SIGNED_OUT` needed explicit regression coverage.

## Changes

- Added pure `BackupOwnershipUiPolicy`. It immediately resolves a null Auth session to SignedOut, invalid identity to InvalidAuthIdentity, and an unresolved/new Auth owner to Checking. Only after the ownership state has been refreshed for that same canonical Auth UUID is the actual Linked/DifferentAccount/NeedsConfirmation state displayed.
- Wired `MainScreen` to the presentation policy so Settings never briefly renders the previous account's ownership state.
- Moved the worker RUNNING transition until after encrypted Auth restore, durable owner metadata validation, and successful generation/installation context capture.
- Removed premature RUNNING from manual enqueue. Cooldown persistence is retained; WorkManager queueing/offline waiting is no longer misrepresented as active execution.
- A worker whose context is stale now clears its spinner state when signed out, or stops its own spinner when another active Auth generation remains; it never overwrites the newer outcome.
- Added owner-state UI-policy tests and explicit `AUTH_SESSION_INVALIDATED → SIGNED_OUT` copy/notification tests.

## Regression tests added

- `BackupOwnershipUiPolicyTest`: signed-out override, account-switch checking state, canonical UUID matching, invalid identity fail-closed, resolved different-account and needs-confirmation presentations.
- `SyncStatusTest`: C3 invalidation code maps to SignedOut, displays the correct headline and does not raise a background failure notification.

## Verification

- Source-level re-fetch/contract inspection only.
- Android compile: NOT RUN.
- JUnit/Robolectric: NOT RUN.
- APK build: NOT RUN.
- Physical-device logout/login, queued-offline status and visual regression: NOT RUN.
- GitHub Actions: no manual dispatch/rerun. Automatic push-triggered workflow behavior is not asserted.
- Supabase production resources: unchanged.
- `main`: unchanged.

## Follow-up

- C1–C4 source implementation is ready for the separate verification gate.
- Sub-phase D must compile, run JUnit/Robolectric, build the APK and inspect the relevant runtime cases only after separate explicit authorization.
- RT-01 through RT-16 remain runtime verification items; source implementation alone does not mark them PASS.

## Rollback

Revert the C4 UI policy, MainScreen/worker/scheduler status changes, associated tests and this note on the feature branch. No Room migration or Supabase production change is required.
