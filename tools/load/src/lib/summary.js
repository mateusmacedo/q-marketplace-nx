// Resumo da execução para summary.md e summary.json. Não importa módulos do k6, porque profiles.js usa as sondas
// no Node.
import { kybRounds } from '../kyb.js';
import { massPlan, SKU_GROUPS, STATES } from '../mass-plan.js';
import { K6_SCENARIOS } from '../params.js';
import { INVARIANTS, OUTCOMES, SERVICE_OPERATIONS } from './checks.js';

// WHY: o k6 só guarda no resumo as submétricas (por cenário, invariante ou operação) que algum limite referencia;
// estas condições sempre passam e servem só para criá-las.
const PROBE_CONDITIONS = new Set(['count>=0', 'max>=0', 'rate>=0', 'value>=0']);

export const isProbe = (metric, condition) => PROBE_CONDITIONS.has(condition);

export function summaryProbes(params) {
  const probes = { 'http_reqs{kind:api}': ['count>=0'], 'http_req_duration{kind:api}': ['max>=0'] };
  for (const s of params.scenarios.filter((name) => K6_SCENARIOS.includes(name))) {
    probes[`http_reqs{scenario:${s},kind:api}`] = ['count>=0'];
    probes[`http_req_duration{scenario:${s},kind:api}`] = ['max>=0'];
    probes[`iterations{scenario:${s}}`] = ['count>=0'];
    probes[`dropped_iterations{scenario:${s}}`] = ['count>=0'];
    probes[`unexpected_responses{scenario:${s}}`] = ['rate>=0'];
  }
  for (const id of Object.keys(INVARIANTS))
    probes[`inventory_invariants{invariant:${id}}`] = ['rate>=0'];
  for (const op of SERVICE_OPERATIONS)
    probes[`service_update_p99_seconds{operation:${op}}`] = ['value>=0'];
  for (const outcome of Object.keys(OUTCOMES))
    probes[`outcomes{outcome:${outcome}}`] = ['count>=0'];
  return probes;
}

const value = (data, metric, stat) => data.metrics[metric]?.values?.[stat] ?? null;
const valueOrZero = (data, metric, stat) => value(data, metric, stat) || 0;
const round = (n, digits) => (n === null ? null : Number(n.toFixed(digits)));

function thresholdRows(data) {
  const rows = [];
  for (const [metric, entry] of Object.entries(data.metrics)) {
    for (const [condition, result] of Object.entries(entry.thresholds || {})) {
      if (!isProbe(metric, condition)) rows.push({ metric, condition, ok: result.ok });
    }
  }
  return rows.sort(
    (a, b) => a.metric.localeCompare(b.metric) || a.condition.localeCompare(b.condition),
  );
}

// Inverte a área da rampa linear de 0 ao pico: iterações agendadas até o fim dão o instante e a taxa-alvo nele.
function rateAtEnd(peakPerSecond, rampSeconds, scheduled) {
  if (peakPerSecond <= 0) return 0;
  const elapsed = Math.min(rampSeconds, Math.sqrt((2 * scheduled * rampSeconds) / peakPerSecond));
  return (peakPerSecond * elapsed) / rampSeconds;
}

function scenarioReport(data, { params, plan }, name, planned) {
  const latency = `http_req_duration{scenario:${name},kind:api}`;
  const iterations = valueOrZero(data, `iterations{scenario:${name}}`, 'count');
  const dropped = valueOrZero(data, `dropped_iterations{scenario:${name}}`, 'count');
  const report = {
    plannedPeakPerSecond: planned.burst ? 'rajada' : round(planned.peakPerSecond, 2),
    plannedIterations: planned.iterations,
    iterations,
    dropped,
    requests: valueOrZero(data, `http_reqs{scenario:${name},kind:api}`, 'count'),
    unexpected: valueOrZero(data, `unexpected_responses{scenario:${name}}`, 'passes'),
    p95Ms: round(value(data, latency, 'p(95)'), 1),
    p99Ms: round(value(data, latency, 'p(99)'), 1),
    maxMs: round(value(data, latency, 'max'), 1),
  };
  if (params.profile === 'breakpoint' && !planned.burst) {
    report.targetPerSecondAtEnd = round(
      rateAtEnd(planned.peakPerSecond, plan.totalSeconds, iterations + dropped),
      1,
    );
  }
  return report;
}

function invariantReport(data) {
  const byId = {};
  let passed = 0;
  let failed = 0;
  for (const [id, description] of Object.entries(INVARIANTS)) {
    const ok = valueOrZero(data, `inventory_invariants{invariant:${id}}`, 'passes');
    const bad = valueOrZero(data, `inventory_invariants{invariant:${id}}`, 'fails');
    if (ok + bad > 0) byId[id] = { description, passed: ok, failed: bad };
    passed += ok;
    failed += bad;
  }
  return { checked: passed + failed, passed, failed, byId };
}

function serviceP99Report(data) {
  const serviceP99Ms = {};
  for (const op of SERVICE_OPERATIONS) {
    const seconds = value(data, `service_update_p99_seconds{operation:${op}}`, 'value');
    if (seconds !== null) serviceP99Ms[op] = round(seconds * 1000, 1);
  }
  return serviceP99Ms;
}

function outcomeReport(data) {
  const outcomeCounts = {};
  for (const [id, description] of Object.entries(OUTCOMES)) {
    const count = value(data, `outcomes{outcome:${id}}`, 'count');
    if (count) outcomeCounts[id] = { description, count };
  }
  return outcomeCounts;
}

function lastUnitReport(data, params) {
  if (!params.scenarios.includes('last_unit')) return null;
  return {
    stock: params.lastUnitStock,
    attempts: params.lastUnitAttempts,
    accepted: valueOrZero(data, 'last_unit_accepted', 'count'),
    refused: valueOrZero(data, 'last_unit_refused', 'count'),
  };
}

export function buildReport(data, { params, plan }) {
  const thresholds = thresholdRows(data);
  const failed = thresholds.filter((row) => !row.ok).length;
  const byScenario = Object.fromEntries(
    Object.entries(plan.scenarios).map(([name, planned]) => [
      name,
      scenarioReport(data, { params, plan }, name, planned),
    ]),
  );
  const mass = massPlan(params);
  const rounds = kybRounds(params, plan);
  return {
    testid: params.testid,
    profile: params.profile,
    seed: params.seed,
    scenarios: params.scenarios,
    result: failed === 0 ? 'aprovado' : 'reprovado',
    failedThresholds: failed,
    remoteWrite: params.remoteWrite,
    plannedSeconds: plan.totalSeconds,
    elapsedSeconds: round(data.state.testRunDurationMs / 1000, 1),
    requests: {
      total: valueOrZero(data, 'http_reqs', 'count'),
      api: valueOrZero(data, 'http_reqs{kind:api}', 'count'),
      apiPerSecond: round(valueOrZero(data, 'http_reqs{kind:api}', 'rate'), 1),
      p95Ms: round(value(data, 'http_req_duration{kind:api}', 'p(95)'), 1),
      p99Ms: round(value(data, 'http_req_duration{kind:api}', 'p(99)'), 1),
    },
    iterations: {
      total: valueOrZero(data, 'iterations', 'count'),
      dropped: valueOrZero(data, 'dropped_iterations', 'count'),
    },
    mass: {
      products: mass.products.length,
      skus: mass.skus,
      byState: mass.byState,
      skusByGroup: Object.fromEntries(SKU_GROUPS.map((g) => [g, mass.skusByRole[g] || 0])),
      moderation: params.moderationProducts,
      setupSeconds: round(value(data, 'mass_setup_seconds', 'value'), 1),
    },
    byScenario,
    outcomes: outcomeReport(data),
    kyb: { rounds, stores: rounds > 0 ? params.kybStores : 0, intervalS: params.kybIntervalS },
    thresholds,
    invariants: invariantReport(data),
    hot: {
      mode: params.hotMode,
      stock: params.hotStock,
      accepted: valueOrZero(data, 'hot_sku_accepted', 'count'),
      refused: valueOrZero(data, 'hot_sku_refused', 'count'),
      finalOnHand: value(data, 'hot_final_on_hand', 'value'),
      finalReserved: value(data, 'hot_final_reserved', 'value'),
      finalSold: value(data, 'hot_final_sold', 'value'),
    },
    lastUnit: lastUnitReport(data, params),
    spreadRefused: valueOrZero(data, 'spread_refused', 'count'),
    outbox: {
      lagMaxS: round(value(data, 'outbox_publish_lag_max_seconds', 'max'), 3),
      lagLimitS: params.outboxLagMaxS,
      pendingMax: value(data, 'outbox_pending', 'max'),
      drained: value(data, 'outbox_drained', 'rate') === 1,
      drainSeconds: round(value(data, 'outbox_drain_seconds', 'value'), 1),
    },
    service: {
      p99Ms: serviceP99Report(data),
      refusedInsufficientStock: value(data, 'service_refused_insufficient_stock', 'value'),
    },
    params: { PROFILE: params.profile, TESTID: params.testid, ...params.effective },
  };
}

const ms = (n) => (n === null ? '—' : n.toFixed(1));
const orDash = (n) => (n === null || n === undefined ? '—' : String(n));

function overviewLines(report) {
  const scenarioList = report.scenarios.map((s) => `\`${s}\``).join(', ');
  const verdict =
    report.result === 'aprovado'
      ? 'todos os limites passaram'
      : `${report.failedThresholds} limite(s) reprovado(s)`;
  const remoteWrite =
    report.remoteWrite === 'on' ? `ligado, tag \`testid=${report.testid}\`` : 'desligado';
  return [
    `# Carga do catálogo — ${report.testid}`,
    '',
    `- Perfil: \`${report.profile}\`, semente ${report.seed}, cenários ${scenarioList}`,
    `- Resultado: ${verdict}`,
    `- Duração planejada da carga: ${report.plannedSeconds} s; tempo total com preparo e verificação: ${report.elapsedSeconds} s`,
    `- Requisições de negócio: ${report.requests.api} (${report.requests.apiPerSecond}/s); total com login, preparo e métricas: ${report.requests.total}`,
    `- Iterações: ${report.iterations.total} (descartadas: ${report.iterations.dropped})`,
    `- Remote write ao Prometheus: ${remoteWrite}`,
  ];
}

function massLines(report) {
  const byState = STATES.map((s) => `${s} ${report.mass.byState[s]}`).join(', ');
  const byGroup = SKU_GROUPS.map((g) => `${g} ${report.mass.skusByGroup[g]}`).join(', ');
  const kyb =
    report.kyb.rounds > 0
      ? `${report.kyb.rounds} rodadas a cada ${report.kyb.intervalS} s para ${report.kyb.stores} lojas sintéticas`
      : 'fora da execução';
  return [
    '',
    '## Massa',
    '',
    `- ${report.mass.products} produtos e ${report.mass.skus} SKUs, preparados em ${orDash(report.mass.setupSeconds)} s; ${report.mass.moderation} reservados à moderação`,
    `- Por estado: ${byState}`,
    `- SKUs por grupo: ${byGroup}`,
    `- Vereditos do KYB: ${kyb}`,
  ];
}

function thresholdLines(report) {
  return [
    '',
    '## Limites',
    '',
    '| Métrica | Condição | Resultado |',
    '| --- | --- | --- |',
    ...report.thresholds.map(
      (row) => `| \`${row.metric}\` | \`${row.condition}\` | ${row.ok ? 'ok' : 'falhou'} |`,
    ),
  ];
}

function scenarioLines(report) {
  const breakpoint = report.profile === 'breakpoint';
  const rows = Object.entries(report.byScenario).map(([name, s]) => {
    const target = breakpoint ? ` ${s.targetPerSecondAtEnd} |` : '';
    return `| \`${name}\` | ${s.plannedPeakPerSecond} | ${s.iterations} | ${s.dropped} | ${s.requests} | ${s.unexpected} | ${ms(s.p95Ms)} | ${ms(s.p99Ms)} | ${ms(s.maxMs)} |${target}`;
  });
  return [
    '',
    '## Cenários',
    '',
    `| Cenário | Pico planejado (/s) | Iterações | Descartadas | Requisições | Inesperadas | p95 (ms) | p99 (ms) | máx (ms) |${breakpoint ? ' Taxa-alvo no fim (/s) |' : ''}`,
    `| --- | --- | --- | --- | --- | --- | --- | --- | --- |${breakpoint ? ' --- |' : ''}`,
    ...rows,
    `| todos | | | | ${report.requests.api} | | ${ms(report.requests.p95Ms)} | ${ms(report.requests.p99Ms)} | |${breakpoint ? ' |' : ''}`,
  ];
}

function outcomeLines(report) {
  const rows = Object.values(report.outcomes).map((o) => `- ${o.description}: ${o.count}`);
  return ['', '## Desfechos de negócio', '', ...(rows.length === 0 ? ['- nenhum'] : rows)];
}

function invariantLines(report) {
  const inv = report.invariants;
  return [
    '',
    '## Invariantes',
    '',
    `${inv.passed} de ${inv.checked} conferências passaram.`,
    '',
    '| Invariante | Passaram | Falharam |',
    '| --- | --- | --- |',
    ...Object.values(inv.byId).map(
      (entry) => `| ${entry.description} | ${entry.passed} | ${entry.failed} |`,
    ),
  ];
}

function contentionLines(report) {
  const hot = report.hot;
  const last = report.lastUnit;
  const hotMode = hot.mode === 'contend' ? ' (reserva e libera)' : ' (reserva até esgotar)';
  return [
    '',
    '## Disputa e último item',
    '',
    `- SKU disputado, modo \`${hot.mode}\`${hotMode}: estoque ${hot.stock}; reservas aceitas ${hot.accepted}, recusas 409 \`insufficient-stock\` ${hot.refused}`,
    `- Saldo final do disputado: onHand=${orDash(hot.finalOnHand)}, reserved=${orDash(hot.finalReserved)}, sold=${orDash(hot.finalSold)}`,
    last
      ? `- Último item: estoque ${last.stock}, ${last.attempts} tentativas na rajada; aceitas ${last.accepted}, recusas 409 \`insufficient-stock\` ${last.refused}`
      : '- Último item: fora da execução',
    `- Recusas por estoque na reserva espalhada: ${report.spreadRefused}; recusas \`insufficient-stock\` contadas pelo serviço no período: ${orDash(report.service.refusedInsufficientStock)}`,
  ];
}

function outboxLines(report) {
  const outbox = report.outbox;
  const drained = outbox.drained
    ? `sim, em ${orDash(outbox.drainSeconds)} s`
    : 'não, dentro de OUTBOX_DRAIN_S';
  return [
    '',
    '## Outbox',
    '',
    `- Maior atraso de publicação visto durante a carga: ${orDash(outbox.lagMaxS)} s (limite ${outbox.lagLimitS} s)`,
    `- Maior fila pendente: ${orDash(outbox.pendingMax)}`,
    `- Fila zerada depois da carga: ${drained}`,
  ];
}

function serviceLatencyLines(report) {
  const ops = Object.entries(report.service.p99Ms).map(([op, p99]) => `- \`${op}\`: ${ms(p99)} ms`);
  return [
    '',
    '## Latência no serviço',
    '',
    'p99 de `catalog.inventory.update.latency` no período, pela diferença dos buckets:',
    '',
    ...(ops.length === 0 ? ['- sem amostras'] : ops),
  ];
}

function parameterLines(report) {
  return [
    '',
    '## Parâmetros',
    '',
    '| Variável | Valor |',
    '| --- | --- |',
    ...Object.entries(report.params).map(([name, val]) => {
      const shown = val === '' ? '(padrão do perfil)' : `\`${val}\``;
      return `| \`${name}\` | ${shown} |`;
    }),
  ];
}

export function renderMarkdown(report) {
  const lines = [
    ...overviewLines(report),
    ...massLines(report),
    ...thresholdLines(report),
    ...scenarioLines(report),
    ...outcomeLines(report),
    ...invariantLines(report),
    ...contentionLines(report),
    ...outboxLines(report),
    ...serviceLatencyLines(report),
    ...parameterLines(report),
  ];
  return `${lines.join('\n')}\n`;
}
