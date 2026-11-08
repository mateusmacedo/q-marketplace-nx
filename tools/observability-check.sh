#!/usr/bin/env bash
# Valida a configuração de observabilidade sem subir a stack (ADR 019): Prometheus, regras e seus testes pelo
# promtool, Collector pelo próprio binário, e painéis do Grafana (JSON, uid únicos, datasources provisionados e PromQL
# de cada consulta). Usa as mesmas imagens do compose.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OBS="$ROOT/infra/observability"
COMPOSE="$ROOT/infra/local/compose"
image_of() { sed -n 's/^ *image: *//p' "$COMPOSE/$1" | head -1; }
PROMETHEUS_IMAGE=$(image_of prometheus.yml)
OTELCOL_IMAGE=$(image_of otel-collector.yml)
WORK=$(mktemp -d)
chmod 755 "$WORK"
trap 'trash "$WORK" 2>/dev/null || true' EXIT

promtool() {
  docker run --rm --entrypoint promtool -v "$OBS/prometheus:/etc/prometheus:ro" -v "$WORK:/work:ro" \
    -w /etc/prometheus "$PROMETHEUS_IMAGE" "$@"
}

echo "== Prometheus: configuração, regras e testes das regras"
promtool check config --syntax-only prometheus.yaml
# WHY: o promtool roda sem shell no contêiner, então os globs são expandidos aqui, relativos ao -w.
(cd "$OBS/prometheus" && promtool check rules rules/*.yaml && promtool test rules tests/*.test.yaml)

echo "== Collector"
docker run --rm -v "$OBS/otel-collector/otel-collector.yaml:/etc/otelcol/config.yaml:ro" "$OTELCOL_IMAGE" \
  validate --config=/etc/otelcol/config.yaml
echo "configuração válida"

echo "== Painéis do Grafana"
# Cada consulta PromQL vira uma regra de gravação, para o promtool checar a sintaxe; as variáveis do Grafana recebem
# valores neutros antes disso.
python3 - "$OBS/grafana" "$WORK/dashboards.rules.yaml" <<'EOF'
import json, pathlib, re, sys
grafana, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
provisioned = set(re.findall(r"^\s*uid:\s*(\S+)", (grafana / "provisioning" / "datasources" / "datasources.yaml").read_text(), re.M))
neutral = {"$__rate_interval": "5m", "$__interval": "1m", "$__range": "1h"}
uids, rules, errors = {}, [], []

def panels(items):
    for p in items:
        yield p
        yield from panels(p.get("panels", []))

def datasource_uid(ds):
    return ds.get("uid") if isinstance(ds, dict) else None

for path in sorted(grafana.glob("dashboards/*/*.json")):
    name = path.relative_to(grafana)
    dash = json.loads(path.read_text())
    if dash["uid"] in uids:
        errors.append(f"{name}: uid {dash['uid']} repetido em {uids[dash['uid']]}")
    uids[dash["uid"]] = name
    for panel in panels(dash.get("panels", [])):
        for ds in [panel.get("datasource")] + [t.get("datasource") for t in panel.get("targets", [])]:
            uid = datasource_uid(ds)
            if uid and not uid.startswith("$") and uid not in provisioned:
                errors.append(f"{name}: painel '{panel.get('title')}' usa o datasource {uid}, que não é provisionado")
        for target in panel.get("targets", []):
            if datasource_uid(target.get("datasource") or panel.get("datasource")) != "prometheus" or "expr" not in target:
                continue
            expr = target["expr"]
            for var, value in neutral.items():
                expr = expr.replace(var, value)
            expr = re.sub(r"\$\{?\w+\}?", ".*", expr)
            rules.append({"record": f"dashboard:check_{len(rules)}", "expr": expr,
                          "labels": {"dashboard": str(name), "panel": panel.get("title", "")}})

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
out.write_text(json.dumps({"groups": [{"name": "dashboards", "rules": rules}]}, ensure_ascii=False))
out.chmod(0o644)
print(f"{len(uids)} painéis, {len(rules)} consultas PromQL")
EOF
promtool check rules /work/dashboards.rules.yaml
