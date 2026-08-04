# Skill: to-issues
# Source: mattpocock/skills → skills/engineering/to-issues/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when a PRD or plan needs to be broken into actionable tasks.

## What it produces

A list of independently-completable tasks added to `.claude/tasks/backlog.md`.

## Vertical slice rule

Each task must be completable without depending on another task being in-progress.
If two tasks are tightly coupled, merge them or sequence them explicitly.

## Task format

```markdown
## TASK-<id>: <Short title>

**Module**: <Kotlin module or Flutter screen>
**Android-first**: yes / no (if no, justify)
**Estimated size**: XS / S / M / L
**Blocks**: <TASK-id if any>
**Description**: What exactly needs to change.
**Done when**: Concrete acceptance criteria.
```

## Sizing guide

| Size | Meaning |
|------|---------|
| XS | < 30 min, single file change |
| S | 30 min – 2h, few files, no schema change |
| M | 2–6h, new class or migration |
| L | > 6h, new module or major refactor — consider splitting |
