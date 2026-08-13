# Skill: grill-with-docs
# Source: mattpocock/skills → skills/engineering/grill-with-docs/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when planning any new feature, refactor, or architectural change.

## What it does

Interviews you about every dimension of the plan until all decisions are resolved. Challenges your language against `glossary.md`. Surfaces contradictions with the existing codebase. Updates `glossary.md` and creates ADRs inline as decisions crystallise.

## How to invoke

Say: "grill me on [plan]" before implementing anything non-trivial.

## Session protocol

1. Claude explores the relevant Android modules first
2. Asks one question at a time, provides a recommended answer
3. Challenges any term that conflicts with `glossary.md`
4. When a term is resolved → updates `glossary.md` immediately
5. Offers ADR only when: hard to reverse + surprising without context + real trade-off
6. Does NOT ask about Flutter-only patterns unless the change touches the bridge

## Appatpro-specific additions

- Always verify: does the plan stay in Kotlin? If it requires Flutter expansion, stop and justify
- Always check: does it touch `FlutterBridge`? If yes, the scope must be strictly minimal
- Always check: does it require a new Room schema migration? If yes, migration script is required
