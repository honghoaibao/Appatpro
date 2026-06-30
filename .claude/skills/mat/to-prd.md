# Skill: to-prd
# Source: mattpocock/skills → skills/engineering/to-prd/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when a feature request or idea needs to become executable work.

## What it produces

A PRD (Product Requirements Document) as a markdown file in `.claude/tasks/`.

## PRD template (Appatpro)

```markdown
# PRD: <Feature Name>

## Summary
One paragraph. What is being built and why.

## Problem
What breaks or is missing today.

## Proposed solution
Concrete description. Reference modules from architecture.md.

## Android modules touched
List each Kotlin module that changes.

## Flutter impact
[ ] None
[ ] Bridge method added (document in FlutterBridge.kt)
[ ] Screen change (display only)

## Acceptance criteria
- [ ] ...
- [ ] ...

## Out of scope
Explicitly list what this does NOT include.

## ADR needed?
[ ] Yes — reason: ...
[ ] No
```

## Rules for Appatpro PRDs

- Flutter expansion must be justified in the PRD — "Flutter needed because: ..."
- If a new Room entity or migration is required, call it out explicitly
- If it touches `TikTokAccessibilityService`, note TikTok version sensitivity
