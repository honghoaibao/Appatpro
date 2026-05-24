# Revision: 2026-05-22 — Kotlin 1.8.22 → 1.9.25 (fix DependencyHandler.module)

## What changed

Upgraded Kotlin plugin from `1.8.22` to `1.9.25` across all three Kotlin plugins,
and updated Compose Compiler extension version accordingly.

### android/settings.gradle

```diff
-    id "org.jetbrains.kotlin.android"                 version "1.8.22" apply false
-    id "org.jetbrains.kotlin.kapt"                    version "1.8.22" apply false
-    id "org.jetbrains.kotlin.plugin.serialization"    version "1.8.22" apply false
+    id "org.jetbrains.kotlin.android"                 version "1.9.25" apply false
+    id "org.jetbrains.kotlin.kapt"                    version "1.9.25" apply false
+    id "org.jetbrains.kotlin.plugin.serialization"    version "1.9.25" apply false
```

### android/app/build.gradle

```diff
-    // Kotlin 1.8.22 → Compose Compiler 1.4.8
-    composeOptions { kotlinCompilerExtensionVersion '1.4.8' }
+    // Kotlin 1.9.25 → Compose Compiler 1.5.15
+    composeOptions { kotlinCompilerExtensionVersion '1.5.15' }

-    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.22'
+    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.25'
```

## Why it changed

**Root cause**: `org.jetbrains.kotlin.kapt:1.8.22` calls `DependencyHandler.module(Object)`
internally when registering its annotation processing classpath. This method was removed
in Gradle 8.0 (deprecated since Gradle 4.0). The project uses Gradle 8.3, so every build
fails with:

```
java.lang.NoSuchMethodError:
  'org.gradle.api.artifacts.Dependency
   org.gradle.api.artifacts.dsl.DependencyHandler.module(java.lang.Object)'
```

No build script in this repo calls `.module()` explicitly — confirmed by grep.
The call originates 100% inside the kapt plugin binary.

This was fixed in Kotlin 1.9.0 by rewriting the kapt plugin's dependency registration
to use the Gradle 8.x-compatible `project.dependencies.create()` / `add()` APIs.

**Why 1.9.25 specifically** (not 1.9.0):
- 1.9.25 is the last stable release in the 1.9.x series → most bug-fixes included
- Compose Compiler 1.5.15 maps exactly to Kotlin 1.9.25 per JetBrains' official matrix
- AGP 8.1.0 supports Kotlin 1.9.x → no AGP change needed
- Gradle 8.3 supports Kotlin 1.9.x → no Gradle wrapper change needed

## Compatibility matrix (post-fix)

| Component              | Version   | Status |
|------------------------|-----------|--------|
| Gradle wrapper         | 8.3       | ✅ unchanged |
| AGP                    | 8.1.0     | ✅ unchanged |
| Kotlin / kapt          | 1.9.25    | ✅ Gradle 8.x safe |
| Compose Compiler       | 1.5.15    | ✅ matches Kotlin 1.9.25 |
| compose-bom            | 2024.05.00| ✅ unchanged |
| Room                   | 2.6.1     | ✅ unchanged, kapt works |
| coroutines-android     | 1.7.3     | ✅ unchanged |
| serialization-json     | 1.6.3     | ✅ unchanged |

## Files changed

| File | Change |
|------|--------|
| `android/settings.gradle` | Kotlin plugin versions: `1.8.22` → `1.9.25` (3 plugins) |
| `android/app/build.gradle` | `kotlinCompilerExtensionVersion`: `1.4.8` → `1.5.15`; `kotlin-stdlib`: `1.8.22` → `1.9.25` |

Backups: `android/settings.gradle.bak2`, `android/app/build.gradle.bak`

## Rollback note

```bash
cp android/settings.gradle.bak2 android/settings.gradle
cp android/app/build.gradle.bak android/app/build.gradle
```

Or:
```bash
git checkout android/settings.gradle android/app/build.gradle
```

## Risks / follow-ups

| Risk | Level | Note |
|------|-------|------|
| Kotlin 1.9 introduces deprecations in 1.8.x-only APIs | VERY LOW | No APIs removed, only deprecated-warn |
| Compose Compiler 1.5.x generates different bytecode | VERY LOW | Same semantics, binary output differs slightly — fine |
| `nanohttpd-websocket:2.3.1` library (2017) | LOW | Old library, POM may cause metadata warnings; not a blocker but worth monitoring. See follow-up below. |

### Follow-up: nanohttpd-websocket:2.3.1

The library `org.nanohttpd:nanohttpd-websocket:2.3.1` is from 2017 and not actively
maintained. While it is NOT the cause of the current error, its parent POM
(`nanohttpd-project:2.3.1`) may generate Gradle metadata warnings during resolution.
If CI starts showing metadata-related warnings for this lib, replace with
`org.java-websocket:Java-WebSocket:1.5.6` (maintained, AndroidX-compatible) or
a raw Socket/OkHttp WebSocket implementation. Not urgent.

## ADR reference

ADR-0007 (see `.claude/decisions/ADR-0007-kotlin-1.9.25-gradle8-kapt.md`)
