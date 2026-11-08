#!/usr/bin/env bash
# Teste de carga do catálogo por perfil: valida os parâmetros, aprova a loja pelo tópico do KYB/KYC, roda o k6 em
# contêiner contra o compose local, envia vereditos do KYB durante a carga e procura erros nos logs do período.
# Uso: run.sh [--check] <perfil> [args do k6].
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LOAD_DIR="$ROOT/tools/load"
SRC="$LOAD_DIR/src"
RESULTS_DIR="$LOAD_DIR/results"
COMPOSE=(docker compose -f "$ROOT/infra/local/docker-compose.yml" --profile catalog)
K6_IMAGE=${K6_IMAGE:-grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8}
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}
DASHBOARD_UID=marketplace-catalog-load

usage() {
  echo "uso: run.sh [--check] <smoke|average|stress|spike|soak|breakpoint> [argumentos do k6]" >&2
  exit 2
}
cli() { node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON "$SRC/cli.js" "$@"; }

CHECK=false
if [ "${1:-}" = "--check" ]; then
  CHECK=true
  shift
fi
[ "$#" -ge 1 ] || usage
export PROFILE="$1"
shift

if "$CHECK"; then
  cli plan
  echo "run.sh: parâmetros válidos para o perfil $PROFILE"
  exit 0
fi

export TESTID=${TESTID:-$PROFILE-$(date -u +%Y%m%d-%H%M%S)}
# WHY: params.js é a única fonte dos padrões; o run.sh exporta os valores efetivos em vez de repeti-los.
RESOLVED=$(cli env) || exit 2
PARAM_NAMES=(PROFILE TESTID)
while IFS='=' read -r name value; do
  export "$name=$value"
  PARAM_NAMES+=("$name")
done <<< "$RESOLVED"
KYB_LINES=$(cli kyb) || exit 2

json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
token() {
  curl -sf -X POST "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$KEYCLOAK_CLIENT_ID" -d username="$1" -d password="$1" | json 'd["access_token"]'
}
store_status() {
  curl -sf "$BASE_URL/stores/$STORE_ID/eligibility" -H "Authorization: Bearer $SELLER" | json 'd["status"]'
}
# Mesmo envelope do roteiro do README: o catálogo só muda a situação da loja por veredito do KYB/KYC.
send_approval() {
  local payload
  payload=$(python3 -c 'import json,sys,uuid,datetime
now=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00","Z")
print(json.dumps({"eventId":str(uuid.uuid4()),"eventType":"SellerApproved","eventVersion":1,"source":"kyb-kyc",
  "tenantId":sys.argv[1],"occurredAt":now,"correlationId":"carga-"+sys.argv[2],"data":{"storeId":sys.argv[3]}}))' \
    "$TENANT_ID" "$TESTID" "$STORE_ID")
  echo "$STORE_ID|$payload" | produce 2>/dev/null
}
produce() {
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic kyb-kyc.seller.v1 \
    --reader-property parse.key=true --reader-property key.separator='|'
}
# Uma rodada do roteiro de src/kyb.js num lote só: uma linha por loja sintética (loja=evento), chave = storeId.
verdict_lines() {
  python3 - "$TENANT_ID" "$TESTID" "$@" <<'PY'
import datetime, json, sys, uuid
tenant, testid, pairs = sys.argv[1], sys.argv[2], sys.argv[3:]
now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
for pair in pairs:
    store, event = pair.split("=")
    print(store + "|" + json.dumps({"eventId": str(uuid.uuid4()), "eventType": event, "eventVersion": 1, "source": "kyb-kyc",
        "tenantId": tenant, "occurredAt": now, "correlationId": "carga-" + testid, "data": {"storeId": store}}))
PY
}
# WHY: o roteiro começa quando o setup termina a massa (a frase "massa pronta" no log do k6), para cair na carga;
# as rodadas seguem o relógio desde então, e o teardown confere o último veredito de cada loja.
kyb_rounds() {
  local waited=0 round=0 start due line
  local -a pairs
  until grep -q 'massa pronta' "$K6_LOG" 2>/dev/null; do
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge 600 ]; then echo "o preparo não terminou em 600 s; nenhum veredito enviado" >&2; return 1; fi
  done
  start=$(date +%s)
  while IFS= read -r line; do
    due=$((start + round * KYB_INTERVAL_S))
    while [ "$(date +%s)" -lt "$due" ]; do sleep 1; done
    read -ra pairs <<< "$line"
    if verdict_lines "${pairs[@]}"; then echo "rodada $round enviada: ${#pairs[@]} vereditos" >&2; else echo "rodada $round: falha ao montar" >&2; fi
    round=$((round + 1))
  done <<< "$KYB_LINES"
}

echo "== Execução $TESTID contra $BASE_URL"
cli plan
for HEALTH in "$API_MANAGEMENT_URL" "$WORKER_MANAGEMENT_URL"; do
  if ! curl -sf -o /dev/null "$HEALTH/q/health/ready"; then
    echo "Catálogo indisponível em $HEALTH; suba o compose (docker compose -f infra/local/docker-compose.yml --profile catalog up -d --wait)." >&2
    exit 2
  fi
done

K6_OUTPUT=()
DOCKER_ENV=()
PROMETHEUS_URL=${K6_PROMETHEUS_RW_SERVER_URL%/api/v1/write}
if [ "$REMOTE_WRITE" != off ]; then
  if curl -sf -o /dev/null "$PROMETHEUS_URL/-/ready"; then
    REMOTE_WRITE=on
  elif [ "$REMOTE_WRITE" = on ]; then
    echo "Prometheus indisponível em $PROMETHEUS_URL; suba o profile observability ou use REMOTE_WRITE=off." >&2
    exit 2
  else
    REMOTE_WRITE=off
  fi
fi
if [ "$REMOTE_WRITE" = on ]; then
  K6_OUTPUT=(-o experimental-prometheus-rw --features native-histograms --tag "testid=$TESTID" --tag "profile=$PROFILE")
  # WHY: push a cada 2 s deixa o painel perto do terminal; os stale markers encerram as séries no fim da execução.
  DOCKER_ENV=(-e "K6_PROMETHEUS_RW_SERVER_URL=$K6_PROMETHEUS_RW_SERVER_URL" -e K6_PROMETHEUS_RW_PUSH_INTERVAL=2s
    -e K6_PROMETHEUS_RW_STALE_MARKERS=true)
fi
echo "== Remote write ao Prometheus: $REMOTE_WRITE"

SELLER=$(token seller-demo) || { echo "Falha ao obter token no Keycloak ($KEYCLOAK_URL)." >&2; exit 2; }
STATUS=$(store_status || echo DESCONHECIDA)
if [ "$STATUS" != "APPROVED" ]; then
  echo "== Loja $STORE_ID está $STATUS; enviando SellerApproved em kyb-kyc.seller.v1"
  send_approval
  for _ in $(seq 1 30); do
    STATUS=$(store_status || echo DESCONHECIDA)
    [ "$STATUS" = "APPROVED" ] && break
    sleep 1
  done
  if [ "$STATUS" != "APPROVED" ]; then
    echo "Loja continua $STATUS após 30 s; confira o consumidor do KYB/KYC nos logs do catálogo." >&2
    exit 2
  fi
fi
echo "== Loja $STORE_ID APPROVED"

mkdir -p "$RESULTS_DIR"
if ! mkdir "$RESULTS_DIR/$TESTID" 2>/dev/null; then
  echo "Já existe tools/load/results/$TESTID; rode de novo em 1 s ou escolha outro TESTID." >&2
  exit 2
fi
ENV_ARGS=()
for name in "${PARAM_NAMES[@]}"; do ENV_ARGS+=(-e "$name=${!name}"); done
TTY_ARGS=()
PROGRESS_ARGS=()
# WHY: fora de um terminal, o k6 grava uma linha de progresso por cenário a cada segundo nos logs do npm, do Nx e do CI.
if [ -t 1 ]; then TTY_ARGS=(-t); else PROGRESS_ARGS=(--quiet); fi
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START_MS=$(($(date +%s) * 1000))
DASHBOARD="$GRAFANA_URL/d/$DASHBOARD_UID?var-testid=$TESTID&from=$START_MS"
if [ "$REMOTE_WRITE" = on ]; then echo "== Painel: $DASHBOARD&to=now&refresh=10s"; fi

K6_LOG="$RESULTS_DIR/$TESTID/k6.log"
KYB_LOG="$RESULTS_DIR/$TESTID/kyb.log"
KYB_PID=""
PRODUCER_PID=""
if [ -n "$KYB_LINES" ]; then
  # WHY: um produtor só, aberto na execução inteira; uma JVM nova por rodada gastava 2 a 3 s de CPU a cada 5 s no
  # contêiner do Kafka e travava o catálogo por até 1,7 s numa máquina compartilhada.
  exec {KYB_FD}> >(produce >> "$KYB_LOG" 2>&1)
  PRODUCER_PID=$!
  kyb_rounds >&"$KYB_FD" 2>> "$KYB_LOG" &
  KYB_PID=$!
  exec {KYB_FD}>&-
fi
set +e
docker run --rm "${TTY_ARGS[@]}" --network host --user "$(id -u):$(id -g)" \
  -v "$SRC:/scripts:ro" -v "$RESULTS_DIR:/results" "${DOCKER_ENV[@]}" "${ENV_ARGS[@]}" \
  "$K6_IMAGE" run --no-usage-report "${PROGRESS_ARGS[@]}" "${K6_OUTPUT[@]}" "$@" /scripts/main.js 2>&1 | tee "$K6_LOG"
K6_STATUS=${PIPESTATUS[0]}
if [ -n "$KYB_PID" ]; then
  kill "$KYB_PID" 2>/dev/null
  wait "$KYB_PID" 2>/dev/null
  # WHY: sem quem escreva, o produtor recebe fim de arquivo, envia o que falta e termina.
  wait "$PRODUCER_PID" 2>/dev/null
fi
set -e
END_MS=$((($(date +%s) + 60) * 1000))

# WHY: deadlock ou outro erro de banco pode ser absorvido por nova tentativa e não virar 5xx; o log é a prova.
LOG_REPORT="$RESULTS_DIR/$TESTID/logs.txt"
LOG_FAILED=0
{
  echo "== Logs desde $START"
  DB_CID=$("${COMPOSE[@]}" ps -q postgres 2>/dev/null || true)
  if [ -n "$DB_CID" ]; then
    DB_ERRORS=$(docker logs --since "$START" "$DB_CID" 2>&1 | grep -E 'ERROR:|FATAL:|deadlock detected' || true)
    if [ -n "$DB_ERRORS" ]; then
      LOG_FAILED=1
      echo "PostgreSQL registrou $(echo "$DB_ERRORS" | wc -l) erro(s):"
      echo "$DB_ERRORS" | head -10
    else
      echo "PostgreSQL: nenhum ERROR, FATAL ou deadlock."
    fi
  else
    echo "Contêiner postgres não encontrado; verificação do log do banco pulada."
  fi
  for SERVICE in catalog-api catalog-worker; do
    APP_CID=$("${COMPOSE[@]}" ps -q "$SERVICE" 2>/dev/null || true)
    if [ -z "$APP_CID" ]; then
      echo "Contêiner $SERVICE não encontrado; verificação do log pulada."
      continue
    fi
    APP_LOG=$(docker logs --since "$START" "$APP_CID" 2>&1)
    APP_ERRORS=$(echo "$APP_LOG" | grep -E '"level":"(ERROR|FATAL)"| ERROR \[|deadlock|40P01' || true)
    APP_WARNS=$(echo "$APP_LOG" | grep -cE '"level":"WARN(ING)?"| WARN \[' || true)
    if [ -n "$APP_ERRORS" ]; then
      LOG_FAILED=1
      echo "$SERVICE registrou $(echo "$APP_ERRORS" | wc -l) erro(s) e $APP_WARNS aviso(s) WARN:"
      echo "$APP_ERRORS" | head -10 | cut -c1-400
    else
      echo "$SERVICE: nenhum ERROR nem deadlock ($APP_WARNS aviso(s) WARN)."
    fi
  done
  if [ -n "$KYB_LINES" ]; then
    echo "Vereditos do KYB: $(grep -c ' enviada: ' "$KYB_LOG" 2>/dev/null || true) de $(wc -l <<< "$KYB_LINES") rodadas enviadas (kyb.log)."
  fi
} > "$LOG_REPORT"
cat "$LOG_REPORT"
if [ -f "$RESULTS_DIR/$TESTID/summary.md" ]; then
  { echo; echo "## Logs do período"; echo; echo '```text'; cat "$LOG_REPORT"; echo '```'; } >> "$RESULTS_DIR/$TESTID/summary.md"
fi

echo "== Resultado em tools/load/results/$TESTID (summary.md, summary.json, logs.txt, k6.log e kyb.log)"
if [ "$REMOTE_WRITE" = on ]; then echo "== Painel da execução: $DASHBOARD&to=$END_MS"; fi
if [ "$K6_STATUS" -ne 0 ]; then
  echo "k6 terminou com código $K6_STATUS (99 = limite reprovado)." >&2
  exit "$K6_STATUS"
fi
if [ "$LOG_FAILED" -ne 0 ]; then
  echo "Reprovado: erros de banco ou do serviço nos logs do período." >&2
  exit 1
fi
echo "Aprovado."
