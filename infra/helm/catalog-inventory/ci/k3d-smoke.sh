#!/usr/bin/env bash
# Teste de fumaça do chart num cluster k3d efêmero: sobe PostgreSQL (com schema e roles do provision.sh do compose) e
# Kafka (SASL) mínimos no cluster, instala o chart, confere os tópicos criados pelo Job, as tabelas no schema do contexto
# donas do migrator, o role de runtime sem DDL, os Deployments api e worker prontos, o readiness e as métricas na porta
# de gestão de cada papel, repete o upgrade sem mudança e apaga o cluster.
#   SOURCE_IMAGE=marketplace-nx/catalog-inventory:local infra/helm/catalog-inventory/ci/k3d-smoke.sh
# A kubeconfig do cluster fica no diretório de evidências; a kubeconfig do usuário não é alterada.
set -euo pipefail

CHART_DIR=$(cd "$(dirname "$0")/.." && pwd)
REPO_DIR=$(cd "$CHART_DIR/../../.." && pwd)
SOURCE_IMAGE=${SOURCE_IMAGE:?informe a imagem nativa local do catálogo, por exemplo marketplace-nx/catalog-inventory:local}
CLUSTER=${CLUSTER:-catalog-helm-smoke}
EVIDENCE=${EVIDENCE_DIR:-$(mktemp -d)}
NAMESPACE=catalog
RELEASE=catalog-inventory
TAG=k3d-smoke
IMAGE=marketplace/catalog-inventory:$TAG
DEP_IMAGES=(apache/kafka:4.2.0@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9 postgres:16.15-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea)
TOPICS=(catalog-inventory.product.v1 catalog-inventory.offer.v1 catalog-inventory.inventory.v1
  catalog-inventory.store.v1 catalog-inventory.kyb-seller.dlq)
PORT=${PORT:-18080}
API_MANAGEMENT_PORT=${API_MANAGEMENT_PORT:-19000}
WORKER_MANAGEMENT_PORT=${WORKER_MANAGEMENT_PORT:-19001}

log() { printf '\n== %s\n' "$*"; }
fail() { printf 'FALHOU: %s\n' "$*" >&2; exit 1; }
k() { kubectl -n "$NAMESPACE" "$@"; }

# Grava os logs do pod do Job de tópicos enquanto o helm aguarda o hook (o Job some ao concluir).
capture_topics_log() {
  local out=$1 pod
  for _ in $(seq 1 300); do
    pod=$(k get pods -l app.kubernetes.io/component=kafka-topics -o name 2>/dev/null | head -n1)
    if [ -n "$pod" ] && k logs -f "$pod" > "$out" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

kafka_topics() {
  k exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9091 "$@"
}

if k3d cluster list "$CLUSTER" > /dev/null 2>&1; then
  fail "o cluster $CLUSTER já existe; apague-o ou use CLUSTER=<outro nome>"
fi
docker image inspect "$SOURCE_IMAGE" > /dev/null
for image in "${DEP_IMAGES[@]}"; do
  docker image inspect "$image" > /dev/null 2>&1 || docker pull -q "$image"
done
mkdir -p "$EVIDENCE"
echo "Evidências em $EVIDENCE"

log "Cluster $CLUSTER"
k3d cluster create "$CLUSTER" --no-lb --k3s-arg "--disable=traefik@server:0" \
  --kubeconfig-update-default=false --kubeconfig-switch-context=false --wait --timeout 180s
trap 'if [ "${KEEP_CLUSTER:-0}" != 1 ]; then k3d cluster delete "$CLUSTER"; fi' EXIT
trap 'exit 130' INT TERM
export KUBECONFIG=$EVIDENCE/kubeconfig
k3d kubeconfig get "$CLUSTER" > "$KUBECONFIG"

log "Imagens"
docker tag "$SOURCE_IMAGE" "$IMAGE"
k3d image import -c "$CLUSTER" "$IMAGE" "${DEP_IMAGES[@]}"

log "PostgreSQL, Kafka e Secrets"
kubectl create namespace "$NAMESPACE"
k create secret generic postgres-admin --from-literal=password=postgres-k3d
k create secret generic catalog-inventory-db --from-literal=username=catalog_inventory --from-literal=password=catalog-k3d \
  --from-literal=migrator-username=catalog_inventory_migrator --from-literal=migrator-password=catalog-migrator-k3d
k create configmap postgres-provision --from-file="$REPO_DIR/infra/postgres/provision.sh"
k create secret generic catalog-kafka-client \
  --from-literal=KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT \
  --from-literal=KAFKA_SASL_MECHANISM=PLAIN \
  --from-literal=KAFKA_SASL_JAAS_CONFIG='org.apache.kafka.common.security.plain.PlainLoginModule required username="catalog" password="catalog-kafka-k3d";'
k apply -f "$CHART_DIR/ci/k3d-deps.yaml"
k rollout status deploy/postgres --timeout 180s
k wait --for=condition=complete job/postgres-init --timeout 180s
k logs job/postgres-init | tee "$EVIDENCE/postgres-init.log"
k rollout status deploy/kafka --timeout 180s
for _ in $(seq 1 60); do kafka_topics --list > /dev/null 2>&1 && break; sleep 2; done
kafka_topics --list > "$EVIDENCE/topics-before.txt"

log "helm install"
capture_topics_log "$EVIDENCE/topics-job-install.log" & capture_pid=$!
helm install "$RELEASE" "$CHART_DIR" -n "$NAMESPACE" -f "$CHART_DIR/ci/k3d-values.yaml" \
  --set image.tag="$TAG" --wait --timeout 5m | tee "$EVIDENCE/helm-install.txt"
wait "$capture_pid" || fail "sem logs do Job de tópicos na instalação"
cat "$EVIDENCE/topics-job-install.log"

log "Tópicos no Kafka"
kafka_topics --list | tee "$EVIDENCE/topics-after-install.txt"
for topic in "${TOPICS[@]}"; do
  grep -qx "$topic" "$EVIDENCE/topics-after-install.txt" || fail "tópico $topic ausente"
done
if grep -qx kyb-kyc.seller.v1 "$EVIDENCE/topics-after-install.txt"; then fail "kyb-kyc.seller.v1 não deveria ser criado"; fi
grep -c '^Created topic' "$EVIDENCE/topics-job-install.log" | grep -qx "${#TOPICS[@]}" || fail "o Job não criou os ${#TOPICS[@]} tópicos"
k get events --field-selector involvedObject.kind=Job -o custom-columns=TIME:.lastTimestamp,REASON:.reason,OBJECT:.involvedObject.name,MESSAGE:.message \
  | tee "$EVIDENCE/job-events-install.txt"
grep -q Completed "$EVIDENCE/job-events-install.txt" || fail "Job de tópicos não concluiu"

log "Schema e roles no PostgreSQL"
psql_admin() { k exec deploy/postgres -- psql -X -qAt -U postgres -d marketplace -c "$1"; }
psql_admin "SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'catalog_inventory' ORDER BY 1" \
  | tee "$EVIDENCE/schema-tables.txt"
grep -q '^flyway_schema_history|catalog_inventory_migrator$' "$EVIDENCE/schema-tables.txt" \
  || fail "histórico do Flyway ausente no schema catalog_inventory"
if grep -v '|catalog_inventory_migrator$' "$EVIDENCE/schema-tables.txt"; then fail "tabela fora da posse do migrator"; fi
if k exec deploy/postgres -- env PGPASSWORD=catalog-k3d psql -X -q -h 127.0.0.1 -U catalog_inventory -d marketplace \
    -c 'CREATE TABLE catalog_inventory.k3d_probe (id int)' > "$EVIDENCE/runtime-ddl.txt" 2>&1; then
  fail "o role de runtime conseguiu criar tabela"
fi
cat "$EVIDENCE/runtime-ddl.txt"

log "Deployments"
k get deploy,pods -o wide | tee "$EVIDENCE/workloads.txt"
for spec in api:2 worker:1; do
  role=${spec%%:*} expected=${spec#*:}
  ready=$(k get deploy "$RELEASE-$role" -o jsonpath='{.status.readyReplicas}')
  [ "$ready" = "$expected" ] || fail "réplicas prontas de $role: ${ready:-0} (esperado $expected)"
done

log "Saúde na porta de gestão de cada papel (port-forward)"
k port-forward "deploy/$RELEASE-api" "$API_MANAGEMENT_PORT:9000" > "$EVIDENCE/port-forward-api.log" 2>&1 & pf_pids=($!)
k port-forward "deploy/$RELEASE-worker" "$WORKER_MANAGEMENT_PORT:9000" > "$EVIDENCE/port-forward-worker.log" 2>&1 & pf_pids+=($!)
k port-forward "svc/$RELEASE" "$PORT:80" > "$EVIDENCE/port-forward.log" 2>&1 & pf_pids+=($!)
for spec in "api:$API_MANAGEMENT_PORT" "worker:$WORKER_MANAGEMENT_PORT"; do
  role=${spec%%:*} port=${spec#*:}
  for _ in $(seq 1 30); do curl -fsS "http://localhost:$port/q/health/live" > /dev/null 2>&1 && break; sleep 1; done
  curl -fsS "http://localhost:$port/q/health/ready" | tee "$EVIDENCE/health-ready-$role.json"
  echo
  grep -q '"status": "UP"' "$EVIDENCE/health-ready-$role.json" || fail "readiness do $role não está UP"
done

log "Service da api: /q/* fora da porta 8080"
for _ in $(seq 1 30); do [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/q/health/ready")" != 000 ] && break; sleep 1; done
status_health=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/q/health/ready")
echo "/q/health/ready pelo Service: $status_health" | tee "$EVIDENCE/service-health.txt"
[ "$status_health" = 404 ] || fail "esperado 404 em /q/health/ready pelo Service (recebido $status_health)"

log "JWT: JWKS fictício só é buscado ao validar um token"
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }
header=$(printf '{"alg":"RS256","typ":"JWT","kid":"smoke"}' | b64url)
payload=$(printf '{"iss":"https://issuer.invalid/realms/marketplace","aud":"catalog-inventory","sub":"smoke","tenant_id":"t1","store_id":"s1","groups":["seller"],"exp":%s}' "$(( $(date +%s) + 600 ))" | b64url)
status_without=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/products")
status_forged=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $header.$payload.c2lnbmF0dXJl" "http://localhost:$PORT/products")
echo "sem token: $status_without; token com chave desconhecida: $status_forged" | tee "$EVIDENCE/jwt.txt"
[ "$status_without" = 401 ] && [ "$status_forged" = 401 ] || fail "esperado 401 nas duas chamadas"
k logs -l app.kubernetes.io/name=catalog-inventory --tail=-1 --prefix > "$EVIDENCE/catalog.log"
grep -i 'jwks.invalid' "$EVIDENCE/catalog.log" | head -n3 | tee -a "$EVIDENCE/jwt.txt" || echo "(sem menção ao JWKS no log)"

# WHY: depois das chamadas acima, porque a api só publica http_server_requests_seconds após a primeira requisição.
log "Métricas na porta de gestão de cada papel"
curl -fsS "http://localhost:$WORKER_MANAGEMENT_PORT/q/metrics" > "$EVIDENCE/metrics-worker.txt"
grep -E '^catalog_outbox_pending' "$EVIDENCE/metrics-worker.txt" || fail "catalog_outbox_pending ausente nas métricas do worker"
curl -fsS "http://localhost:$API_MANAGEMENT_PORT/q/metrics" > "$EVIDENCE/metrics-api.txt"
grep -m5 -E '^http_server_requests_seconds_count' "$EVIDENCE/metrics-api.txt" \
  || fail "http_server_requests_seconds_count ausente nas métricas da api"
kill "${pf_pids[@]}"

log "helm upgrade sem mudança (idempotência do Job)"
pods_before=$(k get pods -l app.kubernetes.io/name=catalog-inventory -o jsonpath='{.items[*].metadata.name}')
capture_topics_log "$EVIDENCE/topics-job-upgrade.log" & capture_pid=$!
helm upgrade "$RELEASE" "$CHART_DIR" -n "$NAMESPACE" -f "$CHART_DIR/ci/k3d-values.yaml" \
  --set image.tag="$TAG" --wait --timeout 5m | tee "$EVIDENCE/helm-upgrade.txt"
wait "$capture_pid" || fail "sem logs do Job de tópicos no upgrade"
cat "$EVIDENCE/topics-job-upgrade.log"
if grep -q '^Created topic' "$EVIDENCE/topics-job-upgrade.log"; then fail "o upgrade recriou tópicos"; fi
k get events --field-selector involvedObject.kind=Job -o custom-columns=TIME:.lastTimestamp,REASON:.reason,OBJECT:.involvedObject.name,MESSAGE:.message \
  | tee "$EVIDENCE/job-events-upgrade.txt"
[ "$(grep -c Completed "$EVIDENCE/job-events-upgrade.txt")" -ge 2 ] || fail "Job de tópicos não concluiu no upgrade"
helm history "$RELEASE" -n "$NAMESPACE" | tee "$EVIDENCE/helm-history.txt"
[ "$(k get pods -l app.kubernetes.io/name=catalog-inventory -o jsonpath='{.items[*].metadata.name}')" = "$pods_before" ] \
  || fail "o upgrade sem mudança trocou os pods"
k get jobs | tee "$EVIDENCE/jobs-after.txt"

log "OK: todas as verificações passaram (evidências em $EVIDENCE)"
