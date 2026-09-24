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

## Índice de embeddings (RAG)

O workflow `.github/workflows/index-codebase.yml` dispara a cada `push` na
branch **`develop`** e reconstrói, do zero, o índice de embeddings que
alimenta o RAG do pipeline de revisão. Ele roda `scripts/build_index.py`
(Python puro, só stdlib) e publica o resultado no S3 em
`index/develop/index.json`, no mesmo bucket de artefatos usado para os
diffs.

**Por que `develop` e não `main`**: a análise por IA roda nos PRs que têm
`develop` como base, então é o estado de código da `develop` que o índice
precisa refletir. Indexar a `main` deixaria o RAG defasado em relação ao
código que está de fato sendo revisado.

### Formato do `index.json`

Este arquivo é um **contrato compartilhado** com o `codereview-lambda`, que
é quem lê o índice na etapa `RetrieveContext`. Mudanças de formato aqui
quebram o consumidor lá — se mexer, incremente o `version` e alinhe os dois
repositórios.

```json
{
  "version": 1,
  "branch": "develop",
  "commit": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
  "generatedAt": "2026-09-22T14:03:11Z",
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "chunks": [
    { "path": "src/main/java/com/codereview/app/tasks/TaskService.java", "text": "...", "vector": [0.013, -0.087, "..."] }
  ]
}
```

Só o campo `vector` vem da API do Gemini (`gemini-embedding-001`, com
`outputDimensionality=768` e `taskType=RETRIEVAL_DOCUMENT`). Todo o resto é
montado pelo próprio script: `path`/`text` saem do filesystem,
`commit`/`branch` vêm do contexto do GitHub Actions, e `model`/`dimensions`
são os próprios parâmetros que o script usou na chamada.

**Um chunk por arquivo**: o `text` de cada chunk é o conteúdo **completo**
do arquivo, sem nenhum split. É uma simplificação deliberada desta primeira
versão, viável porque o projeto é pequeno. Se a base crescer a ponto de os
arquivos estourarem o limite de tokens do modelo de embedding (ou de a
recuperação ficar imprecisa demais por diluição), o passo natural é trocar
por chunking por método/classe — o que exigirá bump do `version` e ajuste
no `codereview-lambda`.

### O que entra no índice

São indexados os arquivos sob `src/` e o `pom.xml`. `target/`, `.git/` e
arquivos binários (qualquer coisa que não decodifique como UTF-8) ficam de
fora.

**O `README.md` é deliberadamente excluído.** Ele descreve o propósito deste
repositório — que é servir de cobaia para o pipeline de revisão — e, quando
entra como contexto recuperado, isso enviesa o revisor: em vez de julgar o
código pelo que ele é, o modelo passa a ler as mudanças à luz de "este repo
existe para gerar PRs de teste". O índice do RAG deve conter só código-fonte
e a definição de build; prosa sobre o propósito do projeto não acrescenta
nada à revisão de um diff.

## Configuração pendente

A infraestrutura já está no ar: o `codereview-infra` foi implantado e os
recursos (role OIDC, bucket, event bus) existem de verdade. O que falta é
só registrar os quatro valores abaixo em **Settings > Secrets and variables
> Actions** deste repositório — nenhum passo de infraestrutura pendente.

| Nome | Tipo | Usado por | Valor |
| --- | --- | --- | --- |
| `AWS_ROLE_ARN` | Secret | ambos os workflows | ARN da role OIDC (`terraform output -raw github_actions_pr_review_role_arn` no `codereview-infra`) |
| `ARTIFACTS_BUCKET_NAME` | Variable | ambos os workflows | `codereview-artifacts` — guarda os diffs em `prs/` e o índice em `index/` |
| `EVENT_BUS_NAME` | Variable | `pr-checks.yml` | `codereview-bus` |
| `GEMINI_API_KEY` | Secret | `index-codebase.yml` | Chave de API do Google AI Studio, para as chamadas de embedding |

O `GEMINI_API_KEY` é um secret **do GitHub Actions**, não do Secrets Manager
— a chave do Secrets Manager é lida só pelas Lambdas, em runtime. São
credenciais separadas, com escopos separados: este repositório nunca lê
nada do Secrets Manager.

Enquanto esses valores não forem preenchidos, a autenticação na AWS falha: o
job `trigger-review` do `pr-checks.yml` falha (mas não bloqueia o merge, já
que roda com `continue-on-error: true` e sem `needs` em relação ao job
`test`), e o `index-codebase.yml` falha por inteiro.

## Convenções do projeto

Ver `CLAUDE.md` e `.claude/rules/` para as convenções de código, idioma e
commits adotadas neste repositório.
