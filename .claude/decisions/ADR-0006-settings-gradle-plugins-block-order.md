# ADR-0006: settings.gradle — plugins {} phải đứng ngay sau pluginManagement {}

**Date**: 2026-05-22
**Status**: Accepted

## Context

Gradle 7+ (Gradle 8.x mà CI dùng với AGP 8.1.0) yêu cầu thứ tự block nghiêm ngặt
trong `settings.gradle`. Cụ thể:

- Chỉ `buildscript {}` và `pluginManagement {}` được phép đứng **trước** `plugins {}`.
- Tất cả blocks khác (`dependencyResolutionManagement {}`, statements như `rootProject.name`,
  `include()`) phải đứng **sau** `plugins {}`.

File `android/settings.gradle` có `dependencyResolutionManagement {}` nằm giữa
`pluginManagement {}` và `plugins {}`, dẫn đến build failure với error:

```
Only 'buildscript {}' and 'pluginManagement {}' blocks are allowed before 'plugins {}' in settings files.
```

## Decision

Cố định thứ tự block trong `android/settings.gradle` như sau:

```groovy
// 1. pluginManagement — PHẢI ĐẦU TIÊN
pluginManagement { ... }

// 2. plugins — NGAY SAU pluginManagement
plugins { ... }

// 3. dependencyResolutionManagement — sau plugins
dependencyResolutionManagement { ... }

// 4. rootProject + include — cuối
rootProject.name = "..."
include ":app"
```

## Rationale

- Đây là quy tắc bắt buộc của Gradle, không phải style preference.
- Fix là reorder thuần túy — không thay đổi nội dung, không có rủi ro chức năng.
- Lý do `dependencyResolutionManagement` bị để sai chỗ: có thể từ template generation
  cũ (AGP < 7) hoặc copy từ project khác có Gradle cũ hơn.

## Consequences

- **Dễ hơn**: CI/CD build không còn bị block bởi lỗi parsing settings.gradle.
- **Cần nhớ**: Khi thêm block mới vào `settings.gradle`, phải đặt sau `plugins {}`.
- **Không ảnh hưởng**: Plugin versions, repository config, project structure — tất cả giữ nguyên.

## Alternatives considered

- **Option A — Xóa plugins {} khỏi settings.gradle, giữ trong app/build.gradle**:
  Rejected. Plugin version pinning ở settings-level là best practice cho AGP 8.x,
  giúp tất cả modules dùng cùng version.

- **Option B — Downgrade Gradle để bỏ qua rule này**:
  Rejected. Downgrade Gradle để workaround một rule hợp lệ là technical debt.

## Rollback

```bash
cp android/settings.gradle.bak android/settings.gradle
```

Hoặc: `git revert <commit>` nếu đã committed.
