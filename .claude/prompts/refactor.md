# Prompt: refactor

Use when improving existing code without changing behavior.

---

You are refactoring code in the Appatpro Android project.

Before touching any file:
1. Run zoom-out on the target module (see `.claude/skills/mat/zoom-out.md`)
2. Confirm: what behavior must be preserved exactly?
3. Confirm: are there tests? If not, write characterisation tests first

Refactor rules:
- Smallest possible change that achieves the goal
- One concern per commit (don't combine refactor + feature)
- Do not change public interfaces unless explicitly scoped
- Do not introduce new dependencies
- If splitting a monolithic file (`Entities.kt`, `Daos.kt`): split by domain, one file per entity group
- If extracting a class: new class in same package unless it clearly belongs elsewhere

After refactor:
- Run `./gradlew test` — all tests must pass
- Update `.claude/context/architecture.md` if module boundaries changed
- Add revision note in `.claude/revisions/`
