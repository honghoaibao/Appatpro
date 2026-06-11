# Skill: diagnose
# Source: mattpocock/skills → skills/engineering/diagnose/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when a bug or regression is reported or observed.

## Loop

```
reproduce → minimise → hypothesise → instrument → fix → regression-test
```

## Reproduce

1. Identify the exact device + Android version where the bug occurs
2. Identify the TikTok version (TikTok updates frequently and breaks selectors)
3. Reproduce with logging enabled (`adb logcat | grep AtPro`)

## Minimise

- Is this an AccessibilityService issue (node not found), AutomationEngine issue (wrong state), or DB issue?
- Does it occur on all accounts or a specific account status (`active` / `checkpoint`)?
- Does it occur on first farm run or after multiple runs (memory/state leak)?

## Hypothesise

- List top 3 hypotheses, rank by probability
- Check against `NodeTraverser` and `PopupHandler` — most TikTok-related bugs start here

## Instrument

- Add targeted `Log.d(TAG, ...)` around the suspected path
- Use `adb logcat -s AutomationEngine:D NodeTraverser:D PopupHandler:D`
- For DB issues: print Room DAO query result before and after the suspected operation

## Fix

- Smallest possible change
- Do not fix unrelated issues in the same commit
- Add or update a regression test

## Regression test

- For AccessibilityService bugs: add a Robolectric test or document a manual regression checklist
- For DB bugs: add a Room DAO unit test
- For AutomationEngine bugs: mock the service, add a coroutine test
