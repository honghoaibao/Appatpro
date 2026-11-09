# ADR-0015: Optional Supabase Auth and local-first cloud backup

**Date**: 2026-11-09
**Status**: Accepted

## Context

AT PRO currently stores its operational data locally in Room, DataStore/SharedPreferences and app-private files.
The owner wants registration and sign-in, plus a way to retain settings and user data after reinstalling or changing devices.
The existing Supabase project **AT App** already contains the initial account/device/session/config tables.

## Decision

- Keep Room and the existing local stores as the source of truth for app behavior.
- Add optional Supabase email/password authentication. Signed-out and offline users can continue using core AT PRO features.
- Persist Supabase sessions encrypted with AES-GCM using an Android Keystore key.
- Use only the project's publishable key in the Android app. Never ship service-role or secret keys.
- Implement cloud backup as a separate, batched data layer. It must not run in AutomationEngine or Accessibility callbacks.
- Synchronize only changed data while online. No Realtime connection or continuous polling.
- Never upload passwords, Follow4Follow credentials/tokens, Discord webhooks, or managed social-account credentials.
- Cloud restore must merge by stable identifiers and must never overwrite local data blindly.

## Phase 3 scope

Add Auth REST client, registration/login/logout UI, secure session persistence and unit tests.
This phase does not modify Supabase schema or start data backup/restore. Schema hardening and actual sync are handled in later phases.

## Consequences

- Existing automation behavior remains independent of Supabase.
- Uninstall removes the local Keystore key and encrypted session; the user signs in again after reinstall.
- Data backup/restore is not available until the later sync phase is completed.
- RLS and least-privilege grants remain mandatory for every exposed table.

## Rollback

Remove the Auth UI entry and Auth module. Room and all existing local data remain unchanged.
