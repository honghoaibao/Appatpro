# Current Tasks

Tasks actively in progress. Keep this list short (≤5 items).

---

## TD-BUILD-001: DependencyHandler.module() — Kotlin 1.8.22 vs Gradle 8.x ✅ RESOLVED (session 11)

**Status**: Fixed
**Root cause**: `kotlin-kapt:1.8.22` calls `DependencyHandler.module(Object)` internally;
method removed in Gradle 8.0. Fixed by upgrading to Kotlin 1.9.25.
**Files changed**:
- `android/settings.gradle` — Kotlin plugins: `1.8.22` → `1.9.25`
- `android/app/build.gradle` — Compose Compiler: `1.4.8` → `1.5.15`; stdlib: `1.8.22` → `1.9.25`
**ADR**: ADR-0007
**Cleanup**: Delete `android/settings.gradle.bak2`, `android/app/build.gradle.bak` after CI green.

---

## TD-CI-001: Commit gradle-wrapper.jar (requires local gradle)

**Status**: Blocked on local environment
**Action**: Run `bash scripts/commit_gradle_wrapper.sh` on a machine with `gradle` installed.
After commit, the CI bootstrap step becomes a no-op (~1s saved per build).

---

_Other tasks: see backlog.md_
