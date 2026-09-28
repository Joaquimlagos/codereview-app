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

## Review exclusions

Some files cost tokens without teaching the reviewer anything. The diff sent to the pipeline leaves them out.

**This does not touch the pull request.** Every changed file still shows up under "Files changed" on GitHub, still counts for `mvn test`, and still needs human review. The exclusion applies only to the diff uploaded to S3, which means excluded files are absent from `filesChanged`, `linesAdded`, `linesRemoved` and `paths` in the event, and never reach `RouteModel`, the RAG step, or the LLM.

### Default list

| Category | Patterns |
| --- | --- |
| Documentation | `*.md`, `*.txt`, `docs/**`, `LICENSE` |
| Generated and lock files | `*.lock`, `**/target/**` |
| Binaries and media | `*.png`, `*.jpg`, `*.jpeg`, `*.gif`, `*.ico`, `*.webp`, `*.pdf`, `*.zip`, `*.jar`, `*.class`, `*.woff`, `*.woff2`, `*.ttf`, `*.otf` |

Two things are deliberately **not** excluded. YAML and everything under `.github/workflows/` stay reviewable, because CI configuration is security-relevant — a workflow change is exactly the kind of diff worth a second pair of eyes. And `*.svg` stays reviewable: it is text, it can carry script, and excluding it would create a blind spot for little gain.

Be aware that binaries were never expensive to begin with — git renders them as `Binary files differ`, with no line content. Excluding them keeps `paths` tidy; the real token saving comes from `*.md` and `docs/**`.

### Overriding the list

Drop a `.codereview.yml` at the repository root:

```yaml
exclude:
  - "*.md"
  - "docs/**"
  - "src/generated/**"
```

The file replaces the default list rather than adding to it. An `exclude` key that is missing or empty means nothing is excluded — every changed file gets reviewed — and the run logs a warning, so a typo'd key errs towards more review rather than less.

**Patterns are [git pathspecs](https://git-scm.com/docs/gitglossary#Documentation/gitglossary.txt-aiddefpathspecapathspec), not full `.gitignore` syntax.** Each one is passed to `git diff` as `:(exclude)<pattern>`. In practice:

| Pattern | Matches |
| --- | --- |
| `*.md` | every `.md` at any depth — `*` crosses `/`, so this covers `README.md` and `.claude/rules/language.md` alike |
| `docs/**` | the whole `docs` tree |
| `LICENSE` | that exact path at the repository root |

There is no negation: `.gitignore`'s `!pattern` has no pathspec equivalent, so a list cannot re-include something it excluded. Write a narrower pattern instead.

### The config is read from the merge base

The workflow reads `.codereview.yml` from the **merge base**, not from the pull request's head. A change to the file therefore takes effect only once it is merged — editing it in a PR does not affect that same PR's review.

That is on purpose. Reading it from the head would let a pull request add its own files to the exclusion list and skip its own review, which is the one thing an exclusion mechanism must not allow.

### When everything is excluded

If every changed file matches the list, the diff comes out empty and the workflow stops there: no upload, no event, no pipeline run. It comments on the PR saying there was nothing to analyse, and the check stays green. Publishing an event instead would cost a Step Functions execution to reach the same conclusion — and the embeddings API rejects an empty diff outright (`content contains an empty Part`), so the run would fail rather than conclude anything.

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
        ├─ pip install -r scripts/requirements-index.txt   (tree-sitter)
        ├─ python scripts/build_index.py     → index.json (version 2)
        │    one chunk per Java method (blocks for other files), embedded
        │    in batches of up to 100 (gemini-embedding-001,
        │    outputDimensionality=768, taskType=RETRIEVAL_DOCUMENT)
        │
        └─ aws s3 cp index.json
              s3://<artifacts-bucket>/index/develop/index.json
```

`codereview-lambda`'s `RetrieveContext` reads that object to rank the project's code against the diff under review. The workflow has a per-branch concurrency group: a newer push to `develop` cancels a build still running, so an older build can never overwrite a newer index.

**Why `develop` and not `main`:** the AI review runs on pull requests targeting `develop`, so `develop` is the code state the index has to mirror. Indexing `main` would leave the retrieved context stale relative to the code actually being reviewed.

[`scripts/build_index.py`](scripts/build_index.py) and [`scripts/chunking.py`](scripts/chunking.py) use the Python standard library plus tree-sitter, pinned in [`scripts/requirements-index.txt`](scripts/requirements-index.txt), to parse Java. They index everything under `src/` plus `pom.xml`; `target/`, `.git/`, and anything that does not decode as UTF-8 are skipped. `python scripts/build_index.py --dry-run` prints the chunk counts without calling any API. Their tests run on pull requests that touch `scripts/**` ([`index-script-tests.yml`](.github/workflows/index-script-tests.yml)).

**`README.md` is excluded on purpose**, for two independent reasons. It describes what this repository is *for* — a test bed for the review pipeline — and feeding that to the reviewer as retrieved context biases the review: the model starts reading diffs through "this repo exists to generate test PRs" rather than judging the code on its own terms. It is also the one file large enough to hit the embedding model's 2,048-token input limit, which `gemini-embedding-001` enforces by silently discarding the overflow — a whole-file embedding of this README dropped roughly a quarter of it with no error and no warning. Keep the index limited to source and build definition.

### `index.json` format

This file is a **shared contract** with `codereview-lambda`. A format change here breaks the consumer there — bump `version` and align both repositories.

```json
{
  "version": 2,
  "branch": "develop",
  "commit": "<full 40-char sha>",
  "generatedAt": "<ISO 8601 UTC>",
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "chunks": [
    {
      "id": "src/main/java/com/codereview/app/tasks/TaskService.java#TaskService.create(Task):25-31",
      "path": "src/main/java/com/codereview/app/tasks/TaskService.java",
      "kind": "method",
      "symbol": { "type": "TaskService", "method": "create" },
      "startLine": 25, "endLine": 31, "part": null,
      "header": "package com.codereview.app.tasks;\n\n@Service public class TaskService {\n    ...fields...",
      "text": "public Task create(Task task) { ... }",
      "vector": [0.013, -0.087, "..."]
    }
  ]
}
```

(Line numbers illustrative.) The full definition lives in [codereview-lambda's index contract](https://github.com/Joaquimlagos/codereview-lambda/blob/develop/specs/002-method-chunking/contracts/index-v2.md). Only `vector` comes from the Gemini API; everything else is assembled by the scripts.

**One chunk per Java method**, each with a header (package, enclosing type declarations, fields) so it reads on its own. Trivial getters, setters and assign-only constructors are dropped, types with nothing left (records, DTOs) become one chunk each, and other files are split into blocks. Nothing is ever sent over the embedding model's 2,048-token limit, which it enforces by silently discarding the overflow: anything estimated above 1,800 tokens is split into parts. `path`, `text` and `vector` keep their version-1 meanings, so a consumer that only understands version 1 still works.

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

Until these are set, AWS authentication fails: the `trigger-review` job of `pr-checks.yml` fails — reported as a red check, but not blocking the merge, since branch protection requires only `test` — and `index-codebase.yml` fails outright. When `trigger-review` fails it also comments on the pull request, so the absence of a review is never silent.

## Project conventions

See [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/) for the code, language, and commit conventions used in this repository.
