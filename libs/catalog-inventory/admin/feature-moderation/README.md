# catalog-inventory-admin-feature-moderation

Documentação de manutenção da feature Angular de moderação do catálogo. A biblioteca reúne a fila de produtos aguardando decisão, o painel de detalhe e a administração do catálogo de motivos de rejeição e bloqueio, por tenant.

## Visão geral

A feature atende dois perfis de trabalho relacionados:

- **Moderação operacional:** revisar produtos submetidos à moderação e aprovar, rejeitar ou bloquear cada item ou um conjunto selecionado.
- **Configuração da plataforma:** manter os motivos prontos que moderadores podem selecionar ao rejeitar ou bloquear.

As consequências de negócio são diferentes: aprovar publica o produto; rejeitar o devolve ao rascunho do lojista; bloquear o remove da vitrine. A interface apresenta as regras de decisão recebidas do servidor em vez de reproduzir localmente a política de elegibilidade.

## Estrutura da feature/lib

O projeto Nx é `catalog-inventory-admin-feature-moderation`, do tipo `library`, com tags `scope:catalog-inventory`, `layer:feature`, `framework:angular` e `lang:ts` (ver [project.json](./project.json)). O ponto de entrada [src/index.ts](./src/index.ts) exporta as rotas da fila, as rotas do catálogo de motivos e `MODERATION_QUEUE_BADGE`.

| Arquivo | Responsabilidade |
|---|---|
| [lib.routes.ts](./src/lib/lib.routes.ts) | Define `routes` (fila e rota filha do detalhe) e `reasonRoutes` (administração dos motivos); carrega componentes sob demanda. |
| [moderation-queue-page.component.ts](./src/lib/moderation-queue-page.component.ts) | Lista, seleção, paginação, confirmações, execução e resumo das decisões em lote. |
| [moderation-queue.store.ts](./src/lib/moderation-queue.store.ts) | Estado da fila compartilhado com o detalhe: consulta, cursor, produtos, seleção, item aberto, erros e badge. |
| [moderation-item-page.component.ts](./src/lib/moderation-item-page.component.ts) | Carrega o produto e seus dados auxiliares, mostra as decisões permitidas e executa a decisão individual. |
| [moderation-decision-modal.component.ts](./src/lib/moderation-decision-modal.component.ts) | Seleção de motivos e complemento para rejeição/bloqueio, usada tanto no detalhe quanto em lote. |
| [moderation-reasons-page.component.ts](./src/lib/moderation-reasons-page.component.ts) | Lista, filtra e aciona as operações administrativas sobre os motivos. |
| [moderation-reason-form.component.ts](./src/lib/moderation-reason-form.component.ts) | Formulário reativo de criação/edição em offcanvas; carrega a versão do registro e aplica validações. |
| [moderation-decisions.ts](./src/lib/moderation-decisions.ts) | Apresentação local dos códigos de decisão: rótulos, cores, textos de sucesso e efeitos explicativos. |
| [moderation-reasons.ts](./src/lib/moderation-reasons.ts) | Resource de motivos por decisão e filtro de motivos aplicáveis. |
| [product-summary.ts](./src/lib/product-summary.ts) | Projeções de apresentação: atributos, categoria, SKUs, estoque, situação da loja e histórico. |
| [queue-badge.ts](./src/lib/queue-badge.ts) | Chave estável do badge da fila no menu administrativo. |

As chamadas e tipos HTTP vêm do projeto irmão [admin/data-access](../data-access/README.md), cujos clientes são gerados a partir do contrato OpenAPI do catálogo. A interface também compõe controles compartilhados de `admin-ui` e utilitários de domínio/HTTP; não há um serviço HTTP específico duplicado dentro da feature. O estado compartilhado próprio da fila fica concentrado no `ModerationQueueStore`.

## Roteamento e contexto

O app hospedeiro registra as rotas em [apps/admin-web/src/app/app.routes.ts](../../../../apps/admin-web/src/app/app.routes.ts):

| URL | Conteúdo | Acesso |
|---|---|---|
| `/moderacao/fila` | Fila e painel vazio/selecionado | Capacidade `MODERATE_PRODUCTS`, verificada pelo app. |
| `/moderacao/fila/:id` | Detalhe do produto como rota filha da fila | A mesma capacidade da área de moderação. |
| `/plataforma/motivos-de-moderacao` | Administração dos motivos | Capacidade `MANAGE_MODERATION_REASONS`, verificada pelo app. |

`/moderacao` redireciona para `fila`. A lista e o detalhe são duas colunas no mesmo componente de fila em telas largas; a rota filha `:id` alimenta o `RouterOutlet` da coluna de detalhe. Em telas menores, o detalhe oferece navegação de volta à lista. O `ModerationQueueStore` é fornecido no escopo da página da fila e injetado opcionalmente pelo detalhe, permitindo que ambas as rotas compartilhem o mesmo estado sem torná-lo global ao app.

O cursor e o tamanho da página vivem nos query params `cursor` e `limit`. A navegação para um produto e a volta preservam esses parâmetros. A decisão de produto aberto é derivada da navegação do Router; trocar cursor ou tamanho de página não fecha a rota filha. A rota de fila também usa `leaveGuard`; durante um lote, a tela avisa antes de sair e associa o processamento ao ciclo de vida do componente.

## Componentes principais

- **`ModerationQueuePageComponent`:** exibe os produtos na ordem fornecida pelo backend, permite selecionar itens e expõe ações em lote. Tem estados de carregamento, erro com retry e fila vazia. A seleção de página é distinta do conjunto de itens selecionados mantido pelo utilitário compartilhado.
- **`ModerationItemPageComponent`:** detalha conteúdo e categoria, atributos efetivos, SKUs, oferta/preço, saldo, histórico e, quando autorizado, situação de elegibilidade da loja. Botões são formados a partir das decisões conhecidas e da resposta do servidor.
- **`ModerationDecisionModalComponent`:** controla a seleção de motivos e o complemento. Recebe contexto (decisão, produto ou contagem, lote/individual, carregamento e problema) por inputs; emite confirmação, fechamento ou pedido para recarregar a versão do produto.
- **`ModerationReasonsPageComponent`:** mostra os motivos em tabela, com decisões atendidas, estado ativo/inativo, posição e menu contextual de editar, desativar ou reativar.
- **`ModerationReasonFormComponent`:** formulário de criação/edição em offcanvas. Emite eventos de salvamento ou código duplicado para que a página pai atualize a lista e mostre feedback.

Os componentes usam `OnPush`, Signals e computed values para derivar rótulos, estados de ação, mensagens e linhas de apresentação. Estados transitórios (salvando, erro, modal aberto, seleção) permanecem próximos da tela que os possui.

## Regras de negócio e domínio

### Decisões de produto

Os códigos apresentados são `APPROVE`, `REJECT` e `BLOCK`. [moderation-decisions.ts](./src/lib/moderation-decisions.ts) centraliza apenas apresentação; a disponibilidade efetiva vem do produto retornado pela API:

- `actions` indica as ações executáveis pelo chamador.
- `blockedActions` explica ações aplicáveis ao estado, mas impedidas por alguma regra.
- A função compartilhada `actionState` consulta esses campos. A tela exibe ações permitidas e ações bloqueadas com a razão traduzida; ações desconhecidas não são inventadas pela UI.

Isso mantém validações de publicação no backend. Por exemplo, o contrato descreve aprovação sujeita à oferta em todos os SKUs e à aprovação KYC/KYB do lojista. O detalhe também apresenta `publishingIssues` quando fornecido. A consulta de elegibilidade em `/stores/{storeId}/eligibility` só é iniciada se a sessão tiver `READ_SELLER_ELIGIBILITY`.

### Motivos

- Motivos pertencem ao catálogo do tenant. Só motivos ativos e associados à decisão atual podem ser enviados numa nova decisão.
- Um motivo pode atender `REJECT`, `BLOCK` ou ambas. `APPROVE` não aparece como decisão configurável no formulário.
- O código é estável e não editável após criação; duplicidade é recusada pelo servidor. Ao receber o erro de código duplicado, a tela mostra os inativos para que um motivo existente possa ser encontrado/reativado.
- Desativar não apaga o motivo nem o histórico: ele deixa de ser oferecido e não vale para novas decisões. As decisões passadas preservam o rótulo da época.
- Posição ordena a lista; no empate, o código desempata. Na criação, posição é opcional e ausência coloca o motivo no fim.

Os limites e formatos são derivados dos schemas OpenAPI por `fieldRules`, e não mantidos como constantes concorrentes: código kebab-case até 60 caracteres; rótulo não vazio até 120; uma ou duas decisões; posição entre 0 e 9999. O schema de revisão exige posição, rótulo e decisões.

## Formulários e interações

### Decidir produto

`APPROVE` no detalhe é enviado diretamente, sem modal de confirmação. `REJECT` e `BLOCK` abrem o modal compartilhado. O modal busca opções com `GET /moderation/reasons?decision=...`, filtra novamente por código, atividade e decisão, e não mantém um catálogo local.

O moderador pode marcar um ou mais motivos e informar um complemento. Sem motivo selecionado, o complemento se torna obrigatório; com motivo, é opcional. O limite do campo vem de `DecisionRequestSchema` (500 caracteres) e é mostrado por contador. A confirmação envia somente os códigos ainda presentes nas opções atuais, evitando submeter uma seleção que tenha ficado obsoleta após recarregar a lista.

### Manter motivos

Na criação, o formulário envia código, rótulo, decisões e posição quando preenchida. Na edição, carrega o registro por código, deixa o código somente leitura e envia apenas rótulo, decisões e posição. Validações locais usam `schemaValidator`; erros de campo devolvidos pelo servidor são associados aos controles com `applyServerErrors`.

Ativar e desativar são ações separadas da edição. Desativar pede confirmação; reativar é acionado pelo menu contextual. Ambas atualizam a lista após sucesso. A listagem busca também inativos desde o início (`includeInactive=true`), mas começa mostrando apenas ativos; o toggle filtra localmente a resposta.

## Fluxos principais da feature

### Fila, detalhe e decisão individual

1. `ModerationQueueStore` observa `limit`/`cursor` da URL e carrega `GET /moderation/products`. Um contador de requisição impede que uma resposta antiga sobrescreva a página de uma navegação mais recente.
2. O usuário abre `/moderacao/fila/:id`; o detalhe busca o produto e, a partir dele, categoria, histórico e elegibilidade autorizada.
3. A UI apresenta as decisões retornadas como permitidas/bloqueadas. Aprovar envia a versão corrente; rejeitar/bloquear recolhe motivos/complemento e envia a mesma versão.
4. No sucesso, mostra toast, recarrega a fila e o badge. Se o produto decidido era o detalhe aberto, abre o próximo produto na ordem anterior; se ele não estiver mais disponível, abre o primeiro restante; se não houver itens, volta à lista. Se a pessoa já saiu da tela, o store não força navegação.
5. Em erro, o problema é normalizado para `Problem` e aparece no detalhe/modal. Conflitos oferecem recarga explícita da versão atual; não há sobrescrita automática.

### Decisão em lote

1. A barra de lote apresenta contagens por ação, considerando apenas produtos cuja lista `actions` permite aquela ação.
2. Aprovar abre confirmação; rejeitar/bloquear abre o modal de motivos. Para itens selecionados que não aceitam a decisão, a execução os exclui e informa a contagem ignorada.
3. `runBatch` divide a seleção em grupos do tamanho permitido pelo schema (`maxItems`, atualmente 100) e envia um `POST /moderation/products/batch` por grupo. Rejeição/bloqueio aplicam os mesmos motivos e complemento a todos os itens daquele lote; aprovação não envia motivo.
4. Cada item inclui `id` e `version`. Respostas individuais alimentam sucesso/falha por produto; uma falha de transporte interrompe os grupos seguintes e deixa itens sem resposta como não processados.
5. A interface mostra resumo/toast, mantém visíveis os itens que falharam e remove da seleção os que tiveram sucesso. Se houve sucesso parcial, recarrega a fila e sincroniza o detalhe aberto.

### Administração do catálogo de motivos

1. A página carrega motivos ativos e inativos; o filtro local decide quais linhas exibir.
2. Novo abre o formulário vazio. Editar carrega o registro e sua versão. Salvar chama criação ou revisão, fecha o offcanvas, mostra toast e recarrega a lista.
3. Desativar confirma antes de gravar; reativar grava diretamente pelo menu. Ambas enviam a versão esperada e recarregam a lista.
4. Em conflito, a interface permite carregar a lista/registro atual antes de tentar novamente; não aplica alterações sobre uma versão silenciosamente desatualizada.

## Integração com API e contratos

As operações usam funções e resources gerados no data-access, derivados de [contracts/catalog-inventory/openapi.json](../../../../contracts/catalog-inventory/openapi.json). `apiData`/`apiDataWithVersion` concentram a leitura das respostas e metadados; os tipos de request/response não são redefinidos pela feature.

| Operação | Endpoint | Uso |
|---|---|---|
| Consultar fila | `GET /moderation/products?cursor=&limit=` | Página e próximo cursor; ordenação de chegada é do servidor. |
| Decidir lote | `POST /moderation/products/batch` | Ação, itens `{id, version}` e, para rejeitar/bloquear, motivos e complemento. Até 100 itens por chamada conforme schema. |
| Aprovar produto | `POST /moderation/products/{id}/approve` | Decisão individual com `If-Match`; sem corpo de decisão. |
| Rejeitar/bloquear produto | `POST /moderation/products/{id}/reject` ou `/block` | `If-Match` e corpo de `DecisionRequest`. |
| Ler produto/categoria/histórico | `GET /products/{id}`, `GET /categories/{id}`, `GET /products/{id}/history` | Conteúdo, versão, SKUs e ações; metadados de atributos efetivos/categoria; transições e decisões anteriores. |
| Consultar elegibilidade | `GET /stores/{storeId}/eligibility` | Só quando a sessão tem `READ_SELLER_ELIGIBILITY`. |
| Badge da fila | `GET /stats/moderation/queue` | Lê `count` para o badge do menu. A leitura vale por 60 s e é compartilhada com o menu (`NavBadgeService.refresh`); depois de uma decisão, é refeita mesmo dentro da janela. |
| Consultar motivos | `GET /moderation/reasons?decision=...` ou `?includeInactive=true` | Opções aplicáveis ao modal ou catálogo administrativo completo. |
| Criar/ler/revisar motivo | `POST /moderation/reasons`, `GET /moderation/reasons/{code}`, `PUT /moderation/reasons/{code}` | Criação; leitura com versão; revisão condicionada por `If-Match`. |
| Ativar/desativar motivo | `POST /moderation/reasons/{code}/activate` ou `/deactivate` | Transição de atividade condicionada por `If-Match`; não há exclusão física na UI. |

### Versão, conflitos e erros

O detalhe usa o ETag da resposta de `GET /products/{id}` como `If-Match`, com fallback para a versão do corpo quando necessário. Enquanto a leitura corrente não resolveu ou não tem versão, a decisão não é enviada. No lote, a versão vai no item do corpo, com a semântica equivalente à checagem `If-Match`.

Para editar motivo, `apiDataWithVersion` captura o ETag do `GET /moderation/reasons/{code}`; ativar/desativar usam a versão da listagem convertida para `If-Match`. Criação não exige versão.

Falhas HTTP são convertidas para o modelo `Problem` e exibidas em alertas ou erros de campo. Conflitos são reconhecidos por `isEditConflict` e oferecem uma ação explícita para recarregar. Se o servidor recusa um código de motivo que deixou de ser válido, o modal recarrega as opções; para duplicidade no cadastro, a lista inclui inativos. Falha ao consultar as estatísticas do badge é deliberadamente best-effort: mantém o valor anterior, pois o badge é informativo e não bloqueia a fila.

## Sincronização entre partes da tela

- **URL → store:** `toSignal` observa query params e Router; um `effect` recarrega a fila ao mudar cursor/tamanho. Paginação navega via Router e preserva o detalhe.
- **Store → lista/detalhe:** sinais do store alimentam a tabela, seleção e item aberto. A rota filha fornece o `id`; o detalhe injeta opcionalmente o mesmo store e chama `afterDecision` após sucesso.
- **Lista → detalhe após decisão:** `afterDecision` remove decididos da seleção, recarrega a página e escolhe o próximo produto de forma determinística; mantém o contexto de navegação e força uma nova leitura do badge, sem esperar a janela de 60 s.
- **Seleção → lote:** `ListSelection` mantém snapshots dos itens selecionados entre cursores com o mesmo `limit`; mudar o tamanho da página troca o escopo e limpa a seleção. A elegibilidade por ação deriva de `actions`.
- **Modal/formulário → página:** eventos `confirm`, `closed`, `reload`, `saved` e `duplicate` mantêm os componentes reutilizáveis e deixam execução, toast e refresh sob responsabilidade da página proprietária.

## Padrões arquiteturais importantes

- **Lazy loading por domínio:** o app carrega `routes` ou `reasonRoutes` via `loadChildren`; a lib não força o carregamento antecipado da tela administrativa.
- **Estado reativo local:** Signals guardam estado mutável; `computed` deriva interface/validação; `effect` conecta URL, recursos e efeitos de recarga. `OnPush` reduz atualizações implícitas.
- **Resources e cliente gerado:** leituras de detalhe/categoria/histórico/eligibilidade e listas de motivos usam resources do data-access; mutações usam operações tipadas geradas. A camada feature orquestra UX, não serialização HTTP.
- **Domínio decidido pelo backend:** permissões para decidir, razões de bloqueio, ordem da fila e elegibilidade são fornecidas pelo servidor. Helpers locais formatam ou traduzem dados, sem substituir essas políticas.
- **Concorrência otimista:** ETag/`If-Match` protege alterações de produto e motivo; conflitos são apresentados para revisão humana.
- **Reuso com propriedade clara do estado:** o modal de decisão atende ações individuais e em lote; formulário e componentes de estado compartilhados são reutilizados, enquanto cada página mantém seus próprios erros e operações.

## Resumo executivo

A lib implementa o fluxo administrativo completo de moderação: localizar produtos em fila, inspecionar contexto suficiente para decidir, aplicar decisões individuais ou em lote e manter o catálogo tenant-scoped de motivos. A separação entre rotas lazy, store de fila, componentes de tela, helpers de apresentação e contratos gerados mantém o estado compartilhado restrito ao que lista e detalhe realmente precisam. Regras de elegibilidade e concorrência permanecem no backend, enquanto a UI oferece validação alinhada aos schemas, feedback de erros e recuperação explícita de conflitos.

