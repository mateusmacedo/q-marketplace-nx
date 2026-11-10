# Feature de produtos do catálogo

Documentação de manutenção da biblioteca Nx `catalog-inventory-admin-feature-products`. A lib implementa a experiência administrativa do catálogo de produtos: listagem e ações da loja, edição por abas, gerenciamento de SKUs/ofertas/estoque, consulta de histórico, visão de produtos entre lojas e widgets do painel inicial.

## Visão geral

A feature cobre dois contextos relacionados, mas com permissões e telas distintas:

- **Catálogo da loja** (`/loja/produtos`): mantém os produtos associados à loja da sessão; permite criar rascunhos, editar conteúdo e SKUs, enviar à moderação, pausar/reativar e arquivar.
- **Catálogo de plataforma** (`/plataforma/produtos`): permite consultar produtos de diferentes lojas, filtrar por ID da loja e executar as ações liberadas pelo backend para esse contexto, incluindo desbloqueio.
- **Painel inicial** (`/inicio`): apresenta estatísticas, indicadores e atividade adequados à capacidade administrativa da sessão.

O contrato de negócio é orientado pelo servidor. A interface não infere transições apenas pelo status: usa `actions`, `blockedActions` e `saveReturnsToModeration` recebidos no produto para definir operações disponíveis, motivos de bloqueio e efeitos de edição.

## Estrutura da feature/lib

A biblioteca é uma lib Angular standalone classificada como camada `feature` no Nx (`scope:catalog-inventory`, `layer:feature`, `framework:angular`, `lang:ts` em [project.json](./project.json)). O ponto público [src/index.ts](./src/index.ts) exporta as rotas, as regras/apresentações de ações e os componentes/helpers do painel inicial; as telas internas de produto são carregadas pelas rotas, não exportadas individualmente.

| Área                         | Arquivos principais                                                                    | Responsabilidade                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ---------------------------- | -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Entrada e rotas              | [lib.routes.ts](./src/lib/lib.routes.ts), [index.ts](./src/index.ts)                   | Define `routes` e `platformRoutes`; o índice expõe a API pública da lib.                                                                                                                                                                                                                                                                                                                                                                                          |
| Regras de ações              | [product-actions.ts](./src/lib/product-actions.ts)                                     | Centraliza apresentações, locais de exibição, confirmações, permissões informadas pelo servidor e regras derivadas para edição.                                                                                                                                                                                                                                                                                                                                   |
| Listagem                     | `product-list/`                                                                        | [product-list-page.component.ts](./src/lib/product-list/product-list-page.component.ts) compõe a tela e as ações; [product-list-query.ts](./src/lib/product-list/product-list-query.ts) interpreta/serializa o estado de busca; [product-list-route.ts](./src/lib/product-list/product-list-route.ts) sincroniza-o com a URL; filtros, tabela e executor de ações ficam em componentes/serviços próprios.                                                         |
| Criação                      | [new-product-modal.component.ts](./src/lib/new-product/new-product-modal.component.ts) | Formulário de criação de rascunho e carregamento paginado da árvore de categorias utilizáveis.                                                                                                                                                                                                                                                                                                                                                                    |
| Detalhe                      | `product/`                                                                             | [product-page.component.ts](./src/lib/product/product-page.component.ts) compõe abas e checklist; [product-store.ts](./src/lib/product/product-store.ts) é o estado compartilhado do detalhe; componentes separados implementam cabeçalho, conteúdo, SKUs e histórico.                                                                                                                                                                                            |
| Regras de domínio do produto | `product/`                                                                             | [product-attributes.ts](./src/lib/product/product-attributes.ts), [product-categories.ts](./src/lib/product/product-categories.ts), [product-draft-check.ts](./src/lib/product/product-draft-check.ts), [product-history.ts](./src/lib/product/product-history.ts), [product-tabs.ts](./src/lib/product/product-tabs.ts) e [product-menu.ts](./src/lib/product/product-menu.ts) contêm transformações e lógica de apoio reutilizável.                             |
| SKU, oferta e estoque        | `sku-panel/` e `product/`                                                              | [product-skus-tab.component.ts](./src/lib/product/product-skus-tab.component.ts) lista e administra SKUs; [add-sku-offcanvas.component.ts](./src/lib/product/add-sku-offcanvas.component.ts) cria um SKU; [sku-panel.component.ts](./src/lib/sku-panel/sku-panel.component.ts) reúne [sku-offer-section.component.ts](./src/lib/sku-panel/sku-offer-section.component.ts) e [sku-stock-section.component.ts](./src/lib/sku-panel/sku-stock-section.component.ts). |
| Visão de plataforma          | `platform/`                                                                            | [platform-product-list-page.component.ts](./src/lib/platform/platform-product-list-page.component.ts) e [platform-product-page.component.ts](./src/lib/platform/platform-product-page.component.ts) implementam a consulta transversal, separada do fluxo da loja.                                                                                                                                                                                                |
| Painel inicial               | `home/`                                                                                | [home-dashboard.component.ts](./src/lib/home/home-dashboard.component.ts), widgets, estatísticas, gráficos, indicadores, atividade e produtos recentes.                                                                                                                                                                                                                                                                                                           |

A integração tipada com o backend está em [data-access](../data-access/src/generated/index.ts) e no SDK gerado [sdk.gen.ts](../data-access/src/generated/sdk.gen.ts); os componentes também reutilizam `@marketplace/shared-admin-ui` e `@marketplace/shared-web-util` para sessão, estados de tela, notificações, validação, erros e controle de versão.

## Roteamento e contexto

O app raiz aplica `authGuard` ao layout administrativo e carrega as rotas da feature sob demanda em [app.routes.ts](../../../../apps/admin-web/src/app/app.routes.ts):

- **Loja**: `/loja/produtos` carrega `routes` e exige `MANAGE_OWN_PRODUCTS` no `canMatch` da seção `/loja`.
- **Detalhe da loja**: `/loja/produtos/:id`; a rota sem aba redireciona para `conteudo`. As rotas filhas são `conteudo`, `skus` e `historico`. A listagem usa `leaveGuard` para alertar durante a execução de um lote; conteúdo usa a proteção para evitar perder alterações não salvas.
- **Plataforma**: `/plataforma/produtos` carrega `platformRoutes` e exige a permissão `readTenantCatalog`; possui listagem e rota `:id`, sem reutilizar a árvore de abas da loja.
- **Painel inicial**: `/inicio` delega o carregamento de `HomeDashboardComponent` à tela inicial do app.

A listagem usa a query string como estado persistente: `status`, `q`, `sort`, `limit`, `cursor` e, na plataforma, `storeId`. `injectProductListRoute()` lê `queryParamMap` como signal e navega preservando os parâmetros não alterados; uma nova busca/filtro reinicia o cursor. A busca é limitada a 100 caracteres, a ordenação aceita `title`, `createdAt` ou `updatedAt` com prefixo `-` para ordem inversa, e o padrão é `-updatedAt`. Valores de status/ordenação inválidos são normalizados antes de montar a requisição.

## Componentes principais

- **`ProductListPageComponent`**: liga query string, `listProductsResource`, filtros, tabela, seleção, modal de criação, alertas, ações individuais e ações em lote. Também lê a elegibilidade da loja e mostra o aviso quando `canPublish === false`.
- **`ProductListFiltersComponent` / `ProductTableComponent`**: representam busca/filtros e linhas do catálogo. A tabela mostra estado e menu por produto; a seleção alimenta somente as ações em lote aplicáveis.
- **`ProductPageComponent`**: shell do detalhe. Mantém cabeçalho, navegação por abas, `RouterOutlet` e checklist de publicação; mostra estados de carregamento/erro com possibilidade de tentar novamente.
- **`ProductHeaderComponent`**: apresenta ações do produto e pede confirmação quando a ação exige; executa a transição e trata recusas de publicação e conflitos de versão.
- **`ProductContentTabComponent`**: monta formulário de conteúdo e atributos de acordo com a categoria, pré-visualiza a troca de categoria, mantém o rascunho e salva alterações.
- **`ProductSkusTabComponent` / `AddSkuOffcanvasComponent`**: listam, adicionam e removem SKUs; a aba abre o painel de preço e estoque para o SKU selecionado.
- **`SkuPanelComponent`**: coordena as seções independentes de oferta/preço/promoções e estoque. Cada seção controla sua leitura, validação, versão e erros.
- **`ProductHistoryTabComponent`**: consulta transições e decisões de moderação e converte códigos/observações em apresentação legível.
- **`CategoryChangeModalComponent` e `SubmissionChecklistComponent`**: suportam confirmação/prévia de mudanças de categoria e navegação até pendências de publicação.
- **`HomeDashboardComponent`**: escolhe o conjunto de estatísticas adequado às capacidades da sessão e compõe indicadores, gráficos, atividade e produtos recentes.
- **Telas de plataforma**: reutilizam os contratos de produto e ações, mas mantêm seleção de loja e estado próprios.

## Regras de negócio e domínio

### Status e ações

Os estados exibidos são centralizados em `PRODUCT_STATUSES` no pacote de UI: `DRAFT`, `PENDING_MODERATION`, `ACTIVE`, `OUT_OF_STOCK`, `PAUSED_BY_SELLER`, `BLOCKED` e `ARCHIVED`. O status fornece rótulo/apresentação; a disponibilidade real de cada operação vem das listas `actions` e `blockedActions` do servidor. `product-actions.ts` converte essas listas em itens de menu, motivos de bloqueio, confirmações e regras de edição, evitando que cada tela implemente sua própria máquina de estados.

As ações comuns são enviar para moderação, pausar, reativar e arquivar. `EDIT` abre o detalhe a partir da listagem; `UNBLOCK` aparece no detalhe/plataforma. Arquivar é apresentado como ação irreversível e remove o produto da vitrine para consulta; embora o contrato use `DELETE /products/{id}`, a operação de UI é arquivamento, não uma exclusão genérica de produto. A elegibilidade da loja (`GET /stores/{storeId}/eligibility`) é usada para o aviso de publicação; as ações efetivamente disponíveis continuam sendo as declaradas pelo backend.

### Categoria, conteúdo e hierarquia

A categoria é uma árvore vinda do backend. Na criação, a lib carrega todas as páginas de `/categories/tree` com `usable=true`. No detalhe, os metadados da categoria definem quais atributos dinâmicos são exibidos e validados. O formulário só grava depois de montar os controles correspondentes aos campos da categoria: o `PUT /products/{id}` substitui o mapa completo de atributos, então enviar um mapa incompleto apagaria dados existentes.

A troca de categoria é uma edição pendente do formulário, não uma gravação imediata. O endpoint de verificação devolve a prévia e os atributos removidos; os valores de atributos que continuam disponíveis são carregados para a nova categoria e o usuário pode desfazer a troca antes de salvar. A relação hierárquica do domínio é categoria-pai/subcategoria e produto/SKUs; esta lib não implementa uma hierarquia de produtos pai/filho.

### Moderação, integridade e lotes

A criação inicia em rascunho. Conteúdo publicado pode voltar à moderação ao ser alterado: `saveReturnsToModeration` ativa uma confirmação antes de salvar conteúdo ou adicionar SKU e ajusta a mensagem de sucesso. A submissão pode ser recusada pelo servidor com `422`; as pendências retornadas são mostradas no checklist até o produto ou o rascunho mudar.

Ações em lote são limitadas às ações declaradas para lote. A seleção é filtrada pela permissão de cada produto e a capacidade máxima de itens por chamada vem de `ProductBatchRequestSchema` (o contrato atual permite até 100). Cada item inclui `id` e `version`; o resultado é independente por item, portanto o lote pode ter sucessos e falhas simultaneamente.

## Formulários e interações

- **Criação**: o modal desabilita o seletor enquanto percorre todas as páginas de categorias utilizáveis. Valida categoria, título e moeda, remove espaços do título e cria o produto como rascunho. Se a resposta trouxer o ID, fecha o modal e abre `/conteudo`; se não, atualiza a listagem.
- **Conteúdo**: formulário reativo com campos comuns e atributos dinâmicos; compara o valor com a versão carregada para detectar alterações, marca campos tocados quando inválido e aplica erros de validação do servidor aos controles correspondentes. Alterações pendentes são protegidas contra navegação e fechamento da página.
- **Checklist**: verifica o produto salvo ou o rascunho de conteúdo via `POST /products/{id}/check`. A checagem do rascunho é reativa e com debounce. Selecionar uma pendência navega para a aba apropriada e solicita foco no campo indicado.
- **SKU**: o formulário de adição envia SKU do vendedor, atributos de variação e indicação de item físico; dimensões são enviadas quando físicas e completas. Remoção exige confirmação. Alterações em SKUs respeitam a versão do produto.
- **Preço, promoção e estoque**: preço e estoque têm validação própria; promoções exigem preço e intervalo de datas. Cancelar promoção pede confirmação. Cada seção exibe falhas de leitura/gravação sem ocultar erros não associados a campos.
- **Ações destrutivas ou com efeito de fluxo**: arquivamento e operações marcadas com confirmação passam por modal/diálogo antes da chamada; ações em execução bloqueiam repetição e mostram estado de processamento.

## Fluxos principais da feature

1. **Consultar produtos da loja**: os filtros são lidos da URL; a resource de listagem solicita a página corrente. Busca, ordenação, status e cursor podem ser compartilhados/recarregados pela URL, e a próxima página é controlada pelo cursor devolvido pela API.
2. **Criar produto**: abrir modal → carregar árvore de categorias utilizáveis → validar e enviar `POST /products` → exibir confirmação → navegar ao detalhe do novo rascunho ou recarregar a listagem.
3. **Editar e publicar**: abrir `/:id` → carregar produto e versão → montar campos da categoria → editar/verificar rascunho → salvar com `If-Match` → aceitar a resposta como estado atual. A ação `SUBMIT` executa o endpoint de submissão; uma recusa `422` alimenta o checklist.
4. **Trocar categoria**: solicitar categorias e verificação do rascunho → apresentar o efeito/atributos removidos → manter a escolha como alteração local → permitir desfazer → salvar o conteúdo completo com a versão atual.
5. **Gerenciar SKU**: adicionar/remover SKU usando a versão do produto → atualizar o `ProductStore` ou recarregá-lo → abrir o painel de oferta/estoque. Preço, estoque e promoção são gravações separadas, cada uma atualizando sua própria versão.
6. **Executar ação individual**: selecionar ação do menu → confirmar quando necessário → enviar com a versão lida → toast ou erro específico. Em conflito, a interface oferece carregar a versão atual; após sucesso, a listagem é recarregada.
7. **Executar lote**: contar itens elegíveis → confirmar se necessário → enviar grupos sequenciais até o limite do contrato → consolidar sucessos, falhas e itens sem resposta → notificar o resumo e atualizar a listagem. A interrupção/rejeição de uma chamada não transforma itens restantes em sucesso.
8. **Consultar plataforma/painel**: na plataforma, validar o ID da loja antes de gravá-lo na query string e consultar o catálogo transversal; no painel inicial, selecionar estatísticas de loja, moderação ou categorias conforme a capacidade disponível.

## Integração com API e contratos

O SDK gerado em `@marketplace/catalog-inventory-admin-data-access` usa base URL `/api/catalog-inventory` ([client-config.ts](../data-access/src/client-config.ts)) e declara segurança HTTP bearer nas operações. `provideCatalogInventoryApi()` registra o cliente compartilhado; a lib usa funções geradas e wrappers comuns (`apiData`, `apiDataWithVersion`, `toProblem`) em vez de montar URLs manualmente.

| Método e caminho (relativos à base URL)                                               | Uso na feature                                                                                                   |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `GET /products`                                                                       | Listagem paginada, busca, status, ordenação e filtro opcional de loja.                                           |
| `POST /products`                                                                      | Criação de produto em rascunho.                                                                                  |
| `GET /products/{id}` / `PUT /products/{id}`                                           | Leitura e substituição do conteúdo, incluindo categoria e mapa de atributos. O `PUT` exige versão.               |
| `DELETE /products/{id}`                                                               | Arquivamento exposto pela UI como ação irreversível.                                                             |
| `POST /products/{id}/check`                                                           | Verificação sem gravação das pendências e da prévia de troca de categoria.                                       |
| `GET /products/{id}/history`                                                          | Histórico de transições e decisões de moderação.                                                                 |
| `POST /products/{id}/submit`, `/pause`, `/resume`                                     | Transições de estado liberadas pelo produto.                                                                     |
| `POST /moderation/products/{id}/unblock`                                              | Desbloqueio disponível nos contextos de detalhe/plataforma.                                                      |
| `POST /products/batch`                                                                | Ações em lote; cada item contém ID/versão e recebe resultado próprio.                                            |
| `POST /products/{id}/skus` / `DELETE /products/{id}/skus/{skuId}`                     | Inclusão e remoção de SKU associado ao produto.                                                                  |
| `GET /inventory/skus/{skuId}` / `PUT /inventory/skus/{skuId}`                         | Leitura e ajuste versionado do estoque.                                                                          |
| `GET /offers/{skuId}` / `PUT /offers/{skuId}`                                         | Leitura e gravação do preço/oferta; a primeira gravação cria a oferta e as seguintes usam `If-Match`.            |
| `POST /offers/{skuId}/promotions` / `DELETE /offers/{skuId}/promotions/{promotionId}` | Agendamento e cancelamento versionado de promoções.                                                              |
| `GET /categories/tree`                                                                | Árvore paginada, até 1000 itens por página; criação e troca de categoria filtram por `usable=true`.              |
| `GET /categories/{id}`                                                                | Atributos efetivos da categoria, que montam os campos dinâmicos do conteúdo e os atributos de variação dos SKUs. |
| `GET /categories?ids=`                                                                | Nomes das categorias dos produtos atualizados por último, numa chamada só com os ids distintos da página.        |
| `GET /stores/{storeId}/eligibility`                                                   | Elegibilidade da loja para publicação.                                                                           |
| `GET /stats/store`, `/stats/moderation`, `/stats/categories`                          | Dados do painel inicial selecionados por capacidade; loja/moderação recebem granularidade de atividade.          |

### Versões, concorrência e erros

Leituras/gravações que precisam de controle de concorrência preservam a versão da resposta: `apiDataWithVersion` extrai o `ETag` e `ProductStore` usa-o, com fallback para a versão do corpo. `ifMatch()` omite o header quando não há versão; ações da listagem usam a versão da linha. Estoque e oferta mantêm versões independentes, pois pertencem a recursos distintos. No lote, a versão segue em cada item do body, não em um único header para a requisição inteira.

O helper compartilhado identifica conflito por HTTP `412` ou pelos códigos `version-mismatch`/`concurrent-update`. A tela não tenta sobrescrever silenciosamente: oferece recarregar a versão atual, descartando o rascunho local quando essa opção é escolhida. Erros são normalizados para `Problem`; erros `400` associados a campos são vinculados aos controles e os demais permanecem visíveis como erro geral. Falhas de leitura exibem estado com retry; erros de rede, falhas por item e rejeições `422` de submissão têm apresentação própria.

## Sincronização entre partes da tela

- `ProductStore` é provido no escopo de `ProductPageComponent`, portanto cabeçalho, abas e checklist injetam a mesma instância sem estado global entre produtos. Ele guarda produto, versão, loading/erro, rascunho não salvo, foco do checklist, recusa de submissão e sinal de descarte.
- A rota `:id` alimenta o store por `effect`; um contador de requisição impede que uma leitura antiga sobrescreva uma gravação ou uma leitura posterior. `accept()` aplica a resposta de escrita, atualiza a versão e invalida leituras iniciadas antes dela.
- A aba de conteúdo publica o rascunho no store; o checklist observa esse signal e troca entre pendências do produto salvo e do rascunho. Mudanças no produto/rascunho limpam uma recusa `422`; navegação para uma pendência entrega o foco à aba/campo correspondente.
- A lista deriva query/state de `queryParamMap`; mudanças de URL reexecutam a resource. Ações individuais e em lote recarregam a listagem após as gravações.
- O painel de SKU mantém leitura/versão próprias para oferta e estoque e notifica a aba quando uma gravação afeta o resumo. Assim, atualização de preço/estoque não depende de um cache global de produtos.

## Padrões arquiteturais importantes

- **Rotas e componentes standalone sob demanda**: a lib entrega arrays de rotas e o app os carrega com `loadChildren`; componentes de tela são carregados com `loadComponent`.
- **Camadas explícitas**: componentes coordenam UI e interação; funções puras concentram transformação de query, atributos, categorias, histórico, estatísticas e ações; o SDK gerado concentra contratos HTTP.
- **Angular Signals**: `signal`, `computed`, `linkedSignal`, `effect` e `toSignal` mantêm estado local e derivado explícito. `httpResource` é usado para listagem, checagem do rascunho e estatísticas reativas; gravações assíncronas passam pelos wrappers do SDK.
- **Regra de negócio centralizada e orientada pelo servidor**: tabela de ações e `productEditRules` evitam discrepâncias entre listagem, detalhe e plataforma; schemas do data-access fornecem limites/validações reutilizados pela UI.
- **Concorrência otimista**: `ETag`/`If-Match` e tratamento de conflito protegem gravações concorrentes, inclusive quando várias áreas da página editam recursos diferentes.
- **Erros e UX consistentes**: `Problem`, validação de campo, estados de carregamento/erro, retry, toast e confirmação são reutilizados dos pacotes compartilhados.

## Resumo executivo

Esta lib entrega o ciclo de manutenção do catálogo, desde criação de rascunho até moderação, publicação, pausa, reativação e arquivamento, além do gerenciamento separado de SKUs, preço, promoções e estoque. A implementação combina rotas lazy, componentes focados, helpers de domínio puros, estado de detalhe scoped por DI e SDK gerado a partir de contratos. A URL preserva o contexto da listagem, o backend é a fonte de verdade das transições permitidas e `If-Match`/ETag evita sobrescritas concorrentes; esses limites deixam os fluxos consistentes entre loja, plataforma e painel inicial.
