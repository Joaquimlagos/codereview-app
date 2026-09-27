# codereview-app

GitHub Actions workflows (CI + tests) authenticating via OIDC that trigger PR review events through EventBridge.

## Trigger flow

Everything below comes from [`.github/workflows/pr-checks.yml`](.github/workflows/pr-checks.yml) — the two jobs are independent (no `needs`), so they run in parallel.

```text
pull_request: opened │ synchronize │ reopened
        │
        ▼
.github/workflows/pr-checks.yml
        │
        ├── job: test ──────────────── blocking gate, runs on every trigger
        │     checkout → setup-java 21 (temurin) → mvn --batch-mode test
        │
        └── job: trigger-review ────── non-blocking, pull_request only
              │
              ├─ checkout PR head (fetch-depth: 0)
              │
              ├─ git diff origin/<base>...HEAD → pr.diff
              │  git apply --numstat pr.diff   → filesChanged / linesAdded
              │                                  linesRemoved / paths
              │
              ├─ aws-actions/configure-aws-credentials  ← OIDC, no static keys
              │  (job declares permissions: id-token: write)
              │
              ├─ aws s3 cp pr.diff
              │     s3://<artifacts-bucket>/prs/{pr}/{sha}.diff  ← claim check
              │
              └─ aws events put-events
                    Source     = codereview.app
                    DetailType = PRReviewRequested
                    Detail     = PR metadata + S3 key, never the diff itself
                          │
                          ▼
                  EventBridge custom bus
                          │
                          ▼
   codereview-infra   rule → Step Functions state machine
   codereview-lambda  RouteModel → RetrieveContext → InvokeLLM → PostComment
                      └── review posted back onto the pull request
```

The diff travels through S3 rather than inside the event (claim-check pattern): EventBridge and Step Functions only carry PR metadata plus the object key. The lightweight stats ride along in the event so `codereview-lambda`'s `RouteModel` can pick a complexity tier without fetching the full diff from S3 in the common case.

## Part of a 3-repo pipeline

| Repository | Role |
| --- | --- |
| [`codereview-app`](https://github.com/Joaquimlagos/codereview-app) **(this repo)** | **Entry point.** Computes the PR diff in CI, authenticates to AWS via OIDC, uploads the diff to S3, and publishes the `PRReviewRequested` event that starts the pipeline. |
| [`codereview-infra`](https://github.com/Joaquimlagos/codereview-infra) | Terraform for the AWS glue: EventBridge bus and rule, Step Functions state machine, the shared artifacts bucket, and the OIDC role this repo assumes. |
| [`codereview-lambda`](https://github.com/Joaquimlagos/codereview-lambda) | The Lambda functions behind each Step Functions state: complexity routing, RAG context retrieval, the LLM call, and posting the review back to the PR. |

This repository is the entry point and knows nothing about how the review itself works. It never calls an LLM, never posts a comment, and never reads pipeline internals — it publishes one event and stops. Everything downstream lives in the other two repositories.

## Configuring OIDC authentication

Both workflows reach AWS with short-lived credentials minted by GitHub's OIDC provider; no AWS access keys are stored in this repository. The AWS side is provisioned by `codereview-infra` (`oidc.tf`), which creates:

1. **An OIDC identity provider** for `token.actions.githubusercontent.com` with audience `sts.amazonaws.com`. Its thumbprint is read from GitHub's live TLS certificate rather than hardcoded, so it does not go stale when GitHub rotates certificates.

2. **An IAM role whose trust policy is pinned to this repository.** GitHub sends the `sub` claim with the *immutable numeric IDs* of the owner account and the repository, not only their names:

   ```
   repo:<owner>@<owner-id>/<repo>@<repo-id>:ref:refs/heads/<branch>
   ```

   The trust policy matches that exact shape — `repo:<owner>@<owner-id>/<repo>@<repo-id>:*` — with no wildcard on the owner or repo portion. Because names can change hands, pinning the IDs means a repository that was renamed, or deleted and re-created under the same name by someone else, no longer matches. A condition written against the name-only form (`repo:<owner>/<repo>:*`) never matches this claim at all, and `AssumeRoleWithWebIdentity` is denied.

3. **A least-privilege policy** on that role: `events:PutEvents` on the pipeline's custom event bus, and `s3:PutObject` restricted to the `prs/` and `index/` prefixes of the artifacts bucket — not the bucket as a whole, since the same bucket also holds Terraform state.

On the GitHub side, every job that talks to AWS declares the token permission explicitly:

```yaml
permissions:
  id-token: write
  contents: read
```

Without `id-token: write`, GitHub never issues the OIDC token and the step fails even when the AWS role is configured correctly. The role ARN is read from the `AWS_ROLE_ARN` secret (see [Pending configuration](#pending-configuration)); no ARN, account ID, or bucket name is hardcoded in this repository.

## Embedding index workflow (RAG)

[`.github/workflows/index-codebase.yml`](.github/workflows/index-codebase.yml) runs on every `push` to **`develop`** and rebuilds, from scratch, the embedding index that feeds the pipeline's RAG step:

```text
push to develop
        │
        ▼
checkout → setup-python 3.12 → OIDC: configure-aws-credentials
        │
        ├─ python scripts/build_index.py     → index.json
        │    one Gemini embedding call per file (gemini-embedding-001,
        │    outputDimensionality=768, taskType=RETRIEVAL_DOCUMENT)
        │
        └─ aws s3 cp index.json
              s3://<artifacts-bucket>/index/develop/index.json
```

`codereview-lambda`'s `RetrieveContext` reads that object to rank the project's files against the diff under review.

**Why `develop` and not `main`:** the AI review runs on pull requests targeting `develop`, so `develop` is the code state the index has to mirror. Indexing `main` would leave the retrieved context stale relative to the code actually being reviewed.

[`scripts/build_index.py`](scripts/build_index.py) uses only the Python standard library, so the runner needs no dependency install step. It indexes everything under `src/`, plus `README.md` and `pom.xml`; `target/`, `.git/`, and anything that does not decode as UTF-8 are skipped.

### `index.json` format

This file is a **shared contract** with `codereview-lambda`. A format change here breaks the consumer there — bump `version` and align both repositories.

```json
{
  "version": 1,
  "branch": "develop",
  "commit": "<full 40-char sha>",
  "generatedAt": "<ISO 8601 UTC>",
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "chunks": [
    { "path": "src/main/java/com/codereview/app/tasks/TaskService.java", "text": "...", "vector": [0.013, -0.087, "..."] }
  ]
}
```

Only `vector` comes from the Gemini API. Everything else is assembled by the script: `path`/`text` from the filesystem, `commit`/`branch` from the Actions context, and `model`/`dimensions` from the parameters the script itself used for the call.

**One chunk per file** — each chunk's `text` is the *entire* file, never split. A deliberate first-version simplification, viable because this project is small. Once files start exceeding the embedding model's token limit, or retrieval accuracy degrades from dilution, the next step is per-class/per-method chunking, which requires a `version` bump and a matching change in `codereview-lambda`.

## The application under test

This repository is **not a real product.** It is a deliberately simple "guinea pig" application whose purpose is to generate pull requests of varying complexity so the AI review pipeline can be exercised against them.

- Java 21 (LTS), Spring Boot 3.5.x via `spring-boot-starter-parent`
- Maven, single module, `pom.xml` at the repository root
- `jjwt` for JWT generation/validation

Two modules, sized to produce different review complexity tiers:

- **`auth/`** — JWT generation/validation (`JwtValidator`) and a simple login endpoint (`AuthController`), backed by fixed in-memory credentials with no production hardening. Intended to seed **hard**-tier review PRs.
- **`tasks/`** — task CRUD over REST (`GET/POST/PUT/DELETE /tasks`) with in-memory storage (`TaskService`). Intended to seed **medium**-tier review PRs.

The two modules are intentionally independent at this stage: task CRUD has no authentication wired into it.

### Running it

Requires Java 21 and Maven.

```bash
mvn spring-boot:run
```

Starts on port `8080` (see [`src/main/resources/application.yml`](src/main/resources/application.yml)). No external database or service is needed — tasks are held in memory and login uses fixed in-memory credentials.

```bash
mvn test
```

## Pending configuration

The infrastructure is already deployed: `codereview-infra` was applied and the OIDC role, artifacts bucket, and event bus all exist. What remains is registering the four values below under **Settings > Secrets and variables > Actions** in this repository — no infrastructure step is outstanding.

| Name | Type | Used by | Value |
| --- | --- | --- | --- |
| `AWS_ROLE_ARN` | Secret | both workflows | ARN of the OIDC role (`terraform output -raw github_actions_pr_review_role_arn` in `codereview-infra`) |
| `ARTIFACTS_BUCKET_NAME` | Variable | both workflows | Name of the shared artifacts bucket — PR diffs under `prs/`, the embedding index under `index/` |
| `EVENT_BUS_NAME` | Variable | `pr-checks.yml` | Name of the pipeline's EventBridge bus |
| `GEMINI_API_KEY` | Secret | `index-codebase.yml` | Google AI Studio API key used for the embedding calls |

`GEMINI_API_KEY` is a **GitHub Actions** secret, distinct from the Secrets Manager secret the Lambdas read at runtime. They are separate credentials with separate scopes: this repository never reads anything from Secrets Manager.

Until these are set, AWS authentication fails: the `trigger-review` job of `pr-checks.yml` fails (without blocking merges, since it runs with `continue-on-error: true` and no `needs` relationship to `test`), and `index-codebase.yml` fails outright.

## Project conventions

See [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/) for the code, language, and commit conventions used in this repository.
