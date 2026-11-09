# AT PRO — Supabase implementation notes

## Phase 3: Android foundation

- Added a small Supabase Auth REST client using the platform `HttpURLConnection`.
- Added an AES-256-GCM encrypted session store backed by Android Keystore.
- Kept authentication state in the `com.atpro.cloud.auth` package, separate from Follow4Follow credentials.
- No Supabase URL, publishable key, service-role key, password, or user token is committed.
- No existing Activity, Room entity, DAO, repository, automation service, or scheduling logic is changed in this phase.
- The new client is not wired into app launch yet; that is intentionally reserved for the connection phase after schema and Auth settings are verified.

## Connection phase requirements

1. Supply only the project's Supabase URL and public/publishable key through local build configuration. Never embed a service-role key in the APK.
2. Confirm email/password Auth and email-confirmation behavior in the Supabase Dashboard.
3. Wire an auth gate without starting automation services before successful authentication.
4. Refresh an expired access token before protected requests; clear local session if refresh is rejected.
5. Apply the reviewed RLS, grants, view, and trigger corrections before enabling cloud writes.
6. Add a durable Room outbox/change marker before implementing background sync. Do not infer deletion from a missing row.
7. Implement account-scoped snapshots and restore with conflict-safe IDs. Never upload Follow4Follow tokens or Discord webhooks.
8. Keep logs bounded and exclude secrets from request/response logging.

## Resource policy

- No Realtime subscriptions or polling.
- Use the existing Coroutines and WorkManager dependencies.
- Network calls run on `Dispatchers.IO` with bounded connect/read timeouts.
- Batch writes and schedule work only when local data has changed and the network is available.
