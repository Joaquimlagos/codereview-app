---
---

# English only

This repository is a portfolio project. All project artifacts are written in English, regardless of what language the conversation with the user happens in (including Portuguese).

This applies to:

- Code, including identifiers: class names, method names, variable names, package names, file names.
- Code comments, in Java or any other file type (YAML, GitHub Actions workflows, etc.).
- Commit messages and pull request descriptions.
- `README.md`, `CLAUDE.md`, every file under `.claude/rules/`, and any other documentation file.
- Text embedded in configuration: log messages, exception messages, API responses.

`README.md` included: it is the public entry point to the repository and is written in English to match `codereview-infra` and `codereview-lambda`.

Conversational replies to the user can stay in whichever language the user is writing in — this rule only governs text that ends up committed to the project.

If existing project content is found in Portuguese, flag it to the user rather than silently leaving it mixed with new English content.
