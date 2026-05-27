# Handoff: 2026-05-23 — Session 11: Fix DependencyHandler.module / Kotlin 1.9.25 upgrade

## What was done

### Root cause identified
`org.jetbrains.kotlin.kapt:1.8.22` calls `DependencyHandler.module(Object)` internally
when registering annotation processor classpath. This method was **removed in Gradle 8.0**.
Project uses Gradle 8.3 → hard `NoSuchMethodError` on every build.

No build script in this repo calls `.module()` explicitly (confirmed by grep).
Error is 100% inside kapt plugin binary. Fix: upgrade to Kotlin 1.9.0+ where JetBrains
rewrote the kapt plugin to use Gradle 8.x-compatible APIs.

### Files changed

| File | Change |
|------|--------|
| `android/settings.gradle` | `kotlin.android` / `kotlin.kapt` / `kotlin.plugin.serialization`: `1.8.22` → `1.9.25` |
| `android/app/build.gradle` | `kotlinCompilerExtensionVersion`: `1.4.8` → `1.5.15` (must match Kotlin) |
| `android/app/build.gradle` | `kotlin-stdlib`: `1.8.22` → `1.9.25` |
| `.claude/decisions/ADR-0007-kotlin-1.9.25-gradle8-kapt.md` | New ADR |
| `.claude/context/current_focus.md` | Updated |
| `.claude/tasks/current_tasks.md` | Updated |
| `.claude/tasks/technical_debt.md` | TD-BUILD-001 resolved + TD-KSP-001 added |

### Compatibility post-fix

```
Gradle wrapper:  8.3      (unchanged)
AGP:             8.1.0    (unchanged)
Kotlin:          1.9.25   ← was 1.8.22
Compose Compiler 1.5.15   ← was 1.4.8  (must match Kotlin 1.9.25)
kotlin-stdlib:   1.9.25   ← was 1.8.22
```

## What was NOT done (left in progress)

- TD-CI-001: `gradle-wrapper.jar` still not committed (requires local gradle env)
- KSP migration (TD-KSP-001): kapt → KSP for Room is low priority, not yet started
- Backup files NOT yet deleted (delete after CI confirms green):
  - `android/settings.gradle.bak2`
  - `android/app/build.gradle.bak`

## Next step

Push the 3 changed files (`settings.gradle`, `app/build.gradle`, ADR-0007) and verify
CI is green. Then clean up backup files.

## Gotchas / blockers

- **None blocking** after this fix.
- `nanohttpd-websocket:2.3.1` (2017, unmaintained) may generate POM metadata warnings
  in Gradle output. Not a blocker. If warnings escalate, replace with Java-WebSocket or
  OkHttp WebSocket. Noted in ADR-0007.

## Rollback

```bash
cp android/settings.gradle.bak2   android/settings.gradle
cp android/app/build.gradle.bak   android/app/build.gradle
```
Or: `git checkout android/settings.gradle android/app/build.gradle`

## Relevant context

- ADR-0007 (this fix)
- ADR-0006 (settings.gradle block order — previous session)
- Module: `build` layer only — no Kotlin source files changed
