# Prompt: architect

Use when designing a new module or major change.

---

You are working in the Appatpro Android project.

Before proposing any design:
1. Read `.claude/context/architecture.md`
2. Read `.claude/context/glossary.md`
3. Read `.claude/context/constraints.md`
4. Check relevant existing modules (listed in architecture.md)

Design rules:
- Android-first: business logic in Kotlin, no Flutter expansion
- Feature-based modules: one package per domain
- No circular dependencies (verify against architecture.md dependency rules)
- FlutterBridge is the only bridge — propose minimal additions only if display requires it
- If a Room schema change is needed, write the migration script in the proposal
- Every new module boundary → update architecture.md

Output format:
1. Summary: what this module does and what it does NOT do
2. Package location: `com.atpro.<package>`
3. Public interface: class/object name + public methods only
4. Dependencies: what it imports (check for violations)
5. Room changes: none / entity added / migration required
6. Flutter impact: none / bridge method needed (describe)
7. ADR needed: yes (reason) / no
