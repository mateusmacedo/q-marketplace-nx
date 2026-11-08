#!/usr/bin/env bash
# Soma os tetos de CPU e memória que o compose de infra/local resolve, com todos os profiles, e reprova se a soma
# passar da fração do host (INFRA_BUDGET_FRACTION, padrão 0,97) ou se algum serviço não declarar teto (ADR 019).
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


rows, missing, cpu_total, mem_total = [], [], 0.0, 0.0
for name, service in sorted(services.items()):
    limits = service.get("deploy", {}).get("resources", {}).get("limits", {})
    if not limits.get("cpus") or not limits.get("memory"):
        missing.append(name)
        continue
    cpu, mem = float(limits["cpus"]), size(limits["memory"])
    cpu_total, mem_total = cpu_total + cpu, mem_total + mem
    rows.append(f"  {name:<20} {cpu:>5.2f} vCPU  {mem / 1024**2:>6.0f} MiB")

cpu_cap, mem_cap = cpu_host * fraction, mem_host * fraction
print(f"host: {cpu_host:.0f} vCPU e {mem_host / 1024**3:.2f} GiB; teto ({fraction:.0%}): {cpu_cap:.2f} vCPU e "
      f"{mem_cap / 1024**3:.2f} GiB")
print("\n".join(rows))
print(f"  {'soma':<20} {cpu_total:>5.2f} vCPU  {mem_total / 1024**2:>6.0f} MiB "
      f"({cpu_total / cpu_host:.0%} e {mem_total / mem_host:.0%} do host)")
problems = [f"sem teto: {', '.join(missing)}"] if missing else []
if cpu_total > cpu_cap:
    problems.append(f"CPU: {cpu_total:.2f} > {cpu_cap:.2f} vCPU")
if mem_total > mem_cap:
    problems.append(f"memória: {mem_total / 1024**3:.2f} > {mem_cap / 1024**3:.2f} GiB")
if problems:
    sys.exit("REPROVADO: " + "; ".join(problems))
print("OK: a soma dos tetos cabe na fração do host.")
EOF
