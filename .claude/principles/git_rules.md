# Git Rules

## Branch naming

```
feature/<short-description>
fix/<short-description>
refactor/<short-description>
chore/<short-description>
```

## Commit messages

```
<type>(<scope>): <short description>

type: feat | fix | refactor | chore | docs | test
scope: automation | accessibility | db | bridge | flutter | scheduler | network | ci

Examples:
feat(automation): add rest period between accounts
fix(accessibility): handle null root node on TikTok cold start
refactor(db): split Entities.kt into per-domain files
chore(ci): pin Gradle wrapper to 8.4
```

## PR rules

- One concern per PR
- Include test for any new Android logic
- Reference ADR number if the PR implements an architectural decision
- Do not merge if `./gradlew test` fails

## Never commit

- `keystore.jks`, `key.properties`
- `local.properties`
- Any file containing credentials or tokens
- Compiled artifacts (`build/`, `*.apk`, `*.aab`)

## CI

- Build triggered on push to `main` and on PR
- See `.github/workflows/build.yml` for current pipeline
