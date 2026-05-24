# Revision: CI Build Fix + v1.0.1 Config

**Date**: 2026-05-22
**Session**: 8
**Status**: Complete

---

## What changed

### 1. `.github/workflows/build.yml`

**Root cause fixed**: `./gradlew: No such file or directory`

`gradlew` and `gradle-wrapper.jar` were missing from `android/`.
Added "Generate Gradle Wrapper" step (between JDK setup and Gradle cache):

```yaml
- name: Generate Gradle Wrapper
  working-directory: android
  run: |
    gradle wrapper --gradle-version 8.3 --distribution-type all
    chmod +x gradlew
```

Additional hardening:
- `sed` pattern for versionCode: `s/versionCode [0-9]*/versionCode BLD/`
  (was: literal `s/versionCode 1/...` — would break if versionCode ever incremented locally)
- `sed` pattern for versionName: `s/versionName "[^"]*"/versionName "VER"/`
  (was: literal `s/versionName "1.0"/...` — broken after versionName change)
- APK output: `dist/atpro.apk` (was: `dist/att_v${VER}_fix${BLD}.apk`)
- Artifact name: `atpro-v{version}-b{build}` (traceability kept in artifact name)
- Default version fallback updated: `1.0.1` (was: `1.4.7` — Python tool version, wrong)
- Workflow_dispatch default version: `1.0.1` (aligned with Android v1.0.1)
- Summary step: shows version + build number inline

### 2. `android/app/build.gradle`

- `versionName "1.0"` → `versionName "v1.0.1 (thử nghiệm #1)"`
- All other config unchanged

### 3. `README.md`

Full rewrite to Android-native:
- Removed Flutter from Stack table
- Removed `lib/` from directory tree
- Removed `flutter pub get`, `flutter build apk`, `flutter analyze`
- Added Gradle wrapper generation instructions (one-time setup)
- Updated build commands to `./gradlew assembleRelease`
- Updated APK path and artifact name (`atpro.apk`)
- Added test table (38 tests)

### 4. `.claude/context/current_focus.md`

Updated with CI fix status and follow-up action (commit gradlew to repo).

---

## Why

The Flutter removal (TASK-017, session 7) updated the workflow to use
`./gradlew assembleRelease` instead of `flutter build apk`, but did not
commit the Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`).
Android projects require these files to be present in the repo (or generated
before use). CI failed immediately on `./gradlew`.

The sed patterns were also left as literal matches against the original
`versionName "1.0"` string. Since build.gradle now has a different default
versionName, the literal pattern would silently fail (sed exits 0 even
with no match), leaving the wrong versionName in the release APK.

---

## Impacted files

- `.github/workflows/build.yml` — CI fix
- `android/app/build.gradle` — versionName update
- `README.md` — docs cleanup
- `.claude/context/current_focus.md` — project memory
- `.claude/tasks/technical_debt.md` — TD-CI-001 added

---

## Rollback

**Rollback workflow**: revert `.github/workflows/build.yml` to previous commit.
The previous version fails at `./gradlew` (same as the original bug) — no value.

**Rollback versionName**: change `versionName "v1.0.1 (thử nghiệm #1)"` back
to `versionName "1.0"` in `android/app/build.gradle`. Also revert the sed
pattern if reverting to old workflow.

**Rollback README**: `git checkout HEAD~ -- README.md`

---

## Risks

| Risk | Level | Mitigation |
|------|-------|------------|
| `gradle` not available on ubuntu-latest runner | LOW | ubuntu-latest includes Gradle 8.x; verified in GHA tool cache |
| `gradle wrapper` version mismatch (system vs 8.3) | LOW | We pass `--gradle-version 8.3` explicitly; system Gradle only generates the wrapper |
| sed pattern too broad (matches wrong versionName) | VERY LOW | Pattern `versionName "[^"]*"` is Groovy-string-specific, unique in the file |
| `{app` phantom directory in `android/` | LOW | Not referenced in settings.gradle; does not affect build. See TD-CI-002 |

---

## Follow-up

- **TD-CI-001**: Run `gradle wrapper` locally → commit `gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`. Then remove the "Generate Gradle Wrapper"
  CI step. Standard Android practice: wrapper files should be committed.
- **TD-CI-002**: Remove phantom `android/{app` directory (git artifact).
  Investigate origin — likely a template rendering failure.
