# ADR-0004: Flutter Removal — Appatpro goes fully native Android

**Date**: 2026-05-22
**Status**: Accepted
**Supersedes**: ADR-0001 (Flutter as thin bridge layer)

---

## Context

ADR-0001 established Flutter as a "thin display layer only" with a plan to
migrate to native Compose incrementally. TASK-007 through TASK-016 completed
that migration: all 6 screens (Dashboard, Accounts, Logs, Stats, Config,
Schedule) have native Compose replacements behind compile-time feature flags.

Flutter remained in the repo only as a fallback, but with all flags flipped
to `true` (TASK-013), the Flutter code was unreachable at runtime. Continuing
to carry the Flutter toolchain in CI and the build system added cost for
zero benefit.

---

## Decision

Remove Flutter from Appatpro entirely:
- Delete `lib/` (all Dart source)
- Delete `pubspec.yaml`, `pubspec.lock`, Flutter metadata files
- Convert `MainActivity` to `AppCompatActivity`
- Replace `FlutterBridge` with a thin stub that forwards `sendEvent()`
  to `LanWebSocketServer.broadcast()` only
- Remove Flutter Gradle plugin from `settings.gradle` and `app/build.gradle`
- Rebuild CI pipeline around pure `./gradlew assembleRelease`

The `bridge/FlutterBridge.kt` stub is kept temporarily (Option A) to avoid
touching 5 callsite files in one pass. It will be removed after a dedicated
refactor (TD-NEW-001).

---

## Rationale

- All Flutter screens were already marked LEGACY and bypassed at runtime
- Flutter engine adds ~4MB to APK with no corresponding user-facing feature
- `flutter.sdk` path requirement blocked pure Android builds (e.g. no Flutter SDK on CI = broken build)
- Flutter build step added ~40–60s to every CI run
- Native Compose + Room + Coroutines covers all UI and data needs completely

---

## Consequences

**Positive:**
- Pure Android Gradle project — any Android dev can build with standard tools
- APK size reduced (Flutter engine removed)
- CI simplified: JDK 17 + Gradle only, no Flutter SDK setup
- `MainActivity` is now a trivial redirect — easy to test and understand
- No more `local.properties` flutter.sdk requirement

**Negative / risks:**
- `FlutterBridge.sendEvent()` stub remains until TD-NEW-001 is done
- No Flutter fallback possible — if a native screen has a critical bug,
  the fix must come from native code or a hotfix APK
- OEM QA still pending on physical devices (MIUI 14, ColorOS 14, OneUI 6)

---

## Rollback

See `revision-2026-05-22-task-013-017.md` for complete rollback steps.
Full rollback requires restoring deleted Dart source from git history.

---

## Review trigger

- When TD-NEW-001 is done: delete `bridge/FlutterBridge.kt` and update this ADR to Closed.
- When OEM QA signs off: update QA-013 checklist sign-off section.
