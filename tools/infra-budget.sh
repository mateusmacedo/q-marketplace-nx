#!/usr/bin/env bash
# Confere os tetos do compose de infra/local, com todos os profiles (ADR 019). A CPU não é somada: cada teto é um
# disjuntor contra processo descontrolado, e o kernel aceita a soma acima do host, só com disputa. Reprova serviço sem
# teto de CPU ou de memória e teto de CPU acima do host. A memória é somada, porque o teto mata o processo e a soma
# acima da RAM leva ao OOM do host; os inits (restart "no") ficam fora, porque terminam antes do resto subir.
# A fração do host vem de INFRA_BUDGET_FRACTION (padrão 0,97).
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FRACTION=${INFRA_BUDGET_FRACTION:-0.97}
CONFIG=$(mktemp)
trap 'trash "$CONFIG" 2>/dev/null || true' EXIT

docker compose -f "$ROOT/infra/local/docker-compose.yml" --profile all --profile observability config --format json > "$CONFIG"

python3 - "$FRACTION" "$(nproc)" "$CONFIG" <<'EOF'
import json, sys

fraction, cpu_host = float(sys.argv[1]), float(sys.argv[2])
mem_host = next(int(line.split()[1]) * 1024 for line in open("/proc/meminfo") if line.startswith("MemTotal:"))
services = json.load(open(sys.argv[3]))["services"]
units = {"k": 1024, "m": 1024**2, "g": 1024**3}


def size(value):
    text = str(value).strip().lower().rstrip("b")
    return float(text[:-1]) * units[text[-1]] if text[-1:] in units else float(text)


rows, missing, too_big, cpu_total, mem_total = [], [], [], 0.0, 0.0
for name, service in sorted(services.items()):
    limits = service.get("deploy", {}).get("resources", {}).get("limits", {})
    if not limits.get("cpus") or not limits.get("memory"):
        missing.append(name)
        continue
    cpu, mem = float(limits["cpus"]), size(limits["memory"])
    one_shot = service.get("restart") == "no"
    if cpu > cpu_host:
        too_big.append(f"{name} ({cpu:.2f})")
    cpu_total += cpu
    if not one_shot:
        mem_total += mem
    rows.append(f"  {name:<20} {cpu:>5.2f} vCPU  {mem / 1024**2:>6.0f} MiB" + ("  (init, fora da soma de memória)" if one_shot else ""))

mem_cap = mem_host * fraction
print(f"host: {cpu_host:.0f} vCPU e {mem_host / 1024**3:.2f} GiB; teto de memória ({fraction:.0%}): {mem_cap / 1024**3:.2f} GiB")
print("\n".join(rows))
print(f"  {'memória somada':<20} {mem_total / 1024**2:>17.0f} MiB ({mem_total / mem_host:.0%} do host)")
print(f"  {'CPU (informativo)':<20} {cpu_total:>5.2f} vCPU de tetos somados ({cpu_total / cpu_host:.1f}x o host)")
problems = [f"sem teto: {', '.join(missing)}"] if missing else []
if too_big:
    problems.append(f"teto de CPU acima dos {cpu_host:.0f} vCPU do host: {', '.join(too_big)}")
if mem_total > mem_cap:
    problems.append(f"memória: {mem_total / 1024**3:.2f} > {mem_cap / 1024**3:.2f} GiB")
if problems:
    sys.exit("REPROVADO: " + "; ".join(problems))
print("OK: todos os serviços têm teto, nenhum teto de CPU passa do host e a memória cabe na fração do host.")
EOF
