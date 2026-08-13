# ADR-0007: Upgrade Kotlin 1.8.22 → 1.9.25 để fix DependencyHandler.module() incompatibility

**Date**: 2026-05-23
**Status**: Accepted

## Context

Build fail toàn bộ với lỗi runtime:

```
java.lang.NoSuchMethodError:
  'org.gradle.api.artifacts.Dependency
   org.gradle.api.artifacts.dsl.DependencyHandler.module(java.lang.Object)'
```

Project dùng Gradle 8.3 + AGP 8.1.0. Không có call `.module()` nào trong
build scripts của repo — lỗi xuất phát 100% từ bên trong binary của
`org.jetbrains.kotlin.kapt:1.8.22`.

**Root cause**: `DependencyHandler.module(Object)` bị remove trong Gradle 8.0
(đã deprecated từ Gradle 4.0). Plugin `kotlin-kapt:1.8.22` gọi method này
nội bộ khi đăng ký annotation processor classpath. Lỗi này được fix trong
Kotlin 1.9.0 khi JetBrains rewrote kapt plugin để dùng API mới của Gradle 8.x.

## Decision

Upgrade tất cả Kotlin plugins từ `1.8.22` lên `1.9.25`.

Cũng cập nhật hai giá trị phụ thuộc:
- `composeOptions.kotlinCompilerExtensionVersion`: `1.4.8` → `1.5.15`
  (Compose Compiler phải khớp chính xác version Kotlin theo ma trận JetBrains)
- `kotlin-stdlib` dependency: `1.8.22` → `1.9.25`

AGP (8.1.0) và Gradle wrapper (8.3) **không thay đổi**.

## Rationale

**Tại sao 1.9.25 (không phải 1.9.0)?**
- 1.9.25 là bản stable cuối của series 1.9.x → many bug-fixes
- Compose Compiler 1.5.15 map chính xác tới Kotlin 1.9.25 theo official JetBrains table
- AGP 8.1.0 tested với Kotlin 1.9.x
- Gradle 8.3 fully supports Kotlin 1.9.x

**Tại sao không upgrade lên Kotlin 2.x?**
- Kotlin 2.x yêu cầu Compose Compiler Plugin riêng (không còn dùng
  `composeOptions.kotlinCompilerExtensionVersion`)
- Cần AGP 8.3+ để hỗ trợ tốt K2 compiler
- Risk cao hơn không cần thiết — 1.9.25 đã giải quyết đúng vấn đề cụ thể
- Upgrade lên 2.x là một task riêng, cần test riêng

**Tại sao không downgrade Gradle thay vì upgrade Kotlin?**
- `DependencyHandler.module(Object)` removed trong Gradle 8.0 — đây là quyết định
  có chủ đích của Gradle team, không phải accident
- Downgrade Gradle để bypass một API removal chính đáng là technical debt
- AGP 8.1.0 yêu cầu Gradle 8.0+ → không thể downgrade đáng kể

## Compatibility matrix

| Component                       | Before  | After   | Changed |
|---------------------------------|---------|---------|---------|
| Gradle wrapper                  | 8.3     | 8.3     | ❌      |
| AGP                             | 8.1.0   | 8.1.0   | ❌      |
| `kotlin.android` plugin         | 1.8.22  | 1.9.25  | ✅      |
| `kotlin.kapt` plugin            | 1.8.22  | 1.9.25  | ✅      |
| `kotlin.plugin.serialization`   | 1.8.22  | 1.9.25  | ✅      |
| Compose Compiler extension      | 1.4.8   | 1.5.15  | ✅      |
| `kotlin-stdlib`                 | 1.8.22  | 1.9.25  | ✅      |
| Room                            | 2.6.1   | 2.6.1   | ❌      |
| kotlinx.coroutines              | 1.7.3   | 1.7.3   | ❌      |
| kotlinx.serialization.json      | 1.6.3   | 1.6.3   | ❌      |
| compose-bom                     | 2024.05.00 | 2024.05.00 | ❌ |

## Files changed

| File | Change |
|------|--------|
| `android/settings.gradle` | Kotlin plugin versions: `1.8.22` → `1.9.25` (3 IDs) |
| `android/app/build.gradle` | `kotlinCompilerExtensionVersion`: `1.4.8` → `1.5.15` |
| `android/app/build.gradle` | `kotlin-stdlib`: `1.8.22` → `1.9.25` |

## Consequences

- **Build unblocked**: `DependencyHandler.module()` error sẽ không xuất hiện nữa
- **No API breakage**: Kotlin 1.8 → 1.9 không xoá API, chỉ thêm deprecation warnings
- **Compose output stable**: Compose Compiler 1.5.x giữ semantics tương đương 1.4.x
- **Room kapt unaffected**: Room annotation processing qua kapt vẫn hoạt động
- **CI unchanged**: Không cần thay đổi `.github/workflows/build.yml`

## Rollback

```bash
# Option A — từ backup files (nếu chưa commit)
cp android/settings.gradle.bak2      android/settings.gradle
cp android/app/build.gradle.bak      android/app/build.gradle

# Option B — từ git (sau khi commit)
git revert <commit-hash>
# hoặc
git checkout <previous-tag> -- android/settings.gradle android/app/build.gradle
```

## Alternatives considered

**Option A — Upgrade lên Kotlin 2.x**
Rejected: risk cao hơn cần thiết. Cần AGP 8.3+, build config khác (không dùng
`composeOptions`), chưa tested trong repo này. Là một upgrade riêng, cần ADR riêng.

**Option B — Thay `kotlin-kapt` bằng KSP**
Considered nhưng out of scope. KSP là hướng đúng long-term cho Room.
Tuy nhiên migration kapt → KSP cần thay đổi Room dependency và test riêng.
Ghi vào backlog như một improvement task, không block fix hiện tại.

**Option C — Downgrade Gradle 8.3 → 7.x**
Rejected: AGP 8.1.0 yêu cầu Gradle 8.0+. Incompatible.

## Follow-up (không blocking)

- **KSP migration (low priority)**: Replace `kotlin-kapt` với `com.google.devtools.ksp`
  cho Room. Kapt đang deprecated bởi JetBrains, KSP là successor chính thức.
  Room đã hỗ trợ KSP từ 2.5.0. Cần ADR riêng trước khi thực hiện.
- **Kotlin 2.x upgrade (future)**: Khi sẵn sàng, cần AGP 8.3+, Gradle 8.5+,
  và xoá `composeOptions` block (Compose Compiler sẽ là Gradle plugin riêng).
