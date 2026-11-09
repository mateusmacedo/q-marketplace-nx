# Domínio do catálogo-inventário

Módulo `catalog-inventory-domain` (`libs/catalog-inventory/domain`). Visão geral do contexto em [docs/catalog-inventory.md](../../../../docs/catalog-inventory.md).

## Propósito

Guarda as regras de negócio do catálogo-inventário em Java puro, sem framework (ADR 002 e ADR 010 em [decisoes.md](../../../../docs/decisoes.md)):

- agregados `Category`, `Product`, `Offer` e `Reservation`, mais o saldo `StockLevel`;
- invariantes, com códigos de recusa estáveis;
- máquinas de estado do produto e da reserva;
- políticas de publicação (`PublishingPolicy`) e de reserva (`ReservationPolicy`);
- eventos de domínio que os repositórios gravam na outbox.

O `build.gradle` não declara dependência de produção. O verificador de limites da ADR 010 só permite `java.*` (exceto `java.sql`) e o próprio pacote `dev.marketplace.cataloginventory.domain`.

### O que não está aqui

| Assunto | Onde fica |
|---|---|
| Casos de uso, portas de repositório, regras de acesso, usabilidade da categoria pela cadeia, regra da vida máxima da reserva na extensão | [`catalog-inventory-application`](../../application/docs/README.md) |
| Persistência, SQL das transições da reserva, outbox, Kafka, consumo dos vereditos KYB/KYC, cache, HTTP e tradução de erros | [`catalog-inventory-service`](../../../../apps/catalog-inventory-service/docs/README.md) |
| Concorrência otimista na gravação (`version` como ETag) | aplicação e serviço; o domínio só guarda `version` e expõe `markPersisted` |
| Envelope e schema dos eventos de integração | serviço e [contratos](../../../../contracts/catalog-inventory/README.md) |
| Importação CSV, `ImportStatus` | aplicação |

## Glossário

| Termo | Significado no código |
|---|---|
| Produto | `Product`: conteúdo (título, descrição, atributos, categoria), SKUs e ciclo de publicação de uma loja. A moeda é fixada na criação. |
| SKU | `Sku`: variação vendável do produto. Tem código do seller (`sellerSku`), atributos de variante e, se físico, dimensões. Não tem status. |
| Oferta | `Offer`: preço e promoções de um SKU. Uma por SKU, chave `skuId`. Não tem status. |
| Preço de/por | `Price`: `listPrice` ("de", opcional) e `salePrice` ("por", obrigatório). O "de" nunca é menor que o "por". |
| Promoção | `Promotion`: preço promocional válido em `[startsAt, endsAt)`. Início inclusivo, fim exclusivo. |
| Preço vigente | `EffectivePrice`: `salePrice` da promoção ativa no instante ou, sem promoção, o "por". Leva a `priceVersion` atual. |
| Saldo físico | `StockLevel.onHand`: estoque vendável informado pela loja. |
| Saldo reservado | `StockLevel.reserved`: quantidade retida por reservas `ACTIVE`. |
| Saldo vendido | `StockLevel.sold`: contador de auditoria, sobe na confirmação. |
| Saldo disponível | `available() = onHand - reserved`. |
| Reserva | `Reservation`: retenção de estoque com prazo (`expiresAt`), feita pelo checkout. Cada item guarda o preço e a `priceVersion` do momento. |
| Elegibilidade | `SellerStatus`: situação KYB/KYC da loja vista pelo catálogo. Só `APPROVED` é elegível (`isEligible`). |
| Atributo efetivo | Atributos da cadeia da raiz até a categoria (`Category.effectiveAttributes`). `variant = true` indica atributo de SKU. |

## Modelo

[diagramas/dominio-classes.puml](diagramas/dominio-classes.puml): classes por pacote (`category`, `product`, `offer`, `inventory` e a raiz `domain`). Composição (losango cheio) indica parte do agregado; seta tracejada com `skuId` ou `categoryId` indica referência por identificador entre agregados.

### Raiz do pacote

| Tipo | Papel | Regras |
|---|---|---|
| [`Money`](../src/main/java/dev/marketplace/cataloginventory/domain/Money.java) | VO | Valor ≥ 0, até 2 casas (normaliza para 2), máximo `99999999999999999.99`; moeda ISO 4217 (`[A-Z]{3}`). Comparar moedas diferentes recusa com `invalid-value`. |
| [`Attributes`](../src/main/java/dev/marketplace/cataloginventory/domain/Attributes.java) | utilitário | Cópia imutável e ordenada de atributos; chave ou valor nulo recusa com `invalid-value`. |
| [`DomainRuleViolation`](../src/main/java/dev/marketplace/cataloginventory/domain/DomainRuleViolation.java) | exceção | Recusa de negócio com `code` e `reasons`. Ver [Exceções](#exceções-e-tradução-no-serviço). |
| [`Reason`](../src/main/java/dev/marketplace/cataloginventory/domain/Reason.java) | VO | Motivo de recusa: `code`, `params` e `message` opcional. `key()` dá a forma compacta de `reasons` (`missing-variant-attribute:CAM-P:cor`). |
| [`ReasonCode`](../src/main/java/dev/marketplace/cataloginventory/domain/ReasonCode.java), [`DomainReasons`](../src/main/java/dev/marketplace/cataloginventory/domain/DomainReasons.java) | registro | Códigos estáveis em kebab-case, derivados do nome da constante. Código novo nasce no enum e ganha texto no catálogo de mensagens do serviço. |
| [`InvalidTransitionException`](../src/main/java/dev/marketplace/cataloginventory/domain/InvalidTransitionException.java) | exceção | Código `invalid-transition`. |

### Categoria (`category`)

[`Category`](../src/main/java/dev/marketplace/cataloginventory/domain/category/Category.java) (agregado): `id`, `tenantId`, `ancestry` (ids da raiz até o pai), `code`, `name`, `attributes`, `active`, `version`.

| Invariante | Recusa |
|---|---|
| Profundidade: `ancestry` com menos de `MAX_DEPTH = 6` níveis; mover considera a altura da subárvore | `category-too-deep` |
| `code` kebab-case minúsculo, até 60 caracteres; `name` até 120 | `invalid-value` |
| Até 50 atributos próprios, sem código repetido | `invalid-value` |
| Código de atributo não se repete no caminho (ancestrais, própria, descendentes) | `attribute-conflict`, com `attribute-conflict:<código>` em `reasons` |
| `childOf` exige pai direto ativo | `category-inactive` |
| `moveUnder` não aceita a própria categoria nem descendente como novo pai; aceita pai inativo | `category-cycle` |
| `requireRemovable` só exclui categoria sem subcategorias; os produtos, inclusive arquivados, são conferidos na gravação (`category-has-products`) | `category-has-children` (`CategoryNotEmptyException`) |

[`AttributeDefinition`](../src/main/java/dev/marketplace/cataloginventory/domain/category/AttributeDefinition.java) (VO): `code` (`[a-z][a-z0-9_]{0,39}`), `label` (até 80), `type`, `required`, `variant`, `options`. `OPTION` exige de 1 a 100 opções distintas; os outros tipos não aceitam opções. `accepts(value)` valida o valor: `TEXT` até 500 caracteres, `INTEGER` com até 18 dígitos, `DECIMAL` com até 18 dígitos inteiros e 6 decimais, os dois em notação simples, com ponto e sem expoente, `BOOLEAN` só `true` ou `false`, `OPTION` uma das opções.

[`AttributeType`](../src/main/java/dev/marketplace/cataloginventory/domain/category/AttributeType.java): `TEXT`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `OPTION`.

### Produto (`product`)

[`Product`](../src/main/java/dev/marketplace/cataloginventory/domain/product/Product.java) (agregado raiz): `id`, `tenantId`, `storeId`, `currency`, `categoryId`, `title`, `description`, `attributes`, `skus`, `status`, `version`, `createdAt`, `updatedAt`. Acumula `StatusChange` e `ProductEvent` até o repositório drená-los (`drainChanges`, `drainEvents`).

| Invariante | Recusa |
|---|---|
| `title` obrigatório, até 200; `description` até 5000 | `invalid-value` |
| Até 100 SKUs (zero é aceito no rascunho; a publicação exige ao menos um) | `invalid-value` |
| `sellerSku` único dentro do produto | `duplicate-seller-sku` |
| Atributos do produto e dos SKUs aceitos pelos atributos efetivos | `invalid-attributes`, com `unknown-attribute:<c>` ou `invalid-attribute:<c>` em `reasons` |
| Categoria ativa na criação e na revisão de conteúdo | `category-inactive` |
| Categoria do mesmo tenant na revisão de conteúdo | `invalid-value` |
| Edição só fora de `BLOCKED` e `ARCHIVED` (`isEditable`) | `invalid-transition` (operação `EDIT`) |
| Remover SKU só em `DRAFT` | `sku-removal-requires-draft` |
| SKU informado pertence ao produto | `sku-not-found` |
| Motivo pronto do catálogo do tenant ou complemento de até 500, em `REJECT` e `BLOCK` (`DecisionReason`) | `invalid-value`; motivo ausente, inativo ou de outra decisão: `invalid-moderation-reason` |
| Oferta e estoque congelados com produto arquivado (`requireNotArchived`) | `invalid-transition` |

[`Sku`](../src/main/java/dev/marketplace/cataloginventory/domain/product/Sku.java) (entidade interna): `id`, `sellerSku` (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`), `variantAttributes`, `physical`, `dimensions`. SKU físico sem dimensões recusa com `missing-dimensions`; SKU não físico com dimensões, com `invalid-value`.

[`Dimensions`](../src/main/java/dev/marketplace/cataloginventory/domain/product/Dimensions.java) (VO): `heightCm`, `widthCm`, `lengthCm` positivos, até 1000 cm e 2 casas; `weightGrams` de 1 a 1.000.000.

[`StatusChange`](../src/main/java/dev/marketplace/cataloginventory/domain/product/StatusChange.java) (VO): registro de auditoria de cada transição (`transition`, `from`, `to`, `actor`, `reasons`, `note`, `at`). Na rejeição e no bloqueio, `reasons` traz os motivos prontos com o rótulo da época ([`ReasonLabel`](../src/main/java/dev/marketplace/cataloginventory/domain/product/ReasonLabel.java)) e `note`, o complemento; `reason()` deriva o texto único.

[`ModerationReasonDefinition`](../src/main/java/dev/marketplace/cataloginventory/domain/product/ModerationReasonDefinition.java) (VO): motivo pronto do catálogo do tenant (`code`, `label`, `decisions`, `active`), que é dado (ADR 030). O domínio guarda só as regras: código em kebab-case só com letras, até 60 caracteres, que não muda (`revised` e `withActive` o preservam); rótulo de até 120; decisões não vazias, só `REJECT` e `BLOCK`.

[`DecisionReason`](../src/main/java/dev/marketplace/cataloginventory/domain/product/DecisionReason.java) (VO): `of(decision, catalog, codes, note)` confere os códigos contra o catálogo do tenant e recusa com `invalid-moderation-reason` e os motivos `unknown-reason:<código>`, `reason-inactive:<código>` e `reason-not-applicable:<código>`; os dois últimos levam o rótulo na mensagem. Guarda os rótulos na ordem do catálogo e o complemento, de até 500 caracteres; sem motivo nem complemento, `invalid-value`.

Enums: [`ProductStatus`](../src/main/java/dev/marketplace/cataloginventory/domain/product/ProductStatus.java), [`ProductTransition`](../src/main/java/dev/marketplace/cataloginventory/domain/product/ProductTransition.java), [`TransitionSide`](../src/main/java/dev/marketplace/cataloginventory/domain/product/TransitionSide.java) e [`SellerStatus`](../src/main/java/dev/marketplace/cataloginventory/domain/product/SellerStatus.java). Os nomes de `ProductTransition` são gravados e publicados; não renomear. `ProductTransition.side()` diz quem faz a transição: `STORE` (criar, enviar, pausar, reativar, arquivar e a volta à moderação) ou `MODERATION` (aprovar, rejeitar, bloquear e desbloquear).

`Product.moderationQueuedAt` guarda quando o produto entrou em `PENDING_MODERATION` (envio, desbloqueio ou volta à moderação); fica vazio nos demais estados. A fila de moderação sai nessa ordem, que não muda com edições feitas na fila.

### Oferta (`offer`)

[`Offer`](../src/main/java/dev/marketplace/cataloginventory/domain/offer/Offer.java) (agregado separado do produto, ADR 014): `skuId` (chave), `productId`, `tenantId`, `storeId`, `currency`, `price`, `priceVersion`, `promotions`, `version`.

| Invariante | Recusa |
|---|---|
| Preço na moeda da oferta (a do produto) | `currency-mismatch` |
| Promoção na mesma moeda do preço | `invalid-value` |
| Promoções pendentes sem sobreposição (intervalos que só se tocam não se sobrepõem) | `promotion-overlap` |
| Promoção não acima do preço "de", quando houver | `promotion-above-list-price` |
| Até 20 promoções pendentes; promoção nova termina no futuro | `invalid-value` |
| Cancelar só promoção existente e não encerrada | `promotion-not-found`, `promotion-ended` |

- `create` nasce com `priceVersion = 1`. `changePrice`, `schedulePromotion` e `cancelPromotion` sobem `priceVersion` e registram `PriceChanged`.
- Promoções encerradas são descartadas na próxima mudança.
- `effectivePrice(now)` não altera a oferta.

VOs: [`Price`](../src/main/java/dev/marketplace/cataloginventory/domain/offer/Price.java) (`list-price-below-sale-price` se "de" < "por"), [`Promotion`](../src/main/java/dev/marketplace/cataloginventory/domain/offer/Promotion.java) (início antes do fim, janela até 366 dias; `phaseAt(instante)` devolve a [`PromotionPhase`](../src/main/java/dev/marketplace/cataloginventory/domain/offer/PromotionPhase.java): `SCHEDULED`, `ACTIVE` ou `ENDED`) e [`EffectivePrice`](../src/main/java/dev/marketplace/cataloginventory/domain/offer/EffectivePrice.java).

### Estoque e reserva (`inventory`)

[`StockLevel`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/StockLevel.java) (VO de estado, um por SKU): `skuId`, `onHand`, `reserved`, `sold`, `version`.

- Invariante: contadores ≥ 0 e `reserved ≤ onHand`, espelhando os CHECKs do banco. Violação lança `IllegalStateException` (falha técnica, não recusa).
- `requireOnHand` aceita de 0 a 1.000.000; fora disso, `invalid-value`.

[`Reservation`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/Reservation.java) (agregado, record imutável): `id`, `tenantId`, `idempotencyKey`, `requestHash`, `orderRef`, `status`, `items`, `createdAt`, `expiresAt`, `closedAt`.

- `expiresAt` posterior a `createdAt`; senão, `IllegalStateException`.
- `overdueAt(now)`: `ACTIVE` com prazo vencido. Não pode ser confirmada, mesmo antes de o varredor expirá-la.

Records de apoio:

| Record | Conteúdo |
|---|---|
| [`ReservationItem`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/ReservationItem.java) | Snapshot por item: `skuId`, `productId`, `storeId`, `quantity`, `unitPrice`, `priceVersion` |
| [`RequestedItem`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/RequestedItem.java) | Pedido do checkout: `skuId`, `quantity` (1 a 1000), `expectedUnitPrice` |
| [`SkuCandidate`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/SkuCandidate.java) | Leitura do SKU para decidir a reserva: `productStatus`, `seller`, `offer` |

## Máquinas de estado

### Produto

[diagramas/estados-produto.puml](diagramas/estados-produto.puml): `ProductStatus` e as transições de `ProductTransition`. Os estados compostos agrupam `isEditable` e `isPublished`; a seta tracejada para `OUT_OF_STOCK` é cálculo de leitura, não transição.

| Transição | De | Para | Comando | Guarda |
|---|---|---|---|---|
| `CREATE` | (nenhum) | `DRAFT` | `Product.draft` | categoria ativa; atributos válidos; SKUs válidos |
| `SUBMIT` | `DRAFT` | `PENDING_MODERATION` | `submit` | sem motivos de `PublishingPolicy` (`product-not-publishable`) |
| `APPROVE` | `PENDING_MODERATION` | `ACTIVE` | `approve` | publicável e loja `APPROVED` (`seller-not-eligible`) |
| `REJECT` | `PENDING_MODERATION` | `DRAFT` | `reject` | motivo obrigatório |
| `BLOCK` | todos, exceto `BLOCKED` e `ARCHIVED` | `BLOCKED` | `block` | motivo obrigatório |
| `UNBLOCK` | `BLOCKED` | `PENDING_MODERATION` | `unblock` | nenhuma |
| `PAUSE` | `ACTIVE` | `PAUSED_BY_SELLER` | `pause` | nenhuma |
| `RESUME` | `PAUSED_BY_SELLER` | `ACTIVE` | `resume` | loja `APPROVED` |
| `ARCHIVE` | todos, exceto `ARCHIVED` | `ARCHIVED` | `archive` | nenhuma |
| `REVIEW_REQUIRED` | `ACTIVE`, `PAUSED_BY_SELLER` | `PENDING_MODERATION` | automática em `reviseContent`, `addSku`, `reviseSku` | produto publicado (`isPublished`) |

- Origem fora da lista: `invalid-transition`.
- Estado inicial `DRAFT`; `ARCHIVED` é final.
- Editar em `DRAFT` ou `PENDING_MODERATION` só atualiza `updatedAt`. `removeSku` não registra transição.
- `OUT_OF_STOCK` nunca é gravado: `effectiveStatus(anyAvailable)` devolve `OUT_OF_STOCK` para `ACTIVE` sem saldo disponível (ADR 012).
- `isVisible` vale só para `ACTIVE`. `isPubliclyVisible(seller)` exige também loja `APPROVED`.

### Reserva

[diagramas/estados-reserva.puml](diagramas/estados-reserva.puml): `ReservationStatus`. A autotransição em `ACTIVE` é a extensão de prazo; os três estados finais devolvem ou baixam o saldo.

| De | Para | Comando | Guarda | Efeito no saldo |
|---|---|---|---|---|
| (nenhum) | `ACTIVE` | `ReservationPolicy.open` + gravação | itens elegíveis; saldo `on_hand - reserved >= n` em todos os SKUs | `reserved += n` |
| `ACTIVE` | `ACTIVE` | extend | não vencida; novo prazo até `createdAt + 24 h` | nenhum; sem evento |
| `ACTIVE` | `COMMITTED` | commit | `expires_at > agora` | `onHand -= n`, `reserved -= n`, `sold += n` |
| `ACTIVE` | `RELEASED` | release | ativa | `reserved -= n` |
| `ACTIVE` | `EXPIRED` | varredor | `expires_at <= agora` | `reserved -= n` |

O domínio define estados, regras de abertura e eventos. As transições após a criação são aplicadas pelo armazenamento, por compare-and-set ([`PgReservationRepository`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/persistence/PgReservationRepository.java)), e a extensão pela aplicação ([`ReservationService`](../../application/src/main/java/dev/marketplace/cataloginventory/application/ReservationService.java)). Idempotência de release e commit: ver ADR 012.

### Elegibilidade da loja

[diagramas/estados-elegibilidade-loja.puml](diagramas/estados-elegibilidade-loja.puml): `SellerStatus` e o veredito KYB/KYC que leva a cada estado. Não há estado final.

| De | Para | Veredito (`eventType`) | Guarda |
|---|---|---|---|
| (sem registro) | `PENDING` | nenhum | estado implícito |
| qualquer | `APPROVED` | `SellerApproved` | `decidedAt` do veredito ≥ o registrado |
| qualquer | `REJECTED` | `SellerRejected` | idem |
| qualquer | `PENDING` | `ReviewRequired` | idem |

O enum está no domínio; o consumo dos vereditos fica no serviço ([`SellerVerdictConsumer`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/messaging/SellerVerdictConsumer.java)).

### Categoria

[diagramas/estados-categoria.puml](diagramas/estados-categoria.puml): o booleano `active` e o que cada situação permite.

| De | Para | Comando | Guarda |
|---|---|---|---|
| (nenhum) | ativa | `root`, `childOf` | `childOf`: pai direto ativo |
| ativa | inativa | `deactivate` | nenhuma |
| inativa | ativa | `activate` | nenhuma |

- Usável para produto só quando ela e todos os ancestrais estão ativos. Essa regra fica na aplicação (`CategoryView.usable` em [`CategoryService`](../../application/src/main/java/dev/marketplace/cataloginventory/application/CategoryService.java)); o domínio confere só a própria categoria em `Product.draft` e `reviseContent`.
- `childOf` confere o pai direto, não a cadeia.

## Políticas

### `PublishingPolicy`

[`PublishingPolicy`](../src/main/java/dev/marketplace/cataloginventory/domain/product/PublishingPolicy.java) devolve todos os motivos de uma vez, em vez de falhar no primeiro.

- `valueViolations`: valores presentes que a categoria não aceita. Vale para rascunho e publicado. Motivos: `unknown-attribute:<c>`, `invalid-attribute:<c>` (nos SKUs, `<sellerSku>:<c>`).
- `publishingViolations`: tudo o que impede `SUBMIT` e `APPROVE`.
- `valueIssues` e `publishingIssues`: os mesmos motivos, na mesma ordem, cada um como `PublishingIssue` com o campo do produto onde se resolve (`categoryId`, `attributes.<c>`, `skus`, `skus[<sellerSku>].offer`, `skus[<sellerSku>].variantAttributes` e `skus[<sellerSku>].variantAttributes.<c>`).
- `CategoryChange.of` aplica `valueViolations` à troca de categoria sem gravar: atributos do produto que somem ou ficam com valor recusado, obrigatórios que a categoria atual não exigia e SKUs com variação recusada, que impedem a gravação. `Product.draftIssues` e `Product.categoryChange` avaliam o rascunho com os SKUs gravados.

| Motivo | Causa |
|---|---|
| `category-inactive` | categoria não usável |
| `no-sku` | produto sem SKU |
| `missing-offer:<sellerSku>` | SKU sem oferta |
| `missing-attribute:<c>` | atributo obrigatório de produto ausente |
| `missing-variant-attribute:<sellerSku>:<c>` | atributo obrigatório de variante ausente no SKU |
| `unknown-attribute:…`, `invalid-attribute:…` | os de `valueViolations` |
| `duplicate-variant:<sellerSku>` | dois SKUs com a mesma combinação de variantes |

### `ReservationPolicy`

[`ReservationPolicy`](../src/main/java/dev/marketplace/cataloginventory/domain/inventory/ReservationPolicy.java):

- `open` valida limites (`invalid-value`): de 1 a 100 itens, `ttl` positivo e até `maxTtl`, `orderRef` até 100.
- Por item, acumula motivos: `duplicate-sku:<id>`, `unknown-sku:<id>`, `product-unavailable:<id>` (produto não `ACTIVE`), `seller-not-eligible:<id>`, `price-changed:<id>` (preço vigente diferente do aceito no checkout).
- Compara o preço vigente, não só a versão, para pegar promoção que começa ou termina sem mudar a definição.
- Com motivos, lança `ReservationRefusal`. O código é o prefixo comum dos motivos ou `reservation-refused` quando há mais de um tipo.
- Sem motivos, devolve a reserva `ACTIVE` com prazo `now + ttl` e itens com snapshot de preço.
- O saldo é conferido depois, atomicamente, no armazenamento. Falta de saldo usa `insufficientStock`, com `insufficient-stock:<id>`.

### Ordem canônica de lock

`ReservationPolicy.LOCK_ORDER` compara UUIDs como bytes sem sinal, igual ao tipo `uuid` do PostgreSQL. `inLockOrder` ordena os itens por ela. Reserva, confirmação, liberação e expiração travam os saldos nessa ordem, o que evita deadlock entre elas (ADR 012).

## Eventos de domínio

[diagramas/eventos-dominio.puml](diagramas/eventos-dominio.puml): as três interfaces seladas, seus records e a fábrica `InventoryEvents`.

| Evento | Emitido por | Quando |
|---|---|---|
| `ProductCreated` | `Product.draft` | criação; leva os SKUs (`SkuSummary`) |
| `ProductStatusChanged` | `Product` | toda transição, exceto `CREATE` (inclui `REVIEW_REQUIRED`) |
| `ProductBlocked` | `Product.block` | `BLOCK`, junto com `ProductStatusChanged` |
| `PriceChanged` | `Offer` | criação da oferta, mudança de preço, agendamento e cancelamento de promoção |
| `InventoryUpdated` (`ADJUSTED`) | `InventoryEvents.adjusted` | ajuste do físico; sem `reservationId` |
| `InventoryDepleted` | `InventoryEvents.adjusted`, `reserved` | disponível passou a zero no ajuste ou na reserva |
| `InventoryReserved` | `InventoryEvents.reserved` | reserva gravada |
| `InventoryUpdated` (`COMMITTED`, `RELEASED`, `EXPIRED`) | `InventoryEvents.closed` | encerramento da reserva, um por item |

- `Product` e `Offer` acumulam eventos; o repositório os drena (`drainEvents`) e grava na outbox na mesma transação (ADR 013).
- `InventoryEvents` deriva os eventos do saldo antes e depois; o armazenamento o chama dentro da transação.
- `SkuStock.version` só cresce: o consumidor descarta saldo com versão menor que a já aplicada.
- Extensão de reserva e mudanças de categoria não geram evento.
- A tradução para os tópicos Kafka fica no serviço.

## Exceções e tradução no serviço

[diagramas/excecoes-dominio.puml](diagramas/excecoes-dominio.puml): hierarquia das exceções e o status HTTP de cada uma.

| Exceção | Uso | Resposta (RFC 9457) |
|---|---|---|
| `DomainRuleViolation` | recusa de negócio; `code` estável e `reasons` | 422 |
| `InvalidTransitionException` | transição ou edição negada pelo estado | 409 |
| `ReservationRefusal` | reserva recusada; o cliente pode recotar | 409 |
| `StockConflictException` | lançada pelo serviço: `stock-below-reserved`, `sku-has-stock` | 409 |
| `CategoryNotEmptyException` | exclusão de categoria com subcategorias (`category-has-children`) ou com produtos, inclusive arquivados (`category-has-products`) | 409 |
| `IllegalStateException` | saldo ou reserva inconsistente (falha técnica) | 500 `internal-error` |

O `type` da resposta é o `code` da exceção. Mapeamento em [`ProblemMappers`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/api/ProblemMappers.java) (ADR 008). `seller-not-eligible` sai como 422 em `approve`/`resume` e como 409 na reserva.

## Limites numéricos

| Limite | Valor | Constante |
|---|---|---|
| Profundidade da árvore de categorias | 6 níveis | `Category.MAX_DEPTH` |
| Atributos próprios por categoria | 50 | `Category.MAX_ATTRIBUTES` |
| Código / nome da categoria | 60 / 120 caracteres | `Category` |
| Código / rótulo do atributo | 40 / 80 caracteres | `AttributeDefinition` |
| Opções de `OPTION` | 1 a 100 | `AttributeDefinition.MAX_OPTIONS` |
| Valor `TEXT` | 500 caracteres | `AttributeDefinition.MAX_TEXT_LENGTH` |
| Título / descrição do produto | 200 / 5000 caracteres | `Product` |
| SKUs por produto | 100 | `Product.MAX_SKUS` |
| Complemento de reject e block | 500 caracteres | `DecisionReason.NOTE_MAX_LENGTH` |
| Código / rótulo do motivo de moderação | 60 / 120 caracteres | `ModerationReasonDefinition` |
| `sellerSku` | 64 caracteres | `Sku.SELLER_SKU_PATTERN` |
| Medidas / peso | até 1000 cm, 2 casas / 1 a 1.000.000 g | `Dimensions` |
| Valor monetário | `99999999999999999.99`, 2 casas | `Money.MAX` |
| Promoções pendentes por oferta | 20 | `Offer.MAX_PROMOTIONS` |
| Janela da promoção | 366 dias | `Promotion.MAX_WINDOW` |
| Estoque físico | 0 a 1.000.000 | `StockLevel.MAX_ON_HAND` |
| Itens por reserva | 1 a 100 | `ReservationPolicy.MAX_ITEMS` |
| Quantidade por item | 1 a 1000 | `RequestedItem.MAX_QUANTITY` |
| `orderRef` | 100 caracteres | `ReservationPolicy.ORDER_REF_MAX_LENGTH` |

O prazo da reserva (`ttl`, `maxTtl`) chega como parâmetro de `open`. Os padrões de 15 min, 2 h por operação e 24 h de vida total estão na ADR 012 e são configurados no serviço (`CatalogConfig.Reservations`); a aplicação recebe esses valores e recusa a extensão além da vida máxima (`reservation-lifetime-exceeded`).

## Testes

Testes unitários JUnit 5, sem framework, em [`src/test/java/dev/marketplace/cataloginventory/domain`](../src/test/java/dev/marketplace/cataloginventory/domain):

| Arquivo | Cobre |
|---|---|
| `MoneyTest` | limites e normalização de `Money` |
| `DomainRuleViolationTest` | motivos na forma compacta e detalhada; a própria recusa como detalhe |
| `category/CategoryTest`, `category/AttributeDefinitionTest` | árvore, conflitos, ciclo, profundidade, tipos de atributo |
| `product/ProductTest`, `product/ProductTransitionTest`, `product/SkuTest` | invariantes, publicação, regras de edição, pendências com o campo, transições, SKU e dimensões |
| `product/CategoryChangeTest` | efeito da troca de categoria: atributos que somem, obrigatórios novos e SKUs que impedem a troca |
| `product/DecisionReasonTest`, `product/ModerationReasonDefinitionTest` | motivos da decisão contra o catálogo do tenant e regras do motivo pronto |
| `offer/OfferTest` | preço, promoções e `priceVersion` |
| `inventory/StockLevelTest`, `inventory/ReservationPolicyTest`, `inventory/InventoryEventsTest` | saldo, abertura de reserva, `LOCK_ORDER` e derivação de eventos |

```bash
npx nx test catalog-inventory-domain
# ou
./gradlew :catalog-inventory-domain:test
```

## Decisões relacionadas

Em [decisoes.md](../../../../docs/decisoes.md): ADR 002 (três módulos por contexto), ADR 010 (limites entre camadas), ADR 011 (categoria, produto e SKU), ADR 012 (inventário e reservas), ADR 013 (eventos com outbox) e ADR 014 (produto, oferta e estoque como agregados separados).
