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
├── build_index.py            # Builds the RAG embedding index — CI tooling, not part of the app
├── chunking.py               # Splits files into index chunks (per Java method, blocks otherwise)
├── requirements-index.txt    # Pinned tree-sitter packages for the two scripts above
└── tests/                    # unittest suite, run by index-script-tests.yml
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
- **`trigger-review`** — `if: github.event_name == 'pull_request'`, so it's skipped (not failed) on a direct push to `main` — there's no PR to diff against there. Not blocking, because branch protection requires only `test`; `continue-on-error` is deliberately **not** set, since it suppresses the red check and once hid a broken diff step here for days. On failure the job writes to the step summary and comments on the PR, so a missing review is never silent. Permissions are scoped to this job (`id-token: write` for OIDC, `pull-requests: write` for that comment). It computes the PR diff against the **merge base** (`git merge-base`, over a full-depth fetch of the base branch), uploads it to the shared artifacts S3 bucket under `prs/` (claim-check pattern, matching `codereview-infra`'s `s3.tf`), and publishes a `PRReviewRequested` event on the `<project_name>-bus` EventBridge bus (matching `codereview-infra`'s `eventbridge.tf` event pattern: `source = "codereview.app"`, `detail-type = "PRReviewRequested"`). The event `Detail` carries `prNumber`, `repository`, `sha`, `diffBucket`, `diffKey`, plus lightweight diff stats (`filesChanged`, `linesAdded`, `linesRemoved`, `paths`) derived from the same diff via `git apply --numstat` — no second `git diff` against the base branch. Those stats exist so `codereview-lambda`'s `route-model` can pick a complexity tier without fetching the full diff from S3 in the common case. `codereview-infra`'s Step Functions state machine picks up execution from there.
- Authentication to AWS is via GitHub Actions OIDC (`aws-actions/configure-aws-credentials`), assuming a role provisioned by `codereview-infra`'s `oidc.tf` — no static AWS credentials are ever stored in this repo.

**Why the base branch is fetched at full depth, not `--depth=1`**: `actions/checkout` resolves the PR's merge ref, whose first parent is the base tip *as of when GitHub last computed that ref*, and a shallow fetch truncates `origin/<base>` to a single parentless commit. That is usually harmless, because the base tip is a parent of HEAD and the merge base is directly reachable. It breaks when the **merge ref is stale relative to the base branch** — the base advanced and GitHub had not recomputed the ref yet — because the real merge base then sits behind a parentless commit and git fails with `no merge base`. Don't "simplify" this back to `base.sha` from the event payload either: that is the base tip, not the merge base, so diffing against it folds the base's own newer commits into the PR diff, inverted (measured: 5 files vs 11 on the same branch).

**Review exclusions**: the diff is generated with `:(exclude)` pathspecs, so documentation, generated files and binaries never reach the pipeline — they still appear on the PR itself. Patterns come from `.codereview.yml` at the **merge base** (never the PR head: a PR must not be able to exclude itself from review) or from the default list inlined in the workflow. YAML and `.github/workflows/**` are never excluded — CI config is security-relevant — and `*.svg` stays reviewable because it is script-capable text. When every changed file is excluded the job stops before publishing: no event, a PR comment saying so, check green. Don't move that decision into `RouteModel` — it costs an execution to reach the same answer, and the embeddings API rejects an empty diff (`content contains an empty Part`), so the run would fail instead of concluding.

`.github/workflows/index-codebase.yml` rebuilds the RAG embedding index on every push to `develop`, running `scripts/build_index.py` and uploading the result to `index/develop/index.json` in the same artifacts bucket. It indexes `develop`, not `main`, because the AI review runs against PRs targeting `develop` — indexing `main` would leave the RAG context stale relative to the code actually under review. It has a per-branch `concurrency` group with `cancel-in-progress: true`: a newer push cancels an older build, so two builds can never finish out of order and publish a stale index last. `.github/workflows/index-script-tests.yml` runs the builder's tests and a `--dry-run` on pull requests that touch `scripts/**` or the index workflows; it is not a required check.

### `index.json` — shared contract with codereview-lambda

`codereview-lambda`'s `RetrieveContext` reads this file, so the schema is a cross-repo contract: a format change here breaks the consumer there. Bump `version` and align both repos when changing it. The authoritative definition is [codereview-lambda's index-v2 contract](https://github.com/Joaquimlagos/codereview-lambda/blob/develop/specs/002-method-chunking/contracts/index-v2.md); `RetrieveContext` reads versions 1 and 2.

```json
{
  "version": 2,
  "branch": "develop",
  "commit": "<full 40-char sha>",
  "generatedAt": "<ISO 8601 UTC>",
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "chunks": [{
    "id": "src/.../JwtValidator.java#JwtValidator.isValid(String):44-46",
    "path": "src/...", "kind": "method | type | block",
    "symbol": { "type": "JwtValidator", "method": "isValid" },
    "startLine": 44, "endLine": 46, "part": null,
    "header": "<package, enclosing type declarations, fields>",
    "text": "<the method body>",
    "vector": [768 floats]
  }]
}
```

Only `vector` comes from the Gemini API (`batchEmbedContents`, up to 100 inputs per call, `outputDimensionality=768`, `taskType=RETRIEVAL_DOCUMENT`, embedding `header + "\n" + text`). Everything else is assembled by the scripts: chunks from the filesystem, `commit`/`branch` from the Actions context, `model`/`dimensions` from the parameters the script chose for the call. **`path`, `text` and `vector` keep their version-1 names and meanings on purpose**: a `RetrieveContext` that only knows version 1 still ranks a version-2 index without error, so the two repos can deploy in either order.

**One chunk per Java method** (`scripts/chunking.py`, tree-sitter; javalang cannot parse Java 21 records). Each chunk carries a header, the package plus every enclosing type's declaration plus the innermost type's fields, so a method is understandable on its own. Trivial methods (plain getters/setters, empty bodies, assign-only constructors) are dropped; a type left with no chunk (a record, a DTO) becomes one `type` chunk. Non-Java files are cut into `block` chunks at blank lines. **No chunk is ever sent over the embedding limit**: `gemini-embedding-001` silently drops input past 2,048 tokens, so anything estimated above 1,800 tokens (characters / 3, which over-counts every tokenizer measured here) is split into `part`s that each repeat the header. Chunk ids are stable across builds for unchanged code.

**Failure never publishes a partial index**: HTTP 429/503 and timeouts are retried with backoff (honouring `Retry-After` / `RetryInfo`), anything else fails at once, and `index.json` is written only after every embedding succeeded — so on failure the upload step never runs and the previous index stays in S3. `python scripts/build_index.py --dry-run` chunks and reports counts with no key and no network.

**Source only** — indexed: everything under `src/`, plus `pom.xml`; `target/`, `.git/` and anything that doesn't decode as UTF-8 are skipped. `README.md` is excluded on purpose: it explains that this repository exists as a test bed for the review pipeline, and feeding that to the reviewer as retrieved context biases it — the model starts reading diffs through "this repo exists to generate test PRs" instead of judging the code on its own terms. Keep the index to source and build definition; don't add prose files describing the project's purpose.

The index scripts use the Python standard library (`urllib.request` for the HTTP calls) **plus one approved exception: tree-sitter**, pinned in `scripts/requirements-index.txt` and installed by both index workflows. A Java parser is the one thing the standard library can't provide. Don't add other dependencies. `tree-sitter` is pinned to 0.25.2, not 0.26.0, which corrupted memory while walking large methods on Windows/CPython 3.12; any version bump changes chunk boundaries and therefore every chunk id, so bump it deliberately and check the `--dry-run` counts.

`codereview-infra` is deployed, so the AWS resources both workflows target already exist. What's still missing is registering their values in this repo's GitHub settings: `AWS_ROLE_ARN` and `GEMINI_API_KEY` (secrets), `ARTIFACTS_BUCKET_NAME` and `EVENT_BUS_NAME` (variables) — until then both workflows fail at the AWS authentication step. `GEMINI_API_KEY` is an Actions secret, distinct from the Secrets Manager secret the Lambdas read at runtime — this repo never reads anything from Secrets Manager. See the comment block at the top of each workflow file and `README.md` for the exact values.

One bucket (`codereview-artifacts`), split by prefix: PR diffs under `prs/`, the embedding index under `index/`.

## Code style

See `.claude/rules/`:
- `language.md` — English-only codebase, `README.md` included.
- `java-conventions.md` — Java 21 / Spring Boot conventions (records, `Optional`, constructor injection, thin controllers).
- `commits.md` — Conventional Commits, in English.
