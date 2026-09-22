# codereview-app

Simple Task Manager REST API (Java 21 + Spring Boot) used as a "guinea pig" to generate pull requests of varying complexity and exercise the AI PR review pipeline. Portfolio project split into 3 independent repositories:

- **`codereview-app`** (this repo) — fires the PR review event via GitHub Actions.
- **`codereview-infra`** — EventBridge, Step Functions and IAM.
- **`codereview-lambda`** — the Lambda functions: routing to the LLM, RAG context, posting the review comment back to the PR.

**Ownership boundary — read this before touching `pr-checks.yml`'s `trigger-review` job**: this repository has no knowledge of how the review pipeline works internally. Its only job is to upload the PR diff to S3 and publish a `PRReviewRequested` event to EventBridge — it never calls an LLM, never posts a PR comment, and never reads pipeline internals. All of that logic lives entirely in `codereview-infra` (routing/orchestration) and `codereview-lambda` (the actual analysis). If a change seems to require adding pipeline logic here, stop and reconsider — it almost certainly belongs in one of those two repos.

## Stack

- Java 21 (LTS)
- Spring Boot 3.5.x via `spring-boot-starter-parent`
- Maven, single module (no multi-module build), `pom.xml` at the repo root
- `jjwt` for JWT generation/validation (`auth` module only)

## Structure

```
src/main/java/com/codereview/app/
├── CodereviewAppApplication.java
├── auth/            # JWT login — deliberately simple, meant to seed future "hard" complexity PRs
│   ├── AuthController.java
│   ├── JwtValidator.java
│   ├── InMemoryUsers.java
│   ├── LoginRequest.java
│   └── LoginResponse.java
└── tasks/           # Task CRUD, in-memory storage — meant to seed future "medium" complexity PRs
    ├── Task.java
    ├── TaskController.java
    └── TaskService.java
```

`auth` and `tasks` are intentionally independent at this stage — task CRUD has no real authentication wired in yet.

## Running tests

```bash
mvn test
```

## Running the app locally

```bash
mvn spring-boot:run
```

Starts on port 8080 (see `src/main/resources/application.yml`). No external database or service is required — task storage is in-memory and auth uses fixed in-memory credentials.

## Relation to codereview-infra / codereview-lambda

This repo only *triggers* the pipeline; it has no dependency on, and no knowledge of, the other two repos' implementation:

`.github/workflows/pr-checks.yml` is a single workflow, triggered on `pull_request` (`opened`, `synchronize`, `reopened`) and on `push` to `main`, with two independent jobs (no `needs` between them):

- **`test`** — standard build/test gate (`mvn test`), blocking, no `if:` condition — it runs on both triggers, so `main` stays green on direct pushes too. Point branch protection's required check at this exact job name.
- **`trigger-review`** — `if: github.event_name == 'pull_request'`, so it's skipped (not failed) on a direct push to `main` — there's no PR to diff against there. Non-blocking (`continue-on-error: true`, and its own `permissions: id-token: write` scoped to just this job), that computes the PR diff against the base branch, uploads it to the `<project_name>-pr-diffs` S3 bucket (claim-check pattern, matching `codereview-infra`'s `s3.tf`), and publishes a `PRReviewRequested` event on the `<project_name>-bus` EventBridge bus (matching `codereview-infra`'s `eventbridge.tf` event pattern: `source = "codereview.app"`, `detail-type = "PRReviewRequested"`). The event `Detail` carries `prNumber`, `repository`, `sha`, `diffBucket`, `diffKey`, plus lightweight diff stats (`filesChanged`, `linesAdded`, `linesRemoved`, `paths`) derived from the same diff via `git apply --numstat` — no second `git diff` against the base branch. Those stats exist so `codereview-lambda`'s `route-model` can pick a complexity tier without fetching the full diff from S3 in the common case. `codereview-infra`'s Step Functions state machine picks up execution from there.
- Authentication to AWS is via GitHub Actions OIDC (`aws-actions/configure-aws-credentials`), assuming a role provisioned by `codereview-infra`'s `oidc.tf` — no static AWS credentials are ever stored in this repo.

`pr-checks.yml` currently references three GitHub Actions secrets/variables that don't have real values yet, because `codereview-infra` hasn't been deployed: `AWS_ROLE_ARN` (secret), `DIFF_BUCKET_NAME` and `EVENT_BUS_NAME` (variables). See the comment block at the top of that workflow file and `README.md` for what to fill in after the first `codereview-infra` deploy.

## Code style

See `.claude/rules/`:
- `language.md` — English-only codebase (one exception: `README.md`, which stays in Portuguese).
- `java-conventions.md` — Java 21 / Spring Boot conventions (records, `Optional`, constructor injection, thin controllers).
- `commits.md` — Conventional Commits, in English.
