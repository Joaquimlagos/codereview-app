---
---

# Commit conventions for codereview-app

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), written in English regardless of the language used in conversation with the user.

## Format

```
<type>(<optional scope>): <description>

<optional body>
```

## Types

- `feat` — a new feature (e.g. a new endpoint, a new module).
- `fix` — a bug fix.
- `test` — adding or updating tests, no production code change.
- `refactor` — a code change that neither fixes a bug nor adds a feature.
- `chore` — tooling, build config, CI, dependency bumps.
- `docs` — documentation only (`README.md`, `CLAUDE.md`, rules).

## Rules

- Description in the imperative mood ("add", not "added"/"adds"), lowercase, no trailing period.
- Keep the subject line under ~72 characters; use the body for the "why" when it isn't obvious from the diff alone.
- Scope, when used, names the module (`auth`, `tasks`, `ci`) — e.g. `feat(tasks): add PUT /tasks/{id} endpoint`.
- This convention exists so a changelog can eventually be generated automatically from history — don't deviate without updating this file.
