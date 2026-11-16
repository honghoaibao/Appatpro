# AT PRO — Runtime Verification Plan

**Date:** 2026-11-16  
**Repository:** `honghoaibao/Appatpro`  
**Branch:** `feature/supabase-completion-2026-11-16`  
**Baseline commit:** `06125f8ae0057898ca29d7facdcb88cadda0a754`  
**App build:** `v1.12.6` / versionCode `46`; CI artifact `atpro-v1.12.6-b47`

## 1. Goal and boundary

Verify that the implemented Auth and cloud-sync lifecycle behaves correctly on a real Android installation. Room remains the local source of truth. A network or cloud failure must not erase local data or cause blind acknowledgement.

This phase covers one-device runtime behavior:
- Sign-in, persisted session restore, token refresh and local sign-out.
- Automatic WorkManager sync and user-triggered **Đồng bộ ngay**.
- Offline mutation retention and automatic retry after connectivity returns.
- Process restart while work is pending.
- Visible sync status and safe error reporting.

Two-device restore, competing edits, tombstones and schedule behavior are reserved for the separate multi-device validation phase.

## 2. Existing automated evidence

The current branch contains:
- 13 Robolectric tests for `SupabaseAuthRepository` (signup confirmation response, sign-in, refresh rotation, expiry parsing, refresh rejection classification, transient errors, persistence failure, local restore, logout and concurrent refresh serialization).
- Unit coverage for CAS read/merge/retry, Supabase remote transport, acknowledgement policy/coordinator, snapshot validation, merge rules, tombstones, restore journal codec, schedule restore policy, batching, status text and notification policy.
- CI run [#286](https://github.com/honghoaibao/Appatpro/actions/runs/43831656216) succeeded on the baseline commit: JUnit, Android APK build and artifact upload all passed.

These results establish build and automated-test success only. They do not prove actual Android Keystore, WorkManager, background execution, Room crash recovery or live Supabase behavior.

## 3. Test preparation

1. Obtain the APK artifact `atpro-v1.12.6-b47` from [GitHub Actions run #286](https://github.com/honghoaibao/Appatpro/actions/runs/43831656216).
2. Keep the existing app installation and its data when testing an upgrade. Do not clear storage or uninstall as a first step; doing so would remove the local Room database and encrypted local session.
3. Use the intended AT PRO test account on the device. Enter credentials only in the app; do not paste passwords, access tokens, refresh tokens, publishable keys or raw authorization headers into chat or logs.
4. Start with stable internet access and record the current state in **Cài đặt → Sao lưu dữ liệu**.

## 4. One-device runtime cases

### RT-01 — Auth session survives process restart

- Sign in through the app.
- Force-stop the app from Android Settings, then reopen it.
- Expected: the session is restored locally; the app does not ask for credentials again while the session is valid.
- Also confirm that no token or password is shown in app logs or UI.

### RT-02 — Automatic sync after a local change

- Ensure the local backup is explicitly linked to the currently signed-in Auth user.
- Change one harmless, allowlisted setting that is included in the backup. Do not start automation merely to create test data.
- Keep the device online and wait for the existing debounce window.
- Expected: WorkManager performs one sync; the status changes to success; the pending count returns to zero; the local setting remains unchanged.

### RT-03 — Offline change is retained and retried

- Disable Wi-Fi and mobile data.
- Make one harmless allowlisted local setting change.
- Expected while offline: the setting remains available locally and its Outbox change remains pending. The app must not report a false successful cloud sync.
- Re-enable connectivity.
- Expected: WorkManager retries when the CONNECTED constraint is met; the pending count eventually reaches zero and status becomes successful.

### RT-04 — Manual sync and cooldown

- With network available, open **Cài đặt → Sao lưu dữ liệu** and tap **Đồng bộ ngay**.
- Expected: one manual sync runs immediately, including a read/merge cycle when no local changes are pending.
- Tap again while it is running: the app should not create a parallel worker.
- After an accepted request, another tap inside 30 seconds should be rejected by the cooldown. A request after the interval may be accepted.

### RT-05 — Pending work survives process death

- Create a pending local change while offline.
- Force-stop and reopen the app without clearing its data.
- Restore connectivity.
- Expected: the durable Outbox entry remains and can be synchronized; it is not lost merely because the process stopped.

### RT-06 — Logout while work is pending

- Create a pending local change, then sign out through the app.
- Expected: the local Auth session is cleared, queued unique sync work is cancelled, sync status is cleared, and local business data remains on the device.
- Do not use a second real account on this same local backup to test owner mismatch; ownership reassignment is deliberately blocked.

## 5. Evidence to record

For each case, record:
- Case ID and pass/fail.
- Android version and app version.
- The visible sync headline, last-sync text and pending count.
- Approximate time from local change to success.
- Any user-visible error text.

A screenshot of the status panel is useful. Logs must be redacted before sharing; remove email addresses, tokens, keys, request headers and private user data.

## 6. Completion criteria

Phase 5 passes only when RT-01 through RT-06 succeed on a real Android device, or any failures are fixed and retested. A green GitHub Actions run by itself is not sufficient.

If a test fails, preserve the local data, note the case ID, and capture only redacted evidence. Do not change Supabase production settings or delete remote rows as a diagnostic shortcut.

## 7. Out of scope

- Two-device pull/restore and merge-conflict validation.
- Reinstall recovery on a clean installation.
- Supabase Auth dashboard changes such as leaked-password protection.
- Production schema, RLS, RPC, key or data changes.
- Further GitHub Actions dispatches without separate user authorization.

## 8. Rollback

This document changes no runtime behavior, Room schema, Supabase object or production data. It can be removed independently if the verification workflow is revised.
