# CLAUDE

Este arquivo orienta Claude ao trabalhar neste repositório.

## Índice

1. [Meta](#meta)
2. [Regras Críticas](#regras-críticas)
3. [Stack e Estrutura](#stack-e-estrutura)
4. [Padrões de Código](#padrões-de-código)
5. [Workflows](#workflows)
6. [Referências](#referências)
7. [Glossário](#glossário)

## Meta

### Propósito e escopo

- Marketplace multivendor white-label em oito contextos (Quarkus + Angular), com o catálogo e inventário implementados e o
  painel de operação para lojistas, moderadores e administradores da plataforma.
- Este arquivo é a fonte de verdade para convenções do repositório.
- Se houver conflito de instruções, priorize este arquivo, o `package.json` e as ADRs de `docs/decisoes.md`.

### Idioma

**Sempre responda ao usuário em português (PT-BR).** Identificadores, mensagens de commit e títulos de PR em inglês;
comentários de código e documentação em PT-BR.

### Resolução de conflitos

| Nível           | Significado                           | Exemplo                                 |
| --------------- | ------------------------------------- | --------------------------------------- |
| **OBRIGATÓRIO** | Quebra build, CI ou deploy se violado | Validação após tarefa, fronteiras do Nx |
| **RECOMENDADO** | Padrão esperado                       | Convenções de código                    |
| **OPCIONAL**    | Melhoria incremental                  | Remoção proativa de código morto        |

## Regras Críticas

### Validação obrigatória após cada tarefa

Ao finalizar qualquer código, rode só o que a mudança afeta, um comando pesado por vez:

1. `npm run check:memory` antes de testes, e2e ou build nativo
2. Formatação: `npx nx format:write` (arquivos alterados) e, se mexeu em Java, `npx nx affected -t spotlessApply`
3. Front: `npx nx run-many -t lint test build -p <projetos alterados>` (o build do Angular faz a checagem de tipos)
4. Java: `./gradlew --no-daemon :<módulo>:test` (por exemplo `:catalog-inventory-service:test`)
5. Fronteiras entre módulos: `npm run check`
6. Mudou a API: `./gradlew --no-daemon :catalog-inventory-service:test --tests '*OpenApiContractTest' -Dcontract.update=true`,
   depois `npx nx run catalog-inventory-admin-data-access:codegen`
7. Fluxos do painel com a stack local no ar: `npx nx e2e admin-web-e2e`

Se qualquer comando falhar, corrija e repita até todos passarem. Para tudo o que a branch afeta:
`npx nx affected -t lint test build`.

### Regras de proteção

- NUNCA modifique testes para passar — corrija o código
- NUNCA use --no-verify em commits
- NUNCA permita force push ou operações Git destrutivas sem pedido explícito
- SEMPRE revise `git diff` antes de commit
- SEMPRE peça confirmação explícita antes de commit/push

### Escopo de alterações

- **Solicitação explícita**: Faça APENAS o solicitado
- **Scope creep controlado**: Ao encontrar problemas adjacentes:
  - Pequenos (< 5 linhas): Corrija junto
  - Médios (5-20 linhas): Mencione, pergunte se deve corrigir
  - Grandes (> 20 linhas): Documente para tarefa separada
- **Refatoração**: NUNCA sem solicitação explícita

### Refatoração de símbolos

Ao refatorar qualquer símbolo (variável, função, tipo):

1. **SEMPRE** buscar todos os usos no arquivo/projeto antes de alterar
2. **NUNCA** assumir que o caso mencionado é o único uso

### Imports ao mover arquivos

Ao mover arquivos ou pastas:

1. **SEMPRE** verificar TODOS os imports afetados, inclusive os aliases de `tsconfig.base.json`
2. **SEMPRE** verificar mocks e fixtures de testes que citam caminhos

### Coesão e colocalização

1. **SEMPRE** criar funções, tipos e utils na mesma lib de quem usa
2. **NUNCA** colocar em `libs/shared/*` se há apenas 1 consumidor
3. Mover para a camada compartilhada apenas quando 2+ libs precisarem, respeitando as tags `scope:` e `layer:`

### TypeScript

- **NUNCA** usar `any` sem justificativa documentada — usar `unknown` e refinar com type guard
- **SEMPRE** usar `import type` para importações que são apenas tipos
- **SEMPRE** manter `strict: true` no admin-web e nas libs do painel, onde já está habilitado
- **NUNCA** usar `@ts-ignore` — preferir `@ts-expect-error` com comentário explicativo
  - Por que: `@ts-expect-error` falha se o erro for corrigido; `@ts-ignore` silencia para sempre

### SCSS

- **SEMPRE** usar as variáveis do CoreUI ou CSS custom properties para cores e espaçamento
- **NUNCA** aninhar seletores além de 3 níveis
- **NUNCA** usar `@extend` — preferir mixins ou composição de classes

### Autenticação

- **NUNCA** colocar segredos de cliente ou de token em código — usar variáveis de ambiente
- **NUNCA** confiar em dados do token sem verificação de assinatura; no serviço, o OIDC do Quarkus valida antes de
  qualquer recurso

### Padrões que causam bug

- **NUNCA** repetir no painel uma regra ou catálogo do servidor (ADR 031) — ações, pendências, mensagens e motivos vêm da
  API. Quando uma cópia for inevitável, marcar com `// WHY: cópia pendente (ADR 031); saída: ...` e registrá-la na
  tabela "Valores fixos com saída pendente" da proposta de design.
- **NUNCA** decidir o que o painel mostra pelo nome do papel — usar as capacidades de `GET /me` (`capabilityGuard`).
  **SEMPRE** usar só componentes do CoreUI grátis; os PRO exigem licença.
- **NUNCA** rodar testes, e2e e build nativo ao mesmo tempo nem em agentes paralelos — o host já ficou sem memória e o
  editor caiu. **SEMPRE** uma tarefa pesada por vez, com `npm run check:memory` antes e Gradle com `--no-daemon`.

### Comentários e documentação

- **No código**: só o porquê não óbvio, com os marcadores `WHY:`, `NOTE:`, `INVARIANT:`; Javadoc e TSDoc em API pública
- **Em arquivos .md**: documentação completa, em PT-BR
- **Exceção**: TODO só com ID de tracker

## Stack e Estrutura

### Contexto rápido

- Stack: monorepo Nx 24 com Java 21 + Quarkus 3.40 (Gradle, reativo com Mutiny) e Angular 22 + CoreUI 5.7 (TypeScript 6)
- Gerenciador de pacotes: npm (`package-lock.json`), Node `>=24 <25` (`.nvmrc`)
- Persistência e mensageria: PostgreSQL (migrations Flyway, reescritas até o lançamento pela ADR 015), Kafka com outbox
  e inbox, Redis de cache desligado por padrão
- Identidade: Keycloak (OIDC) no ambiente local; painel com code flow + PKCE
- Cliente da API do painel gerado por `@hey-api/openapi-ts` a partir de `contracts/<contexto>/openapi.json`

### Estrutura do projeto

Principais diretórios:

- `apps/<contexto>-service/`: serviço Quarkus de cada contexto (o do catálogo é o implementado)
- `apps/admin-web/`: painel de operação (Angular); `apps/admin-web-e2e/`: e2e com Playwright
- `libs/<contexto>/domain/` e `libs/<contexto>/application/`: domínio e casos de uso de cada contexto (Java)
- `libs/catalog-inventory/admin/`: libs do painel do catálogo (`data-access`, `ui`, `feature-*`)
- `libs/shared/`: `web-util` (TypeScript sem framework) e `admin-ui` (componentes Angular comuns)
- `contracts/`: OpenAPI e schemas de eventos por contexto
- `infra/`: compose local, Helm, Keycloak, Postgres, observabilidade e imagens Docker
- `tools/`: verificadores (`check-boundaries`, `check-images`, `infra-budget`, `memory-guard`) e carga com k6 (`load/`)
- `docs/`: ADRs, propostas, validação, plano de implementação e decisões em aberto

## Padrões de Código

**RECOMENDADO. Padrão esperado.**

### Convenções de código

- Naming de arquivos TypeScript: kebab-case (`product-store.ts`, `category-page.component.ts`)
- Exports: named; `export default` só onde o framework exige
- Java: pacote `dev.marketplace.<contexto>`; domínio sem dependência de framework; o serviço é a raiz de composição e
  contém os adaptadores
- Fronteiras do Nx por tags `scope:`, `layer:`, `framework:` e `lang:`, conferidas pelo ESLint e por `npm run check`
- Concorrência: escrita com `If-Match`; a versão vem da `version` do corpo (`apiDataWithVersion`)

### Formatação

Conferida no CI só no que a mudança toca (ADR 033); `npm run format` aplica em tudo, e `npm run format:check` só confere.

- Indentação de 2 espaços em tudo o que tem formatador (`.editorconfig` do Angular); Gradle, shell, SQL e PlantUML, sem
  formatador, ficam em 4
- TS, JS, JSON, YAML, HTML, SCSS e markdown: Prettier com o `.prettierrc` do Angular (100 colunas, aspas simples); no
  YAML, aspas duplas, que o `tools/chart-check.sh` espera no `Chart.yaml`
- Java: Spotless com o perfil Eclipse de `tools/eclipse-formatter.xml`, o mesmo do VS Code (120 colunas); ele não mexe
  em comentários nem junta linhas quebradas, então **SEMPRE** quebrar comentários à mão em 120 colunas
- **NUNCA** formatar à mão templates inline de componentes: o Prettier os deixa como estão
  (`embeddedLanguageFormatting: off`), porque quebrar o corpo de um `@if` vira espaço visível na tela
- **NUNCA** formatar código gerado (`src/generated`, `contracts/*/openapi.json`): regerar com o `codegen` ou com o
  `OpenApiContractTest`; o `.prettierignore` já os deixa de fora
- ESLint não decide estilo: o `eslint-config-prettier` desliga essas regras

### Testes

- Java: JUnit em `src/test/java`, com `*Test.java`; testes HTTP com `@QuarkusTest` e Dev Services (PostgreSQL e Kafka
  reais)
- Libs do painel: Vitest (`vitest-analog`), `*.spec.ts` ao lado do código, com teto de 2 workers no `vite.config.mts`
- admin-web: `@angular/build:unit-test` no Chromium headless
- e2e: Playwright em `apps/admin-web-e2e/src`, 1 worker local; cada teste cria os próprios dados pela API

### Qualidade e CI

- Linter: ESLint 9 (`npx nx lint <projeto>`)
- Type check: build do Angular e compilação do Gradle
- Fronteiras: `npm run check`; imagens: `npm run check:images`; observabilidade: `npm run check:observability`
- Formatação: Prettier e Spotless (`npm run format:check`); no CI, `nx format:check` e `nx affected -t spotlessCheck`
- CI: `.github/workflows/ci.yml`; não há hooks de git

## Workflows

### Fluxo recomendado

1. `npm run dev:catalog` — serviço do catálogo em modo dev, com Dev Services
2. `npm run dev:admin` — painel em `http://localhost:4200`
3. Stack local completa: `docker compose -f infra/local/docker-compose.yml --profile catalog up -d --build --wait`,
   depois de `npx nx run catalog-inventory-service:native` (ver README)
4. Após cada tarefa: validação obrigatória, uma tarefa pesada por vez

### Protocolo de alterações

1. **Análise** — visão global, arquivos impactados
2. **Execução** — alteração mínima
3. **Validação** — verificar regressões

## Referências

### Guias

- `README.md` — requisitos, execução, stack local e memória do host
- `apps/catalog-inventory-service/docs/README.md` — API, erros, cache e diagramas do serviço
- `contracts/catalog-inventory/README.md` — contrato da API e dos eventos

### Decisões e propostas

- `docs/decisoes.md` — ADRs
- `docs/proposta-tecnica.md` — organização dos contextos
- `docs/proposta-design-admin-web.md` — telas do painel e valores fixos com saída pendente
- `docs/catalog-inventory.md` — regras do catálogo
- `docs/validacao.md` — o que foi validado e como
- `docs/plano-implementacao.md` — entregue e sequência por capacidade
- `docs/decisoes-em-aberto.md` — pendências com motivo e saída

## Glossário

| Termo                   | Definição                                                                                                              |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Capacidade              | Permissão derivada do papel no servidor (`Capability`); o painel decide o que mostrar só por ela, via `GET /me`        |
| Cópia pendente          | Valor do servidor repetido no painel, marcado com `// WHY: cópia pendente (ADR 031); saída: ...` e listado na proposta |
| Lote                    | Chamada `*/batch` com até 100 itens, aplicada a cada item isolado e com o resultado de cada um na ordem do pedido      |
| Ação barrada            | Ação que vale no status e para o chamador, mas que um motivo impede agora (`blockedActions`, com o motivo)             |
| Pendência de publicação | Item que impede enviar ou aprovar o produto (`publishingIssues`), calculado pelo servidor                              |
