# Marketplace — monorepo Nx

Marketplace multivendor em oito contextos, cada um com serviço Quarkus e módulos de domínio e aplicação. O contexto de catálogo e inventário está implementado; os outros sete têm estrutura, saúde e informações, sem endpoints de negócio. **Os PRDs ainda não estão implementados por completo.**

## Requisitos e execução

- Node.js 24 (versão de referência em `.nvmrc`), npm e JDK 21.
- Internet no primeiro build para baixar npm, Gradle e dependências Maven.
- Gradle Wrapper incluído; não é necessário instalar Gradle globalmente.
- Docker para os Dev Services do catálogo (testes e `dev:catalog`), o smoke, os containers e a compilação nativa.

```bash
npm ci
npm run check
npm test
npm run build
npm run smoke
npm run dev:catalog
```

O catálogo escuta em `http://localhost:8083`. No Windows, o Nx chama o `gradlew.bat`; os exemplos curl abaixo usam sintaxe Bash.

O catálogo é reativo (Quarkus REST + Mutiny + Reactive SQL Client) e persiste em PostgreSQL. Em `dev:catalog`, os Dev Services sobem um PostgreSQL, um Kafka e um Redis efêmeros via Docker; o Redis guarda o cache da vitrine e da taxonomia ([ADR 024](docs/decisoes.md)), invalidado a cada escrita. As chamadas de negócio exigem um JWT do provedor OIDC com `tenant_id` e um papel: `seller` (produtos, ofertas e estoque da loja indicada em `store_id`), `catalog-moderator` (moderação), `catalog-admin` (categorias, leitura do catálogo do tenant e consulta da elegibilidade das lojas) ou `catalog-reservations` (reservas do checkout e do OMS). Por padrão, o modo dev espera o realm `marketplace` em `http://localhost:8180` (ajustável por `CATALOG_JWT_ISSUER`, `CATALOG_JWT_JWKS_URL` e `CATALOG_JWT_AUDIENCE`).

Em produção, configure `QUARKUS_DATASOURCE_REACTIVE_URL`, `QUARKUS_DATASOURCE_JDBC_URL` (usado só pelas migrations), `QUARKUS_DATASOURCE_USERNAME` e `QUARKUS_DATASOURCE_PASSWORD` (role de runtime, só DML), `QUARKUS_FLYWAY_USERNAME` e `QUARKUS_FLYWAY_PASSWORD` (role dono do schema `catalog_inventory`, que só as migrations usam), `CATALOG_JWT_ISSUER`, `CATALOG_JWT_JWKS_URL`, `CATALOG_JWT_AUDIENCE` e `KAFKA_BOOTSTRAP_SERVERS`; sem eles o serviço não inicia. O cache Redis fica desligado por padrão, no compose e em produção, porque no A/B da carga não trouxe ganho (ADR 024); `CATALOG_CACHE_ENABLED=true` liga e exige `QUARKUS_REDIS_HOSTS` (com o usuário da ACL, por exemplo `redis://catalog@redis:6379`) e `QUARKUS_REDIS_PASSWORD`; `CATALOG_CACHE_TTL` (60 s) e `CATALOG_CACHE_TIMEOUT` (100 ms) ajustam a validade e o prazo de cada comando. Decisões nas [ADRs 006 a 024](docs/decisoes.md).

O catálogo publica seus eventos no Kafka por uma outbox transacional, em quatro tópicos: `catalog-inventory.product.v1`, `catalog-inventory.offer.v1`, `catalog-inventory.inventory.v1` e `catalog-inventory.store.v1` (contrato em `contracts/catalog-inventory/events`). Ele consome os vereditos de loja do KYB/KYC em `kyb-kyc.seller.v1` (contrato em `contracts/kyb-kyc/events`), e a situação da loja só muda por esses vereditos. Como o serviço KYB/KYC ainda não publica, o roteiro abaixo grava o veredito direto no tópico. Os tópicos e a fila de descarte `catalog-inventory.kyb-seller.dlq` existem antes de o serviço subir, sem criação automática: no cluster, pelo Job do Helm chart; no compose, pelo `kafka-init`, que cria também `kyb-kyc.seller.v1`.

Para a stack local completa, `docker compose -f infra/local/docker-compose.yml --profile catalog up -d --build --wait` (após `npx nx run catalog-inventory-service:native`, que gera o executável nativo do catálogo) sobe o PostgreSQL compartilhado (porta `5432`, banco `marketplace`, um schema por contexto), Redis (porta `6379`, cache da vitrine e da taxonomia, com ACL), Kafka (porta `9094` no host) com o Redpanda Console (`http://localhost:8090`: tópicos, mensagens, grupos de consumo e lag), Keycloak, o Collector OpenTelemetry o catálogo em dois papéis do mesmo executável ([ADR 020](docs/decisoes.md)) e o gateway. A api roda em duas instâncias, `catalog-api-1` e `catalog-api-2`, com o HTTP de negócio pelo gateway em `8083`; o `catalog-worker` faz relay da outbox, varredor de reservas, importação, contagem de SKUs, limpeza e consumo do KYB/KYC. Saúde e `/q/metrics` ficam na porta de gestão de cada instância: `9301` e `9302` nas duas api e `9311` no worker. O gateway é um nginx que serve o painel em `http://localhost:8080` e balanceia a api entre as duas instâncias, com failover: `docker compose -f infra/local/docker-compose.yml --profile catalog stop catalog-api-2` derruba uma, e o cabeçalho `X-Upstream` das respostas mostra qual instância respondeu. As portas seguem o esquema da [ADR 019](docs/decisoes.md): `808c` para a api de cada contexto pelo gateway e `9c0n`/`9c1n` para a gestão da api e do worker. O gateway usa a imagem do painel, então rode `npx nx run admin-web:build` antes do `--build`. `--profile infra` sobe só Kafka, o console dele, Redis, PostgreSQL e Keycloak. Cada recurso fica num arquivo de `infra/local/compose/`, as portas são publicadas só em `127.0.0.1` e portas e senhas locais podem ser trocadas em `infra/local/.env` (modelo em `infra/local/.env.example`). O Keycloak (`http://localhost:8180`, administrador `admin`/`admin-local`) importa o realm `marketplace` de `infra/keycloak/marketplace-realm.json`, com os usuários de desenvolvimento `seller-demo` (papel `seller`, loja `22222222-2222-4222-8222-222222222222`), `moderator-demo` (`catalog-moderator`), `admin-demo` (`catalog-admin`) e `checkout-demo` (`catalog-reservations`, conta de serviço do checkout), todos no tenant `11111111-1111-4111-8111-111111111111` e com senha igual ao usuário. Credenciais apenas locais; não use este realm fora do desenvolvimento. O login usa o tema `marketplace`, com o visual do painel e em português. Se o realm mudar, recrie o Keycloak com `docker compose -f infra/local/docker-compose.yml --profile infra up -d --force-recreate keycloak` e reinicie as instâncias da api (`restart catalog-api-1 catalog-api-2`), que guardam as chaves de assinatura do Keycloak anterior.

Para observar a stack, acrescente `--profile observability`: sobem Grafana (`http://localhost:3000`, `admin`/`admin`), Prometheus (`http://localhost:9090`), Tempo, Loki, Alloy, postgres-exporter, redis-exporter e cAdvisor, além do Collector OpenTelemetry, que já sobe com o catálogo (OTLP em `localhost:4317` e `4318`). O catálogo do compose exporta traces e logs ao Collector, e o Prometheus raspa `/q/metrics`. Os painéis ficam nas pastas Marketplace · Catálogo (Serviço, Negócio e Ponta a ponta, que inclui os SLOs e a busca do ciclo de vida por produto, reserva ou lote) e Marketplace · Infra (com o painel do Redis do cache) do Grafana, e os alertas em `infra/observability/prometheus/rules` ([ADRs 019 e 021](docs/decisoes.md)). O trace de cada operação desce da entrada (com autenticação e autorização como eventos) ao resource, a cada adaptador e às consultas que ele fez, e segue até a publicação do evento no worker. Cada requisição leva tenant, loja, usuário e os agregados do caminho no span e no log; cada etapa do worker, o tenant e o id do agregado; e cada evento de domínio, o tipo e o agregado (ADR 021). `npm run check:observability` valida configuração, alertas e painéis sem subir a stack.

No compose, o Swagger UI fica na porta de gestão de cada instância, `http://localhost:9301/q/swagger-ui` (use **Authorize** com o token). Em produção ele vem desligado e só aparece com `CATALOG_SWAGGER_UI_ENABLED=true`; a especificação continua em `/q/openapi`.

O roteiro abaixo usa códigos fixos (`camisetas`, `CAM-P` e a chave de idempotência); num banco já usado, troque-os para não receber 409 de duplicidade.

```bash
token() { curl -s -X POST http://localhost:8180/realms/marketplace/protocol/openid-connect/token \
  -d grant_type=password -d client_id=marketplace-cli -d username=$1 -d password=$1 \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'; }
ADMIN=$(token admin-demo); SELLER=$(token seller-demo); MODERATOR=$(token moderator-demo)
STORE=22222222-2222-4222-8222-222222222222; TENANT=11111111-1111-4111-8111-111111111111

# Categoria com atributo do produto (marca) e de SKU (tamanho); loja aprovada por um veredito do KYB/KYC.
CATEGORY=$(curl -s http://localhost:8083/categories -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"code":"camisetas","name":"Camisetas","attributes":[
        {"code":"marca","label":"Marca","type":"TEXT","required":true,"variant":false},
        {"code":"tamanho","label":"Tamanho","type":"OPTION","required":true,"variant":true,"options":["P","M","G"]}]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "$STORE|{\"eventId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"eventType\":\"SellerApproved\",\"eventVersion\":1,\"source\":\"kyb-kyc\",\"tenantId\":\"$TENANT\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"data\":{\"storeId\":\"$STORE\"}}" \
  | docker compose -f infra/local/docker-compose.yml --profile infra exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
      --topic kyb-kyc.seller.v1 --reader-property parse.key=true --reader-property key.separator='|'
curl -s http://localhost:8083/stores/$STORE/eligibility -H "Authorization: Bearer $ADMIN"   # APPROVED em até alguns segundos

# Produto em rascunho; cada SKU recebe oferta e estoque antes do envio para moderação.
PRODUCT=$(curl -s http://localhost:8083/products -H "Authorization: Bearer $SELLER" -H 'Content-Type: application/json' \
  -d '{"categoryId":"'$CATEGORY'","currency":"BRL","title":"Camiseta básica","attributes":{"marca":"Acme"},"skus":[
        {"sellerSku":"CAM-P","variantAttributes":{"tamanho":"P"},"physical":true,
         "dimensions":{"heightCm":3,"widthCm":25,"lengthCm":30,"weightGrams":200}}]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
SKU=$(curl -s http://localhost:8083/products/$PRODUCT -H "Authorization: Bearer $SELLER" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["skus"][0]["id"])')
curl -s -X PUT http://localhost:8083/offers/$SKU -H "Authorization: Bearer $SELLER" -H 'Content-Type: application/json' \
  -d '{"listPrice":{"amount":79.90,"currency":"BRL"},"salePrice":{"amount":59.90,"currency":"BRL"}}'
curl -s -X PUT http://localhost:8083/inventory/skus/$SKU -H "Authorization: Bearer $SELLER" \
  -H 'Content-Type: application/json' -d '{"onHand":5}'
curl -s -X POST http://localhost:8083/products/$PRODUCT/submit -H "Authorization: Bearer $SELLER"
curl -s -X POST http://localhost:8083/moderation/products/$PRODUCT/approve -H "Authorization: Bearer $MODERATOR"
curl -s http://localhost:8083/storefront/$TENANT/products/$PRODUCT

# Reserva idempotente pelo checkout e baixa após o pagamento (conta de serviço checkout-demo).
CHECKOUT=$(token checkout-demo)
RESERVATION=$(curl -s http://localhost:8083/inventory/reservations -H "Authorization: Bearer $CHECKOUT" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: pedido-42-tentativa-1" \
  -d '{"orderRef":"pedido-42","items":[{"skuId":"'$SKU'","quantity":2,"expectedUnitPrice":{"amount":59.90,"currency":"BRL"}}]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
curl -s -X POST http://localhost:8083/inventory/reservations/$RESERVATION/commit -H "Authorization: Bearer $CHECKOUT"
curl -s http://localhost:8083/inventory/skus/$SKU -H "Authorization: Bearer $SELLER"

# Importação por planilha CSV, processada em segundo plano: CAM-P já existe e recebe só preço e estoque;
# CAM-V-M e CAM-V-G, com o mesmo product_ref, viram um produto novo em rascunho.
cat > /tmp/produtos.csv <<'CSV'
product_ref,category_code,title,description,attributes,seller_sku,variant_attributes,physical,height_cm,width_cm,length_cm,weight_g,currency,list_price,sale_price,on_hand
basica,camisetas,Camiseta básica,,marca=Acme,CAM-P,tamanho=P,true,3,25,30,200,BRL,79.90,54.90,8
gola-v,camisetas,Camiseta gola V,"Algodão, gola V",marca=Acme,CAM-V-M,tamanho=M,true,3,25,30,200,BRL,,49.90,4
gola-v,camisetas,Camiseta gola V,"Algodão, gola V",marca=Acme,CAM-V-G,tamanho=G,true,3,25,30,200,BRL,,49.90,2
CSV
IMPORT=$(curl -s http://localhost:8083/products/imports -H "Authorization: Bearer $SELLER" -H 'Content-Type: text/csv' \
  --data-binary @/tmp/produtos.csv | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')   # 202, PENDING
sleep 2; curl -s http://localhost:8083/products/imports/$IMPORT -H "Authorization: Bearer $SELLER"   # COMPLETED, resultado por linha

# Eventos publicados pela outbox (ProductCreated, PriceChanged, InventoryReserved...), com a chave do agregado.
docker compose -f infra/local/docker-compose.yml --profile infra exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic catalog-inventory.inventory.v1 --from-beginning --timeout-ms 5000 --formatter-property print.key=true
```

### Painel (admin-web)

O painel de operação ([ADR 027 e ADR 028](docs/decisoes.md)) é Angular 22 com o CoreUI. Para desenvolver com hot reload, suba a stack (`--profile catalog`) e rode `npm run dev:admin`: o painel abre em `http://localhost:4200`, o login vai para o Keycloak (por exemplo, `seller-demo`/`seller-demo`), e as chamadas a `/api/catalog-inventory` passam pelo proxy do dev server até o gateway, na `8083`. No compose, o mesmo build roda no gateway, em `http://localhost:8080`.

```bash
npm run dev:admin
npx nx run catalog-inventory-admin-data-access:codegen
npx nx run-many -t lint test build -p admin-web catalog-inventory-admin-data-access
npx nx e2e admin-web-e2e
npx nx run admin-web:container
```

O `codegen` gera o cliente da API a partir de `contracts/catalog-inventory/openapi.json` com o Hey API, e o código gerado fica versionado em `libs/catalog-inventory/admin/data-access/src/generated`. Os testes unitários rodam no Chromium headless do Playwright (na primeira vez, `npx playwright install chromium`), e o e2e entra pelo Keycloak local, que precisa estar no ar. A referência dos componentes é a documentação do CoreUI em `https://coreui.io/angular/docs/llms-full.txt`.

### Clientes de banco

Com o compose no ar (profiles `infra`, `catalog` ou `all`), o PostgreSQL e o Redis aceitam conexões do host em `127.0.0.1`, sem TLS, de clientes como DBeaver, pgAdmin, psql e RedisInsight. As senhas são os padrões locais; se `infra/local/.env` as trocar, valem as de lá (variáveis entre parênteses, modelo em `infra/local/.env.example`). Cada role do PostgreSQL abre no próprio schema (`search_path`), então as tabelas aparecem sem prefixo.

| Recurso | Host e porta | Usuário | Senha padrão | Acesso |
|---|---|---|---|---|
| PostgreSQL, banco `marketplace` | `localhost:5432` (`POSTGRES_PORT`) | `catalog_inventory` | `catalog-inventory-local` (`CATALOG_INVENTORY_DB_PASSWORD`) | Só DML no schema `catalog_inventory`, como o serviço |
| | | `catalog_inventory_migrator` | `catalog-inventory-migrator-local` (`CATALOG_INVENTORY_DB_MIGRATOR_PASSWORD`) | Dono do schema, com DDL, como o Flyway |
| | | `postgres` | `postgres-local` (`POSTGRES_PASSWORD`) | Superusuário, só para administração |
| Redis, banco 0 | `localhost:6379` (`REDIS_PORT`) | `inspector` | `inspector-local-redis` (`REDIS_INSPECTOR_PASSWORD`) | Só leitura: chaves (`SCAN`, `GET`, `TYPE`, `TTL`) e `INFO`, sem escrita |

## Serviços

| Projeto Nx | Porta de desenvolvimento | Implementação |
|---|---:|---|
| identity-organization-service | 8081 | Estrutura + saúde + informações |
| kyb-kyc-service | 8082 | Estrutura + saúde + informações |
| catalog-inventory-service | 8083 | Categorias, produtos, ofertas, moderação, estoque, reservas e eventos (reativo, PostgreSQL, Kafka, JWT) |
| cart-checkout-service | 8084 | Estrutura + saúde + informações |
| order-management-service | 8085 | Estrutura + saúde + informações |
| payment-split-service | 8086 | Estrutura + saúde + informações |
| fulfillment-logistics-service | 8087 | Estrutura + saúde + informações |
| settlement-payout-service | 8088 | Estrutura + saúde + informações |

Todos expõem `/q/health/ready` e `/q/health/live`, e `/internal/info` na porta de negócio, que no catálogo só a api abre; no catálogo, saúde e métricas ficam na porta de gestão 9000, e em produção o papel vem de `QUARKUS_PROFILE=prod,api` ou `prod,worker` (ADR 020). O catálogo também publica métricas Prometheus em `/q/metrics`, incluindo as de negócio do PRD e suas consultas PromQL ([ADR 017](docs/decisoes.md)). Os testes do catálogo usam porta aleatória.

## Tarefas

```bash
npx nx show projects
npm run graph
npx nx run catalog-inventory-service:test
npx nx run catalog-inventory-service:assemble
npx nx run order-management-service:serve
npx nx run catalog-inventory-service:container
docker compose -f infra/local/docker-compose.yml --profile catalog up --build
npx nx run catalog-inventory-service:native
```

Os targets dos módulos Java vêm do próprio Gradle, pelo `@nx/gradle` ([ADR 025](docs/decisoes.md)): `npx nx show project <módulo>` lista todos, `npm run graph` mostra também essas tarefas inferidas, e `test-ci` divide o teste por classe. A CI usa o cache remoto e distribui as tarefas entre três agentes pelo Nx Cloud ([ADR 026](docs/decisoes.md)); localmente, o cache remoto só entra depois do `npx nx connect`. `npm run build` roda o `assemble` de cada módulo e produz artefatos JVM sem rodar testes; a CI (`.github/workflows/ci.yml`) confere imagens, observabilidade, chart, perfis de carga e vulnerabilidades, testa os projetos afetados e faz o build completo antes do smoke, sem cluster ([ADR 023](docs/decisoes.md)). Cada verificação roda sozinha: `npm run check:images`, `check:observability`, `check:chart`, `check:vulnerabilities` e `npx nx run load:check`. `npm run infra:budget` soma os tetos de CPU e memória da stack local e reprova acima de 97% da máquina ([ADR 019](docs/decisoes.md)). A carga fica em `tools/load`, por perfil (`npm run load:smoke`, `load:average` etc., [ADR 022](docs/decisoes.md)), e roda também sob demanda no workflow `load.yml`. `smoke` inicia os oito JARs e confere saúde e informações; para o catálogo, sobe PostgreSQL e Kafka efêmeros. O Kafka do smoke usa a imagem `apache/kafka-native`, de partida rápida; o compose usa `apache/kafka`, na mesma versão. Os contêineres rodam como executável nativo: `native` compila com o builder Mandrel padrão do Quarkus (distribuição do GraalVM) dentro de Docker/Podman, sem GraalVM local, e leva alguns minutos e vários GB de RAM por serviço; `container` depende dele e empacota o executável em `infra/docker/Dockerfile.native`. Builds nativos e containers não utilizam o cache Nx. A imagem base usa tag legível; fixe seu digest antes de uma release de produção.

## Implantação

O catálogo vai para Kubernetes pelo Helm chart `infra/helm/catalog-inventory` ([ADR 018](docs/decisoes.md)). O chart não cria segredos nem o schema: crie antes o schema `catalog_inventory` e os roles de runtime e migrator, como faz `infra/postgres/provision.sh` no compose, e, no namespace, o Secret com as credenciais dos dois roles, se o Kafka exigir SASL/SSL o de cliente Kafka e, com o cache ligado (`cache.enabled`, desligado por padrão; o Redis é externo e standalone), o da senha do Redis, pelos comandos abaixo ou por uma cópia ajustada de `secrets.example.yaml` (fora do repositório). Depois, instale com uma cópia ajustada de `values-production.example.yaml`; a tag da imagem é obrigatória.

```bash
kubectl create namespace catalog
kubectl -n catalog create secret generic catalog-inventory-db \
  --from-literal=username=catalog_inventory --from-literal=password='<senha>' \
  --from-literal=migrator-username=catalog_inventory_migrator --from-literal=migrator-password='<senha>'
kubectl -n catalog create secret generic catalog-inventory-kafka \
  --from-literal=KAFKA_SECURITY_PROTOCOL=SASL_SSL \
  --from-literal=KAFKA_SASL_MECHANISM=SCRAM-SHA-512 \
  --from-literal=KAFKA_SASL_JAAS_CONFIG='org.apache.kafka.common.security.scram.ScramLoginModule required username="catalog" password="<senha>";'
kubectl -n catalog create secret generic catalog-inventory-redis --from-literal=password='<senha>'
helm upgrade --install catalog-inventory infra/helm/catalog-inventory -n catalog \
  -f values-production.yaml --set image.tag=<versão> --wait
```

Antes de cada instalação ou upgrade, um Job cria os tópicos do catálogo e a fila de descarte que ainda não existem. `infra/helm/catalog-inventory/ci/k3d-smoke.sh` repete, só localmente, o teste de fumaça do chart num cluster k3d efêmero (`SOURCE_IMAGE=marketplace-nx/catalog-inventory:local`, a imagem nativa do compose).

## Organização e documentação

| Diretório | Conteúdo |
|---|---|
| `apps/<contexto>-service` | Composição Quarkus, adaptadores REST e infraestrutura do contexto |
| `libs/<contexto>/domain` | Modelo e invariantes, Java puro |
| `libs/<contexto>/application` | Casos de uso e portas; Java puro mais Mutiny (ADR 009) |
| `contracts/<contexto>` | Contratos públicos do contexto: OpenAPI e JSON Schema dos eventos |
| `infra` | Docker, Compose, realm do Keycloak e Helm chart do catálogo (`infra/helm`) |
| `tools` | Ponte Gradle/Nx, smoke e verificação de limites |
| `docs` | Proposta técnica, decisões, plano de implementação e relatório de validação |

Leia [a proposta](docs/proposta-tecnica.md), [as decisões](docs/decisoes.md), [o plano](docs/plano-implementacao.md) e [o relatório de validação](docs/validacao.md).

Não há frontend: os oito contextos do PRD são o escopo deste repositório. Storefront e painéis poderão entrar em `apps/` sem alterar os módulos Java. Também não há publicação de repositório ou implantação em nuvem.
