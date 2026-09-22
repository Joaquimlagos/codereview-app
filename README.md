# codereview-app

API REST simples de gerenciamento de tarefas ("Task Manager"), em Java 21 +
Spring Boot, com workflows do GitHub Actions (CI + testes) que autenticam via
OIDC e disparam eventos de revisão de PR pelo EventBridge.

## Propósito

Este repositório **não é um produto real** — é uma aplicação "cobaia",
propositalmente simples, criada para gerar pull requests de diferentes
níveis de complexidade e testar um pipeline de revisão de código por IA.
Faz parte de um projeto de portfólio dividido em 3 repositórios
independentes:

- **`codereview-app`** (este repositório) — dispara o evento de revisão via
  GitHub Actions.
- **`codereview-infra`** — EventBridge, Step Functions e IAM.
- **`codereview-lambda`** — as funções Lambda: roteamento para o LLM,
  contexto via RAG, e publicação do comentário de revisão no PR.

Este repositório não conhece a implementação da análise de código — ele só
dispara o pipeline via um evento no EventBridge.

## Stack

- Java 21 (LTS)
- Spring Boot 3.5.x (via `spring-boot-starter-parent`)
- Maven, módulo único (`pom.xml` na raiz)
- `jjwt` para geração/validação de JWT (módulo `auth`)

## Como rodar localmente

Pré-requisitos: Java 21 e Maven instalados.

```bash
mvn spring-boot:run
```

A aplicação sobe na porta `8080` (ver `src/main/resources/application.yml`).
Não é necessário banco de dados externo — as tasks ficam em memória e o
login usa um usuário/senha fixos, também em memória.

## Como rodar os testes

```bash
mvn test
```

## Módulos / tiers de complexidade

A aplicação é um "Task Manager" dividido em dois módulos, pensados para
gerar PRs de complexidade diferente no futuro:

- **`auth/`** — validação/geração de JWT (`JwtValidator`) e um endpoint de
  login simples (`AuthController`), com usuário/senha fixos em memória, sem
  nenhuma sofisticação de produção. Este módulo existe propositalmente para
  gerar PRs de complexidade **hard**.
- **`tasks/`** — CRUD de tarefas via REST (`GET/POST/PUT/DELETE /tasks`),
  com armazenamento em memória (`TaskService`). Este módulo existe para
  gerar PRs de complexidade **medium**.

Os dois módulos coexistem sem estarem de fato integrados nesta primeira
versão — o CRUD de tasks não exige autenticação.

## Pipeline de revisão por IA

Um único workflow, `.github/workflows/pr-checks.yml`, disparado tanto em
`pull_request` (`opened`, `synchronize`, `reopened`) quanto em `push` para
`main`, com dois jobs independentes (sem `needs` entre eles):

- **`test`** — build + testes (`mvn test`), bloqueante, sem condição `if:`
  — roda nos dois gatilhos, então a `main` continua sendo validada mesmo em
  push direto. É o check que a proteção de branch deve apontar como
  obrigatório.
- **`trigger-review`** — só roda em `pull_request` (`if:
  github.event_name == 'pull_request'`; em push direto pra `main` não faz
  sentido tentar disparar revisão de um PR que não existe, então o job
  aparece como *skipped*, não *failed*). Não-bloqueante
  (`continue-on-error: true`), que:

1. Calcula o diff do PR contra a branch base.
2. Sobe o diff para um bucket S3 (padrão *claim-check*).
3. Publica um evento `PRReviewRequested` no EventBridge, com o número do
   PR, o repositório, o SHA, o ponteiro para o diff no S3, e estatísticas
   leves do diff (`filesChanged`, `linesAdded`, `linesRemoved`, `paths`) —
   derivadas do mesmo diff já computado no passo anterior, via
   `git apply --numstat`. Esses metadados existem para que o `route-model`
   do `codereview-lambda` consiga decidir o tier de complexidade sem
   precisar buscar o diff completo no S3 na maioria dos casos.

A partir daí, quem processa o evento é o `codereview-infra`
(Step Functions) e o `codereview-lambda` (chamada ao LLM) — este
repositório não sabe nada sobre como a revisão é feita.

### Configuração AWS (pendente)

O `codereview-infra` ainda não foi implantado, então os três valores abaixo
ainda não existem de verdade. Depois do primeiro deploy do
`codereview-infra`, configure em **Settings > Secrets and variables >
Actions** deste repositório:

| Nome | Tipo | Onde encontrar |
| --- | --- | --- |
| `AWS_ROLE_ARN` | Secret | `terraform output -raw github_actions_pr_review_role_arn` no `codereview-infra` |
| `DIFF_BUCKET_NAME` | Variable | Nome do bucket S3 de diffs (`codereview-pr-diffs` por padrão) |
| `EVENT_BUS_NAME` | Variable | Nome do bus do EventBridge (`codereview-bus` por padrão) |

Até lá, o job `trigger-review` do `pr-checks.yml` falha (mas não bloqueia o
merge, já que roda com `continue-on-error: true` e sem `needs` em relação ao
job `test`).

## Convenções do projeto

Ver `CLAUDE.md` e `.claude/rules/` para as convenções de código, idioma e
commits adotadas neste repositório.
