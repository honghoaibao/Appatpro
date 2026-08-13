# Skill: improve-codebase-architecture
# Source: mattpocock/skills → skills/engineering/improve-codebase-architecture/SKILL.md
# Adapted for: Appatpro

**Trigger**: Run once a week or after a sprint of heavy feature work.

## What it does

Finds places where the codebase is drifting from the architecture rules.

## Checklist

### Module boundaries
- [ ] Any file in the wrong package? (e.g. data class in `automation/`)
- [ ] Any import that violates dependency rules in `architecture.md`?
- [ ] Any Flutter screen importing Android-only APIs directly?

### File size
- [ ] Any file > 300 lines that should be split?
- [ ] `Entities.kt` still monolithic? (known issue — split into per-domain files)
- [ ] `Daos.kt` still monolithic? (same)

### Flutter surface
- [ ] Did any Flutter screen grow business logic?
- [ ] Did `FlutterBridge` gain undocumented MethodChannel methods?
- [ ] Were any new Flutter dependencies added without ADR?

### Android patterns
- [ ] Any new `lateinit var` that could be constructor-injected?
- [ ] Any coroutine launched in `GlobalScope`?
- [ ] Any Room query running on main thread?

### Tests
- [ ] Any new module without a test file?
- [ ] Any `TODO: add test` comment > 2 weeks old?

## Output

List findings as tasks in `.claude/tasks/technical_debt.md`.
