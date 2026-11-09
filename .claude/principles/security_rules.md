# Security Rules

## Credentials

- TikTok account credentials (if stored) must pass through `StringEncryptor` before Room insertion
- Encryption key must never be hardcoded. Use Android Keystore via `StringEncryptor`
- Never log credentials, tokens, or encrypted values — even at DEBUG level

## Network

- `LanWebSocketServer` is LAN-only. Do not expose it to the internet
- If the server gains auth in the future, use a session token generated at farm start — not a static secret
- All external HTTP calls (if any) must use HTTPS

## Secrets in code

- No API keys, tokens, or passwords in source files
- No secrets in `BuildConfig` fields that could leak in APK metadata
- `.gitignore` already covers `keystore.jks` and `key.properties` — verify before any new secret file

## APK signing

- Release APK signing config lives in `scripts/generate_keystore.sh` and `scripts/build_release.sh`
- Keystore file must never be committed to repo
- CI/CD uses secrets injection — see `.github/workflows/build.yml`

## Data at rest

- Room DB file is stored in the app's private data directory — protected by Android sandbox
- No sensitive data in `assets/` or `res/` directories
- `StringEncryptor` must use AES-256-GCM or equivalent — verify in code before modifying


## AT PRO cloud account (phase 3)

- The Supabase publishable key may be included in the Android client; never ship service_role or secret keys.
- Persist Supabase access/refresh tokens only through AES-GCM with a key generated and held by Android Keystore.
- Never send Follow4Follow tokens, passwords, Discord webhooks, or credentials for managed social accounts to cloud backup.
- Core functionality must remain available without an AT PRO login or internet connectivity.
