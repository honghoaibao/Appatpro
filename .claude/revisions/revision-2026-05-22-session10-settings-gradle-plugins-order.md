# Revision: 2026-05-22 — Session 10: Fix settings.gradle plugins {} block order

## What changed

Reordered blocks in `android/settings.gradle`:

**Before (broken):**
```
pluginManagement {}          ← L1
dependencyResolutionManagement {}  ← L9  ❌ giữa pluginManagement và plugins
plugins {}                   ← L17
rootProject.name / include
```

**After (correct):**
```
pluginManagement {}          ← L1  ✅
plugins {}                   ← L9  ✅ ngay sau pluginManagement
dependencyResolutionManagement {}  ← L16  ✅ sau plugins
rootProject.name / include   ← L24–25  ✅
```

## Why it changed

Gradle 7+ (và Gradle 8.x) enforces strict block ordering in `settings.gradle`.
The rule: only `buildscript {}` and `pluginManagement {}` are allowed before `plugins {}`.
`dependencyResolutionManagement {}` is NOT in that whitelist — placing it before `plugins {}`
causes a hard build failure:

```
> Build file 'android/settings.gradle' line X: Only 'buildscript {}' and 'pluginManagement {}'
  blocks are allowed before 'plugins {}' in settings files.
```

## Files changed

| File | Change |
|------|--------|
| `android/settings.gradle` | Moved `plugins {}` from L17 to L9 (before `dependencyResolutionManagement {}`) |
| `android/settings.gradle.bak` | Snapshot của file gốc trước khi sửa — xóa sau khi CI confirm xanh |

## Rollback note

```bash
cp android/settings.gradle.bak android/settings.gradle
```

Hoặc `git checkout android/settings.gradle` nếu đã commit file gốc trước đó.

## Risks / follow-ups

- Không có rủi ro chức năng: đây là reorder thuần túy, không thay đổi nội dung bất kỳ block nào.
- `app/build.gradle` dùng đúng 4 plugin IDs đã khai báo trong `settings.gradle` — confirmed.
- `android/build.gradle` (root) không khai báo plugin, không bị ảnh hưởng.
- Xóa `android/settings.gradle.bak` sau khi CI confirm pass.

## ADR reference

ADR-0006 (xem `.claude/decisions/ADR-0006-settings-gradle-plugins-block-order.md`)
