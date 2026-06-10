# CLAUDE.md

## Project identity

Project: Appatpro

Appatpro is an Android-first application. The long-term target is a mostly pure Android codebase with Kotlin as the primary implementation language.

Flutter may remain only as a minimal legacy or bridge layer if needed for migration or for small UI areas already in place. Do not expand Flutter unless there is a clear, justified reason.

## Primary goals

1. Maintainability
2. Stability
3. Modularity
4. Performance
5. Clean architecture
6. Safe migrations
7. Long-term simplicity

## Technology direction

Preferred:
- Kotlin
- Native Android APIs
- Modular architecture
- Clear domain boundaries
- Room
- Coroutines / Flow
- WorkManager or foreground services where appropriate
- Lean native UI layers

Use Flutter only when:
- it is already part of the existing surface
- migration would be too risky to do in one step
- there is a narrow bridge/UI use case

Do not introduce new Flutter-heavy patterns for new work.

## Repository process

Use the `.claude/` workspace as the source of project rules, context, skills, revisions, and decisions.

Relevant skill source:
- mattpocock/skills

Use the Matt Pocock-style workflow when appropriate:
- `setup-matt-pocock-skills` for repo setup and process scaffolding
- `grill-with-docs` for stress-testing plans and aligning language
- `diagnose` for bugs and regressions
- `tdd` for vertical-slice implementation
- `to-prd` and `to-issues` for turning ideas into executable work
- `triage` for issue sorting
- `improve-codebase-architecture` for architecture cleanup
- `zoom-out` for system-level review
- `handoff` for continuity across sessions
- `prototype` for throwaway experiments

## Architecture rules

- Prefer feature-based modularization.
- Keep UI, domain, data, automation, and bridge code separated.
- Avoid circular dependencies.
- Keep public interfaces small.
- Do not create monolithic files.
- Do not mix native Android logic and migration scaffolding without a clear boundary.
- Preserve module ownership and responsibility.

## Android rules

- Kotlin-first for all new Android work.
- Native code should remain easy to test.
- Keep background work explicit and constrained.
- Accessibility-related logic must be isolated and documented.
- Realtime/network work must handle failure and reconnect safely.

## Flutter rules

- Treat Flutter as secondary.
- Do not add new Flutter dependencies unless absolutely necessary.
- Do not move core business logic into Flutter.
- Keep Flutter screens thin if they still exist.
- Prefer gradual reduction of Flutter surface area.

## Documentation rules

Keep these docs up to date when architecture changes:
- `.claude/context/project_overview.md`
- `.claude/context/architecture.md`
- `.claude/context/current_focus.md`
- `.claude/context/glossary.md`
- `.claude/decisions/`
- `.claude/revisions/`

Use ADR-style notes for major decisions.

After every major architectural or behavioral change:
- update relevant context files
- create or update ADR notes
- store revision summaries
- keep project memory synchronized

If context becomes outdated, refresh it before continuing.

## Revision policy

Store the last 10 major revisions only.

Each revision should include:
- what changed
- why it changed
- impacted files
- rollback note
- risks or follow-ups

## Working style

When planning changes:
1. Inspect the repo
2. Align to the existing language
3. Confirm the smallest safe change
4. Implement incrementally
5. Validate behavior
6. Document the decision
7. Keep the diff clean

## Quality bar

Always prefer:
- clarity over cleverness
- explicit behavior over hidden magic
- narrow interfaces over broad ones
- small safe refactors over risky rewrites

Avoid:
- speculative rewrites
- unnecessary abstraction
- framework churn
- architecture drift

## Current direction

Appatpro is moving toward a mostly pure Android architecture.

That means:
- Kotlin is the default implementation path
- Android native modules own core behavior
- Flutter stays minimal and transitional
- future work should reduce coupling and simplify the stack

---

## External Skills

The following skill sets are included under `.claude/skills/` and should be
consulted when relevant tasks arise. Read the corresponding SKILL.md before
starting any related work.

### multica-ai/andrej-karpathy-skills (MIT)
Source: https://github.com/multica-ai/andrej-karpathy-skills

- **karpathy-guidelines** — Behavioral guidelines to reduce LLM coding mistakes:
  Think Before Coding, Simplicity First, Surgical Changes, Goal-Driven Execution.
  Apply always when writing or reviewing code.

### android/skills — Official Google Android Skills (Apache-2.0)
Source: https://github.com/android/skills

- **agp-9-upgrade** — Migrate Android Gradle Plugin to version 9.
- **migrate-xml-to-compose** — 10-step workflow to migrate XML layouts to Jetpack Compose.
- **navigation-3** — Install/migrate to Jetpack Navigation 3; recipes for deep links,
  scenes, multiple backstacks, conditional navigation, Hilt integration.
- **r8-analyzer** — Analyze and optimize R8/ProGuard keep rules to reduce APK size.
- **play-billing-upgrade** — Upgrade Google Play Billing Library to the latest stable version.
- **edge-to-edge** — Implement edge-to-edge support in Jetpack Compose; fix insets,
  IME handling, system bar legibility.
