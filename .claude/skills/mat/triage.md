# Skill: triage
# Source: mattpocock/skills → skills/engineering/triage/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when reviewing reported bugs or feature requests.

## Triage states

```
reported → confirmed → scoped → scheduled | rejected
```

## Triage questions

1. **Reproducible?** On which device/Android/TikTok version?
2. **Severity?** Crash / data loss / farming stops / cosmetic
3. **Android-first or Flutter-side?** Check the layer before assigning
4. **Scope?** Which module is affected?
5. **Workaround?** Is there a temporary fix?
6. **ADR conflict?** Does the fix require violating an existing ADR?

## Triage output

Add to `.claude/tasks/backlog.md` with:
- Severity: CRITICAL / HIGH / MEDIUM / LOW
- Layer: android-core / flutter-ui / bridge / build
- Module: `automation` / `accessibility` / `db` / etc.
- Status: confirmed / scoped / needs-repro
