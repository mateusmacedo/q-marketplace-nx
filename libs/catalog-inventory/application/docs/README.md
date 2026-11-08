# Aplicação do catálogo-inventário

Módulo `catalog-inventory-application` (`libs/catalog-inventory/application`). Visão geral do contexto em [docs/catalog-inventory.md](../../../../docs/catalog-inventory.md). As ADRs citadas estão em [decisoes.md](../../../../docs/decisoes.md).

## Sumário

- [Propósito e fronteira](#propósito-e-fronteira)
- [Diagramas](#diagramas)
- [Casos de uso por serviço](#casos-de-uso-por-serviço)
- [Modelo de acesso](#modelo-de-acesso)
- [Concorrência otimista](#concorrência-otimista)
- [Portas](#portas)
- [Paginação](#paginação)
- [Idempotência das reservas](#idempotência-das-reservas)
- [Importação CSV](#importação-csv)
- [Exceções da aplicação](#exceções-da-aplicação)
- [Configuração recebida](#configuração-recebida)
- [Testes](#testes)
- [Divergências e nuances](#divergências-e-nuances)

## Propósito e fronteira

A aplicação orquestra os casos de uso do contexto: carrega agregados pelas portas, aplica as regras de acesso do chamador, delega as invariantes ao domínio e grava com concorrência otimista. Não conhece HTTP, SQL, Kafka nem Redis.

| Camada | Responsabilidade | Exemplo |
|---|---|---|
| Domínio ([README](../../domain/docs/README.md)) | Invariantes, máquinas de estado, políticas e eventos | `Product.submit`, `ReservationPolicy.open` |
| Aplicação (este módulo) | Casos de uso, portas, `Actor`/`Capability`, visibilidade por loja, If-Match, paginação, idempotência da reserva, importação | `ProductService.submit`, `ReservationService.reserve` |
| Serviço ([README](../../../../apps/catalog-inventory-service/docs/README.md)) | Adaptadores: API, `Pg*`, outbox, Kafka, cache, agendadores, tradução de papéis e erros | `ProductResource`, `PgProductRepository` |

- **Dependências:** domínio do contexto, BOM do Quarkus e `io.smallrye.reactive:mutiny` ([build.gradle](../build.gradle)). Não há outro framework.
- **Mutiny:** portas e casos de uso devolvem `Uni`. A ADR 002 deixa a aplicação sem framework, a ADR 009 permite compor I/O com Mutiny, e o verificador da ADR 010 libera na aplicação só `io.smallrye.mutiny` (exceto `io.smallrye.mutiny.vertx`).
- **Pacote:** tudo fica em `dev.marketplace.cataloginventory.application`. `ProductSupport`, `ProductAccess` e `Versions` são de pacote privado.
- **Montagem:** o serviço instancia os casos de uso em [`CatalogBeans`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/CatalogBeans.java), como `@Singleton`, porque as classes são `final` e não aceitam proxy.

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas). Para renderizar, veja o comando em [docs/catalog-inventory.md](../../../../docs/catalog-inventory.md#diagramas).

| Diagrama | O que mostra e como ler |
|---|---|
| [casos-de-uso.puml](diagramas/casos-de-uso.puml) | Atores (seller, moderador, admin, comprador, checkout/OMS, worker, KYB/KYC) e as operações de cada serviço. `<<include>>` indica o que a importação reaproveita. |
| [portas-e-casos-de-uso.puml](diagramas/portas-e-casos-de-uso.puml) | Serviços → `ProductSupport`/`ProductAccess` → portas `<<interface>>`. O pacote tracejado é o serviço: `Pg*`, decorators de invalidação e `TaxonomyCache.over()`. |
| [sequencia-reserva-idempotente.puml](diagramas/sequencia-reserva-idempotente.puml) | `reserve` em quatro casos: chave nova, replay idêntico, chave reutilizada com outro corpo e corrida entre duas chamadas. |
| [estados-importacao.puml](diagramas/estados-importacao.puml) | `ImportStatus`: `PENDING`, `PROCESSING` (com renovação e retomada após o lease) e `COMPLETED`. |
| [atividade-importacao.puml](diagramas/atividade-importacao.puml) | `process`: plano por unidade (SKU existente, grupo novo, rejeitado), execução, gravação por unidade e retomada. |

## Casos de uso por serviço

Convenções das tabelas:

- **Capacidade:** o que o caso de uso exige do `Actor`. "dono" = `MANAGE_OWN_PRODUCTS` e loja no token igual à do produto; produto de outra loja responde 404.
- **If-Match:** "opcional" = `Optional<Long> ifMatch` vazio dispensa a checagem. A API exige o cabeçalho em algumas rotas; ver [Concorrência otimista](#concorrência-otimista).
- Recusas de negócio vêm do domínio (`DomainRuleViolation`, `InvalidTransitionException`, `ReservationRefusal`); o código é o `type` da resposta RFC 9457.

### `ProductService`

[`ProductService`](../src/main/java/dev/marketplace/cataloginventory/application/ProductService.java): conteúdo, SKUs e publicação dos produtos da própria loja.

| Operação | Capacidade | If-Match | Efeitos | Recusas principais |
|---|---|---|---|---|
| `create` | `MANAGE_OWN_PRODUCTS` + loja no token | — | Rascunho na loja do token, histórico e evento na outbox; sem idempotência | categoria inexistente (404), `category-inactive`, `duplicate-seller-sku` |
| `get` | leitor do tenant, moderador ou dono | — | — | 404 fora do alcance |
| `list` | leitor do tenant ou moderador (filtra por loja); seller vê só a própria | — | Página por `id` | `limit` fora de 1 a 100; filtro `OUT_OF_STOCK` (`InvalidInputException`) |
| `history` | como `get` | — | Mudanças de estado com ator e motivo | 404 |
| `reviseContent` | dono | opcional | Publicado volta a `PENDING_MODERATION` | `invalid-transition` (`BLOCKED`, `ARCHIVED`), `category-inactive`, `invalid-attributes` |
| `addSku` | dono | opcional | Idem | `duplicate-seller-sku`, `invalid-attributes` |
| `reviseSku` | dono | opcional | Idem | `sku-not-found`, `missing-dimensions` |
| `removeSku` | dono | opcional | A oferta do SKU sai junto | `sku-removal-requires-draft`, `sku-has-stock` |
| `submit` | dono | opcional | `DRAFT` → `PENDING_MODERATION` | `product-not-publishable` com `reasons` |
| `pause` | dono | opcional | `ACTIVE` → `PAUSED_BY_SELLER` | `invalid-transition` |
| `resume` | dono | opcional | `PAUSED_BY_SELLER` → `ACTIVE`, sem nova moderação | `seller-not-eligible` |
| `archive` | dono | opcional | Exclusão lógica definitiva; congela edição, oferta e estoque | `invalid-transition` (já arquivado) |

### `ModerationService`

[`ModerationService`](../src/main/java/dev/marketplace/cataloginventory/application/ModerationService.java): todas as operações exigem `MODERATE_PRODUCTS` e alcançam qualquer loja do tenant (`mutateAsModerator`).

| Operação | If-Match | Efeitos | Recusas principais |
|---|---|---|---|
| `queue` | — | Página de `PENDING_MODERATION` de todas as lojas | `limit` fora de 1 a 100 |
| `approve` | opcional | `PENDING_MODERATION` → `ACTIVE`, reavaliando completude e loja | `product-not-publishable`, `seller-not-eligible` |
| `reject` | opcional | `PENDING_MODERATION` → `DRAFT`, motivo no histórico | motivo vazio ou acima de 500 (`invalid-value`) |
| `block` | opcional | Qualquer estado, menos `BLOCKED` e `ARCHIVED`, → `BLOCKED`; evento de bloqueio | `invalid-transition`, motivo inválido |
| `unblock` | opcional | `BLOCKED` → `PENDING_MODERATION` | `invalid-transition` |

### `OfferService`

[`OfferService`](../src/main/java/dev/marketplace/cataloginventory/application/OfferService.java): preço de/por e promoções do SKU. Mudar preço não devolve o produto à moderação nem disputa a versão do produto (ADR 014).

| Operação | Capacidade | If-Match | Efeitos | Recusas principais |
|---|---|---|---|---|
| `get` | como `ProductService.get`, pelo SKU | — | — | 404 (SKU desconhecido, fora do alcance ou sem oferta) |
| `define` | dono, produto não arquivado | criar: proibido; trocar: obrigatório | Cria a oferta na moeda do produto (`created = true`) ou troca o preço | If-Match em oferta inexistente: `version-mismatch`; troca sem If-Match: `precondition-required`; `currency-mismatch`, `list-price-below-sale-price` |
| `schedulePromotion` | dono, não arquivado | opcional | Agenda promoção, evento de preço | `promotion-overlap`, `promotion-above-list-price`, 404 sem oferta |
| `cancelPromotion` | dono, não arquivado | opcional | Cancela promoção pendente ou em vigor | `promotion-not-found`, `promotion-ended` |

Produto arquivado recusa as três escritas com `invalid-transition` (operação `CHANGE_PRICE`).

### `InventoryService`

[`InventoryService`](../src/main/java/dev/marketplace/cataloginventory/application/InventoryService.java): saldo por SKU.

| Operação | Capacidade | If-Match | Efeitos | Recusas principais |
|---|---|---|---|---|
| `get` | como `ProductService.get`, pelo SKU | — | — | 404 (SKU desconhecido, fora do alcance ou sem saldo) |
| `stockOf` | nenhuma: recebe produtos já autorizados | — | Saldos dos SKUs numa leitura | — |
| `adjust` | dono | opcional, sobre a versão do saldo | Físico absoluto, auditoria e evento na outbox | `invalid-value` (fora de 0 a 1.000.000), `invalid-transition` (`ADJUST_STOCK` em arquivado), `stock-below-reserved` |

### `ReservationService`

[`ReservationService`](../src/main/java/dev/marketplace/cataloginventory/application/ReservationService.java): reservas do checkout e do OMS. Todas as operações com chamador exigem `MANAGE_RESERVATIONS`.

| Operação | Efeitos | Recusas principais |
|---|---|---|
| `reserve` | Tudo ou nada, idempotente por chave; ver [Idempotência das reservas](#idempotência-das-reservas) | chave inválida (`InvalidInputException`), `invalid-value` (itens fora de 1 a 100, `ttl` não positivo ou acima de `maxTtl`), `idempotency-key-reused`, `ReservationRefusal` (`duplicate-sku`, `unknown-sku`, `product-unavailable`, `seller-not-eligible`, `price-changed`, `insufficient-stock`, `reservation-refused`) |
| `get` | Lê a reserva do tenant | 404 |
| `commit` | `ACTIVE` dentro do prazo → `COMMITTED`; repetir devolve a já confirmada | `reservation-expired` (ativa vencida), `reservation-not-active`, `concurrent-update` |
| `release` | `ACTIVE` → `RELEASED`; liberar de novo ou após expirar devolve a reserva sem mexer no saldo | `invalid-transition` (`RELEASE` em `COMMITTED`), `concurrent-update` |
| `extend` | Novo prazo = agora + `ttl` (não soma ao atual); sem evento | `invalid-value` (`ttl` nulo, não positivo ou acima de `maxTtl`), `reservation-not-active`, `reservation-expired`, `reservation-lifetime-exceeded` (além de `createdAt + maxLifetime`) |
| `expireDue` | Sem chamador: expira até `batch` vencidas e devolve quantas | — |

`concurrent-update` aparece quando a transição condicional não se aplicou, mas a releitura mostra a reserva ainda `ACTIVE`: em `commit`, dentro do prazo; em `release`, com ou sem prazo vencido.

### `CategoryService`

[`CategoryService`](../src/main/java/dev/marketplace/cataloginventory/application/CategoryService.java): árvore do tenant. `CategoryView` traz a categoria, os atributos efetivos e `usable` (ela e todos os ancestrais ativos).

| Operação | Capacidade | If-Match | Efeitos | Recusas principais |
|---|---|---|---|---|
| `create` | `MANAGE_TAXONOMY` | — | Raiz ou subcategoria | pai inexistente (404), `category-inactive`, `attribute-conflict`, `category-too-deep`, `duplicate-category-code` |
| `get` | nenhuma | — | — | 404 |
| `children` | nenhuma | — | Página das filhas diretas (ou raízes), inclusive inativas, por `code` | pai inexistente (404), `limit` fora de 1 a 100 |
| `revise` | `MANAGE_TAXONOMY` | opcional | Nome, atributos próprios e `active` | `attribute-conflict` (com herdados ou descendentes) |
| `move` | `MANAGE_TAXONOMY` | opcional | Move com a subárvore; para o pai atual não grava; produtos não mudam | pai inexistente (404), `category-cycle`, `category-too-deep`, `attribute-conflict` |

### `StorefrontService`

[`StorefrontService`](../src/main/java/dev/marketplace/cataloginventory/application/StorefrontService.java): `get(tenantId, productId)` sem chamador autenticado. Reavalia a cada chamada o estado do produto e a situação da loja (`isPubliclyVisible`) e devolve o produto com as ofertas indexadas por SKU. Produto inexistente, não visível ou de loja não `APPROVED` responde 404. O cache da vitrine fica no serviço (ADR 024).

### `SellerEligibilityService`

[`SellerEligibilityService`](../src/main/java/dev/marketplace/cataloginventory/application/SellerEligibilityService.java): cópia local da situação KYB/KYC.

| Operação | Capacidade | Efeitos | Recusas |
|---|---|---|---|
| `get` | nenhuma para a própria loja; outra loja exige `READ_SELLER_ELIGIBILITY` | Loja sem veredito é `PENDING` | `AccessDeniedException` |
| `record` | sem chamador (consumidor Kafka) | Inbox do consumidor `catalog-inventory.seller-eligibility`: `APPLIED`, `DUPLICATE` (mesmo `eventId`) ou `STALE` (`decidedAt` mais antigo) | — |

### `ProductImportService`

[`ProductImportService`](../src/main/java/dev/marketplace/cataloginventory/application/ProductImportService.java): ver [Importação CSV](#importação-csv).

| Operação | Capacidade | Efeitos |
|---|---|---|
| `submit` | `MANAGE_OWN_PRODUCTS` + loja no token | Lote `PENDING` com o conteúdo já validado pelo adaptador |
| `get` | idem | Lote e resultados gravados; de outra loja ou tenant, 404 |
| `claimNext` | sem chamador (worker) | Reivindica o lote mais antigo pendente ou parado há mais que o lease |
| `process` | sem chamador; age como o seller que enviou | Processa as linhas sem resultado e conclui o lote |

## Modelo de acesso

| Tipo | Papel |
|---|---|
| [`Actor`](../src/main/java/dev/marketplace/cataloginventory/application/Actor.java) | Chamador: `tenantId`, `storeId` opcional, `subject` e `capabilities`, todos do token validado. `require` e `requireStore` lançam `AccessDeniedException`; `canReadTenantCatalog` = `READ_TENANT_CATALOG` ou `MODERATE_PRODUCTS`. |
| [`Capability`](../src/main/java/dev/marketplace/cataloginventory/application/Capability.java) | `MANAGE_OWN_PRODUCTS`, `MODERATE_PRODUCTS`, `MANAGE_TAXONOMY`, `READ_TENANT_CATALOG`, `READ_SELLER_ELIGIBILITY`, `MANAGE_RESERVATIONS`. O serviço traduz papéis em capacidades (`CallerContext`). |
| [`ProductAccess`](../src/main/java/dev/marketplace/cataloginventory/application/ProductAccess.java) | Carrega o produto por id ou SKU e aplica o alcance. |
| [`ProductSupport`](../src/main/java/dev/marketplace/cataloginventory/application/ProductSupport.java) | Compartilhado por `ProductService` e `ModerationService`: contexto de publicação (categoria e SKUs com oferta), situação da loja e gravação otimista (`mutateOwned`, `mutateAsModerator`). |

Métodos de `ProductAccess`:

| Método | Alcance | Fora do alcance |
|---|---|---|
| `visible`, `visibleBySku` | qualquer loja do tenant para `canReadTenantCatalog`; senão, só a própria loja com `MANAGE_OWN_PRODUCTS` | `NotFoundException` |
| `owned`, `ownedBySku` | só a loja do token; exige `MANAGE_OWN_PRODUCTS` e loja (`AccessDeniedException`, nesta ordem) | `NotFoundException` |
| `ownedBySku(actor, sku, operação)` | como acima, mais `requireNotArchived(operação)` | `invalid-transition` |
| `inTenant` | qualquer produto do tenant, sem checar loja nem capacidade (moderador, vitrine) | `NotFoundException` |
| `listScope` | leitor do tenant usa o filtro de loja (vazio = todas); seller fica na própria | `AccessDeniedException` sem capacidade |

- **Tenant:** toda porta recebe `tenantId`; id de outro tenant se comporta como inexistente.
- **404 fora do alcance:** inexistente e alheio respondem igual (`NotFoundException`), para não revelar que o produto existe.
- **Sem chamador:** `StorefrontService.get`, `SellerEligibilityService.record`, `ReservationService.expireDue`, `claimNext` e `process`. Este reconstrói o seller com `ProductImport.seller()` (só `MANAGE_OWN_PRODUCTS`).

## Concorrência otimista

- [`Versions.requireMatch`](../src/main/java/dev/marketplace/cataloginventory/application/Versions.java): If-Match ausente dispensa; diferente da versão lida lança `VersionMismatchException` (412) com a versão atual.
- A gravação passa a versão lida como esperada (`update(agregado, expectedVersion)`). Se outra escrita venceu entre a leitura e a gravação, a porta lança `ConcurrentUpdateException` (409).
- Na aplicação, só `OfferService.define` torna o If-Match obrigatório (troca de preço, `PreconditionRequiredException`, 428). A API exige o cabeçalho também em `reviseContent`, `reviseSku`, `CategoryService.revise` e `move` (`ETags.required`).
- `InventoryService.adjust` usa a versão do saldo, que reservas também incrementam.

## Portas

Todas devolvem `Uni`. Implementações e decorators ficam no serviço ([`persistence`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/persistence), [`cache`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/cache)).

| Porta | Contrato principal | Implementação | Cache |
|---|---|---|---|
| [`ProductRepository`](../src/main/java/dev/marketplace/cataloginventory/application/ProductRepository.java) | Agregado inteiro numa transação. `findById`, `findBySku`, `skuIdsBySellerSku`, `find` (ordem de id, cursor exclusivo), `history`. `insert` cria saldo zerado por SKU, histórico e outbox; SKU repetido na loja: `DuplicateException`. `update` com versão esperada; SKU removido com saldo: `sku-has-stock`. | `PgProductRepository` | `ProductCacheInvalidation` |
| [`CategoryRepository`](../src/main/java/dev/marketplace/cataloginventory/application/CategoryRepository.java) | `chain` (raiz até a categoria, vazia se não existe), `idByCode`, `descendantAttributeCodes`, `subtreeHeight`, `children` (ordem de código, inclui inativas). `insert` (código duplicado: `DuplicateException`), `update` e `move` (troca o prefixo dos descendentes) com `ConcurrentUpdateException`. | `PgCategoryRepository` | `CategoryCacheInvalidation`; `TaxonomyCache.over()` |
| [`OfferRepository`](../src/main/java/dev/marketplace/cataloginventory/application/OfferRepository.java) | Oferta e promoções numa transação com os eventos de preço. `ofSku`, `ofProduct` (só SKUs com oferta), `insert` (`DuplicateException`), `update` com versão. | `PgOfferRepository` | `OfferCacheInvalidation` |
| [`InventoryRepository`](../src/main/java/dev/marketplace/cataloginventory/application/InventoryRepository.java) | `levels` (SKU sem saldo fica fora do mapa), `adjust` (404, `version-mismatch`, `stock-below-reserved`), `candidates` (leitura para a decisão de reserva). | `PgInventoryRepository` | nenhum |
| [`ReservationRepository`](../src/main/java/dev/marketplace/cataloginventory/application/ReservationRepository.java) | Cada operação é atômica sobre reserva e saldos. `insert` (tudo ou nada; `insufficient-stock`; chave repetida: `DuplicateException`), `release`, `commit`, `extend` (vazio quando não se aplica), `expireDue`. | `PgReservationRepository` | nenhum |
| [`SellerEligibilityRepository`](../src/main/java/dev/marketplace/cataloginventory/application/SellerEligibilityRepository.java) | `statusOf` (nunca vazio; sem registro é `PENDING`), `apply` (entrega e veredito numa transação). | `PgSellerEligibilityRepository` | `SellerCacheInvalidation` |
| [`ProductImportRepository`](../src/main/java/dev/marketplace/cataloginventory/application/ProductImportRepository.java) | `insert`, `findImport` (vazio para outra loja ou tenant), `results` (ordem de linha), `claimNext` (dois chamadores nunca recebem o mesmo lote), `recordResults` (linha já gravada mantém o primeiro resultado; renova `startedAt`), `complete`. | `PgProductImportRepository` | nenhum |

- Os decorators de invalidação são CDI e envolvem as `Pg*`; incrementam gerações do Redis depois da escrita (ADR 024).
- `TaxonomyCache.over(categories)` envolve a porta de categorias em `CategoryService`, `ProductService`, `ModerationService` e `ProductImportService` ([`CatalogBeans`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/CatalogBeans.java)). Só `chain` e `children` passam pelo cache; as demais leituras e as escritas vão direto. Com o cache desligado, devolve a própria porta.
- `OfferService`, `InventoryService`, `ReservationService`, `StorefrontService` e `SellerEligibilityService` recebem as portas sem esse envoltório.

## Paginação

[`Page`](../src/main/java/dev/marketplace/cataloginventory/application/Page.java): `items` e `nextCursor`, que é a chave do último item (vazio na última página).

| Item | Valor |
|---|---|
| `DEFAULT_SIZE` | 50 (aplicado pela API) |
| `MAX_SIZE` | 100; `limit` fora de 1 a 100 lança `InvalidInputException` |
| Busca | `Page.fetch` pede `limit + 1` à porta; o excedente só indica próxima página |
| Chave | produtos: `id` (`list`, `queue`); categorias: `code` (`children`) |

O cursor chega à aplicação como a chave em texto; a codificação opaca em base64url fica no serviço (`Cursors`).

## Idempotência das reservas

Fluxo em [sequencia-reserva-idempotente.puml](diagramas/sequencia-reserva-idempotente.puml). Base: ADR 012.

| Aspecto | Regra no código |
|---|---|
| Chave | Obrigatória, `[\x21-\x7E]{1,255}`; única por tenant |
| Hash canônico | SHA-256 de `orderRef`, `ttl` em segundos (ou `default` quando omitido) e, por item em ordem de `skuId`, `skuId:quantity:preço:moeda` |
| Chave nova | Lê candidatos, `ReservationPolicy.open`, `insert`; `replayed = false` |
| Replay idêntico | Mesmo hash devolve a reserva existente no estado atual; `replayed = true` (API: 200 com `Idempotent-Replayed: true`) |
| Chave reutilizada | Hash diferente: `DomainRuleViolation` `idempotency-key-reused` (422) |
| Corrida | `insert` falha com `DuplicateException`; o caso de uso relê pela chave e aplica a mesma regra de replay. Se a reserva não aparecer, `ConcurrentUpdateException` (409) |

Recusas de negócio (`ReservationRefusal`) não gravam nada e podem ser repetidas com a mesma chave.

## Importação CSV

Base: ADR 016. O serviço valida a estrutura do arquivo, converte as linhas (`ProductCsv`) e agenda o processamento (`ProductImportWorker`).

### Tipos

| Tipo | Conteúdo |
|---|---|
| [`ProductImport`](../src/main/java/dev/marketplace/cataloginventory/application/ProductImport.java) | Lote: `id`, `tenantId`, `storeId`, `actor` (subject), `status`, `totalLines`, `createdAt`, `startedAt` (última reivindicação ou progresso), `finishedAt` |
| `ImportStatus` | `PENDING`, `PROCESSING`, `COMPLETED` ([estados-importacao.puml](diagramas/estados-importacao.puml)) |
| [`ImportRow`](../src/main/java/dev/marketplace/cataloginventory/application/ImportRow.java) | Linha convertida. `error` invalida a linha inteira (preço, estoque ou contagem de colunas); `productError` afeta só as colunas de produto, ignoradas para SKU existente |
| [`ImportResult`](../src/main/java/dev/marketplace/cataloginventory/application/ImportResult.java) | `lineNumber`, `sellerSku` (até 100), `outcome`, `code` e `message` (até 1000) só em `FAILED`, `productId` quando conhecido |
| `ImportOutcome` | `CREATED`, `UPDATED`, `UNCHANGED`, `FAILED` |

### Lease e retomada

- `claimNext(lease)` pega o lote `PENDING` mais antigo ou o `PROCESSING` com `startedAt` anterior a agora menos o lease.
- `process` grava os resultados ao fim de cada unidade (`recordResults`, que renova `startedAt`) e chama `complete` no fim.
- Falha técnica interrompe o processamento; o que foi gravado fica, e o lote volta depois do lease, pulando as linhas com resultado.
- Recusas de negócio (`DomainRuleViolation`, `DuplicateException`, `NotFoundException`, `ConcurrentUpdateException`, `VersionMismatchException`, `PreconditionRequiredException`, `InvalidInputException`, `AccessDeniedException`) viram linha `FAILED` com o mesmo código que a API responderia.

### Plano por unidade

Fluxo em [atividade-importacao.puml](diagramas/atividade-importacao.puml). As linhas pendentes viram unidades, na ordem do arquivo:

| Unidade | Quando | Processamento | Resultado |
|---|---|---|---|
| `Rejected` | `sellerSku` repetido no arquivo (conferido sobre todas as linhas, inclusive as já gravadas) | — | `FAILED` `duplicate-seller-sku` |
| `Rejected` | SKU novo sem `productRef` | — | `FAILED` `invalid-row` |
| `ExistingSku` | `sellerSku` já existe na loja | Só preço e estoque, cada um se mudou. Preço: `OfferService.define` com a versão lida como If-Match (sem oferta, cria). Estoque: `InventoryService.adjust` sem If-Match. Arquivado: `invalid-transition` | `UPDATED`, `UNCHANGED` ou `FAILED` |
| `NewProduct` | SKUs novos com o mesmo `productRef` | Valida tudo antes de gravar; cria o rascunho com todos os SKUs (`ProductService.create`) e depois oferta e estoque de cada SKU (estoque só se maior que zero) | `CREATED` com `productId`; falha após a criação: `FAILED` com `productId` |

Recusas do grupo `NewProduct` antes de gravar:

| Código | Causa | Linhas afetadas |
|---|---|---|
| `invalid-row` ou código do domínio | linha com erro, sem categoria, título ou indicador físico, ou SKU, preço ou estoque inválidos | a linha; as demais recebem `group-rejected` |
| `inconsistent-product` | categoria, título, descrição, atributos ou moeda diferentes da primeira linha | todas |
| `category-not-found` | `category_code` inexistente no tenant (`idByCode`, ativa ou não) | todas |

## Exceções da aplicação

| Exceção | Uso | Resposta no serviço |
|---|---|---|
| `NotFoundException` | inexistente ou fora do alcance | 404 `not-found` |
| `AccessDeniedException` | capacidade ou loja ausente | 403 `access-denied` |
| `InvalidInputException` | entrada recusada (`limit`, chave, filtro) | 400 `invalid-request` |
| `DuplicateException` | unicidade violada; `code` vem da porta | 409 com o código |
| `VersionMismatchException` | If-Match diferente da versão atual | 412 `version-mismatch` |
| `PreconditionRequiredException` | If-Match obrigatório ausente | 428 `precondition-required` |
| `ConcurrentUpdateException` | escrita perdida entre leitura e gravação | 409 `concurrent-update` |

Mapeamento em [`ProblemMappers`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/api/ProblemMappers.java) (ADR 008). As exceções do domínio estão no [README do domínio](../../domain/docs/README.md#exceções-e-tradução-no-serviço).

## Configuração recebida

A aplicação não lê configuração; recebe valores no construtor.

| Parâmetro | Recebido por | Origem no serviço | Default |
|---|---|---|---|
| `Clock` | todos os serviços que leem o instante | `Clock.systemUTC()` em `CatalogBeans` | — |
| `ReservationService.Settings` (`defaultTtl`, `maxTtl`, `maxLifetime`) | `ReservationService` | `catalog.reservations.default-ttl` / `max-ttl` / `max-lifetime` | 15 min / 2 h / 24 h |
| `batch` | `ReservationService.expireDue` | `catalog.reservations.sweep-batch` | 200 |
| `lease` | `ProductImportService.claimNext` | `catalog.imports.lease` | 5 min |

O limite de linhas por arquivo (`catalog.imports.max-rows`, 5000) é aplicado pelo adaptador antes de `submit`.

## Testes

Testes unitários JUnit 5, sem framework, em [`src/test/java/dev/marketplace/cataloginventory/application`](../src/test/java/dev/marketplace/cataloginventory/application). São 55 casos.

| Arquivo | Casos | Cobre |
|---|---|---|
| `ProductServiceTest` | 13 | criação, visibilidade por loja e tenant, moderação, If-Match e perda de atualização, envio, listagem |
| `CategoryServiceTest` | 12 | herança, escrita restrita, unicidade, revisão, inatividade, paginação, movimentação |
| `ProductImportServiceTest` | 10 | grupos, SKU existente, divergências, categoria, linha inválida, retomada, visibilidade do lote |
| `ReservationServiceTest` | 8 | tudo ou nada, replay e chave reutilizada, commit, release, expiração, extensão, hash independente da ordem |
| `OfferServiceTest` | 5 | criação, If-Match na troca, moderação inalterada, autorização, arquivado |
| `StorefrontServiceTest` | 3 | produto visível, bloqueado, loja inelegível, outro tenant, rascunho |
| `InventoryServiceTest` | 2 | ajuste (reservado, versão, dono), arquivado |
| `SellerEligibilityServiceTest` | 2 | deduplicação e ordem dos vereditos, leitura da situação |

Apoio: `InMemoryStore` implementa as sete portas guardando cópias, com controle otimista de versão; `Catalog` liga os serviços ao mesmo armazenamento; `Fixtures` monta dados; `MutableClock` avança o tempo.

```bash
npx nx test catalog-inventory-application
# ou
./gradlew :catalog-inventory-application:test
```

## Divergências e nuances

Confirmadas no código; não mudam o contrato da API, mas não estão explícitas nas ADRs ou nos outros READMEs.

1. **`idempotency-conflict` não chega à API pela reserva.** `PgReservationRepository.insert` converte a violação de `reservation_idempotency_uk` em `DuplicateException("idempotency-conflict")`, mas `ReservationService.open` captura toda `DuplicateException` e a resolve como replay, 422 `idempotency-key-reused` ou 409 `concurrent-update`.
2. **Replay devolve o estado atual.** O replay compara só o hash e devolve a reserva como está, inclusive `COMMITTED`, `RELEASED` ou `EXPIRED`, sem conferir de novo saldo, preço ou prazo. A ADR 012 diz apenas que a repetição "devolve a mesma reserva".
3. **`ttl` omitido e `ttl` explícito são pedidos diferentes.** O hash usa `default` quando o `ttl` falta. Repetir a chave informando 15 min, depois de tê-la usado sem `ttl`, dá `idempotency-key-reused`.
4. **`TaxonomyCache.over()` em quatro serviços.** Envolve a porta de categorias em `CategoryService`, `ProductService`, `ModerationService` e `ProductImportService`, não só na leitura da árvore.
5. **Leitura de categorias sem capacidade.** `CategoryService.get` e `children` não exigem capacidade: qualquer chamador do tenant lê. A restrição a seller, admin e moderador está só no `@RolesAllowed` de `CategoryResource`.
6. **If-Match opcional na aplicação.** Exceto na troca de preço de `OfferService.define`, os casos de uso aceitam If-Match ausente. A obrigatoriedade em `reviseContent`, `reviseSku`, `revise` e `move` de categoria é aplicada pela API (`ETags.required`).
7. **Elegibilidade da própria loja.** `SellerEligibilityService.get` libera a loja do token sem exigir nenhuma capacidade, nem `MANAGE_OWN_PRODUCTS`.
8. **`stockOf` sem autorização.** `InventoryService.stockOf` confia que os produtos recebidos já foram autorizados pelo chamador e usa o tenant do primeiro produto.
9. **Prazos da reserva ficam no serviço.** O README do domínio diz que os padrões de 15 min, 2 h e 24 h estão "na aplicação". A aplicação só recebe `Settings`; os valores são defaults de `CatalogConfig` no serviço.
