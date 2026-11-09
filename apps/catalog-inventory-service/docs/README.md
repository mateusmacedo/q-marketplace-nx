# catalog-inventory-service

Aplicação Quarkus do contexto catálogo-inventário. Este módulo compõe os casos de uso de [`libs/catalog-inventory/application`](../../../libs/catalog-inventory/application/docs/README.md) e o domínio de [`libs/catalog-inventory/domain`](../../../libs/catalog-inventory/domain/docs/README.md) com os adaptadores de HTTP, PostgreSQL, Kafka, Redis, segurança e observabilidade. A visão do contexto (C4 nível 1) e a lista de módulos ficam em [docs/catalog-inventory.md](../../../docs/catalog-inventory.md). As decisões citadas aqui estão em [docs/decisoes.md](../../../docs/decisoes.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Papéis api e worker](#papéis-api-e-worker)
- [API HTTP](#api-http)
- [Segurança](#segurança)
- [Persistência](#persistência)
- [Mensageria](#mensageria)
- [Cache Redis](#cache-redis)
- [Observabilidade](#observabilidade)
- [Configuração](#configuração)
- [Rodar localmente](#rodar-localmente)
- [Implantar no Kubernetes](#implantar-no-kubernetes)
- [Diagramas](#diagramas)
- [Divergências conhecidas](#divergências-conhecidas)

## Propósito e responsabilidades

O serviço mantém, por tenant, a taxonomia, os produtos e SKUs, as ofertas, a moderação, a vitrine pública, o estoque e as reservas. Tenant e loja vêm sempre do token JWT.

| Responsabilidade | Onde fica no módulo |
|---|---|
| API REST, ETag/If-Match, cursor, erros RFC 9457 | [`api`](../src/main/java/dev/marketplace/cataloginventory/api) |
| Identidade do chamador e papéis | [`security`](../src/main/java/dev/marketplace/cataloginventory/security) |
| Repositórios PostgreSQL e outbox | [`persistence`](../src/main/java/dev/marketplace/cataloginventory/persistence) |
| Relay da outbox, limpeza da mensageria, consumo do KYB/KYC | [`messaging`](../src/main/java/dev/marketplace/cataloginventory/messaging) |
| Contratos de evento, tradução dos eventos de domínio e mensagem da outbox | [`outbox`](../src/main/java/dev/marketplace/cataloginventory/outbox) |
| Cache Redis da vitrine, da taxonomia e dos motivos de moderação | [`cache`](../src/main/java/dev/marketplace/cataloginventory/cache) |
| Importação CSV em segundo plano | [`imports`](../src/main/java/dev/marketplace/cataloginventory/imports) |
| Métricas, spans, interceptores e correlação | [`observability`](../src/main/java/dev/marketplace/cataloginventory/observability) |
| Montagem dos casos de uso | [`CatalogBeans`](../src/main/java/dev/marketplace/cataloginventory/CatalogBeans.java) |
| Configuração tipada (`catalog.*`) | [`config`](../src/main/java/dev/marketplace/cataloginventory/config) ([`CatalogConfig`](../src/main/java/dev/marketplace/cataloginventory/config/CatalogConfig.java)) |
| Varredor de reservas vencidas | [`ReservationSweeper`](../src/main/java/dev/marketplace/cataloginventory/ReservationSweeper.java) |

Os casos de uso (`ProductService`, `ModerationService`, `ModerationReasonService`, `OfferService`, `InventoryService`, `ReservationService`, `CategoryService`, `StorefrontService`, `SellerEligibilityService`, `ProductImportService`, `CatalogStatsService`, mais o apoio `ProductActions`) são classes Java puras instanciadas em `CatalogBeans`. As portas e suas implementações:

| Porta | Implementação | Decorator de cache |
|---|---|---|
| `ProductRepository` | `PgProductRepository` | `ProductCacheInvalidation` |
| `OfferRepository` | `PgOfferRepository` | `OfferCacheInvalidation` |
| `CategoryRepository` | `PgCategoryRepository` | `CategoryCacheInvalidation`; leituras por `TaxonomyCache` |
| `ModerationReasonRepository` | `PgModerationReasonRepository` | `ModerationReasonCacheInvalidation`; leituras por `ModerationReasonCache` |
| `InventoryRepository` | `PgInventoryRepository` | nenhum |
| `ReservationRepository` | `PgReservationRepository` | nenhum |
| `SellerEligibilityRepository` | `PgSellerEligibilityRepository` | `SellerCacheInvalidation` |
| `ProductImportRepository` | `PgProductImportRepository` | nenhum |
| `CatalogStatsRepository` | `PgCatalogStatsRepository` | nenhum |

`PgOutbox` e `IntegrationEvents` ficam fora das portas: os repositórios os chamam dentro da própria transação.

## Papéis api e worker

Um único executável nativo roda em dois papéis, escolhidos pelo perfil do Quarkus (ADR 020). Sem perfil de papel, como em dev e nos testes, tudo roda no mesmo processo.

| | api | worker |
|---|---|---|
| Perfil | `QUARKUS_PROFILE=prod,api` | `QUARKUS_PROFILE=prod,worker` |
| HTTP de negócio | 8080 no contêiner (8083 em dev) | desligado (`quarkus.http.host-enabled=false`) |
| Gestão (saúde e métricas) | 9000 | 9000 |
| Agendadores | os cinco com intervalo `off` | todos ligados |
| Canais Kafka | `catalog-events` e `kyb-seller-verdicts` desligados | ligados |
| `service.name` (OTel) | `catalog-inventory-api` | `catalog-inventory-worker` |
| Migrations Flyway | na partida | na partida |

A configuração está em [`application.properties`](../src/main/resources/application.properties) (perfis `%api` e `%worker`).

Agendadores do worker. Todos usam `concurrentExecution = SKIP` e `skipExecutionIf = ApplicationNotRunning`:

| `identity` | Intervalo | Propriedade | Classe | Faz |
|---|---|---|---|---|
| `reservation-sweeper` | 5 s | `catalog.reservations.sweep-interval` | [`ReservationSweeper`](../src/main/java/dev/marketplace/cataloginventory/ReservationSweeper.java) | Expira até 200 reservas vencidas por execução |
| `outbox-relay` | 1 s, início após 3 s | `catalog.events.relay-interval` | [`OutboxRelay`](../src/main/java/dev/marketplace/cataloginventory/messaging/OutboxRelay.java) | Publica a outbox no Kafka |
| `messaging-purge` | 1 min | `catalog.events.purge-interval` | [`MessagingHousekeeping`](../src/main/java/dev/marketplace/cataloginventory/messaging/MessagingHousekeeping.java) | Apaga outbox publicada e inbox antigas, por até 50 s |
| `product-import` | 1 s | `catalog.imports.interval` | [`ProductImportWorker`](../src/main/java/dev/marketplace/cataloginventory/imports/ProductImportWorker.java) | Processa lotes de importação |
| `sku-gauges` | 60 s | `catalog.metrics.refresh-interval` | [`SkuGauges`](../src/main/java/dev/marketplace/cataloginventory/observability/SkuGauges.java) | Atualiza os gauges de SKUs |

Relay (trava consultiva), varredor e importação (`SKIP LOCKED`) aceitam várias réplicas do worker.

## API HTTP

O contrato versionado está em [`contracts/catalog-inventory/openapi.json`](../../../contracts/catalog-inventory/openapi.json), gerado do código (ADR 008). Endpoint sem anotação de segurança é negado (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).

Legenda da coluna If-Match: **obrigatório** (sem ele, 428), **opcional** (se vier, confere a versão), **—** (não usa).

### Produtos — [`ProductResource`](../src/main/java/dev/marketplace/cataloginventory/api/ProductResource.java)

| Método | Caminho | Papel | If-Match | Sucesso |
|---|---|---|---|---|
| POST | `/products` | seller | — | 201 + `ETag` + `Location` |
| GET | `/products` (`status`, `storeId`, `q`, `sort`, `cursor`, `limit`) | seller, catalog-admin, catalog-moderator | — | 200 |
| GET | `/products/{id}` | seller, catalog-admin, catalog-moderator | — | 200 + `ETag` |
| PUT | `/products/{id}` | seller | obrigatório | 200 + `ETag` |
| DELETE | `/products/{id}` (arquiva) | seller | opcional | 200 + `ETag` |
| GET | `/products/{id}/history` | seller, catalog-admin, catalog-moderator | — | 200 |
| POST | `/products/{id}/check` (rascunho, não grava) | seller | — | 200 |
| POST | `/products/{id}/submit` | seller | opcional | 200 + `ETag` |
| POST | `/products/{id}/pause` | seller | opcional | 200 + `ETag` |
| POST | `/products/{id}/resume` | seller | opcional | 200 + `ETag` |
| POST | `/products/batch` (`SUBMIT`, `PAUSE`, `RESUME` ou `ARCHIVE`, até 100 itens) | seller | por item (`version`) | 200 |
| POST | `/products/{id}/skus` | seller | opcional | 201 + `ETag` + `Location` |
| GET | `/products/{id}/skus/{skuId}` | seller, catalog-admin, catalog-moderator | — | 200 + `ETag` |
| PUT | `/products/{id}/skus/{skuId}` | seller | obrigatório | 200 + `ETag` |
| DELETE | `/products/{id}/skus/{skuId}` | seller | opcional | 200 + `ETag` |

Na listagem, o `status` segue o estado como a resposta o mostra: `OUT_OF_STOCK` filtra o `ACTIVE` sem saldo disponível, e `ACTIVE`, o com saldo; esse estado é calculado na leitura e nunca gravado. `q` busca um trecho do título, sem diferenciar maiúsculas e acentos, ou do `sellerSku`, até 100 caracteres. `sort` aceita `updatedAt`, `createdAt` e `title`, com `-` para inverter (padrão `-updatedAt`). O lote responde 200 com o desfecho de cada item, na ordem do pedido: o produto gravado ou o problema que o endpoint de um item daria. Nas escritas de um item, aqui e em `/moderation/products/{id}/*`, a resposta lê o saldo depois do commit; se essa leitura falhar, a resposta sai com sucesso, o status gravado, sem o `OUT_OF_STOCK` calculado, e os SKUs sem `stock`, como no lote ([`ProductResponses`](../src/main/java/dev/marketplace/cataloginventory/api/ProductResponses.java)). A leitura seguinte corrige o status. Se falhar outra leitura da resposta (ofertas, última rejeição, situação da loja ou a categoria das pendências), a resposta sai com sucesso e o produto gravado, sem `actions`, `blockedActions`, `publishingIssues`, `lastRejection` e a oferta dos SKUs, e o cliente relê o produto.

Toda resposta de produto, inclusive os itens da listagem e da fila de moderação, traz o que o chamador pode fazer, calculado por `ProductActions` com as regras que os endpoints de escrita aplicam: `actions` (códigos em lista aberta, como `EDIT`, `ADD_SKU`, `SUBMIT`, `RESUME`, `APPROVE`), `blockedActions` (ação que vale no status, mas que um motivo impede agora, como `seller-not-eligible` ou `sku-removal-requires-draft`, no formato de `reasonDetails`) e `saveReturnsToModeration`. A situação das lojas que as ações pedem é lida numa consulta por página. A leitura de um produto em status editável traz também `publishingIssues` (`code`, `params`, `message` e `field`); a listagem não as calcula. `POST /products/{id}/check` recebe `categoryId` e `attributes` do formulário ainda não salvo e devolve as pendências e, quando a categoria muda, o efeito da troca (`removedAttributes`, `newRequired` e `blockingSkus`), com o alcance da edição: 404 fora da loja e 409 em `BLOCKED` e `ARCHIVED`. O 409 e o 422 das escritas continuam sendo a palavra final.

`GET /products/{id}/history` não expõe quem fez cada transição: traz `actorSide` (`STORE` ou `MODERATION`, de `ProductTransition.side()`) e `byCaller` (verdadeiro quando o chamador a fez), com os códigos, os rótulos da época e o complemento da decisão.

### Importação — [`ProductImportResource`](../src/main/java/dev/marketplace/cataloginventory/api/ProductImportResource.java)

| Método | Caminho | Papel | If-Match | Sucesso |
|---|---|---|---|---|
| POST | `/products/imports` (`text/csv`, até 1 MB) | seller | — | 202 + `Location` |
| GET | `/products/imports` (`cursor`, `limit`; do mais recente ao mais antigo, com as contagens) | seller | — | 200 |
| GET | `/products/imports/{id}` (`lines`, padrão `true`; com `false`, só status e contagens, `lines` nulo) | seller | — | 200 |

### Moderação — [`ModerationResource`](../src/main/java/dev/marketplace/cataloginventory/api/ModerationResource.java)

Todas as rotas exigem o papel `catalog-moderator`. Rejeitar e bloquear levam `reasonCodes` (motivos ativos do catálogo do tenant) e `note` (complemento de até 500 caracteres); o histórico guarda os códigos, os rótulos da época e o complemento separados (ADR 030).

| Método | Caminho | If-Match | Sucesso |
|---|---|---|---|
| GET | `/moderation/products` (fila, `cursor`, `limit`) | — | 200 |
| POST | `/moderation/products/{id}/approve` | opcional | 200 + `ETag` |
| POST | `/moderation/products/{id}/reject` (`reasonCodes`, `note`) | opcional | 200 + `ETag` |
| POST | `/moderation/products/{id}/block` (`reasonCodes`, `note`) | opcional | 200 + `ETag` |
| POST | `/moderation/products/{id}/unblock` | opcional | 200 + `ETag` |
| POST | `/moderation/products/batch` | por item (`version`) | 200 |

### Motivos de moderação — [`ModerationReasonResource`](../src/main/java/dev/marketplace/cataloginventory/api/ModerationReasonResource.java)

Catálogo por tenant (ADR 030), sem exclusão: o código fica no histórico e nos eventos. O tenant novo começa sem motivos.

| Método | Caminho | Papéis | If-Match | Sucesso |
|---|---|---|---|---|
| GET | `/moderation/reasons` (`decision`, `includeInactive`) | catalog-moderator, catalog-admin | — | 200 |
| GET | `/moderation/reasons/{code}` | catalog-admin | — | 200 + `ETag` |
| POST | `/moderation/reasons` | catalog-admin | — | 201 + `ETag` + `Location` |
| PUT | `/moderation/reasons/{code}` (rótulo, decisões, posição) | catalog-admin | obrigatório | 200 + `ETag` |
| POST | `/moderation/reasons/{code}/activate` | catalog-admin | obrigatório | 200 + `ETag` |
| POST | `/moderation/reasons/{code}/deactivate` | catalog-admin | obrigatório | 200 + `ETag` |

O moderador recebe só os ativos; `includeInactive=true` exige `MANAGE_MODERATION_REASONS`. O motivo inativo é recusado na decisão nova com `reason-inactive`, e o de outro tenant, com `unknown-reason`.

### Categorias — [`CategoryResource`](../src/main/java/dev/marketplace/cataloginventory/api/CategoryResource.java)

| Método | Caminho | Papel | If-Match | Sucesso |
|---|---|---|---|---|
| POST | `/categories` | catalog-admin | — | 201 + `ETag` + `Location` |
| GET | `/categories/{id}` | seller, catalog-admin, catalog-moderator | — | 200 + `ETag` |
| GET | `/categories` (`parentId`, `cursor`, `limit`; ou `ids`, até 100, sem os outros) | seller, catalog-admin, catalog-moderator | — | 200 |
| GET | `/categories/tree` (`usable`, `cursor`, `limit` até 1000, padrão 1000) | seller, catalog-admin, catalog-moderator | — | 200 |
| PUT | `/categories/{id}` | catalog-admin | obrigatório | 200 + `ETag` |
| PUT | `/categories/{id}/parent` | catalog-admin | obrigatório | 200 + `ETag` |
| DELETE | `/categories/{id}` (só a vazia) | catalog-admin | obrigatório | 204 |
| POST | `/categories/batch` (`ACTIVATE`, `DEACTIVATE` ou `DELETE`, até 100 itens) | catalog-admin | por item (`version`, obrigatória) | 200 |

A exclusão recusa com 409 `category-has-children` ou `category-has-products` (produtos inclusive arquivados), também quando a subcategoria ou o produto é gravado ao mesmo tempo. No lote, a exclusão roda das subcategorias para os pais, e um ramo vazio sai inteiro.

### Indicadores — [`StatsResource`](../src/main/java/dev/marketplace/cataloginventory/api/StatsResource.java)

Números da página inicial do painel, por papel (`CatalogStatsService`). `granularity` aceita `day`, `month` (padrão) ou `year`; `from` e `to` vão juntos.

| Método | Caminho | Papel | Sucesso |
|---|---|---|---|
| GET | `/stats/store` (`granularity`, `from`, `to`) | seller | 200 |
| GET | `/stats/moderation` (`granularity`, `from`, `to`) | catalog-moderator | 200 |
| GET | `/stats/moderation/queue` (só o tamanho da fila) | catalog-moderator | 200 |
| GET | `/stats/categories` | catalog-admin | 200 |

### Ofertas — [`OfferResource`](../src/main/java/dev/marketplace/cataloginventory/api/OfferResource.java)

| Método | Caminho | Papel | If-Match | Sucesso |
|---|---|---|---|---|
| GET | `/offers/{skuId}` | seller, catalog-admin, catalog-moderator | — | 200 + `ETag` |
| PUT | `/offers/{skuId}` | seller | criação: proibido (412 se vier); alteração: obrigatório (428 sem ele) | 201 ao criar, 200 ao alterar |
| POST | `/offers/{skuId}/promotions` | seller | opcional | 201 + `ETag` |
| DELETE | `/offers/{skuId}/promotions/{promotionId}` | seller | opcional | 200 + `ETag` |

A regra de If-Match do `PUT` fica em `OfferService.define`.

### Estoque — [`InventoryResource`](../src/main/java/dev/marketplace/cataloginventory/api/InventoryResource.java)

| Método | Caminho | Papel | If-Match | Sucesso |
|---|---|---|---|---|
| GET | `/inventory/skus/{skuId}` | seller, catalog-admin, catalog-moderator | — | 200 + `ETag` |
| PUT | `/inventory/skus/{skuId}` (`onHand` absoluto) | seller | opcional | 200 + `ETag` |

### Reservas — [`ReservationResource`](../src/main/java/dev/marketplace/cataloginventory/api/ReservationResource.java)

Todas as rotas exigem o papel `catalog-reservations` (contas de serviço do checkout e do OMS).

| Método | Caminho | Cabeçalho | Sucesso |
|---|---|---|---|
| POST | `/inventory/reservations` | `Idempotency-Key` obrigatório | 201 + `Location`; repetição idêntica: 200 + `Idempotent-Replayed: true` |
| GET | `/inventory/reservations/{id}` | — | 200 |
| POST | `/inventory/reservations/{id}/release` | — | 200 |
| POST | `/inventory/reservations/{id}/commit` | — | 200 |
| POST | `/inventory/reservations/{id}/extend` (`ttlSeconds`) | — | 200 |

### Rotas públicas e de apoio

| Método | Caminho | Papel | Sucesso | Classe |
|---|---|---|---|---|
| GET | `/storefront/{tenantId}/products/{productId}` | anônimo (`@PermitAll`) | 200 + `Cache-Control: no-cache` | [`StorefrontResource`](../src/main/java/dev/marketplace/cataloginventory/api/StorefrontResource.java) |
| GET | `/stores/{storeId}/eligibility` | seller, catalog-admin, catalog-moderator | 200 | [`StoreEligibilityResource`](../src/main/java/dev/marketplace/cataloginventory/api/StoreEligibilityResource.java) |
| GET | `/me` | qualquer usuário autenticado com tenant | 200 | [`SessionResource`](../src/main/java/dev/marketplace/cataloginventory/api/SessionResource.java) |
| GET | `/internal/info` | anônimo, fora do OpenAPI | 200 | [`ServiceInfoResource`](../src/main/java/dev/marketplace/cataloginventory/ServiceInfoResource.java) |

Na elegibilidade, o seller lê a própria loja; para ler outra, precisa de `READ_SELLER_ELIGIBILITY` (papéis `catalog-admin` e `catalog-moderator`). A resposta traz os efeitos da situação, calculados no servidor: `canPublish` (produtos podem ser aprovados e reativados) e `visibleInStorefront` (produtos ativos aparecem na vitrine).

`GET /me` devolve `subject`, `tenantId`, `storeId` (só do seller), `capabilities` (nomes de `Capability`, lista aberta que o cliente lê ignorando valor desconhecido) e `can`, as permissões compostas calculadas por `Actor` (hoje `readTenantCatalog`). Papel que o catálogo não conhece resulta em `capabilities` vazia.

### ETag e If-Match

Implementação em [`ETags`](../src/main/java/dev/marketplace/cataloginventory/api/ETags.java).

- O ETag é forte e carrega a versão do agregado (`"42"`).
- No modo opcional, If-Match ausente, em branco ou `*` dispensa a checagem.
- No modo obrigatório, If-Match ausente, em branco ou `*` dá 428 `precondition-required`. O `*` não serve de atalho, porque gravaria sem conferir a versão.
- ETag fraco (`W/`) ou sem aspas dá 400 `invalid-request`.
- Versão diferente da atual dá 412 `version-mismatch`.
- Perda de atualização detectada na gravação (UPDATE condicional sem linha afetada) dá 409 `concurrent-update`.

### Paginação por cursor

- Cursor opaco em base64url com a chave de ordenação, de até 128 caracteres; até 1024 na listagem de produtos, que guarda o título, e até 366 na árvore de categorias ([`Cursors`](../src/main/java/dev/marketplace/cataloginventory/api/Cursors.java)).
- Produtos seguem `sort`, com o id como desempate, e o cursor só vale para a mesma ordem, busca e filtros (senão, 400); a fila de moderação sai por ordem de chegada; categorias, pelo `code`, e a árvore, pelo caminho de códigos; lotes de importação, do mais recente ao mais antigo.
- `limit` de 1 a 100, padrão 50; na árvore de categorias (`GET /categories/tree`), de 1 a 1000, padrão 1000, porque quem a lê quer a árvore inteira e cada página refaz a recursão. O repositório busca `limit + 1` itens para saber se há próxima página.

### Idempotency-Key

Só em `POST /inventory/reservations`. A chave segue `[\x21-\x7E]{1,255}` e é única por tenant (`reservation_idempotency_uk`). O serviço guarda o SHA-256 canônico da requisição: mesma chave e mesmo hash devolvem a reserva existente (200 com `Idempotent-Replayed: true`); mesma chave com outro corpo dá 422 `idempotency-key-reused`. Duas requisições simultâneas com a mesma chave resolvem pela constraint: a perdedora relê e faz replay.

### X-Correlation-Id

[`CorrelationFilter`](../src/main/java/dev/marketplace/cataloginventory/api/CorrelationFilter.java) aceita o cabeçalho do chamador se casar com `[A-Za-z0-9._:-]{1,100}`, ou gera um novo, e o devolve na resposta. O valor vai para o MDC, o span e o `correlationId` do envelope dos eventos.

### Problemas RFC 9457

Respostas de erro usam `application/problem+json` com `type = https://marketplace.dev/problems/<código>`, mais as extensões `errors[]` (campos inválidos), `reasons[]` (motivos estáveis, como `missing-offer:CAM-P`) e `reasonDetails[]`, com `code`, `params` e `message` de cada motivo (ou, sem `reasons`, da própria recusa). A `message` é a que o emissor escreveu, quando escreveu, ou o texto do código em [`i18n/ReasonMessages.properties`](../src/main/resources/i18n/ReasonMessages.properties), com os parâmetros. Mapeamento em [`ProblemMappers`](../src/main/java/dev/marketplace/cataloginventory/api/ProblemMappers.java).

| Status | Códigos |
|---|---|
| 400 | `invalid-request`, `validation-failed`, `malformed-body`, `invalid-import-file`. JSON malformado ou com tipos incompatíveis sai como `malformed-body`, inclusive quando o leitor do framework o embrulha num 400 genérico. Texto do corpo JSON com o caractere U+0000, valor ou chave de mapa em qualquer nível, sai como `validation-failed`, com o caminho do campo em `errors` (`title`, `skus[0].variantAttributes[tamanho]`): o [`NullCharacterGuard`](../src/main/java/dev/marketplace/cataloginventory/api/NullCharacterGuard.java) o recusa na leitura do JSON, porque o PostgreSQL não o guarda em `text` (SQLSTATE 22021), e a validação dos demais campos não chega a rodar |
| 403 | `access-denied`, `tenant-not-authorized` |
| 404 | `not-found` |
| 409 | `concurrent-update`; duplicados (`duplicate-seller-sku`, `duplicate-category-code` e outros da `DuplicateException`); transição inválida (`invalid-transition`); recusas de reserva (`insufficient-stock`, `price-changed`, `seller-not-eligible`, `reservation-expired`, `reservation-not-active` e outros); conflitos de estoque (`stock-below-reserved`, `sku-has-stock`); categoria em uso na exclusão (`category-has-children`, `category-has-products`) |
| 412 | `version-mismatch` |
| 422 | demais regras de domínio (`DomainRuleViolation`), por exemplo `product-not-publishable`, `seller-not-eligible`, `category-cycle`, `sku-removal-requires-draft`, `idempotency-key-reused`. O mesmo `seller-not-eligible` sai como 422 em `approve` e `resume` e como 409 na reserva, porque ali é uma `ReservationRefusal`: com esse código quando é o único motivo, ou como `reservation-refused`, com o motivo em `reasons[]`, quando há motivos de outros tipos |
| 428 | `precondition-required` |
| 503 | `service-unavailable`, quando o banco não responde em `catalog.persistence.timeout` (2 s); vem com `Retry-After: 1`. As leituras que montam a resposta de uma gravação de produto, de um item ou em lote, são exceção: a gravação já valeu, e a falha vira aviso no log. Sem o saldo, a resposta sai no status gravado, sem o `OUT_OF_STOCK` calculado e, na resposta de um item, com os SKUs sem `stock`; sem outra leitura, sai sem `actions` e o que depende delas |
| 500 | `internal-error`, sem detalhe; a causa vai só para o log. Inclui a violação das invariantes de saldo e de reserva do domínio (`StockLevel`, `Reservation`), que lança `IllegalStateException` em vez de uma recusa |
| outros | `http-<status>` para `WebApplicationException` do framework (rota inexistente, método ou mídia não aceitos), exceto o 400 do JSON malformado, que sai como `malformed-body` |

## Segurança

- **Token:** MicroProfile JWT valida emissor (`CATALOG_JWT_ISSUER`), audiência (`CATALOG_JWT_AUDIENCE`) e assinatura pelo JWKS (`CATALOG_JWT_JWKS_URL`). [`SecurityConfigCheck`](../src/main/java/dev/marketplace/cataloginventory/security/SecurityConfigCheck.java) impede a partida sem esses três valores.
- **Claims:** `tenant_id` (obrigatório, UUID) e `store_id`, com nomes configuráveis em `catalog.security.tenant-claim` e `store-claim`. Tenant e loja nunca vêm de cabeçalho ou corpo. Sem `tenant_id` válido, a resposta é 403 `tenant-not-authorized` ([`CallerContext`](../src/main/java/dev/marketplace/cataloginventory/security/CallerContext.java)).
- **Papéis:** lidos do claim apontado por `CATALOG_JWT_GROUPS_PATH` (padrão `groups`; no compose, `realm_access/roles`). [`CallerContext`](../src/main/java/dev/marketplace/cataloginventory/security/CallerContext.java) os traduz em capacidades da aplicação. É o único lugar com papel: cada endpoint declara a capacidade que exige (`@PermissionsAllowed`, com os nomes de [`Capabilities`](../src/main/java/dev/marketplace/cataloginventory/security/Capabilities.java)), e o [`CapabilityAugmentor`](../src/main/java/dev/marketplace/cataloginventory/security/CapabilityAugmentor.java) dá à identidade autenticada as capacidades do `CallerContext`. A coluna Papel das tabelas de rotas mostra quem recebe a capacidade hoje:

| Papel ([`Roles`](../src/main/java/dev/marketplace/cataloginventory/security/Roles.java)) | Capacidades |
|---|---|
| `seller` | `MANAGE_OWN_PRODUCTS` |
| `catalog-moderator` | `MODERATE_PRODUCTS`, `READ_SELLER_ELIGIBILITY` |
| `catalog-reservations` | `MANAGE_RESERVATIONS` |
| `catalog-admin` | `MANAGE_TAXONOMY`, `MANAGE_MODERATION_REASONS`, `READ_TENANT_CATALOG`, `READ_SELLER_ELIGIBILITY` |

- **Keycloak local:** [`infra/keycloak/marketplace-realm.json`](../../../infra/keycloak/marketplace-realm.json) cria o realm `marketplace` com os quatro papéis, o cliente público `marketplace-cli` (direct grants) com audiência `catalog-inventory` e mappers de `tenant_id` e `store_id`, e os usuários `seller-demo`, `admin-demo`, `moderator-demo` e `checkout-demo`.

## Persistência

PostgreSQL pelo Reactive SQL Client nas requisições; o JDBC existe só para o Flyway na partida (ADR 006). O schema é `catalog_inventory`, no banco compartilhado `marketplace`, e cada conexão reativa leva o `search_path`. O role de runtime só tem DML; o Flyway migra com o role dono do schema (`QUARKUS_FLYWAY_USERNAME`).

### Tabelas por migration

| Migration | Tabela | Colunas-chave | FKs e restrições |
|---|---|---|---|
| [V1](../src/main/resources/db/migration/V1__catalog.sql) | `category` | `id`, `tenant_id`, `ancestry uuid[]`, `code`, `attributes jsonb`, `active`, `version` | (`tenant_id`, `parent_id`) → `category`; únicos (`tenant_id`, `code`) e (`tenant_id`, `id`) |
| V1 | `product` | `id`, `tenant_id`, `store_id`, `currency`, `title`, `title_key` (título normalizado, `COLLATE "C"`), `attributes`, `status`, `version`, `moderation_queued_at` | (`tenant_id`, `category_id`) → `category`; CHECK de `status` sem `OUT_OF_STOCK`; CHECK de `moderation_queued_at` só em `PENDING_MODERATION`; índices por ordem da listagem, trigram em `title_key` e parcial da fila de moderação |
| V1 | `sku` | `id`, `tenant_id`, `store_id`, `position`, `seller_sku`, `variant_attributes`, `physical`, dimensões | `product_id` → `product`; único (`tenant_id`, `store_id`, `seller_sku`); CHECK de dimensões; trigram em `lower(seller_sku)` |
| V1 | `product_status_change` | `tenant_id`, `store_id`, `transition`, `from_status`, `to_status`, `actor`, `reason_codes text[]`, `reason_labels jsonb` (rótulos da época), `note`, `occurred_at` | `product_id` → `product`; CHECK de rótulos e códigos do mesmo tamanho; índices das séries da loja e da moderação |
| V1 | `seller_eligibility` | PK (`tenant_id`, `store_id`), `status`, `decided_at` | — |
| V1 | `moderation_reason` | PK (`tenant_id`, `code`), `label`, `applies_to_reject`, `applies_to_block`, `position`, `active`, `version` | CHECKs do código em kebab-case, de ao menos uma decisão e da posição de 0 a 9999; sem FK para o histórico |
| [V2](../src/main/resources/db/migration/V2__offer.sql) | `offer` | PK `sku_id`, `currency`, `list_price`, `sale_price`, `price_version`, `version` | `sku_id` → `sku` (CASCADE); `product_id` → `product` |
| V2 | `offer_promotion` | `id`, `sale_price`, `starts_at`, `ends_at` | `sku_id` → `offer` (CASCADE) |
| [V3](../src/main/resources/db/migration/V3__inventory.sql) | `inventory_level` | PK `sku_id`, `on_hand`, `reserved`, `sold`, `version` | `sku_id` → `sku` sem cascata; `product_id` → `product`; CHECKs de não negatividade e `reserved <= on_hand` |
| V3 | `inventory_adjustment` | `sku_id`, `previous_on_hand`, `new_on_hand`, `actor` | sem FK |
| V3 | `inventory_reservation` | `id`, `idempotency_key`, `request_hash`, `status`, `expires_at`, `closed_at` | único (`tenant_id`, `idempotency_key`); CHECK `(status = 'ACTIVE') = (closed_at IS NULL)`; índice parcial em `expires_at` para `ACTIVE` |
| V3 | `inventory_reservation_item` | PK (`reservation_id`, `sku_id`), `quantity`, `unit_price`, `price_version` | `reservation_id` → `inventory_reservation`; sem FK para `sku` |
| [V4](../src/main/resources/db/migration/V4__messaging.sql) | `outbox_event` | PK `seq` (identity), `id` único, `topic`, `message_key`, `event_type`, `payload`, `trace_parent`, `published_at`, `attempts`, `last_error` | — |
| V4 | `inbox_message` | PK (`consumer`, `event_id`), `processed_at` | — |
| [V5](../src/main/resources/db/migration/V5__product_import.sql) | `product_import` | `id`, `status`, `content text`, `total_lines`, `started_at`, contagens por desfecho (`created_lines`, `updated_lines`, `unchanged_lines`, `failed_lines`) | índice parcial dos lotes `PENDING`/`PROCESSING`; índice da lista por loja (`tenant_id`, `store_id`, `created_at`, `id`) |
| V5 | `product_import_line` | PK (`import_id`, `line_number`), `outcome`, `code`, `message`, `reasons` (jsonb), `product_id` | `import_id` → `product_import` (CASCADE) |
| [V6](../src/main/resources/db/migration/V6__outbox_retry.sql) | `outbox_event` | `next_attempt_at`, `parked_at` | índice parcial dos pendentes não estacionados por `seq`; índice parcial dos pendentes por (`topic`, `message_key`, `seq`) |

A V1 só confere a extensão `pg_trgm`: fora dos Dev Services, quem a cria no schema é o provisionamento (`infra/postgres/provision.sh`, com `CATALOG_INVENTORY_DB_EXTENSIONS=pg_trgm`), porque o role do Flyway não tem `CREATE` no banco. O diagrama [dados-er.puml](diagramas/dados-er.puml) mostra as mesmas tabelas com PK e FK.

### Transações e concorrência

- Cada escrita de agregado é uma transação que grava o agregado, o histórico e a outbox juntos.
- **UPDATE condicional por versão:** `product`, `offer` e `category` são atualizados com `WHERE ... AND version = $lida`. Sem linha afetada, o repositório lança `ConcurrentUpdateException` (409).
- **Estoque:** o ajuste usa `SELECT ... FOR UPDATE` em `inventory_level`; a reserva usa `UPDATE ... WHERE on_hand - reserved >= n`, sem trava prévia. Os CHECKs da tabela são a última barreira.
- **Transições de reserva:** compare-and-set em SQL (`WHERE status = 'ACTIVE'`, mais `expires_at > now` no commit e no extend, e `expires_at <= now` na expiração). A expiração trava a reserva com `FOR UPDATE SKIP LOCKED`: a réplica pula a reserva que outra está fechando, e ela volta no ciclo seguinte se ainda estiver ativa.
- **Ordem canônica:** os itens de uma reserva são aplicados na ordem `ReservationPolicy.LOCK_ORDER` (UUID do SKU), em reserva, commit, release e expiração, para evitar deadlock entre reservas concorrentes.
- **Taxonomia:** criação e mudança de pai tomam `pg_advisory_xact_lock` por tenant; o `move` confere também o `ancestry` lido.

## Mensageria

ADR 013. Eventos saem do banco pela outbox; vereditos do KYB/KYC entram por inbox.

### Outbox e relay

1. O repositório traduz os eventos de domínio com [`IntegrationEvents`](../src/main/java/dev/marketplace/cataloginventory/outbox/IntegrationEvents.java) e grava em `outbox_event` na mesma transação ([`PgOutbox`](../src/main/java/dev/marketplace/cataloginventory/persistence/PgOutbox.java)).
2. O [`OutboxRelay`](../src/main/java/dev/marketplace/cataloginventory/messaging/OutboxRelay.java) roda a cada 1 s e drena até 20 lotes de 100. Passa ao próximo lote só se o atual veio cheio e sem falha.
3. Cada lote é uma transação: `pg_try_advisory_xact_lock(hashtextextended('catalog-inventory.outbox-relay', 0))`; sem a trava, não faz nada. Com ela, lê os pendentes por `seq` com `FOR UPDATE`.
4. A leitura pula o evento estacionado (`parked_at`) e o que espera a própria nova tentativa (`next_attempt_at` no futuro). Pula também o evento cuja chave tem um anterior pendente nessas condições, para manter a ordem por chave.
5. Eventos de chaves diferentes saem em paralelo; os de mesmo tópico e chave saem em ordem. Depois da primeira falha num grupo, os seguintes ficam retidos, sem contar tentativa.
6. Envio pelo canal `catalog-events` (`acks=all`, `enable.idempotence=true`), com cabeçalhos `event-type` e `event-id`, o `traceparent` gravado como pai do span e prazo de 10 s.
7. Confirmados recebem `published_at`. A falha incrementa `attempts`, grava `last_error` e adia só aquele evento: `next_attempt_at` = agora + 2^n s (n = tentativas anteriores), no máximo 60 s (`catalog.events.max-backoff`). O relay segue com as outras chaves, sem pausa.
8. Na 20ª falha (`catalog.events.max-attempts`), o evento é estacionado (`parked_at`): sai da fila até o reenvio manual e retém os seguintes da sua chave. O gauge `catalog.outbox.parked` conta esses eventos, e o alerta `CatalogOutboxParked` dispara com algum por 5 min.
9. `MessagingHousekeeping` apaga, a cada minuto, outbox publicada há mais de 7 dias e inbox com mais de 30 dias. Repete lotes de 10 000 enquanto vêm cheios, por até 50 s (`catalog.events.purge-max-duration`) somando as duas tabelas; cada tabela remove pelo menos um lote por execução.

A entrega é pelo menos uma vez: evento publicado e não marcado (queda no meio) sai de novo, e o consumidor deduplica pelo `eventId`.

### Reenviar eventos estacionados

1. Veja o motivo em `last_error` e corrija a causa (tópico, tamanho da mensagem, ACL etc.):

   ```sql
   SELECT seq, topic, message_key, attempts, last_error, parked_at
   FROM outbox_event
   WHERE parked_at IS NOT NULL ORDER BY seq;
   ```

2. Devolva o evento à fila, por `seq` ou por chave. O relay o publica no ciclo seguinte, antes dos retidos da mesma chave:

   ```sql
   UPDATE outbox_event SET parked_at = NULL, next_attempt_at = NULL
   WHERE seq = 123 AND published_at IS NULL;

   UPDATE outbox_event SET parked_at = NULL, next_attempt_at = NULL
   WHERE topic = 'catalog-inventory.product.v1' AND message_key = '<id do agregado>' AND published_at IS NULL;
   ```

3. `attempts` continua no teto, então uma nova falha estaciona o evento de novo na hora. Para dar todas as tentativas outra vez, inclua `attempts = 0` no `SET`.

O role de runtime basta: tem DML na tabela e o `search_path` no schema do catálogo (`infra/postgres/provision.sh`).

### Tópicos, chaves e eventos

Envelope v1 ([`EventContracts`](../src/main/java/dev/marketplace/cataloginventory/outbox/EventContracts.java), schema em [`catalog-inventory-events.v1.schema.json`](../../../contracts/catalog-inventory/events/catalog-inventory-events.v1.schema.json)):

```json
{
  "eventId": "uuid",
  "eventType": "ProductStatusChanged",
  "eventVersion": 1,
  "source": "catalog-inventory",
  "tenantId": "uuid",
  "aggregateType": "product",
  "aggregateId": "uuid",
  "occurredAt": "2026-01-01T00:00:00Z",
  "correlationId": "texto",
  "data": {}
}
```

Valores monetários vão como texto decimal.

| Tópico (padrão) | Chave (`aggregateType`) | `eventType` | Quando |
|---|---|---|---|
| `catalog-inventory.product.v1` | `productId` (`product`) | `ProductCreated` | Criação do rascunho |
| | | `ProductStatusChanged` | Toda transição, exceto a criação |
| | | `ProductBlocked` | Bloqueio, junto com `ProductStatusChanged` |
| `catalog-inventory.offer.v1` | `skuId` (`offer`) | `PriceChanged` | Criação de oferta, mudança de preço, agendamento ou cancelamento de promoção |
| `catalog-inventory.inventory.v1` | `skuId` (`sku`) | `InventoryUpdated` | Ajuste e commit, release ou expiração de reserva (`cause`) |
| | `reservationId` (`reservation`) | `InventoryReserved` | Criação de reserva |
| | `skuId` (`sku`) | `InventoryDepleted` | Disponível chegou a 0 num ajuste ou numa reserva |
| `catalog-inventory.store.v1` | `storeId` (`store`) | `StoreEligibilityChanged` | Veredito do KYB/KYC que mudou a situação da loja |

A extensão de reserva e as mudanças de taxonomia não geram eventos. Os nomes dos tópicos mudam por `CATALOG_EVENTS_PRODUCT_TOPIC`, `_OFFER_TOPIC`, `_INVENTORY_TOPIC` e `_STORE_TOPIC`.

Retenção: o chart cria os tópicos com 6 partições, RF 3 e `min.insync.replicas=2`, sem retenção própria (vale a do broker), exceto a DLQ, com `retention.ms` de 14 dias. No banco, a outbox publicada fica 7 dias e a inbox, 30.

### Consumo do KYB/KYC

- Canal `kyb-seller-verdicts`, tópico `kyb-kyc.seller.v1` (`CATALOG_KYB_SELLER_TOPIC`), grupo `catalog-inventory-service`, `auto.offset.reset=earliest`. Contrato em [`contracts/kyb-kyc/events/seller-verdicts.v1.schema.json`](../../../contracts/kyb-kyc/events/seller-verdicts.v1.schema.json).
- [`SellerVerdictConsumer`](../src/main/java/dev/marketplace/cataloginventory/messaging/SellerVerdictConsumer.java) mapeia `SellerApproved` → `APPROVED`, `SellerRejected` → `REJECTED`, `ReviewRequired` → `PENDING`. `eventType` desconhecido é confirmado sem efeito.
- JSON inválido, `eventVersion` diferente de 1, campo ausente ou UUID e instante inválidos vão direto à DLQ `catalog-inventory.kyb-seller.dlq` (`CATALOG_KYB_SELLER_DLQ`).
- Falhas na aplicação têm retry com backoff de 200 ms a 10 s, até `catalog.events.consumer-retries` (10) vezes; esgotado, a mensagem vai à DLQ.
- [`PgSellerEligibilityRepository.apply`](../src/main/java/dev/marketplace/cataloginventory/persistence/PgSellerEligibilityRepository.java), numa transação: grava `inbox_message` (consumidor `catalog-inventory.seller-eligibility`) com `ON CONFLICT DO NOTHING` (duplicado → `DUPLICATE`); trava a situação atual; faz upsert só se `decided_at` não regride (senão `STALE`); se o status mudou, grava `StoreEligibilityChanged` na outbox.
- O tópico `kyb-kyc.seller.v1` não é criado pelo chart (`create: false`).

## Cache Redis

ADR 024. Desligado por padrão: `%prod` e `%dev` usam `${CATALOG_CACHE_ENABLED:false}`, e o chart tem `cache.enabled: false`. Ligado, exige Redis standalone (`QUARKUS_REDIS_HOSTS`, `QUARKUS_REDIS_PASSWORD`). O Redis fica fora do readiness: fora do ar, tudo é lido do banco.

| O que | Chave ([`CacheKeys`](../src/main/java/dev/marketplace/cataloginventory/cache/CacheKeys.java)) |
|---|---|
| Retrato da vitrine (produto visível, SKUs, ofertas e todas as promoções) | `catalog:storefront:v1:{tenant}:{produto}` |
| Cadeia de categorias até a raiz | `catalog:taxonomy:v1:{tenant}:chain:{categoria}` |
| Página de subcategorias | `catalog:taxonomy:v1:{tenant}:children:{pai\|root}:{limit}:{cursor}` |
| Catálogo de motivos de moderação do tenant, ativos e inativos | `catalog:moderation-reasons:v1:{tenant}` |
| Gerações | `catalog:gen:product:{tenant}:{produto}`, `catalog:gen:store:{tenant}:{loja}`, `catalog:gen:taxonomy:{tenant}`, `catalog:gen:moderation-reasons:{tenant}` |
| Mapa produto → loja | `catalog:product-store:{tenant}:{produto}` |

- **Leitura:** um script Lua (`EVALSHA`, com `EVAL` de reserva) confere conteúdo e gerações numa ida e devolve `HIT`, `MISS` (com as gerações atuais), `UNMAPPED` (sem o mapa produto → loja) ou `BYPASS` (Redis indisponível ou acima de 100 ms). Ver [`CatalogCache`](../src/main/java/dev/marketplace/cataloginventory/cache/CatalogCache.java) e [`StorefrontCache`](../src/main/java/dev/marketplace/cataloginventory/cache/StorefrontCache.java).
- **Gravação:** na falta, a resposta não espera o `SET`. Conteúdo vale 60 s (`catalog.cache.ttl`); gerações, 24 h.
- **Invalidação:** decorators CDI nas portas incrementam a geração depois da escrita, também quando ela falha, com prazo de 1 s e uma nova tentativa ([`CacheInvalidation`](../src/main/java/dev/marketplace/cataloginventory/cache/CacheInvalidation.java)):
  - `ProductRepository.insert/update` e `OfferRepository.insert/update` → geração do produto;
  - `SellerEligibilityRepository.apply` com resultado `APPLIED` ou com falha → geração da loja;
  - `CategoryRepository.insert/update/move/delete` → geração da taxonomia;
  - `ModerationReasonRepository.insert/update` (criar, revisar, ativar e desativar) → geração dos motivos do tenant, e o motivo desativado deixa de valer na decisão seguinte.
- **Não é cacheado:** estoque (lido do banco a cada chamada da vitrine), preço vigente (calculado no instante da leitura), resposta 404, listas de produtos, reservas, ofertas e estoque fora da vitrine.

## Observabilidade

ADRs 017 e 021. Métricas Micrometer em `/q/metrics` e saúde em `/q/health/*`, ambas na porta de gestão 9000. Traces e logs saem por OTLP quando `QUARKUS_OTEL_SDK_DISABLED=false` e `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` apontam para o collector. Em produção o log do console é JSON.

Métricas de negócio e de operação (nomes Micrometer; no Prometheus, pontos viram `_`):

| Métrica | Tags | Origem |
|---|---|---|
| `catalog.products.created` | — | `ProductResource` |
| `catalog.moderation.decisions` | `decision` | `ModerationResource` |
| `catalog.reservations.created`, `.refused`, `.committed` | `reason` em `.refused` | `ReservationResource` |
| `catalog.reservations.expired` | — | `ReservationSweeper` |
| `catalog.inventory.update.latency` | `operation` (`adjust_stock`, `define_offer`, `schedule_promotion`, `cancel_promotion`, `reserve` ...), `outcome` | [`InventoryLatency`](../src/main/java/dev/marketplace/cataloginventory/observability/InventoryLatency.java) |
| `catalog.skus.active`, `catalog.skus.sellable` | — | `SkuGauges` |
| `catalog.imports.lines` | `outcome` | `ProductImportWorker` |
| `catalog.outbox.published`, `.failures`, `.publish.lag`, `.pending`, `.oldest.age`, `.parked` | — | `OutboxRelay` |
| `catalog.kyb.verdicts` | `outcome` | `SellerVerdictConsumer` |
| `catalog.cache.requests`, `.writes`, `.invalidations` | `cache`, `result` | `CatalogCache` |

Traces: span por requisição, por tarefa agendada (`quarkus.scheduler.tracing.enabled`), por consulta e por passagem no cache; atributos `marketplace.tenant.id`, `marketplace.store.id`, `marketplace.correlation.id`, `marketplace.problem.type` e outros de [`Telemetry`](../src/main/java/dev/marketplace/cataloginventory/observability/Telemetry.java). O relay usa o `trace_parent` gravado na outbox como pai do envio.

SLOs (30 dias):

- Disponibilidade: 99,9% das requisições de negócio da api sem 5xx.
- Latência: 99% das escritas de inventário bem-sucedidas em até 500 ms (`catalog.inventory.update.latency`).
- Alertas por taxa de consumo do orçamento em janelas múltiplas.

Onde ficam:

| Artefato | Caminho |
|---|---|
| Regras de SLO e alertas | [`catalog-inventory-slo.yaml`](../../../infra/observability/prometheus/rules/catalog-inventory-slo.yaml), [`catalog-inventory.yaml`](../../../infra/observability/prometheus/rules/catalog-inventory.yaml) |
| Testes do promtool | [`infra/observability/prometheus/tests`](../../../infra/observability/prometheus/tests) |
| Dashboards Grafana | [`infra/observability/grafana/dashboards/catalogo`](../../../infra/observability/grafana/dashboards/catalogo) (`servico`, `negocio`, `fluxo`, `carga`) |
| Collector, Prometheus, Tempo, Loki, Alloy | [`infra/observability`](../../../infra/observability) |

Alertas de [`catalog-inventory.yaml`](../../../infra/observability/prometheus/rules/catalog-inventory.yaml): `CatalogInventoryDown`, `CatalogOutboxBacklog`, `CatalogOutboxPublishFailures`, `CatalogOutboxParked`, `CatalogKybDeadLetters`, `CatalogKybConsumerLag`, `CatalogSqlPoolSaturated`, `PostgresDown`, `RedisDown`, `ObservabilityTargetDown`.

## Configuração

Propriedades `catalog.*` têm default em [`CatalogConfig`](../src/main/java/dev/marketplace/cataloginventory/config/CatalogConfig.java) e aceitam a variável de ambiente correspondente.

| Variável ou propriedade | Default | Uso |
|---|---|---|
| `QUARKUS_PROFILE` | — | `prod,api` ou `prod,worker` |
| `QUARKUS_DATASOURCE_REACTIVE_URL` | — | Banco nas requisições |
| `QUARKUS_DATASOURCE_JDBC_URL` | — | Banco do Flyway |
| `QUARKUS_DATASOURCE_USERNAME` / `_PASSWORD` | — | Role de runtime (só DML) |
| `QUARKUS_FLYWAY_USERNAME` / `_PASSWORD` | as do datasource | Role dono do schema |
| `QUARKUS_FLYWAY_SCHEMAS` | `catalog_inventory` | Schema e `search_path` |
| `KAFKA_BOOTSTRAP_SERVERS` | — (obrigatório em prod) | Kafka |
| `CATALOG_KYB_SELLER_TOPIC` | `kyb-kyc.seller.v1` | Tópico consumido |
| `CATALOG_KYB_SELLER_DLQ` | `catalog-inventory.kyb-seller.dlq` | DLQ |
| `CATALOG_EVENTS_*_TOPIC` | `catalog-inventory.<agregado>.v1` | Tópicos publicados |
| `CATALOG_JWT_ISSUER`, `CATALOG_JWT_AUDIENCE`, `CATALOG_JWT_JWKS_URL` | dev: Keycloak em `localhost:8180`, audiência `catalog-inventory` | Validação do token |
| `CATALOG_JWT_GROUPS_PATH` | `groups` | Claim dos papéis |
| `CATALOG_CACHE_ENABLED` | `false` em prod e dev | Liga o cache |
| `QUARKUS_REDIS_HOSTS`, `QUARKUS_REDIS_PASSWORD` | — | Redis do cache |
| `catalog.cache.ttl` / `timeout` / `invalidation-timeout` / `generation-ttl` | 60 s / 100 ms / 1 s / 24 h | Cache |
| `catalog.persistence.timeout` | 2 s | Prazo do banco (503 acima dele) |
| `catalog.reservations.default-ttl` / `max-ttl` / `max-lifetime` / `sweep-batch` | 15 min / 2 h / 24 h / 200 | Reservas |
| `catalog.events.relay-batch` / `relay-max-batches` / `publish-timeout` / `max-backoff` / `max-attempts` | 100 / 20 / 10 s / 60 s / 20 | Relay; `max-backoff` limita a espera de cada evento e `max-attempts` o estaciona |
| `catalog.events.purge-interval` / `purge-batch` / `purge-max-duration` | 1 min / 10 000 / 50 s | Limpeza contínua; o prazo vale para as duas tabelas |
| `catalog.events.outbox-retention` / `inbox-retention` | 7 d / 30 d | Limpeza |
| `catalog.events.consumer-retries` | 10 | Retry do consumidor KYB |
| `catalog.imports.max-rows` / `lease` | 5000 / 5 min | Importação |
| `QUARKUS_OTEL_SDK_DISABLED` | `true` | `false` liga traces e logs OTLP |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | — | Collector |
| `CATALOG_SWAGGER_UI_ENABLED` | `false` em prod | Swagger UI em `/q/swagger-ui` |

Outros fixos em `application.properties`: corpo HTTP até 1 MB, `quarkus.shutdown.timeout=25S`, pool Redis de 16 conexões e 256 pedidos na fila.

## Rodar localmente

Em modo dev (`quarkus:dev` ou testes), os Dev Services sobem PostgreSQL, Kafka e Redis efêmeros e a API ouve na porta 8083. Os comandos Nx ficam no [README da raiz](../../../README.md).

Com o compose ([`infra/local/docker-compose.yml`](../../../infra/local/docker-compose.yml), serviços do catálogo em [`compose/catalog.yml`](../../../infra/local/compose/catalog.yml)):

```bash
docker compose -f infra/local/docker-compose.yml --profile catalog up --build
```

| Serviço | Porta no host | Depende de |
|---|---|---|
| `catalog-api-1` | 9301 → 9000 (gestão); HTTP pelo `gateway` | `postgres-init`, `redis`, `keycloak`, `otel-collector` |
| `catalog-api-2` | 9302 → 9000 (gestão); HTTP pelo `gateway` | `postgres-init`, `redis`, `keycloak`, `otel-collector` |
| `gateway` | 8080 (painel e `/api/catalog-inventory`), 8083 → API balanceada entre as duas instâncias | `catalog-api-1`, `catalog-api-2` |
| `catalog-worker` | 9311 → 9000 (gestão) | `postgres-init`, `redis`, `kafka-init`, `otel-collector` |

- As portas são publicadas só em `127.0.0.1`.
- `postgres-init` roda `infra/postgres/provision.sh` (schema e roles); `kafka-init` cria os tópicos; o Keycloak importa o realm.
- O cache segue desligado; `CATALOG_CACHE_ENABLED=true` liga.
- O perfil `observability` sobe Prometheus, Tempo, Loki, Alloy, Grafana e os exporters.

Para obter um token de teste, use o cliente `marketplace-cli` com um dos usuários demo do realm.

## Implantar no Kubernetes

Helm chart [`infra/helm/catalog-inventory`](../../../infra/helm/catalog-inventory) (ADR 018), versão 0.1.0, Kubernetes 1.30 ou superior. O passo a passo com os Secrets está no [README da raiz](../../../README.md#implantação).

- Dois Deployments do mesmo template: `api` (2 réplicas) e `worker` (1 réplica), com `RollingUpdate` sem indisponibilidade.
- Service `ClusterIP` (80 → `http`) só da api; Service `-metrics` na 9000 para os dois papéis; ServiceMonitor opcional.
- HPA só da api, desligado por padrão (2 a 6 réplicas, 70% de CPU). PDB por papel (api `minAvailable: 1`, worker `maxUnavailable: 1`).
- Probes na porta `management` (9000): `/q/health/started`, `/q/health/ready`, `/q/health/live`.
- Job `<fullname>-topics` (contêiner `kafka-topics`) como hook `pre-install,pre-upgrade` cria os tópicos do catálogo e a DLQ.
- O chart não cria Secrets nem o schema: banco (runtime e migrator), cliente Kafka e Redis vêm de Secrets existentes.
- Não há Job de migração: o Flyway roda na partida dos dois papéis.

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas). Os C4 usam a biblioteca embutida (`!include <C4/...>`) e trazem legenda. Para renderizar, veja o comando em [docs/catalog-inventory.md](../../../docs/catalog-inventory.md#diagramas).

| Diagrama | O que mostra e como ler |
|---|---|
| [pacotes.puml](diagramas/pacotes.puml) | Os 3 módulos, os 16 pacotes Java e as dependências reais entre eles (derivadas dos imports), sem ciclos, com a regra de camadas. |
| [c4-conteiner.puml](diagramas/c4-conteiner.puml) | C4 nível 2: api e worker, PostgreSQL, Redis, Kafka, Keycloak, OTel Collector, atores e sistemas externos. Setas indicam quem chama quem e por qual protocolo. |
| [c4-componente-api.puml](diagramas/c4-componente-api.puml) | C4 nível 3 da api: filtros, resources, casos de uso, cache, decorators, repositórios `Pg*` e outbox. Siga da esquerda (cliente) até o banco. |
| [c4-componente-worker.puml](diagramas/c4-componente-worker.puml) | C4 nível 3 do worker: os cinco agendadores, `SellerVerdictConsumer`, `OutboxRelay` e o canal Kafka. |
| [implantacao-kubernetes.puml](diagramas/implantacao-kubernetes.puml) | Recursos do Helm chart: Deployments, Services, HPA, PDB, ConfigMap, Job de tópicos e Secrets externos. |
| [implantacao-local.puml](diagramas/implantacao-local.puml) | Compose local: contêineres, portas publicadas e o perfil `observability`. |
| [dados-er.puml](diagramas/dados-er.puml) | Modelo de dados V1 a V6 agrupado por migration, com PK, FK e restrições únicas. |
| [estados-outbox.puml](diagramas/estados-outbox.puml) | Estados implícitos de uma linha de `outbox_event`: pendente, em espera, estacionada, publicada e removida, com o reenvio manual. |
| [sequencia-publicacao-produto.puml](diagramas/sequencia-publicacao-produto.puml) | Criar produto, definir oferta, ajustar estoque, enviar e aprovar, com o UPDATE condicional. |
| [sequencia-ajuste-estoque.puml](diagramas/sequencia-ajuste-estoque.puml) | `PUT /inventory/skus/{skuId}` com `FOR UPDATE`, auditoria e eventos. |
| [sequencia-reserva-ciclo.puml](diagramas/sequencia-reserva-ciclo.puml) | Reserva idempotente, commit, release, extend e expiração pelo `ReservationSweeper`. |
| [sequencia-importacao-csv.puml](diagramas/sequencia-importacao-csv.puml) | Envio do CSV pela api e processamento pelo worker com `SKIP LOCKED` e retomada. |
| [sequencia-outbox-kafka.puml](diagramas/sequencia-outbox-kafka.puml) | Gravação transacional, relay com trava consultiva, publicação por tópico e chave, espera por evento, estacionamento e limpeza. |
| [sequencia-veredito-kyb.puml](diagramas/sequencia-veredito-kyb.puml) | Consumo do veredito: inbox, `DUPLICATE`/`STALE`/`APPLIED`, `StoreEligibilityChanged`, invalidação e DLQ. |
| [sequencia-vitrine-cache.puml](diagramas/sequencia-vitrine-cache.puml) | Leitura da vitrine com gerações (`HIT`, `MISS`, `UNMAPPED`, `BYPASS`) e estoque sempre do banco. |
| [sequencias-produto.puml](diagramas/sequencias-produto.puml) | Uma página por operação de `ProductService` (14): criar, ler, listar com busca e ordem, conferir o rascunho, histórico, revisar conteúdo, adicionar/revisar/remover SKU, enviar, pausar, retomar, arquivar e o lote, com as recusas reais. |
| [sequencias-moderacao.puml](diagramas/sequencias-moderacao.puml) | `ModerationService` (6 páginas): fila por ordem de chegada, aprovar, rejeitar, bloquear, desbloquear e o lote. |
| [sequencias-oferta.puml](diagramas/sequencias-oferta.puml) | `OfferService` (4 páginas): ler, definir ou trocar preço, agendar e cancelar promoção. |
| [sequencias-estoque.puml](diagramas/sequencias-estoque.puml) | `InventoryService` (3 páginas): `stockOf` (sem endpoint próprio), `get` e `adjust`. |
| [sequencias-importacao.puml](diagramas/sequencias-importacao.puml) | `ProductImportService` (5 páginas): enviar, acompanhar (com as linhas ou só o resumo, `lines=false`), listar os lotes, reivindicar e processar por unidade. |
| [sequencias-categoria.puml](diagramas/sequencias-categoria.puml) | `CategoryService` (9 páginas): criar, ler, revisar, mover, listar filhos, ler a árvore, ler pelo id, excluir e o lote, com `TaxonomyCache`, trava consultiva da árvore e `If-Match`. |
| [sequencias-reserva.puml](diagramas/sequencias-reserva.puml) | `ReservationService` (6 páginas): reservar, ler, confirmar, liberar, estender e expirar pelo `ReservationSweeper` com `SKIP LOCKED`. |
| [sequencias-elegibilidade.puml](diagramas/sequencias-elegibilidade.puml) | `SellerEligibilityService`: consulta da situação KYB (403 para outra loja, `PENDING` sem veredito) e registro do veredito. |
| [sequencias-infraestrutura.puml](diagramas/sequencias-infraestrutura.puml) | Fluxos sem caso de uso de negócio: limpeza da mensageria, invalidação pelos decorators, `CatalogCache` com `Snapshots`, `SkuGauges` e `GET /internal/info`. |
| [classes-api.puml](diagramas/classes-api.puml) | Resources, filtros, `ProblemMappers` (com a tabela de status HTTP) e classes de apoio do pacote `api`. |
| [classes-api-modelos.puml](diagramas/classes-api-modelos.puml) | DTOs de produto, categoria, moderação, lotes e vitrine. |
| [classes-api-modelos-operacao.puml](diagramas/classes-api-modelos-operacao.puml) | DTOs de oferta, estoque, reserva, importação e indicadores. |
| [classes-persistencia.puml](diagramas/classes-persistencia.puml) | Os 9 repositórios `Pg*`, `PgOutbox`, `PgSupport`, `PgCatalogMetrics` e as portas que implementam. |
| [classes-mensageria.puml](diagramas/classes-mensageria.puml) | `IntegrationEvents`, `OutboxRelay`, `MessagingHousekeeping`, `SellerVerdictConsumer`, `Correlation` e `EventOrigin`. |
| [classes-mensageria-contrato.puml](diagramas/classes-mensageria-contrato.puml) | `EventContracts` v1 e os 14 records publicados. |
| [classes-cache.puml](diagramas/classes-cache.puml) | `CatalogCache`, os 5 decorators de invalidação, `StorefrontCache`, `TaxonomyCache`, `ModerationReasonCache` e `Snapshots`. |
| [classes-importacao.puml](diagramas/classes-importacao.puml) | `CsvParser`, `ProductCsv`, `Offload`, `InvalidImportFileException` e `ProductImportWorker`. |
| [classes-seguranca-observabilidade.puml](diagramas/classes-seguranca-observabilidade.puml) | Pacotes `security` e `observability`. |
| [classes-raiz.puml](diagramas/classes-raiz.puml) | `CatalogConfig` (com as interfaces aninhadas), `CatalogBeans`, `ReservationSweeper` e `ServiceInfoResource`. |

Os diagramas de classes e de estados do domínio ficam na documentação de [`libs/catalog-inventory/domain`](../../../libs/catalog-inventory/domain/docs/README.md).

## Divergências conhecidas

Diferenças confirmadas entre as ADRs em [docs/decisoes.md](../../../docs/decisoes.md) e o código:

1. **Porta HTTP (ADR 020).** A ADR diz "HTTP de negócio na porta 8080". O código define `quarkus.http.port=8083`; a 8080 vale só no contêiner, porque [`Dockerfile.native`](../../../infra/docker/Dockerfile.native) define `QUARKUS_HTTP_PORT=8080`. Em dev e na JVM local, a porta é 8083.
2. **Vitrine e cache (ADR 011 × ADR 024).** A ADR 011 diz que produto, situação do seller e ofertas da vitrine "vêm do cache Redis". A ADR 024 e a configuração deixam o cache desligado por padrão em dev, compose, produção e chart; na prática, a vitrine lê do banco, salvo opt-in.
3. **Default do cache.** `CatalogConfig.Cache.enabled` tem `@WithDefault("true")`. O "desligado por padrão" depende só dos overrides `%prod` e `%dev` em `application.properties`; num perfil que não inclua `prod` nem `dev` (como os testes), o cache liga.
4. **Remoção de SKU só em `DRAFT`.** `Product.removeSku` recusa com 422 `sku-removal-requires-draft` fora de `DRAFT`. Nenhuma ADR registra essa regra; a ADR 012 cita apenas o 409 `sku-has-stock` para SKU com saldo ou reserva.
5. **Bloqueio emite dois eventos.** O `BLOCK` grava `ProductStatusChanged` e `ProductBlocked` na mesma operação. A ADR 013 lista os dois tipos sem dizer que saem juntos; é complemento, não contradição.
