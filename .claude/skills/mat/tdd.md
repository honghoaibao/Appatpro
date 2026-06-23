# Skill: tdd
# Source: mattpocock/skills → skills/engineering/tdd/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when implementing new Android logic or fixing a bug.

## Red-green-refactor loop

```
1. Write a failing test (RED)
2. Write the minimum code to pass it (GREEN)
3. Refactor without breaking (REFACTOR)
```

## Test layers in Appatpro

| Layer | Framework | Location |
|-------|-----------|----------|
| Unit (pure Kotlin) | JUnit4 + MockK | `android/app/src/test/` |
| Room DAO | JUnit4 + Room in-memory | `android/app/src/test/` |
| Accessibility | Robolectric | `android/app/src/test/` |
| Integration | Espresso / UI Automator | `android/app/src/androidTest/` |

## Rules

- Test file mirrors source: `AutomationEngine.kt` → `AutomationEngineTest.kt`
- One test per behavior, not per method
- Test names: `given_<state>_when_<action>_then_<result>`
- Mock `TikTokAccessibilityService` via interface — do not instantiate the real service in unit tests
- Do not test private methods directly — test them through public behavior

## What to test first (priority)

1. `AutomationEngine` farm loop state transitions (start → account → stop)
2. `LocalRepository` DAO operations (insert, query, update account status)
3. `PopupHandler` detection logic (mock node trees)
4. `ScheduledFarmManager` schedule evaluation

## What not to test

- `FlutterBridge` method dispatch (too coupled to Flutter runtime)
- `OverlayFarmMonitor` (UI — use manual testing)
- Exact timing delays (flaky) — test that delay is called, not the duration
