# Catálogo-inventário

Contexto do marketplace responsável pelo que um seller vende e por quanto pode vender. Ele mantém a taxonomia de categorias, os produtos e seus SKUs, as ofertas com preço e promoções, a moderação, a vitrine pública, o saldo de estoque e as reservas usadas pelo checkout. Cada tenant enxerga apenas os próprios dados, e tenant e loja vêm sempre do token JWT validado.

Este arquivo é o ponto de entrada. O detalhe de cada módulo fica na pasta `docs/` dele.

## Módulos

| Módulo    | Projeto Nx                      | Papel                                                                                                                          | Documentação                                                                                    |
| --------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| Domínio   | `catalog-inventory-domain`      | Agregados, invariantes, máquinas de estado e eventos de domínio, sem framework                                                 | [libs/catalog-inventory/domain/docs](../libs/catalog-inventory/domain/docs/README.md)           |
| Aplicação | `catalog-inventory-application` | Casos de uso, portas de persistência e regras de acesso, sem framework (só o Mutiny para os tipos reativos, ADR 009 e ADR 010) | [libs/catalog-inventory/application/docs](../libs/catalog-inventory/application/docs/README.md) |
| Serviço   | `catalog-inventory-service`     | Aplicação Quarkus: API HTTP, PostgreSQL, Kafka, Redis, agendadores, segurança e observabilidade                                | [apps/catalog-inventory-service/docs](../apps/catalog-inventory-service/docs/README.md)         |
| Contratos | `catalog-inventory-contracts`   | OpenAPI gerado e JSON Schema dos eventos publicados                                                                            | [contracts/catalog-inventory](../contracts/catalog-inventory/README.md)                         |

A dependência entre os módulos segue a ADR 002: o serviço depende da aplicação, que depende do domínio. O verificador de limites da ADR 010 impede o caminho inverso.

## Capacidades

- **Taxonomia:** árvore de até seis níveis, com atributos herdados pelos produtos; só a categoria vazia, sem subcategorias nem produtos, pode ser excluída.
- **Produto e SKU:** rascunho, envio para moderação, publicação, pausa, bloqueio e arquivamento.
- **Moderação:** fila em ordem de chegada, aprovação, rejeição e bloqueio com motivos prontos do catálogo de cada tenant (tabela `moderation_reason`, mantida pelo `catalog-admin` em `/moderation/reasons`, sem exclusão, lida pelo cache Redis e invalidada a cada escrita) e complemento livre; o histórico guarda os rótulos da época.
- **Oferta:** preço "de/por" por SKU, com promoções agendadas e versão de preço.
- **Estoque:** saldo físico, reservado e vendido por SKU.
- **Reserva:** bloqueio atômico de saldo com prazo, confirmação, liberação e expiração automática.
- **Importação:** planilha CSV processada em segundo plano.
- **Elegibilidade da loja:** veredito do KYB/KYC recebido por evento, que libera ou impede a venda.
- **Vitrine:** leitura pública de produto visível, com cache Redis opcional.
- **Indicadores:** números da página inicial do painel por papel, da loja, da moderação e da taxonomia, com séries por dia, mês ou ano no fuso de São Paulo.

## Contrato com os clientes

O contrato completo está em [`contracts/catalog-inventory`](../contracts/catalog-inventory/README.md). O serviço é a fonte das regras, dos catálogos e dos textos, e o painel só desenha o que recebe (ADR 031). Estes são os pontos que o painel e os consumidores mais usam:

- **Sessão:** `GET /me`, para qualquer usuário autenticado com tenant, devolve `subject`, `tenantId`, `storeId` (só do seller), `capabilities` (nomes de `Capability` na ordem do enum, como string aberta) e `can`, as permissões compostas que `Actor` calcula (hoje `readTenantCatalog`). Papel desconhecido dá lista vazia, chamada sem token dá 401 e token sem tenant dá 403 `tenant-not-authorized`. O mapa de papel para capacidade fica só em `CallerContext` (ADR 007): os endpoints declaram a capacidade que exigem (`@PermissionsAllowed`), e o `CapabilityAugmentor` dá à identidade as capacidades que o `CallerContext` deriva dos papéis.
- **Motivos de moderação:** `GET /moderation/reasons` serve ao moderador (só os ativos, com o filtro `decision`) e ao admin (`includeInactive=true`). Com a capacidade `MANAGE_MODERATION_REASONS`, que o `catalog-admin` recebe, ficam `GET` e `PUT /moderation/reasons/{code}`, `POST /moderation/reasons` e `POST /moderation/reasons/{code}/activate` e `/deactivate`, com `ETag` e `If-Match` obrigatório nas escritas (428 sem ele, 412 com versão antiga). O código é kebab-case só com letras, até 60 caracteres, imutável e único no tenant, inclusive entre os inativos (409 `duplicate-moderation-reason-code`); o tenant tem até 100 motivos ativos (422 `moderation-reason-limit` ao criar ou reativar; desativar um abre a vaga) e até 1000 no catálogo, contando os inativos, teto de proteção da leitura que cada decisão faz (422 `moderation-reason-catalog-limit`); não há exclusão (ADR 030).
- **Texto das recusas:** as recusas de regra (os 409 e 422 de `DomainRuleViolation` e o 400 `invalid-import-file`) trazem `reasonDetails: [{code, params, message}]`, com um item por elemento de `reasons`, na mesma ordem, ou, sem `reasons`, um item com a própria recusa. `reasons` continua como lista de textos compactos (`missing-offer:CAM-P`), porque o checkout consome as recusas de reserva. O texto vem do emissor ou de `i18n/ReasonMessages.properties` (ADR 008). O resultado de cada item de lote e as linhas de importação recusadas por regra usam o mesmo formato.
- **Ações:** a resposta de produto, no detalhe, na lista, na fila e nas escritas de um item, traz `actions`, as ações que o chamador pode executar agora (string aberta entre `EDIT`, `CHANGE_CATEGORY`, `ADD_SKU`, `REMOVE_SKU`, `EDIT_OFFER`, `EDIT_STOCK`, `SUBMIT`, `PAUSE`, `RESUME`, `ARCHIVE`, `APPROVE`, `REJECT`, `BLOCK` e `UNBLOCK`); `blockedActions` (`[{action, reason}]`), as que valem no status mas estão barradas, com o primeiro motivo na ordem do domínio; e `saveReturnsToModeration`, verdadeiro quando salvar conteúdo ou SKU devolve o produto à moderação. `ProductActions` as calcula pelas transições, pelas regras de edição do `Product`, pelas capacidades e pela situação da loja, lida numa consulta por página. O resultado dos lotes não traz ações.
- **Pendências de publicação:** a leitura de um produto em status editável traz `publishingIssues: [{code, params, message, field}]`, os motivos da `PublishingPolicy` na mesma ordem, com o caminho do campo (`categoryId`, `attributes.<código>`, `skus`, `skus[<sku>].offer`, `skus[<sku>].variantAttributes` ou `skus[<sku>].variantAttributes.<código>`). O campo fica nulo em `BLOCKED` e `ARCHIVED` e ausente na lista e na fila. Com pendência, `SUBMIT` e `APPROVE` vêm barrados por `product-not-publishable`, e o 422 continua decidindo.
- **Conferência do rascunho:** `POST /products/{id}/check`, só para o seller dono, com o alcance da edição, recebe `{categoryId, attributes}` e devolve, sem gravar, `publishingIssues` e `categoryChange` (`removedAttributes`, `newRequired` e `blockingSkus`), nulo quando a categoria não muda. Responde 404 para outra loja ou categoria fora do tenant e 409 `invalid-transition` em `BLOCKED` e `ARCHIVED`.
- **Situação da loja:** `GET /stores/{storeId}/eligibility` traz, além de `status`, `canPublish` (os produtos podem ser aprovados e reativados) e `visibleInStorefront` (os produtos ativos aparecem na vitrine), calculados de `SellerStatus`.
- **Histórico da moderação:** cada rejeição ou bloqueio grava em `product_status_change` os códigos (`reason_codes`), uma cópia dos rótulos da época (`reason_labels`, `[{code, label}]`) e o complemento (`note`, até 500 caracteres). `GET /products/{id}/history` e `lastRejection` expõem `reasonCodes`, `reasonLabels`, `note` e o `reason` derivado. O histórico não expõe o ator: cada transição traz `actorSide` (`STORE` ou `MODERATION`, de `ProductTransition.side()`) e `byCaller`, verdadeiro quando o chamador a fez.
- **Eventos:** `ProductStatusChanged` e `ProductBlocked` levam `reasonCodes`, `note` e `reasonLabels`; os dois últimos entraram de forma aditiva e são opcionais no schema. `reason` segue derivado e termina abreviado com reticências acima de 500 caracteres (ADR 030).
- **Limites de entrada:** `from` e `to` de `/stats/store` e `/stats/moderation` aceitam de 0001-01-01 a 9999-12-31, e fora disso a resposta é 400. A busca `q` e os cursores recusam o caractere U+0000 com 400 antes de chegar ao banco, que não o aceita em texto. No corpo JSON de toda escrita, o mesmo caractere em qualquer texto (título, descrição, nome, rótulo, opção, complemento da moderação, referência do pedido, valor ou chave de atributo) responde 400 `validation-failed`, como a validação de campo, com o caminho em `errors[].field` (`title`, `attributes[marca]`, `skus[0].variantAttributes[tamanho]`, `attributes[1].options[2]`); a chave de mapa recusada não aparece no caminho, que para no mapa (`attributes`), e a resposta traz só esse erro.
- **Listagens:** `GET /products` busca por `q` (trecho do título, sem diferenciar maiúsculas e acentos, ou do `sellerSku`) e ordena por `sort` (`updatedAt`, `createdAt`, `title`, com `-` para inverter; padrão `-updatedAt`), com cursor preso à ordem, à busca e aos filtros; `status=OUT_OF_STOCK` filtra o `ACTIVE` sem saldo. A fila de moderação sai por ordem de chegada (`moderationQueuedAt`), e `GET /stats/moderation/queue` traz só o tamanho dela, para a leitura frequente. `GET /categories/tree` pagina a árvore inteira em ordem, até 1000 itens por página, e `GET /categories?ids=` lê até 100 categorias pelo id. `GET /products/imports` lista os lotes da loja, e `GET /products/imports/{id}?lines=false` traz só a situação e as contagens.
- **Indicadores:** `GET /stats/store` (seller), `GET /stats/moderation` (moderador) e `GET /stats/categories` (admin), com `granularity` (`day`, `month` ou `year`) e o período em `from` e `to`.
- **Lotes:** `POST /categories/batch` ativa, desativa ou exclui até 100 categorias, com `version` obrigatória por item, e exclui das subcategorias para os pais. Em `POST /products/batch` e `POST /moderation/products/batch`, `results[].status` é o estado como a resposta do produto o mostra, com `OUT_OF_STOCK` pelo saldo, ou o estado gravado quando o saldo não pôde ser lido depois das gravações; os itens já gravados nunca viram erro do lote.
- **Escritas de um item:** as escritas de produto e as decisões de moderação de um item seguem a mesma regra: se a leitura do saldo depois do commit falhar, a resposta é o sucesso da escrita (200 ou 201, com `ETag` da versão nova), com o `status` gravado, sem o `OUT_OF_STOCK` calculado, e `skus[].stock` ausente; a leitura seguinte corrige. Se falhar outra leitura que monta a resposta (ofertas, última rejeição, situação da loja ou a categoria das pendências), a resposta também é o sucesso da escrita, com o produto gravado e sem `actions`, `blockedActions`, `publishingIssues`, `lastRejection` e `skus[].offer`; o painel, ao ver a resposta sem `actions`, relê o produto. A leitura sem escrita (`GET`) continua respondendo 503.

## Diagrama de contexto (C4 nível 1)

```plantuml
@startuml c4-contexto
!include diagramas/tema-catalogo.iuml
!include <C4/C4_Context>
!include <tupadr3/font-awesome-5/key>

AddElementTag("ator", $bgColor="#FFF8D6", $fontColor="#263238", $borderColor="#F9A825", $legendText="Pessoa (papel no JWT)")
AddElementTag("servico", $bgColor="#1565C0", $fontColor="#FFFFFF", $borderColor="#0D47A1", $legendText="Este sistema")

title Catálogo-inventário — contexto

Person(seller, "Seller", "Cadastra produtos, preços, estoque e importa planilhas", $sprite="user_tie", $tags="ator")
Person(moderador, "Moderador", "Aprova, rejeita e bloqueia produtos", $sprite="user_shield", $tags="ator")
Person(admin, "Admin do catálogo", "Mantém a taxonomia e os motivos de moderação e consulta o catálogo do tenant", $sprite="user_cog", $tags="ator")
Person(comprador, "Comprador", "Consulta a vitrine sem autenticação", $sprite="shopping_cart", $tags="ator")

System(catalogo, "Catálogo-inventário", "Taxonomia, produtos, ofertas, moderação, estoque, reservas e vitrine", $sprite="store", $tags="servico")

System_Ext(idp, "Identity & Organization (Keycloak)", "Emite os tokens JWT com tenant, loja e papéis", $sprite="key")
System_Ext(checkout, "Checkout e OMS", "Reservam, confirmam e liberam estoque com contas de serviço", $sprite="exchange_alt")
System_Ext(kyb, "KYB/KYC", "Publica o veredito de verificação da loja", $sprite="id_card")
System_Ext(consumidores, "Consumidores de eventos", "Busca e demais contextos (ainda não implementados)", $sprite="globe")

Rel(seller, catalogo, "Gerencia o próprio catálogo", "HTTPS/JSON")
Rel(moderador, catalogo, "Modera produtos", "HTTPS/JSON")
Rel(admin, catalogo, "Mantém categorias", "HTTPS/JSON")
Rel(comprador, catalogo, "Lê a vitrine", "HTTPS/JSON")
Rel(checkout, catalogo, "Reserva estoque", "HTTPS/JSON + Idempotency-Key")
Rel(seller, idp, "Autentica")
Rel(catalogo, idp, "Valida tokens", "JWKS")
Rel(kyb, catalogo, "SellerApproved, SellerRejected, ReviewRequired", "Kafka")
Rel(catalogo, consumidores, "Produto, oferta, estoque e elegibilidade", "Kafka")

SHOW_LEGEND()
@enduml
```

Os diagramas de contêiner, componente, implantação e sequência ficam na [documentação do serviço](../apps/catalog-inventory-service/docs/README.md). Os de classes e estados ficam na [documentação do domínio](../libs/catalog-inventory/domain/docs/README.md). Os de casos de uso, portas, importação e reserva idempotente ficam na [documentação da aplicação](../libs/catalog-inventory/application/docs/README.md).

## Decisões relacionadas

As decisões do contexto estão em [decisoes.md](decisoes.md):

| ADR           | Assunto                                                                    |
| ------------- | -------------------------------------------------------------------------- |
| 002, 003, 010 | Três módulos por contexto, sem domínio compartilhado, limites fiscalizados |
| 006, 009      | PostgreSQL com Reactive SQL Client e Flyway, modelo reativo                |
| 007           | Tenant e papéis derivados do JWT                                           |
| 008           | Erros RFC 9457, paginação por cursor e OpenAPI gerado                      |
| 011, 014      | Categoria, produto, SKU e oferta como agregados separados                  |
| 012           | Inventário e reservas atômicas com prazo no banco                          |
| 013           | Outbox no Kafka e inbox do KYB/KYC                                         |
| 016           | Importação CSV em segundo plano                                            |
| 017, 021      | Métricas de negócio e instrumentação de ponta a ponta                      |
| 018, 020      | Helm chart e papéis api e worker                                           |
| 024           | Cache Redis da vitrine e da taxonomia                                      |
| 030           | Motivos de moderação como dado de cada tenant                              |
| 031           | Regras e catálogos no servidor, apresentação no painel                     |

## Diagramas

Os diagramas usam PlantUML com a biblioteca padrão C4-PlantUML (`!include <C4/...>`), que já vem embutida no PlantUML. Todos incluem o tema compartilhado [`docs/diagramas/tema-catalogo.iuml`](diagramas/tema-catalogo.iuml) por caminho relativo: paleta por camada, ícones da biblioteca padrão (`tupadr3/font-awesome-5` e `logos`) e legendas prontas. Ao mover um `.puml` de pasta, ajuste o `!include`. Para renderizar todos sem instalar nada, inclusive o de contexto embutido neste arquivo, rode na raiz do repositório. Os SVG ficam em `build/diagramas`, que o Git ignora:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas \
  'libs/catalog-inventory/**/docs/diagramas/*.puml' \
  'apps/catalog-inventory-service/docs/diagramas/*.puml' \
  docs/catalog-inventory.md
```
