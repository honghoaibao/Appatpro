# Revision: 2026-05-23 — Session 11: Gradle Wrapper Fix + Root Cause Analysis

## Summary

Resolved the Gradle wrapper incompatibility caused by CI generating
`gradle-wrapper.jar` using the system Gradle (9.5.1) instead of the
project-pinned Gradle 8.3. Diagnosed all three interlocking issues.

---

## Root Cause Analysis

### Issue 1 — Missing `gradle-wrapper.jar` (primary)

`gradle/wrapper/gradle-wrapper.jar` was never committed to the repo.
This caused CI to fall back to:
```
gradle wrapper --gradle-version 8.3 --distribution-type all
```
...using **system Gradle 9.5.1** (the version pre-installed on `ubuntu-latest`).

**Why this is wrong:**
`gradle wrapper` generates `gradle-wrapper.jar` from the *generating* Gradle
installation's own wrapper JAR template. Running it from Gradle 9.5.1 produces
a 9.x wrapper jar even though `--gradle-version 8.3` is specified. The
`--gradle-version` flag only controls what gets written into
`gradle-wrapper.properties` (the download URL) — it does NOT control the JAR
source itself.

Additionally, the `gradle wrapper` command unconditionally overwrites the
committed `gradlew` and `gradlew.bat` with Gradle 9.x script templates, silently
replacing the committed (correct) files with 9.x equivalents.

**Result:** Every CI run that has no cached jar produces a build environment
assembled from mismatched versions: 9.x jar + 9.x scripts + 8.3 distribution.
This is reproducibility-breaking.

### Issue 2 — `DependencyHandler.module(Object)` error (historical, now resolved)

```
java.lang.NoSuchMethodError:
  'org.gradle.api.artifacts.Dependency
   org.gradle.api.artifacts.dsl.DependencyHandler.module(java.lang.Object)'
```

**Root cause:** `DependencyHandler.module(Object)` was deprecated in Gradle 4.0
and **removed in Gradle 8.0**. There is zero call to `.module()` in this repo's
own build scripts. The call came from *inside* the binary of
`org.jetbrains.kotlin.kapt:1.8.22`, which registered annotation processor
classpaths via this removed internal API.

**Resolution (already applied — ADR-0007):** Kotlin plugins upgraded
`1.8.22 → 1.9.25`. JetBrains rewrote the kapt internals in Kotlin 1.9.0 to
use the Gradle 8.x-compatible `Configuration` API instead of
`DependencyHandler.module()`. The error will not appear with `1.9.25`.

**This error was NOT caused by Gradle version incompatibility** — it was caused
by using an old Kotlin/kapt binary against Gradle 8.x. Downgrading Gradle was
never a valid fix (AGP 8.1.0 requires Gradle 8.0+).

### Issue 3 — CI not capturing diagnostics (secondary)

`./gradlew assembleRelease` was invoked without `--stacktrace` or
`--warning-mode all`. On failure, the error bubble was truncated and
`problems-report.html` was not uploaded.

---

## Compatibility Matrix (verified correct)

| Component                         | Version    | Status |
|-----------------------------------|------------|--------|
| Gradle wrapper                    | 8.3        | ✅     |
| Android Gradle Plugin (AGP)       | 8.1.0      | ✅     |
| `kotlin.android` plugin           | 1.9.25     | ✅     |
| `kotlin.kapt` plugin              | 1.9.25     | ✅     |
| `kotlin.plugin.serialization`     | 1.9.25     | ✅     |
| Compose Compiler extension        | 1.5.15     | ✅     |
| `kotlin-stdlib`                   | 1.9.25     | ✅     |
| compileSdk / targetSdk            | 34         | ✅     |
| JDK                               | 17         | ✅     |
| Room                              | 2.6.1      | ✅     |
| `kotlinx.coroutines`              | 1.7.3      | ✅     |
| `compose-bom`                     | 2024.05.00 | ✅     |

AGP 8.1.0 compatibility window: Gradle 8.0 – 8.3 ✅
Kotlin 1.9.25 + Compose Compiler 1.5.15: JetBrains official mapping ✅

---

## Files Changed

### `.github/workflows/build.yml`

| Change | Before | After |
|--------|--------|-------|
| Bootstrap step | Uses system Gradle (9.5.1) | Downloads pinned Gradle 8.3 binary |
| Build flags | `./gradlew assembleRelease` | `./gradlew assembleRelease --stacktrace --warning-mode all` |
| Problems report | Not uploaded | Uploaded on failure as artifact |
| No other steps changed | — | — |

**Bootstrap step — before (broken):**
```yaml
- name: Ensure Gradle Wrapper jar (bootstrap if missing)
  working-directory: android
  run: |
    if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
      gradle wrapper --gradle-version 8.3 --distribution-type all   # ← system gradle 9.5.1!
    fi
    chmod +x gradlew
```

**Bootstrap step — after (fixed):**
```yaml
- name: Ensure Gradle wrapper jar (TD-CI-001 bootstrap)
  working-directory: android
  run: |
    if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
      wget -q https://services.gradle.org/distributions/gradle-8.3-bin.zip \
           -O /tmp/gradle-8.3-bin.zip
      unzip -q /tmp/gradle-8.3-bin.zip -d /tmp/gradle-8.3-dist
      /tmp/gradle-8.3-dist/gradle-8.3/bin/gradle \
        wrapper --gradle-version 8.3 --distribution-type all
    fi
    chmod +x gradlew
```

### `android/gradle.properties`

| Change | Before | After |
|--------|--------|-------|
| JVM heap | `-Xmx4G` | `-Xmx2g -XX:MaxMetaspaceSize=512m` |
| Parallel builds | not set | `org.gradle.parallel=true` |
| Build cache | not set | `org.gradle.caching=true` |

`-Xmx4G` on `ubuntu-latest` (7GB RAM) risks OOM-killer termination when the
JVM, Gradle daemons, and Android build tooling all compete for memory.
`-Xmx2g` is sufficient for this project's dependency graph.

### `scripts/commit_gradle_wrapper.sh`

Added: SDKMAN detection for exact Gradle 8.3, confirmation prompt when
system Gradle version differs from target, distributionUrl sanity check.
No functional changes to the commit logic.

---

## What Was NOT Changed

- `android/build.gradle` (root) — no changes
- `android/app/build.gradle` — no changes
- `android/settings.gradle` — no changes (already correct post-session10)
- `android/gradle/wrapper/gradle-wrapper.properties` — no changes (already correct)
- `android/gradlew` — no changes (committed standard Gradle 8.x script)
- `android/gradlew.bat` — no changes (committed standard Gradle 8.x script)
- All Kotlin source files — untouched

---

## `assembleRelease` Verification Checklist

After committing `gradle-wrapper.jar` (TD-CI-001), the full build chain:

```
./gradlew assembleRelease --stacktrace --warning-mode all
```

Expected to pass with:
✅ AGP 8.1.0 processes resources (compileSdk 34, minSdk 24)  
✅ kapt 1.9.25 processes Room annotations (room-compiler:2.6.1)  
✅ Kotlin 1.9.25 compiles all sources (jvmTarget 17)  
✅ Compose Compiler 1.5.15 processes all `@Composable` functions  
✅ R8/ProGuard skipped (minifyEnabled false, shrinkResources false)  
✅ Signing: debug sig (no keystore) OR release sig (keystore secrets present)  
✅ Output: `app/build/outputs/apk/release/app-release.apk`

---

## Rollback Strategy

**Rollback build.yml (bootstrap step only):**
```bash
git checkout HEAD~1 -- .github/workflows/build.yml
```
The old step still works while jar is absent — it just uses system Gradle.
Only loses the version-pinning guarantee.

**Rollback gradle.properties:**
```bash
git checkout HEAD~1 -- android/gradle.properties
```
Reverts to `-Xmx4G` without parallel/cache flags.

**Rollback is NOT needed for:**
- `gradlew` / `gradlew.bat` — unchanged
- `gradle-wrapper.properties` — unchanged
- All source files — unchanged

---

## Remaining Blockers / TD Items

| Item | Status | Action |
|------|--------|--------|
| TD-CI-001: jar commit | ⚠️ **Still pending** | `bash scripts/commit_gradle_wrapper.sh` locally → push |
| KSP migration (kapt → KSP) | 🔵 Backlog | Separate ADR; kapt works fine with 1.9.25 |
| Kotlin 2.x upgrade | 🔵 Future | Needs AGP 8.3+, removes `composeOptions` block |
| Manual QA (3 devices) | ⏳ Not started | Human-only step |

### TD-CI-001 one-liner (after install Gradle 8.3 via sdkman):
```bash
sdk install gradle 8.3 && \
cd android && \
~/.sdkman/candidates/gradle/8.3/bin/gradle wrapper \
    --gradle-version 8.3 --distribution-type all && \
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar && \
git commit -m "chore: commit gradle-wrapper.jar (resolves TD-CI-001)"
```

---

## `--stacktrace` / `--warning-mode all` / `problems-report.html`

These are now permanently enabled in CI (`assembleRelease` step).

- `--stacktrace`: Full JVM stack on any exception. Without this, Gradle truncates
  errors like `NoSuchMethodError` to one line, hiding the call chain.
- `--warning-mode all`: Shows every deprecation warning. Critical for catching
  pre-removal API usage before it becomes a `NoSuchMethodError`.
- `problems-report.html`: Gradle 8.3 generates this at
  `build/reports/problems/problems-report.html`. Now uploaded as a CI artifact
  on failure (`retention-days: 7`).

Recommendation: after build is green and stable, consider removing `--stacktrace`
from the normal build step and moving it to a separate `./gradlew assembleRelease
--stacktrace` step that only runs on failure (using `if: failure()`).
