#!/usr/bin/env bash
# Confere se o host tem memória para uma tarefa pesada (testes do front, e2e, build nativo) antes de começar. Mostra a
# memória disponível, o swap livre, os daemons do Gradle vivos e os maiores consumidores, e falha abaixo do mínimo
# (MEMORY_GUARD_MIN_MB, padrão 3072). Com --stop-gradle-daemons, encerra os daemons do Gradle antes de conferir: cada
# JDK que abre o build (gradlew e servidores de linguagem Java) sobe o seu, e eles se acumulam. Ver README, "Memória
# do host".
set -euo pipefail

MIN_MB=${MEMORY_GUARD_MIN_MB:-3072}

if [[ ! -r /proc/meminfo ]]; then
  echo "Sem /proc/meminfo: a conferência de memória só roda no Linux."
  exit 0
fi

gradle_daemons() {
  ps -eo pid=,etimes=,rss=,args= | awk '/GradleDaemon/ && !/awk/ {printf "%d %d %d\n", $1, $2 / 60, $3 / 1024}'
}

if [[ ${1:-} == "--stop-gradle-daemons" ]]; then
  for pid in $(gradle_daemons | awk '{print $1}'); do
    kill -TERM "$pid" && echo "Daemon do Gradle $pid encerrado."
  done
  for _ in $(seq 1 20); do
    [[ -z $(gradle_daemons) ]] && break
    sleep 0.5
  done
fi

meminfo() {
  local field=$1
  awk -v key="$field:" '$1 == key {print int($2 / 1024)}' /proc/meminfo
}
available=$(meminfo MemAvailable)
swap_free=$(meminfo SwapFree)

echo "Memória disponível: ${available} MB (mínimo ${MIN_MB} MB). Swap livre: ${swap_free} MB."

daemons=$(gradle_daemons)
if [[ -n $daemons ]]; then
  echo "Daemons do Gradle vivos (encerre com: npm run check:memory -- --stop-gradle-daemons):"
  echo "$daemons" | awk '{printf "  pid %d, %d min, %d MB\n", $1, $2, $3}'
fi

echo "Maiores consumidores (RAM + swap):"
for status in /proc/[0-9]*/status; do
  # NOTE: o processo pode terminar entre a listagem e a leitura; ele só sai da conta.
  awk '/^Name:/ {name = $2} /^VmRSS:/ {rss = $2} /^VmSwap:/ {swap = $2} END {if (rss + swap > 0) printf "%d %s\n", (rss + swap) / 1024, name}' "$status" 2>/dev/null || true
done | sort -rn | head -5 | awk '{printf "  %s: %d MB\n", $2, $1}'

if ((available < MIN_MB)); then
  echo "Memória insuficiente para tarefa pesada: feche o que não usa ou encerre os daemons acima." >&2
  exit 1
fi
