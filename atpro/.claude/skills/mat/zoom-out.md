# Skill: zoom-out
# Source: mattpocock/skills → skills/engineering/zoom-out/SKILL.md
# Adapted for: Appatpro

**Trigger**: Use when exploring an unfamiliar part of the codebase, or before touching a module not recently worked on.

## What to do

1. Read `architecture.md` and `glossary.md` first
2. Identify the module's role in the system (data flow, dependencies)
3. Summarise: what does this module own, what does it NOT own, who calls it

## Zoom-out questions for Appatpro

- What package is this in? What is that package's responsibility?
- What does it import? Do those imports violate dependency rules?
- Is this Android-first or Flutter-legacy?
- Does it touch `FlutterBridge`? If yes, how?
- Does it write to Room? If yes, which entities?
- Does it run on the main thread? Should it?

## Output

A short summary (3–5 sentences) before proceeding with any change.
