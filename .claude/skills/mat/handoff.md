# Skill: handoff
# Source: mattpocock/skills → skills/productivity/handoff/SKILL.md
# Adapted for: Appatpro

**Trigger**: End of a session, or before switching context to a different part of the codebase.

## What it produces

A handoff note in `.claude/revisions/` (follows revision policy: keep last 10).

## Handoff template

```markdown
# Handoff: <date> — <session topic>

## What was done
- ...

## What was NOT done (left in progress)
- ...

## Files changed
- `path/to/file.kt` — reason

## Next step
One sentence: what should happen next.

## Gotchas / blockers
Things that will bite the next session if not known.

## Relevant context
- ADR references (if any)
- Module: `automation` / `db` / etc.
```

## Revision numbering

Files named: `revision-<YYYY-MM-DD>-<topic>.md`
Keep only the last 10. Delete oldest when adding the 11th.
