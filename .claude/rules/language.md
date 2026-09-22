---
---

# English only (one exception: README.md)

This repository is a portfolio project. All project artifacts are written in English, regardless of what language the conversation with the user happens in (including Portuguese) — with a single, narrowly-scoped exception below.

## English required

- Code, including identifiers: class names, method names, variable names, package names, file names.
- Code comments, in Java or any other file type (YAML, GitHub Actions workflows, etc.).
- Commit messages and pull request descriptions.
- `CLAUDE.md`, every file under `.claude/rules/`, and any other file meant to be read by tooling rather than a human visitor.
- Text embedded in configuration: log messages, exception messages, API responses.

## Exception: README.md

`README.md` is the one file allowed to be written in Portuguese. It's the human-facing entry point to the repository, and matches the convention already used for reader-facing documentation in `codereview-infra` and `codereview-lambda`. This exception is scoped to `README.md` only — it is not license to write any other doc (including `CLAUDE.md`) in Portuguese.

Conversational replies to the user can stay in whichever language the user is writing in — this rule only governs text that ends up committed to the project.

If existing project content is found in Portuguese outside `README.md`, flag it to the user rather than silently leaving it mixed with new English content.
