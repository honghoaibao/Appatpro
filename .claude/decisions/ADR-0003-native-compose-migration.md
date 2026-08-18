# ADR-0003: Native Compose UI Migration Strategy

**Date**: 2026-05-22
**Status**: ACCEPTED
**Deciders**: AT PRO project

---

## Context

Appatpro is a Flutter + native Android hybrid. The Flutter UI layer carries technical debt:
- Heavy dependency on Riverpod providers that duplicate Room data
- Dart/native bridge latency for live farm stats
- Difficulty testing Flutter Widgets against native automation state
- Flutter surface makes it harder to reduce APK size long-term

The goal (from CLAUDE.md) is to migrate toward Kotlin-first, native-Android-first architecture.
Flutter must remain functional throughout migration.

---

## Decision

Migrate UI screen-by-screen behind **compile-time feature flags** (`FeatureFlags.kt`).

Rules:
1. **Flutter screen is never deleted** until native replacement passes QA on 3 OEM targets (MIUI 14, ColorOS 14, OneUI 6)
2. **Feature flag defaults to `false`** — zero impact on existing users until explicitly enabled
3. **One screen = one PR** — no monolithic rewrites
4. **Native screen must match Flutter design** (colors, layout, behavior) — no redesign without explicit decision
5. **ViewModel must observe Room Flows directly** — no Dart bridge for data that lives natively
6. **Navigation bridges via FlutterBridge.sendEvent("navigate", ...)** for screens not yet migrated

Migration order (priority):
```
DashboardScreen  (TASK-007) ✅ — highest value, most live data
AccountsScreen   (TASK-009) ✅ — pure Room data, straightforward
LogsScreen       (TASK-010) ✅ — simplest: one Flow, no mutations
StatsScreen      (TASK-011) — blocked: needs SessionDao aggregation UI
ConfigScreen     (TASK-012) — blocked: complex form, low priority
ScheduleScreen   (TASK-013) — blocked: depends on ScheduledFarmManager
```

---

## Architecture

```
DashboardActivity (Compose)
  └── DashboardScreen
        ├── ShortcutRow
        │     ├── → AccountsActivity (FeatureFlags.NATIVE_ACCOUNTS)
        │     ├── → FlutterBridge navigate /stats (not yet migrated)
        │     └── → LogsActivity (FeatureFlags.NATIVE_LOGS)
        └── [farming state]
              └── LiveFarmStats ← AutomationEngine.liveFarmStats (StateFlow)

AccountsActivity (Compose)
  └── AccountsScreen
        └── AccountDao.observeAll() Flow → StateFlow<List<AccountEntity>>

LogsActivity (Compose)
  └── LogsScreen
        └── LogDao.observeRecent(500) Flow + level filter
```

---

## Consequences

**Positive:**
- Zero regression risk (feature flags)
- Incrementally verifiable
- Native screens have direct Room access — no bridge latency
- ViewModels are unit-testable
- Cleaner separation of concerns

**Negative / risks:**
- Two UIs maintained in parallel during migration (added maintenance burden)
- `LocalContext.current` usage in Compose creates implicit coupling to Activity lifecycle
- `ShortcutRow` sends FlutterBridge events for unmigrated screens — depends on Flutter router still running
- Compose BOM + Kotlin version must stay in sync (compiler extension version)

---

## Rollback

Set all `FeatureFlags.*` to `false`. All native Activities remain registered but unreachable.
Delete native Activities + Screens only after explicit decision (separate ADR).

---

## Review trigger

Revisit this ADR when:
- MIUI 14 + ColorOS 14 QA passes for TASK-007
- All 3 shortcut screens are migrated (TASK-009, 010 done + QA)
- Flutter DashboardScreen can safely be marked `@Deprecated`
