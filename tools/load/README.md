# Teste de carga do catálogo e inventário

Suíte de carga em [k6](https://grafana.com/docs/k6/) contra o compose local do catálogo (api em `http://localhost:8083`, pelo gateway que balanceia as duas instâncias, com gestão em `9301` e `9302`, e worker com gestão em `9311`; ADRs 019 e 020). Ela exercita o catálogo inteiro pela borda: navegação e vitrine, ciclo de vida do produto, moderação, categorias (ADRs 011 e 014), preço e promoções, reservas com confirmação, prorrogação e expiração pelo varredor (ADRs 012 e 017), importação por CSV (ADR 016), o consumo dos vereditos do KYB/KYC e a outbox (ADR 013), em seis perfis de carga sobre os mesmos cenários. A execução reprova quando um limite do perfil falha, quando uma invariante do `teardown` não fecha ou quando os logs do período registram erro. A decisão está na ADR 022 (`docs/decisoes.md`).

O k6 roda no contêiner `grafana/k6:2.3.0`, fixado por digest em `run.sh` (`K6_IMAGE`), com `--network host`; nada é instalado no host além do Node, que valida os parâmetros.

## Como rodar

Pré-requisitos: Docker, Node 24, `curl`, `python3` e a stack local no ar (`docker compose -f infra/local/docker-compose.yml --profile catalog --profile observability up -d --wait`, como no README da raiz). Sem o profile `observability`, a carga roda igual, só sem métricas no Prometheus.

```bash
npm run load:smoke                  # ou: npx nx run load:smoke
npm run load                        # o mesmo que load:average
DURATION=60s npm run load:average   # platô de 60 s
npm run load:check -- breakpoint    # só valida os parâmetros do perfil, sem Docker
npx nx run load:check               # valida os seis perfis
npm run load:stress -- --summary-mode=full   # argumentos depois do perfil vão para o `k6 run`
HOT_MODE=exhaust SCENARIOS=hot_sku,spread_reserve_release,stock_adjust npm run load:average   # baseline anterior
```

Cada target chama `tools/load/run.sh <perfil>`, que:

1. valida os parâmetros no Node (`src/cli.js`, com `src/params.js`, `src/profiles.js` e `src/mass-plan.js`), antes de qualquer Docker; com `--check`, imprime o plano do perfil (massa, picos, iterações previstas, VUs, rodadas do KYB e limites) e sai;
2. gera o `TESTID` (`<perfil>-<UTC aaaammdd-hhmmss>`, ou o informado em `TESTID`) e cria `tools/load/results/<TESTID>/`; um `TESTID` repetido é recusado;
3. confere a saúde dos dois papéis e garante a loja `seller-demo` aprovada: se ela não estiver `APPROVED`, envia o veredito `SellerApproved` pelo tópico `kyb-kyc.seller.v1` com o `kafka-console-producer.sh` do contêiner `kafka`, como no roteiro do README da raiz, e espera até 30 s;
4. liga o remote write ao Prometheus local quando ele responde (ver [Painel](#painel-e-remote-write)) e imprime o link do painel da execução;
5. roda o k6 (fora de um terminal, como no npm sem TTY, no Nx e no `load.yml`, com `--quiet`, sem as linhas de progresso; o resumo sai igual), com a saída copiada em `k6.log`;
6. quando o `setup` termina a massa (a linha `massa pronta` no log do k6), envia em segundo plano as rodadas de vereditos do KYB ([cenário `kyb_verdicts`](#o-que-mede)), uma por `KYB_INTERVAL_S`, por um único `kafka-console-producer.sh` aberto no contêiner `kafka` durante a execução inteira, com o registro em `kyb.log`;
7. no fim, procura erros nos logs do PostgreSQL e dos contêineres `catalog-api-1`, `catalog-api-2` e `catalog-worker` só no período da execução.

Cada execução deixa no tenant de desenvolvimento a massa inteira (categorias, produtos em todos os estados e lojas sintéticas com veredito), os produtos e categorias criados pelos cenários e reservas ativas no último item (e, no modo `exhaust`, no SKU disputado), que expiram em `RESERVATION_TTL_S`. Use num banco local descartável.

## Perfis

As taxas base de cada cenário (variáveis `*_RATE`, em iterações por segundo) são o nível 1×; cada perfil multiplica essas taxas ao longo do tempo com `ramping-arrival-rate`. Com os padrões, 1× soma cerca de 160 requisições de negócio por segundo (ver [Cenários](#o-que-mede)).

| Perfil | Forma | Nível | Duração padrão | Pergunta |
|---|---|---|---|---|
| `smoke` | 0,2× constante, com pelo menos 4 iterações por minuto em cada cenário | 0,2× | 30 s | O script, a massa, a topologia e as invariantes funcionam? |
| `average` | rampa de 30 s até 1×, platô de `DURATION`, descida em 30 s | 1× | 6 min (platô de 5 min) | Latência e erro na carga esperada |
| `stress` | rampa de 1 min, platô de `DURATION`, descida em 30 s | 10× no SKU disputado (200/s, ou os 1.000/s da antiga carga alta no modo `exhaust`), 2× nos demais | 3 min 30 s (platô de 2 min) | Quanto degrada acima do esperado |
| `spike` | 1× por 30 s, salto a 8× em 10 s, `DURATION` em 8×, volta a 1× em 10 s e 1 min em 1× | 8× | 2 min 50 s (pico de 1 min) | O catálogo e a outbox voltam ao normal depois do pico? |
| `soak` | rampa de 1 min, platô de `DURATION`, descida em 30 s | 1× | 31 min 30 s (platô de 30 min) | Degradação ou vazamento ao longo do tempo |
| `breakpoint` | rampa de 0 a 20× em `DURATION`, sem descida, com abort | 20× | 10 min | Onde o catálogo rompe |

`DURATION` muda só o trecho indicado na forma: o platô no `average`, `stress` e `soak`, o tempo em 8× no `spike`, a rampa no `breakpoint` e a duração inteira no `smoke`. O piso do `smoke` existe porque a importação e as categorias, a 0,1 e 0,2 iteração/s, rodariam menos de uma vez em 30 s. A rajada do último item (`last_unit`) fica fora do nível: é a mesma em todos os perfis. O `--check` recusa uma combinação em que as reservas do disputado, da espalhada e do último item expirariam no meio da execução (`RESERVATION_TTL_S` precisa passar da duração da carga, mais `OUTBOX_DRAIN_S` e 120 s de preparo; o serviço aceita prazo de até 2 h), que pré-alocaria mais de 1.000 VUs ou em que o SKU disputado poderia faltar no modo `contend`: cada VU segura no máximo uma reserva dele por vez, então as reservadas ao mesmo tempo (taxa × latência) nunca passam do `maxVUs` do cenário, e o `--check` exige `HOT_STOCK` de pelo menos o dobro disso.

Os VUs de cada cenário vêm do pico: pré-alocados para 0,5 s de iterações (`pico × 0,5`) e no máximo `pico × teto da iteração`, limitados a `MAX_VUS` por cenário. O teto da iteração acompanha o número de requisições: 2 s no ajuste, na vitrine, na expiração e no SKU disputado no modo `exhaust`; 2,5 s no disputado no modo `contend`, na reserva espalhada, na navegação, na moderação, no preço e nas categorias; 3 s no ciclo de reserva; 8 s no ciclo do produto; e 35 s na importação, que espera o worker serial. A rajada do último item usa até 10 VUs, um por tentativa simultânea. Se `dropped_iterations` aparecer, faltaram VUs para manter a taxa: o alvo ficou lento ou o limite de `MAX_VUS` foi atingido.

## O que mede

### Massa

O `setup` cria pela API, a cada execução, a massa descrita em `src/mass-plan.js` e executada em `src/mass.js`, com códigos derivados do `TESTID`: `sellerSku` (`CARGA-<TESTID>-M<n>-<j>`) e `Idempotency-Key` valem para sempre no tenant e nunca se repetem entre execuções. Os passos independentes vão em lotes paralelos de até 20 requisições (`http.batch`); com a máquina livre, a massa padrão fica pronta em 1 a 2 s, e o `setupTimeout` é de 120 s mais 1 s a cada 10 produtos.

- **Categorias:** uma raiz `carga-<TESTID>` com o atributo TEXT obrigatório `marca`, 3 filhas com o atributo OPTION de variante obrigatório `cor` (`c1` a `c4`), 2 netas em cada filha com o TEXT opcional `material`, e 2 filhas sem atributos (`-adm-a` e `-adm-b`) para o cenário de categorias. Os produtos ficam nas netas.
- **Produtos:** `MASS_PRODUCTS` (120) no total, cada um com 1 a 4 SKUs de cores distintas, oferta de R$ 19,90 (de R$ 29,90) em todo SKU, porque o envio à moderação exige, e estoque conforme o papel. Os grupos de SKU dos cenários vêm primeiro, em produtos `ACTIVE` de até 4 SKUs; depois, `MODERATION_PRODUCTS` (10) produtos em `PENDING_MODERATION` reservados à moderação; o resto é a população por estado: 15% `DRAFT`, 10% `PENDING_MODERATION`, 10% `ACTIVE` sem estoque (status efetivo `OUT_OF_STOCK`), 10% `PAUSED_BY_SELLER`, 10% `BLOCKED`, 5% `ARCHIVED` (aprovado e depois arquivado) e o restante `ACTIVE`, metade deles com promoção de R$ 14,90 daqui a 30 dias, que nunca entra em vigor durante a carga. Com os padrões: 120 produtos e 315 SKUs, 12 `DRAFT`, 18 `PENDING_MODERATION` (10 da moderação), 62 `ACTIVE` (28 dos grupos), 8 `OUT_OF_STOCK`, 8 `PAUSED_BY_SELLER`, 8 `BLOCKED` e 4 `ARCHIVED`.
- **Grupos de SKU**, cada um usado por um cenário só, para um não estragar o saldo ou o preço do outro: o disputado (1 SKU com `HOT_STOCK`), o último item (1 SKU com `LAST_UNIT_STOCK`, o único com estoque pequeno de propósito), a reserva espalhada (`SPREAD_SKUS` com `SPREAD_STOCK`), o ajuste (`ADJUST_SKUS` com 100), o ciclo de reserva (`RESERVATION_SKUS` com 100.000), a expiração (`EXPIRY_SKUS` com 100.000), o preço (`PRICING_SKUS` com 100), que nenhuma reserva usa, porque cada troca de preço faria a reserva recusar com `price-changed`, e a importação (`IMPORT_SKUS` com 50). Recusa por falta de saldo só existe onde é intencional e limitada (o último item e o modo `exhaust`); nos demais grupos, o estoque folgado faz do 409 `insufficient-stock` uma resposta inesperada.
- **Lojas sintéticas do KYB:** `KYB_STORES` (6) UUIDs do mesmo tenant derivados do `TESTID` (`src/kyb.js`), sem produtos.

O `setup` confere o estado de cada produto na resposta da última transição e falha se algum sair diferente do planejado.

### Cenários

Os cenários rodam em paralelo, cada um com executor e taxa base próprios. O `X-Correlation-Id` e as chaves de idempotência levam o `TESTID`.

| Cenário | Taxa base | O que faz | Resposta esperada |
|---|---|---|---|
| `hot_sku` | 20/s (100/s no `exhaust`) | No modo `contend` (padrão), disputa contínua sem esgotar: reserva 1 unidade do SKU disputado, com `Idempotency-Key` própria, e libera logo em seguida, travando sempre a mesma linha. No modo `exhaust` (o antigo), só reserva, até acabar `HOT_STOCK` (200) | `contend`: 201 e 200, e 409 é inesperada; `exhaust`: 201 até acabar o estoque e depois 409 `insufficient-stock`, com aceitas nunca acima de `HOT_STOCK` |
| `spread_reserve_release` | 20/s | Reservas de 1 a 3 SKUs sorteados do grupo, em ordem aleatória, seguidas da liberação | 201 e 200 (no modo `exhaust`, 409 `insufficient-stock` também é aceito, como antes); a ordem canônica de travamento (ADR 012) evita deadlock |
| `stock_adjust` | 10/s | `PUT /inventory/skus/{skuId}` em rodízio pelo grupo, entre 100 e 999 unidades | 200 |
| `last_unit` | rajada no começo | O critério do PRD para o último item: o dobro de `LAST_UNIT_STOCK` (20) em tentativas de 1 unidade do SKU próprio, até 10 ao mesmo tempo, sem liberar | Exatamente o estoque aceito (201) e o resto 409 `insufficient-stock`: o primeiro leva, o seguinte falha de forma limpa; as recusas da execução ficam limitadas a tentativas − estoque |
| `catalog_browse` | 5/s | Em lote, alternando seller, admin e moderador: `GET /products` paginado com filtro de status (às vezes a página seguinte pelo cursor), o produto, um entre SKU, histórico, oferta e saldo, e um entre categorias raiz, filhas, uma categoria e a situação da loja | 200 em tudo; a situação da loja dá 403 para o moderador, que não tem o papel |
| `storefront` | 10/s | `GET /storefront/{tenantId}/products/{productId}` anônimo, sorteando entre a população e a moderação | 200 nos `ACTIVE`, com o status certo (`OUT_OF_STOCK` e `inStock=false` sem estoque); 404 nos demais |
| `product_lifecycle` | 0,5/s | Cria produto com 2 SKUs, define as ofertas (criação, sem `If-Match`) e o estoque, inclui um terceiro SKU, revisa-o e o remove (só em `DRAFT`), revisa o produto (`If-Match` pelo ETag), envia; aprova (e pausa e retoma) ou recusa, alternando, e arquiva uma iteração em três | 201 e 200 em cada passo, com o estado certo |
| `moderation` | 1/s | Lê a fila de moderação e, num produto da moderação em rodízio, bloqueia ou desbloqueia conforme o estado lido, com `If-Match` | 200; o desbloqueio devolve a `PENDING_MODERATION` |
| `offer_pricing` | 2/s | No grupo de preço: lê a oferta, troca o preço com `If-Match`, agenda uma promoção numa janela própria da iteração, a 7 dias e sem sobreposição, e a cancela | 200, 201 e 200 |
| `reservation_lifecycle` | 5/s | Reserva 2 SKUs do grupo, repete a mesma requisição uma vez em cinco (200 `Idempotent-Replayed`), consulta, prorroga e confirma; uma iteração em quatro libera em vez de confirmar | 201 e 200 em cada passo |
| `reservation_expiry` | 2/s | Reserva 1 SKU do grupo de expiração com prazo de `EXPIRY_TTL_S` (5 s) e não faz mais nada: o varredor do worker (a cada 5 s, até 200 por vez) expira | 201 |
| `category_admin` | 0,2/s | Cria uma categoria sob um dos dois pais, revisa nome e atributos (`If-Match`; `attributes` vai sempre, porque ausente apagaria os próprios) e a move para o outro pai | 201 e 200 |
| `product_import` | 0,1/s | `POST /products/imports` com CSV de 4 linhas (um produto novo com 2 SKUs e 2 SKUs existentes do grupo, mudando só o estoque), e consulta até `COMPLETED`, por até `IMPORT_WAIT_S` | 202 e 200, com `counts` = 2 criadas, 2 atualizadas, 0 inalteradas e 0 falhas |
| `kyb_verdicts` | 1 rodada a cada `KYB_INTERVAL_S` | Pelo `run.sh` (o k6 não fala Kafka): cada rodada manda num lote só um veredito por loja sintética, com chave = `storeId` e `occurredAt` crescente, alternando `ReviewRequired`, `SellerApproved` e `SellerRejected`; as rodadas ocupam 80% da carga | efeito conferido no `teardown` |
| `outbox_probe` | 1 a cada `PROBE_INTERVAL_S` | Lê `/q/metrics` dos dois papéis | Guarda o maior `catalog_outbox_publish_lag_seconds_max` e a fila `catalog_outbox_pending` vistos durante a carga |

As respostas fora do esperado contam em `unexpected_responses`, no total e por cenário (`unexpected_responses{scenario:<cenário>}`); 5xx e falha de transporte contam também em `server_errors`. As requisições ao catálogo têm prazo de 10 s; sem resposta nesse prazo, contam como falha de transporte (status 0).

### Invariantes

No `teardown`, a suíte confere (342 conferências com os padrões no `smoke` e 377 no `average`; a da importação é feita ao fim de cada iteração dela):

- **SKU disputado:** nenhum valor negativo; no modo `contend`, saldo de volta ao inicial (`reserved = 0`, `sold = 0`, `onHand` inalterado); no modo `exhaust`, `onHand` inalterado, `reserved + sold <= onHand` inicial e, quando o perfil agenda ao menos o dobro de tentativas do estoque, todo o estoque reservado (sem recusa indevida).
- **Último item:** `reserved` igual ao estoque, `available = 0`, `sold = 0` e nenhuma venda sem estoque (`reserved + sold <= estoque`); nos limites, aceitas igual ao estoque e recusas igual a tentativas − estoque.
- **Recusas por falta de saldo:** no modo `contend`, o aumento de `catalog_reservations_refused_total{reason="insufficient-stock"}` no serviço durante a execução igual a tentativas − estoque do último item (zero sem ele).
- **Reserva espalhada:** saldo de volta exatamente ao inicial. **Ajuste:** nenhum valor negativo.
- **Ciclo de reserva:** em cada SKU, nada reservado e `onHand + sold` igual ao estoque inicial; no grupo, as unidades vendidas iguais a 2 × o aumento de `catalog_reservations_committed_total` no serviço durante a execução.
- **Expiração:** espera até `EXPIRY_WAIT_S` o varredor zerar o reservado do grupo e confere `reserved = 0`, `sold = 0` e `onHand` inicial; depois repete as primeiras requisições da expiração (até 10) com a mesma chave, que devolve a reserva original, e confere por `GET /inventory/reservations/{id}` que ela está `EXPIRED`, com `closedAt`.
- **Importação:** cada importação `COMPLETED`, com `totalLines` e `processedLines` 4 e as contagens esperadas.
- **Massa:** o estado de cada produto por `GET /products/{id}` igual ao planejado; os da moderação em `PENDING_MODERATION` ou `BLOCKED`, o do último item `OUT_OF_STOCK` depois da rajada, e o do SKU disputado `ACTIVE` (no modo `exhaust`, `ACTIVE` ou `OUT_OF_STOCK`).
- **Vitrine:** em cada produto da população e da moderação, 200 só para `ACTIVE` e `OUT_OF_STOCK`, com o status e o `inStock` certos, e 404 nos demais.
- **KYB:** espera até `KYB_SETTLE_S` e confere, por `GET /stores/{storeId}/eligibility` com o token do admin, que cada loja sintética está na situação do último veredito enviado (`PENDING`, `APPROVED` ou `REJECTED`).

Depois espera a outbox esvaziar e calcula a latência das atualizações de inventário e de preço medida pelo próprio serviço.

## Semente

Os sorteios (quantos SKUs tem cada produto da população, quais SKUs entram em cada reserva espalhada e do ciclo de reserva, o saldo de cada ajuste, o que a navegação e a vitrine leem e a folga de renovação do token de cada VU) vêm de um gerador pseudoaleatório (`src/lib/random.js`) com a semente `SEED`. A semente de cada iteração deriva de `SEED`, do cenário e do número da iteração no teste, não do VU que a executa; a mesma `SEED` repete a mesma massa e os mesmos sorteios em qualquer execução do perfil. A semente sai no resumo e no log do `setup`.

## Guarda do alvo

`BASE_URL`, `API_MANAGEMENT_URL`, `API_2_MANAGEMENT_URL`, `WORKER_MANAGEMENT_URL`, `KEYCLOAK_URL` e `K6_PROMETHEUS_RW_SERVER_URL` só aceitam `http://localhost` e `http://127.0.0.1` (`src/lib/guard.js`), no `--check` e no `init` do k6: a suíte cria dados, entra com as senhas do realm de demonstração e satura o alvo. Para outro alvo, é preciso `ALLOW_REMOTE_TARGET=true`, explícito. A aprovação da loja e os vereditos do KYB continuam indo pelo Kafka do compose local.

## Parâmetros

| Variável | Padrão | Regra e uso |
|---|---|---|
| `BASE_URL` | `http://localhost:8083` | Catálogo; guarda do alvo |
| `API_MANAGEMENT_URL`, `API_2_MANAGEMENT_URL`, `WORKER_MANAGEMENT_URL` | `http://localhost:9301`, `http://localhost:9302`, `http://localhost:9311` | Saúde e `/q/metrics` de cada instância |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID` | `http://localhost:8180`, `marketplace`, `marketplace-cli` | Tokens reais por `grant_type=password`; cada VU renova o seu antes de expirar (5 min no realm local) |
| `TENANT_ID`, `STORE_ID` | tenant e loja fixos do realm local | Tenant da vitrine e dos vereditos; loja aprovada pelo veredito |
| `ALLOW_REMOTE_TARGET` | `false` | `true` libera alvo fora de localhost |
| `TESTID` | `<perfil>-<UTC aaaammdd-hhmmss>` | Até 40 caracteres em minúsculas, dígitos e hífens; pasta do resultado, sufixo dos dados e tag `testid` |
| `SCENARIOS` | todos os cenários da tabela, menos a sonda | Subconjunto de cenários; a massa é criada inteira em qualquer caso |
| `DURATION` | do perfil | Duração como `30s`, `10m` ou `1h`; trecho que muda em cada perfil, acima |
| `SEED` | `1` | Inteiro de 0 a 2147483647 |
| `MASS_PRODUCTS`, `MODERATION_PRODUCTS` | `120`, `10` | Produtos da massa (até 2.000; ao menos os dos grupos e da moderação, mais um por estado) e os reservados à moderação |
| `HOT_MODE` | `contend` | `contend` reserva e libera sem esgotar; `exhaust` restaura o modo antigo, que esgota o SKU |
| `HOT_STOCK`, `HOT_RATE` | `10000` e `20`/s no `contend`; `200` e `100`/s no `exhaust` | SKU disputado; no `contend`, `HOT_STOCK` de ao menos 2 × `maxVUs` do cenário |
| `LAST_UNIT_STOCK` | `20` | De 1 a 500; estoque do último item, com o dobro de tentativas na rajada |
| `SPREAD_SKUS`, `SPREAD_STOCK`, `SPREAD_RATE` | `20`, `100000`, `20`/s | Reserva e liberação espalhadas |
| `ADJUST_SKUS`, `ADJUST_RATE` | `20`, `10`/s | Ajustes de saldo |
| `RESERVATION_SKUS`, `RESERVATION_RATE` | `20`, `5`/s | Ciclo de reserva (ao menos 2 SKUs) |
| `EXPIRY_SKUS`, `EXPIRY_RATE`, `EXPIRY_TTL_S`, `EXPIRY_WAIT_S` | `10`, `2`/s, `5`, `60` | Expiração: prazo das reservas curtas (1 a 60 s) e espera pelo varredor no `teardown` |
| `PRICING_SKUS`, `PRICING_RATE` | `20`, `2`/s | Preço e promoções |
| `IMPORT_SKUS`, `IMPORT_RATE`, `IMPORT_WAIT_S` | `10`, `0,1`/s, `30` | Importação (ao menos 2 SKUs) e espera pela conclusão de cada uma |
| `BROWSE_RATE`, `STOREFRONT_RATE`, `PRODUCT_RATE`, `MODERATION_RATE`, `CATEGORY_RATE` | `5`, `10`, `0,5`, `1`, `0,2`/s | Navegação, vitrine, ciclo do produto, moderação e categorias |
| `KYB_STORES`, `KYB_INTERVAL_S`, `KYB_SETTLE_S` | `6`, `5`, `30` | Lojas sintéticas, intervalo entre rodadas de vereditos e espera pelo consumidor no `teardown` |
| `MAX_VUS` | `400` | Teto de VUs por cenário |
| `RESERVATION_TTL_S` | `3600` | De 60 a 7200; prazo das reservas do disputado e da espalhada, maior que a execução |
| `P99_<CENÁRIO>_MS` | `500` | Limite do p(99) por cenário no `average` e no `soak`: `P99_HOT_MS`, `P99_SPREAD_MS`, `P99_ADJUST_MS`, `P99_BROWSE_MS`, `P99_STOREFRONT_MS`, `P99_PRODUCT_MS`, `P99_MODERATION_MS`, `P99_PRICING_MS`, `P99_RESERVATION_MS`, `P99_EXPIRY_MS`, `P99_CATEGORY_MS`, `P99_IMPORT_MS` e `P99_LAST_UNIT_MS` |
| `OUTBOX_LAG_MAX_S` | `10` | Limite do maior atraso de publicação da outbox |
| `OUTBOX_DRAIN_S` | `30` | Prazo para `catalog_outbox_pending` voltar a 0 depois da carga |
| `PROBE_INTERVAL_S` | `5` | Intervalo de leitura de `/q/metrics` durante a carga |
| `REMOTE_WRITE` | `auto` | `auto` liga o remote write quando o Prometheus responde, `on` exige o Prometheus, `off` desliga |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://localhost:9090/api/v1/write` | Receptor de remote write; guarda do alvo |
| `K6_IMAGE`, `GRAFANA_URL` | imagem fixada por digest, `http://localhost:3000` | Só no `run.sh` |

Taxas são números maiores que 0 e até 5000 iterações/s, com casas decimais; os grupos de SKU vão até 400. Os padrões e as regras ficam só em `src/params.js`; o `run.sh` exporta os valores efetivos que ele calcula.

## Comparar com o baseline anterior

Até a massa por execução, a suíte tinha só o SKU disputado, que esgotava as 200 unidades em segundos e depois recusava o resto com 409, a reserva espalhada e o ajuste, num produto único de 41 SKUs. Para reproduzir aquele baseline, volte o disputado ao modo antigo e rode só esses cenários, com os mesmos parâmetros:

```bash
HOT_MODE=exhaust SCENARIOS=hot_sku,spread_reserve_release,stock_adjust npm run load:average
```

A massa continua sendo criada inteira, e os SKUs dos três grupos ficam espalhados em produtos de até 4 SKUs, com os mesmos estoques; os limites e as invariantes desses cenários são os mesmos de antes, e as demais conferências da massa (estados e vitrine) também rodam. No modo `exhaust`, cada execução volta a encher os painéis de procura sem estoque com dezenas de milhares de recusas.

## Limites que reprovam a execução

| Métrica do k6 | `smoke` | `average`, `soak` | `stress` | `spike` | `breakpoint` |
|---|---|---|---|---|---|
| `server_errors` (5xx e falha de transporte) | `rate==0` | `rate==0` | `rate<0.01` | `rate<0.05` | `rate<0.01`, aborta |
| `unexpected_responses`, no total e por cenário | `rate==0` | `rate==0` | `rate<0.01` | `rate<0.05` | `rate<0.01` no total, aborta |
| `http_req_duration{scenario:<cenário>,kind:api}` | | `p(99)<P99_<CENÁRIO>_MS` | | | |
| `http_req_duration{kind:api}` | | | `p(99)<2000` | | `p(99)<2000`, aborta |
| `dropped_iterations` | `count==0` | `count==0` | | | |
| `inventory_invariants` | `rate==1` | `rate==1` | `rate==1` | `rate==1` | `rate==1` |
| `hot_sku_accepted` (só no modo `exhaust`) | `count<=HOT_STOCK` | `count<=HOT_STOCK` | `count<=HOT_STOCK` | `count<=HOT_STOCK` | `count<=HOT_STOCK` |
| `last_unit_accepted`, `last_unit_refused` | `count==` estoque e tentativas − estoque | iguais | iguais | iguais | iguais |
| `outbox_publish_lag_max_seconds` | `max<OUTBOX_LAG_MAX_S` | `max<OUTBOX_LAG_MAX_S` | `max<OUTBOX_LAG_MAX_S` | `max<OUTBOX_LAG_MAX_S` | |
| `outbox_drained` | `rate==1` | `rate==1` | `rate==1` | `rate==1` | `rate==1` |

`unexpected_responses` exige a resposta esperada de cada passo, com o status e, quando a tabela de cenários pede, o corpo (estado do produto ou da reserva, contagens da importação, status da vitrine); 409 `insufficient-stock` só é esperada no último item e no modo `exhaust`. O limite da outbox vem do PRD (preço e estoque refletem na busca em menos de 10 s); cada troca de preço, ajuste e transição grava evento, então os cenários novos também pesam no atraso. Os limites de latência são folgados de propósito, para notebook com outros contêineres disputando CPU. No `breakpoint`, os limites que abortam só são avaliados depois de 30 s. Os limites ficam em `src/profiles.js`.

O `run.sh` termina com o código do k6 (99 quando um limite reprova, inclusive no abort do `breakpoint`; 107 em erro de script ou no `setup`) ou com 1 quando há erro de banco ou do serviço nos logs; 2 é parâmetro inválido ou ambiente fora do ar; 0 significa aprovado.

## Resultado

Em `tools/load/results/<TESTID>/`, ignorado pelo git:

- `summary.md`, também impresso no fim do k6: massa (produtos por estado, SKUs por grupo e tempo de preparo) e rodadas do KYB; resultado de cada limite; pico planejado, iterações, descartes, requisições, respostas inesperadas, p95, p99 e máximo por cenário (no `breakpoint`, também a taxa-alvo no instante do fim); desfechos de negócio contados (`outcomes`: vitrine visível e oculta, produtos aprovados, recusados e arquivados, bloqueios e desbloqueios, promoções canceladas, reservas confirmadas, liberadas, repetidas e curtas, categorias movidas e importações concluídas); conferências de invariantes; disputa e último item (modo, estoque, aceitas, recusas e saldo final do disputado, rajada do último item e recusas `insufficient-stock` contadas pelo serviço); maior atraso e maior fila da outbox e o tempo até ela esvaziar; p99 de `catalog.inventory.update.latency` por operação (reserva, liberação, confirmação, prorrogação, ajuste, oferta e promoções), pela diferença dos buckets entre o início e o fim, como o `histogram_quantile` do Prometheus; perfil, semente e todos os parâmetros efetivos; e, acrescentada pelo `run.sh`, a verificação dos logs do período.
- `summary.json`: os mesmos dados estruturados, mais o objeto completo do `handleSummary` em `k6`.
- `logs.txt`: a verificação dos logs, com as primeiras linhas de erro do PostgreSQL ou dos dois papéis, se houver, a contagem de avisos `WARN` e quantas rodadas do KYB foram enviadas.
- `k6.log`: a saída do k6; `kyb.log`: uma linha por rodada de vereditos.

O log do `teardown` traz ainda a linha `resultado <TESTID>` (saldo do disputado e do último item, vendas do ciclo de reserva contra as confirmações do serviço, espera pelo varredor e reservas expiradas por ele, lojas do KYB no último veredito, outbox e p99 do serviço) e, se houver, `invariante violada: <id>` com o que foi lido. As recusas `insufficient-stock` contadas pelo serviço devem bater com `last_unit_refused` no modo `contend` (e com `hot_sku_refused + spread_refused + last_unit_refused` no `exhaust`), e o `reserved` final do último item com `last_unit_accepted`. A latência do k6 inclui rede, filtros HTTP e serialização; a do serviço mede só o caso de uso (ADR 017). Uma diferença grande entre as duas aponta custo fora do caso de uso.

## Painel e remote write

Com `REMOTE_WRITE` ligado, o k6 envia as métricas ao Prometheus local pela saída `experimental-prometheus-rw`, a cada 2 s, com native histograms (`--features native-histograms`) e as tags `testid` e `profile` em todas as séries; no fim, marca as séries como encerradas (stale markers). O Prometheus do compose já recebe remote write (`--web.enable-remote-write-receiver`).

O painel Marketplace · Catálogo · Carga (`http://localhost:3000/d/marketplace-catalog-load`, arquivo `infra/observability/grafana/dashboards/catalogo/carga.json`, login `admin`/`admin`) filtra pela variável `testid`, com as execuções mais recentes primeiro, e mostra: invariantes, aceitas no último item, recusas por falta de saldo, maior atraso da outbox, esvaziamento e descartes; VUs, iterações por cenário e requisições por status; p95 e p99 por cenário e p99 por rota, pelos native histograms, e o p99 de `catalog.inventory.update.latency` no serviço, por operação; a fração de 5xx, falha de transporte e resposta inesperada; reservas aceitas no disputado e no último item e recusas por falta de saldo por cenário; os desfechos de negócio acumulados e a fração de respostas inesperadas por cenário; e o atraso e a fila da outbox pela sonda do k6 e pelo scrape do worker. As séries do serviço aparecem só na janela da execução escolhida. O `run.sh` imprime o link com o `testid` e o intervalo da execução, no início e no fim.

Depois do fim, as séries do k6 recebem stale markers: consultas instantâneas voltam vazias, e é preciso usar intervalo ou `last_over_time`, como fazem os indicadores do painel.

## Consultas PromQL equivalentes

Para acompanhar a mesma carga num Prometheus que colete `/q/metrics` (ADR 017 e ADR 013):

```text
# p(99) das atualizações de inventário e de preço em 5 min, geral e por operação
histogram_quantile(0.99, sum by (le) (rate(catalog_inventory_update_latency_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le, operation) (rate(catalog_inventory_update_latency_seconds_bucket[5m])))

# falhas por operação (outcome="failure" inclui recusas de negócio, como insufficient-stock)
sum by (operation) (rate(catalog_inventory_update_latency_seconds_count{outcome="failure"}[5m]))

# procura sem estoque: recusas por falta de saldo
sum(increase(catalog_reservations_refused_total{reason="insufficient-stock"}[1d]))

# reservas confirmadas e expiradas pelo varredor; vereditos do KYB por desfecho; linhas importadas por desfecho
sum(increase(catalog_reservations_committed_total[5m]))
sum(increase(catalog_reservations_expired_total[5m]))
sum by (outcome) (increase(catalog_kyb_verdicts_total[5m]))
sum by (outcome) (increase(catalog_imports_lines_total[5m]))

# outbox: maior atraso de publicação, p(99) e fila pendente
max(catalog_outbox_publish_lag_seconds_max)
max(catalog_outbox_publish_lag_seconds{quantile="0.99"})
sum(catalog_outbox_pending)
max(catalog_outbox_oldest_age_seconds)

# taxa de respostas 5xx do catálogo
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# p(99) do k6 por cenário e desfechos de negócio numa execução
histogram_quantile(0.99, sum by (scenario) (rate(k6_http_req_duration_seconds{testid="<TESTID>",kind="api"}[1m])))
sum by (outcome) (k6_outcomes_total{testid="<TESTID>"})
```

## Limitações

- A linha do SKU disputado tem vazão limitada: cada reserva e cada liberação a travam, gravam o evento e confirmam. A 100/s no modo `contend` (200 escritas/s na mesma linha), a espera pela trava passou de 2 s, esgotou o pool de conexões e levou o catálogo inteiro a 503; por isso a taxa base do `contend` é 20/s (40 escritas/s), e o `stress`, o `spike` e o `breakpoint` passam desse ponto de propósito. A rajada do último item mede a corrida pela mesma unidade; o `stress`, o `spike` e o `breakpoint` estressam a linha disputada. Rode-os com a máquina livre.
- Gerador e alvo dividem a máquina com outros processos; a latência é sensível a isso. Os vereditos do KYB usam um único `kafka-console-producer.sh` por execução, porque uma JVM nova por rodada custava 2 a 3 s de CPU a cada 5 s no contêiner do Kafka e travava o catálogo por até 1,7 s.
- Gerador e alvo dividem a máquina, com PostgreSQL, Kafka e Keycloak; os números comparam versões na mesma máquina, não dimensionam produção. `dropped_iterations` acima de 0 indica gerador, VUs ou alvo saturados.
- O alvo é um único pod de cada papel do catálogo no compose; o teste não mede escala horizontal, a disputa da trava consultiva do relay entre pods nem a rede de um cluster.
- `catalog_outbox_publish_lag_seconds_max` é o máximo de uma janela que decai em cerca de 2 min; a amostragem a cada `PROBE_INTERVAL_S` reduz, mas não elimina, a chance de perder um pico curto entre duas leituras. O p(99) da publicação vem de percentis calculados no próprio pod, sem agregação entre pods.
- O critério de 10 s do PRD é medido até a confirmação do Kafka; a indexação na busca ainda não existe e não entra na conta.
- A verificação de deadlock depende dos logs: o PostgreSQL registra `deadlock detected` como `ERROR`, e o catálogo registra falhas de banco não tratadas como `ERROR`. Erros que o serviço trate em silêncio não aparecem.
- A importação é serial no worker (um lote por segundo); no `breakpoint`, a 2 importações/s, a fila cresce, as iterações esperam até `IMPORT_WAIT_S` e falham por prazo. O varredor expira até 40 reservas/s; a 20× da expiração (40/s), ele fica no limite, e o `teardown` pode esgotar `EXPIRY_WAIT_S`.
- O teste não confere o veredito descartado como antigo (`STALE`): as rodadas do KYB sempre avançam o `occurredAt`.
- O contêiner do k6 roda sem limite de memória; o teto de 1.000 VUs pré-alocados vem da memória medida no `average`, cerca de 2 MiB por VU (ver ADR 022), e o `stress`, o `spike`, o `soak` e o `breakpoint` só tiveram os parâmetros validados pelo `--check`.
- A saída `experimental-prometheus-rw` e a flag `native-histograms` são experimentais no k6; trocar a imagem fixada exige repetir uma execução e conferir o painel.
