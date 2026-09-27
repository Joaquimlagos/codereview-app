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

scripts/
└── build_index.py   # Builds the RAG embedding index — CI tooling, not part of the app
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
- **`trigger-review`** — `if: github.event_name == 'pull_request'`, so it's skipped (not failed) on a direct push to `main` — there's no PR to diff against there. Non-blocking (`continue-on-error: true`, and its own `permissions: id-token: write` scoped to just this job), that computes the PR diff against the base branch, uploads it to the shared artifacts S3 bucket under `prs/` (claim-check pattern, matching `codereview-infra`'s `s3.tf`), and publishes a `PRReviewRequested` event on the `<project_name>-bus` EventBridge bus (matching `codereview-infra`'s `eventbridge.tf` event pattern: `source = "codereview.app"`, `detail-type = "PRReviewRequested"`). The event `Detail` carries `prNumber`, `repository`, `sha`, `diffBucket`, `diffKey`, plus lightweight diff stats (`filesChanged`, `linesAdded`, `linesRemoved`, `paths`) derived from the same diff via `git apply --numstat` — no second `git diff` against the base branch. Those stats exist so `codereview-lambda`'s `route-model` can pick a complexity tier without fetching the full diff from S3 in the common case. `codereview-infra`'s Step Functions state machine picks up execution from there.
- Authentication to AWS is via GitHub Actions OIDC (`aws-actions/configure-aws-credentials`), assuming a role provisioned by `codereview-infra`'s `oidc.tf` — no static AWS credentials are ever stored in this repo.

`.github/workflows/index-codebase.yml` rebuilds the RAG embedding index on every push to `develop`, running `scripts/build_index.py` and uploading the result to `index/develop/index.json` in the same artifacts bucket. It indexes `develop`, not `main`, because the AI review runs against PRs targeting `develop` — indexing `main` would leave the RAG context stale relative to the code actually under review.

### `index.json` — shared contract with codereview-lambda

`codereview-lambda`'s `RetrieveContext` reads this file, so the schema is a cross-repo contract: a format change here breaks the consumer there. Bump `version` and align both repos when changing it.

```json
{
  "version": 1,
  "branch": "develop",
  "commit": "<full 40-char sha>",
  "generatedAt": "<ISO 8601 UTC>",
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "chunks": [{ "path": "src/...", "text": "<full file contents>", "vector": [768 floats] }]
}
```

Only `vector` comes from the Gemini API (`outputDimensionality=768`, `taskType=RETRIEVAL_DOCUMENT`). Everything else is assembled by the script itself: `path`/`text` from the filesystem, `commit`/`branch` from the Actions context, `model`/`dimensions` from the parameters the script chose for the call.

**One chunk per file** — `text` is the entire file, never split. A deliberate first-version simplification, viable only because this project is small. Once files start exceeding the embedding model's token limit, or retrieval accuracy degrades from dilution, the next step is per-class/per-method chunking, which requires a `version` bump and a matching change in `codereview-lambda`. Indexed: everything under `src/`, plus `README.md` and `pom.xml`; `target/`, `.git/` and anything that doesn't decode as UTF-8 are skipped.

`scripts/build_index.py` uses only the Python standard library (`urllib.request` for the HTTP calls) — nothing to `pip install` on the runner. Keep it that way; a dependency here would mean a install step in the workflow for a script that does one HTTP POST per file.

`codereview-infra` is deployed, so the AWS resources both workflows target already exist. What's still missing is registering their values in this repo's GitHub settings: `AWS_ROLE_ARN` and `GEMINI_API_KEY` (secrets), `ARTIFACTS_BUCKET_NAME` and `EVENT_BUS_NAME` (variables) — until then both workflows fail at the AWS authentication step. `GEMINI_API_KEY` is an Actions secret, distinct from the Secrets Manager secret the Lambdas read at runtime — this repo never reads anything from Secrets Manager. See the comment block at the top of each workflow file and `README.md` for the exact values.

One bucket (`codereview-artifacts`), split by prefix: PR diffs under `prs/`, the embedding index under `index/`.

## Code style

See `.claude/rules/`:
- `language.md` — English-only codebase, `README.md` included.
- `java-conventions.md` — Java 21 / Spring Boot conventions (records, `Optional`, constructor injection, thin controllers).
- `commits.md` — Conventional Commits, in English.
