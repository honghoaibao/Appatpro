# Skill: prototype
# Source: mattpocock/skills → skills/engineering/prototype/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when the correct design is uncertain and throwaway exploration is faster than commitment.

## Rules for Appatpro prototypes

- Prototype lives in a `feature/prototype-<name>` branch — never merged to main
- For Kotlin logic: write a standalone `main()` function or a JUnit test that exercises the idea
- For UI: build in Flutter (acceptable exception) or use a Compose preview — clearly mark as throwaway
- Do not add prototype code to real modules
- Do not create new Room migrations for prototype work — use in-memory Room

## After prototyping

1. If the design is validated → open a proper PRD via `/to-prd`
2. If rejected → delete the branch, document what was learned in a handoff note
3. Never leave prototype branches open > 2 weeks
