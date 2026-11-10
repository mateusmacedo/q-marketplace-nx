// Resumo da execução para summary.md e summary.json. Não importa módulos do k6, porque profiles.js usa as sondas
// no Node.
import { kybRounds } from '../kyb.js';
import { massPlan, SKU_GROUPS, STATES } from '../mass-plan.js';
import { K6_SCENARIOS } from '../params.js';
import { INVARIANTS, OUTCOMES, SERVICE_OPERATIONS } from './checks.js';

// WHY: o k6 só guarda no resumo as submétricas (por cenário, invariante ou operação) que algum limite referencia;
// estas condições sempre passam e servem só para criá-las.
const PROBE_CONDITIONS = ['count>=0', 'max>=0', 'rate>=0', 'value>=0'];

export const isProbe = (metric, condition) => PROBE_CONDITIONS.includes(condition);

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

const value = (data, metric, stat) => {
  const values = data.metrics[metric] && data.metrics[metric].values;
  return values && values[stat] !== undefined ? values[stat] : null;
};
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

export function buildReport(data, { params, plan }) {
  const thresholds = thresholdRows(data);
  const failed = thresholds.filter((row) => !row.ok).length;
  const scenarios = {};
  for (const [name, planned] of Object.entries(plan.scenarios)) {
    const latency = `http_req_duration{scenario:${name},kind:api}`;
    const iterations = value(data, `iterations{scenario:${name}}`, 'count') || 0;
    const dropped = value(data, `dropped_iterations{scenario:${name}}`, 'count') || 0;
    scenarios[name] = {
      plannedPeakPerSecond: planned.burst ? 'rajada' : round(planned.peakPerSecond, 2),
      plannedIterations: planned.iterations,
      iterations,
      dropped,
      requests: value(data, `http_reqs{scenario:${name},kind:api}`, 'count') || 0,
      unexpected: value(data, `unexpected_responses{scenario:${name}}`, 'passes') || 0,
      p95Ms: round(value(data, latency, 'p(95)'), 1),
      p99Ms: round(value(data, latency, 'p(99)'), 1),
      maxMs: round(value(data, latency, 'max'), 1),
    };
    if (params.profile === 'breakpoint' && !planned.burst) {
      const ramp = plan.totalSeconds;
      scenarios[name].targetPerSecondAtEnd = round(
        rateAtEnd(planned.peakPerSecond, ramp, iterations + dropped),
        1,
      );
    }
  }
  const byId = {};
  let passed = 0;
  let failures = 0;
  for (const [id, description] of Object.entries(INVARIANTS)) {
    const ok = value(data, `inventory_invariants{invariant:${id}}`, 'passes') || 0;
    const bad = value(data, `inventory_invariants{invariant:${id}}`, 'fails') || 0;
    if (ok + bad > 0) byId[id] = { description, passed: ok, failed: bad };
    passed += ok;
    failures += bad;
  }
  const serviceP99Ms = {};
  for (const op of SERVICE_OPERATIONS) {
    const seconds = value(data, `service_update_p99_seconds{operation:${op}}`, 'value');
    if (seconds !== null) serviceP99Ms[op] = round(seconds * 1000, 1);
  }
  const outcomeCounts = {};
  for (const [id, description] of Object.entries(OUTCOMES)) {
    const count = value(data, `outcomes{outcome:${id}}`, 'count');
    if (count) outcomeCounts[id] = { description, count };
  }
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
      total: value(data, 'http_reqs', 'count') || 0,
      api: value(data, 'http_reqs{kind:api}', 'count') || 0,
      apiPerSecond: round(value(data, 'http_reqs{kind:api}', 'rate') || 0, 1),
      p95Ms: round(value(data, 'http_req_duration{kind:api}', 'p(95)'), 1),
      p99Ms: round(value(data, 'http_req_duration{kind:api}', 'p(99)'), 1),
    },
    iterations: {
      total: value(data, 'iterations', 'count') || 0,
      dropped: value(data, 'dropped_iterations', 'count') || 0,
    },
    mass: {
      products: mass.products.length,
      skus: mass.skus,
      byState: mass.byState,
      skusByGroup: Object.fromEntries(SKU_GROUPS.map((g) => [g, mass.skusByRole[g] || 0])),
      moderation: params.moderationProducts,
      setupSeconds: round(value(data, 'mass_setup_seconds', 'value'), 1),
    },
    byScenario: scenarios,
    outcomes: outcomeCounts,
    kyb: { rounds, stores: rounds > 0 ? params.kybStores : 0, intervalS: params.kybIntervalS },
    thresholds,
    invariants: { checked: passed + failures, passed, failed: failures, byId },
    hot: {
      mode: params.hotMode,
      stock: params.hotStock,
      accepted: value(data, 'hot_sku_accepted', 'count') || 0,
      refused: value(data, 'hot_sku_refused', 'count') || 0,
      finalOnHand: value(data, 'hot_final_on_hand', 'value'),
      finalReserved: value(data, 'hot_final_reserved', 'value'),
      finalSold: value(data, 'hot_final_sold', 'value'),
    },
    lastUnit: params.scenarios.includes('last_unit')
      ? {
          stock: params.lastUnitStock,
          attempts: params.lastUnitAttempts,
          accepted: value(data, 'last_unit_accepted', 'count') || 0,
          refused: value(data, 'last_unit_refused', 'count') || 0,
        }
      : null,
    spreadRefused: value(data, 'spread_refused', 'count') || 0,
    outbox: {
      lagMaxS: round(value(data, 'outbox_publish_lag_max_seconds', 'max'), 3),
      lagLimitS: params.outboxLagMaxS,
      pendingMax: value(data, 'outbox_pending', 'max'),
      drained: value(data, 'outbox_drained', 'rate') === 1,
      drainSeconds: round(value(data, 'outbox_drain_seconds', 'value'), 1),
    },
    service: {
      p99Ms: serviceP99Ms,
      refusedInsufficientStock: value(data, 'service_refused_insufficient_stock', 'value'),
    },
    params: { PROFILE: params.profile, TESTID: params.testid, ...params.effective },
  };
}

const ms = (n) => (n === null ? '—' : n.toFixed(1));
const orDash = (n) => (n === null || n === undefined ? '—' : String(n));

export function renderMarkdown(report) {
  const lines = [
    `# Carga do catálogo — ${report.testid}`,
    '',
    `- Perfil: \`${report.profile}\`, semente ${report.seed}, cenários ${report.scenarios.map((s) => `\`${s}\``).join(', ')}`,
    `- Resultado: ${report.result === 'aprovado' ? 'todos os limites passaram' : `${report.failedThresholds} limite(s) reprovado(s)`}`,
    `- Duração planejada da carga: ${report.plannedSeconds} s; tempo total com preparo e verificação: ${report.elapsedSeconds} s`,
    `- Requisições de negócio: ${report.requests.api} (${report.requests.apiPerSecond}/s); total com login, preparo e métricas: ${report.requests.total}`,
    `- Iterações: ${report.iterations.total} (descartadas: ${report.iterations.dropped})`,
    `- Remote write ao Prometheus: ${report.remoteWrite === 'on' ? `ligado, tag \`testid=${report.testid}\`` : 'desligado'}`,
    '',
    '## Massa',
    '',
    `- ${report.mass.products} produtos e ${report.mass.skus} SKUs, preparados em ${orDash(report.mass.setupSeconds)} s; ${report.mass.moderation} reservados à moderação`,
    `- Por estado: ${STATES.map((s) => `${s} ${report.mass.byState[s]}`).join(', ')}`,
    `- SKUs por grupo: ${SKU_GROUPS.map((g) => `${g} ${report.mass.skusByGroup[g]}`).join(', ')}`,
    `- Vereditos do KYB: ${report.kyb.rounds > 0 ? `${report.kyb.rounds} rodadas a cada ${report.kyb.intervalS} s para ${report.kyb.stores} lojas sintéticas` : 'fora da execução'}`,
    '',
    '## Limites',
    '',
    '| Métrica | Condição | Resultado |',
    '| --- | --- | --- |',
  ];
  for (const row of report.thresholds)
    lines.push(`| \`${row.metric}\` | \`${row.condition}\` | ${row.ok ? 'ok' : 'falhou'} |`);

  const breakpoint = report.profile === 'breakpoint';
  lines.push(
    '',
    '## Cenários',
    '',
    `| Cenário | Pico planejado (/s) | Iterações | Descartadas | Requisições | Inesperadas | p95 (ms) | p99 (ms) | máx (ms) |${breakpoint ? ' Taxa-alvo no fim (/s) |' : ''}`,
    `| --- | --- | --- | --- | --- | --- | --- | --- | --- |${breakpoint ? ' --- |' : ''}`,
  );
  for (const [name, s] of Object.entries(report.byScenario)) {
    lines.push(
      `| \`${name}\` | ${s.plannedPeakPerSecond} | ${s.iterations} | ${s.dropped} | ${s.requests} | ${s.unexpected} | ${ms(s.p95Ms)} | ${ms(s.p99Ms)} | ${ms(s.maxMs)} |${breakpoint ? ` ${s.targetPerSecondAtEnd} |` : ''}`,
    );
  }
  lines.push(
    `| todos | | | | ${report.requests.api} | | ${ms(report.requests.p95Ms)} | ${ms(report.requests.p99Ms)} | |${breakpoint ? ' |' : ''}`,
  );

  lines.push('', '## Desfechos de negócio', '');
  const outcomeRows = Object.values(report.outcomes);
  if (outcomeRows.length === 0) lines.push('- nenhum');
  for (const o of outcomeRows) lines.push(`- ${o.description}: ${o.count}`);

  const inv = report.invariants;
  lines.push(
    '',
    '## Invariantes',
    '',
    `${inv.passed} de ${inv.checked} conferências passaram.`,
    '',
  );
  lines.push('| Invariante | Passaram | Falharam |', '| --- | --- | --- |');
  for (const entry of Object.values(inv.byId))
    lines.push(`| ${entry.description} | ${entry.passed} | ${entry.failed} |`);

  const hot = report.hot;
  const last = report.lastUnit;
  lines.push(
    '',
    '## Disputa e último item',
    '',
    `- SKU disputado, modo \`${hot.mode}\`${hot.mode === 'contend' ? ' (reserva e libera)' : ' (reserva até esgotar)'}: estoque ${hot.stock}; reservas aceitas ${hot.accepted}, recusas 409 \`insufficient-stock\` ${hot.refused}`,
    `- Saldo final do disputado: onHand=${orDash(hot.finalOnHand)}, reserved=${orDash(hot.finalReserved)}, sold=${orDash(hot.finalSold)}`,
    last
      ? `- Último item: estoque ${last.stock}, ${last.attempts} tentativas na rajada; aceitas ${last.accepted}, recusas 409 \`insufficient-stock\` ${last.refused}`
      : '- Último item: fora da execução',
    `- Recusas por estoque na reserva espalhada: ${report.spreadRefused}; recusas \`insufficient-stock\` contadas pelo serviço no período: ${orDash(report.service.refusedInsufficientStock)}`,
  );

  const outbox = report.outbox;
  lines.push(
    '',
    '## Outbox',
    '',
    `- Maior atraso de publicação visto durante a carga: ${orDash(outbox.lagMaxS)} s (limite ${outbox.lagLimitS} s)`,
    `- Maior fila pendente: ${orDash(outbox.pendingMax)}`,
    `- Fila zerada depois da carga: ${outbox.drained ? `sim, em ${orDash(outbox.drainSeconds)} s` : 'não, dentro de OUTBOX_DRAIN_S'}`,
  );

  lines.push(
    '',
    '## Latência no serviço',
    '',
    'p99 de `catalog.inventory.update.latency` no período, pela diferença dos buckets:',
    '',
  );
  const ops = Object.entries(report.service.p99Ms);
  if (ops.length === 0) lines.push('- sem amostras');
  for (const [op, p99] of ops) lines.push(`- \`${op}\`: ${ms(p99)} ms`);

  lines.push('', '## Parâmetros', '', '| Variável | Valor |', '| --- | --- |');
  for (const [name, val] of Object.entries(report.params))
    lines.push(`| \`${name}\` | ${val === '' ? '(padrão do perfil)' : `\`${val}\``} |`);
  return `${lines.join('\n')}\n`;
}
