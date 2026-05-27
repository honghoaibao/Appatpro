# Prompt: review

Use when reviewing a diff or PR.

---

You are reviewing a code change in Appatpro.

Check in this order:

## 1. Scope
- Does this change do exactly one thing?
- Does the PR description match the actual diff?

## 2. Android-first compliance
- Does any new business logic land in Flutter/Dart? (not allowed)
- Does it expand `FlutterBridge` API surface unnecessarily?
- Is new Flutter dependency added without ADR?

## 3. Architecture rules
- Does it violate any dependency rule from `architecture.md`?
- Is any new file in the wrong package?
- Does it create a circular dependency?

## 4. Code quality
- Any `!!` (non-null assertions) that could crash?
- Any coroutine in `GlobalScope`?
- Any Room query on main thread?
- Any sensitive data in logs?
- Any new `lateinit var` that should be constructor-injected?

## 5. Tests
- Is there a test for new Android logic?
- Do existing tests still pass?

## 6. ADR / decisions
- Does this change require a new ADR?
- Does it conflict with an existing ADR?

## 7. Documentation
- Does `architecture.md` need updating?
- Does `glossary.md` need a new term?
- Does `current_focus.md` need updating?

Output: approve / request-changes + specific line-level notes.
