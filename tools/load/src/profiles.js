// Perfis de carga sobre os mesmos cenários: forma da taxa no tempo, alocação de VUs e limites. Roda no k6 e no
// Node, que o run.sh usa no --check.
import { kybRounds } from './kyb.js';
import { describeMass } from './mass-plan.js';
import { RATED_SCENARIOS } from './params.js';
import { isProbe, summaryProbes } from './lib/summary.js';

export const EXEC = {
  hot_sku: 'hotSku',
  spread_reserve_release: 'spreadReserveRelease',
  stock_adjust: 'stockAdjust',
  catalog_browse: 'catalogBrowse',
  storefront: 'storefront',
  product_lifecycle: 'productLifecycle',
  moderation: 'moderation',
  offer_pricing: 'offerPricing',
  reservation_lifecycle: 'reservationLifecycle',
  reservation_expiry: 'reservationExpiry',
  category_admin: 'categoryAdmin',
  product_import: 'productImport',
  last_unit: 'lastUnit',
};
// WHY: sem a tag url, cada id de reserva no caminho da liberação viraria uma série nova no Prometheus.
export const SYSTEM_TAGS = [
  'status',
  'method',
  'name',
  'group',
  'check',
  'error_code',
  'scenario',
  'expected_response',
];

const PREALLOCATION_S = 0.5;
// WHY: duração de iteração tolerada antes de faltar VU para manter a taxa, pelo número de requisições de cada cenário;
// a importação espera o worker serial. Na taxa base e no modo exhaust, os três primeiros dão os VUs de antes.
const ITERATION_CEILING_S = {
  hot_sku: 2,
  spread_reserve_release: 2.5,
  stock_adjust: 2,
  catalog_browse: 2.5,
  storefront: 2,
  product_lifecycle: 8,
  moderation: 2.5,
  offer_pricing: 2.5,
  reservation_lifecycle: 3,
  reservation_expiry: 2,
  category_admin: 2.5,
  product_import: 35,
};
const MAX_PREALLOCATED_VUS = 1000;
// WHY: no smoke, os cenários de taxa baixa (importação, categorias) rodariam menos de uma vez em 30 s.
const SMOKE_MIN_PER_MINUTE = 4;
const SETUP_MARGIN_S = 120;
const SETUP_TIMEOUT_BASE_S = 120;
// WHY: 10 tentativas simultâneas já disputam a mesma linha; com mais, a espera pela trava ocupa o pool de conexões
// do catálogo e atrasa os outros cenários no começo da execução.
const LAST_UNIT_MAX_VUS = 10;
const DEGRADED_P99_MS = 2000;
const DURATION_UNITS_S = { s: 1, m: 60, h: 3600 };

const level = (value, overrides = {}) =>
  Object.fromEntries(RATED_SCENARIOS.map((s) => [s, overrides[s] ?? value]));
const stage = (duration, value) => ({
  duration,
  level: typeof value === 'number' ? level(value) : value,
});
// WHY: a carga alta anterior subia só o SKU disputado, a 1.000/s; o estresse mantém isso e dobra os demais.
const STRESS_LEVEL = level(2, { hot_sku: 10 });

export function shapeFor(profile, duration) {
  switch (profile) {
    case 'smoke':
      return { start: level(0.2), stages: [stage(duration ?? '30s', 0.2)] };
    case 'average':
      return {
        start: level(0),
        stages: [stage('30s', 1), stage(duration ?? '5m', 1), stage('30s', 0)],
      };
    case 'stress':
      return {
        start: level(0),
        stages: [stage('1m', STRESS_LEVEL), stage(duration ?? '2m', STRESS_LEVEL), stage('30s', 0)],
      };
    case 'spike':
      return {
        start: level(1),
        stages: [
          stage('30s', 1),
          stage('10s', 8),
          stage(duration ?? '1m', 8),
          stage('10s', 1),
          stage('1m', 1),
        ],
      };
    case 'soak':
      return {
        start: level(0),
        stages: [stage('1m', 1), stage(duration ?? '30m', 1), stage('30s', 0)],
      };
    case 'breakpoint':
      return { start: level(0), stages: [stage(duration ?? '10m', 20)] };
    default:
      throw new Error(`profiles: perfil desconhecido ${profile}`);
  }
}

export function durationSeconds(text) {
  const [, amount, unit] = /^(\d+)([smh])$/.exec(text);
  return Number(amount) * DURATION_UNITS_S[unit];
}

export function formatSeconds(total) {
  const parts = [];
  let rest = Math.round(total);
  for (const [unit, size] of [
    ['h', 3600],
    ['m', 60],
    ['s', 1],
  ]) {
    if (rest >= size) {
      parts.push(`${Math.floor(rest / size)}${unit}`);
      rest %= size;
    }
  }
  return parts.join('') || '0s';
}

// WHY: na disputa contínua, cada iteração do SKU disputado reserva e libera, duas requisições como a espalhada.
const iterationCeiling = (params, name) =>
  name === 'hot_sku' && params.hotMode === 'contend' ? 2.5 : ITERATION_CEILING_S[name];

function vuAllocation(params, name, peakPerSecond) {
  const maxVUs = Math.min(
    params.maxVus,
    Math.max(1, Math.ceil(peakPerSecond * iterationCeiling(params, name))),
  );
  return {
    preAllocatedVUs: Math.min(maxVUs, Math.max(1, Math.ceil(peakPerSecond * PREALLOCATION_S))),
    maxVUs,
  };
}

export function planFor(params) {
  const shape = shapeFor(params.profile, params.duration);
  const totalSeconds = shape.stages.reduce((total, s) => total + durationSeconds(s.duration), 0);
  const scenarios = {};
  for (const name of params.scenarios.filter((s) => EXEC[s])) {
    if (name === 'last_unit') {
      const vus = Math.min(params.lastUnitAttempts, LAST_UNIT_MAX_VUS);
      scenarios[name] = {
        burst: true,
        peakPerSecond: null,
        iterations: params.lastUnitAttempts,
        preAllocatedVUs: vus,
        maxVUs: vus,
      };
      continue;
    }
    const perMinute = (lvl) => {
      const rate = Math.round(60 * params.rates[name] * lvl[name]);
      return params.profile === 'smoke' ? Math.max(rate, SMOKE_MIN_PER_MINUTE) : rate;
    };
    const startRate = perMinute(shape.start);
    const stages = shape.stages.map((s) => ({ duration: s.duration, target: perMinute(s.level) }));
    let previous = startRate;
    let iterations = 0;
    for (const s of stages) {
      iterations += ((previous + s.target) / 2 / 60) * durationSeconds(s.duration);
      previous = s.target;
    }
    const peakPerSecond = Math.max(startRate, ...stages.map((s) => s.target)) / 60;
    scenarios[name] = {
      startRate,
      stages,
      peakPerSecond,
      iterations: Math.round(iterations),
      ...vuAllocation(params, name, peakPerSecond),
    };
  }
  return { shape, totalSeconds, scenarios };
}

const abortOnFail = (threshold) => ({ threshold, abortOnFail: true, delayAbortEval: '30s' });
const k6Scenarios = (params) => params.scenarios.filter((s) => EXEC[s]);

function correctness(params) {
  const thresholds = { inventory_invariants: ['rate==1'] };
  if (params.scenarios.includes('hot_sku') && params.hotMode === 'exhaust')
    thresholds.hot_sku_accepted = [`count<=${params.hotStock}`];
  if (params.scenarios.includes('last_unit')) {
    thresholds.last_unit_accepted = [`count==${params.lastUnitStock}`];
    thresholds.last_unit_refused = [`count==${params.lastUnitAttempts - params.lastUnitStock}`];
  }
  return thresholds;
}

const outbox = (params) => ({
  outbox_publish_lag_max_seconds: [`max<${params.outboxLagMaxS}`],
  outbox_drained: ['rate==1'],
});

const responses = (params, maxRate) => {
  const condition = maxRate === 0 ? 'rate==0' : `rate<${maxRate}`;
  const thresholds = { server_errors: [condition], unexpected_responses: [condition] };
  for (const s of k6Scenarios(params))
    thresholds[`unexpected_responses{scenario:${s}}`] = [condition];
  return thresholds;
};

function latency(params) {
  return Object.fromEntries(
    k6Scenarios(params).map((s) => [
      `http_req_duration{scenario:${s},kind:api}`,
      [`p(99)<${params.p99Ms[s]}`],
    ]),
  );
}

const steady = (params) => ({
  ...responses(params, 0),
  ...correctness(params),
  ...outbox(params),
  ...latency(params),
  dropped_iterations: ['count==0'],
});

const THRESHOLDS = {
  smoke: (params) => ({
    ...responses(params, 0),
    ...correctness(params),
    ...outbox(params),
    dropped_iterations: ['count==0'],
  }),
  average: steady,
  soak: steady,
  stress: (params) => ({
    ...responses(params, 0.01),
    ...correctness(params),
    ...outbox(params),
    'http_req_duration{kind:api}': [`p(99)<${DEGRADED_P99_MS}`],
  }),
  spike: (params) => ({ ...responses(params, 0.05), ...correctness(params), ...outbox(params) }),
  breakpoint: (params) => ({
    server_errors: [abortOnFail('rate<0.01')],
    unexpected_responses: [abortOnFail('rate<0.01')],
    'http_req_duration{kind:api}': [abortOnFail(`p(99)<${DEGRADED_P99_MS}`)],
    ...correctness(params),
    outbox_drained: ['rate==1'],
  }),
};

function merge(...maps) {
  const merged = {};
  for (const map of maps) {
    for (const [metric, conditions] of Object.entries(map))
      merged[metric] = (merged[metric] || []).concat(conditions);
  }
  return merged;
}

function assertBudget(params, plan) {
  const preallocated = Object.values(plan.scenarios).reduce(
    (total, s) => total + s.preAllocatedVUs,
    0,
  );
  if (preallocated > MAX_PREALLOCATED_VUS) {
    throw new Error(
      `profiles: o perfil ${params.profile} pré-aloca ${preallocated} VUs, acima do teto de ${MAX_PREALLOCATED_VUS}; reduza as taxas ou MAX_VUS`,
    );
  }
}

// WHY: cada VU segura no máximo uma reserva do SKU disputado por vez (reserva e libera na mesma iteração), então
// taxa × latência nunca passa de maxVUs; o dobro deixa folga para liberações que falhem.
function assertHotStock(params, plan) {
  const hot = plan.scenarios.hot_sku;
  if (!hot || params.hotMode !== 'contend') return;
  if (params.hotStock < 2 * hot.maxVUs) {
    throw new Error(
      `profiles: HOT_STOCK=${params.hotStock} precisa ser ao menos ${2 * hot.maxVUs} (2 × ${hot.maxVUs} VUs do hot_sku no perfil ${params.profile}); na disputa contínua o estoque nunca pode faltar`,
    );
  }
}

function assertTtl(params, plan) {
  const needed = plan.totalSeconds + params.outboxDrainS + SETUP_MARGIN_S;
  if (params.reservationTtlS <= needed) {
    throw new Error(
      `profiles: RESERVATION_TTL_S=${params.reservationTtlS} precisa passar de ${needed} s (perfil ${params.profile} de ${plan.totalSeconds} s, mais OUTBOX_DRAIN_S e ${SETUP_MARGIN_S} s de preparo); reservas que expiram no meio invalidam as invariantes`,
    );
  }
}

export function buildOptions(params) {
  const plan = planFor(params);
  assertBudget(params, plan);
  assertTtl(params, plan);
  assertHotStock(params, plan);
  const scenarios = {
    // WHY: amostra /q/metrics durante a carga porque o _max do Micrometer decai em cerca de 2 min.
    outbox_probe: {
      executor: 'constant-arrival-rate',
      exec: 'outboxProbe',
      rate: 1,
      timeUnit: `${params.probeIntervalS}s`,
      duration: `${plan.totalSeconds}s`,
      preAllocatedVUs: 1,
      maxVUs: 2,
    },
  };
  for (const [name, s] of Object.entries(plan.scenarios)) {
    if (s.burst) {
      // WHY: a rajada do último item corre no começo, com até LAST_UNIT_MAX_VUS tentativas simultâneas pela mesma unidade.
      scenarios[name] = {
        executor: 'shared-iterations',
        exec: EXEC[name],
        vus: s.maxVUs,
        iterations: s.iterations,
        maxDuration: '30s',
      };
      continue;
    }
    scenarios[name] = {
      executor: 'ramping-arrival-rate',
      exec: EXEC[name],
      timeUnit: '1m',
      startRate: s.startRate,
      stages: s.stages,
      preAllocatedVUs: s.preAllocatedVUs,
      maxVUs: s.maxVUs,
    };
  }
  return {
    scenarios,
    thresholds: merge(summaryProbes(params), THRESHOLDS[params.profile](params)),
    systemTags: SYSTEM_TAGS,
    // WHY: o preparo da massa manda os passos independentes em lotes paralelos (http.batch) de até 20 requisições;
    // com a máquina livre, 120 produtos levaram 1,6 s, e o prazo cresce com MASS_PRODUCTS com folga larga.
    batch: 20,
    batchPerHost: 20,
    setupTimeout: `${SETUP_TIMEOUT_BASE_S + Math.ceil(params.massProducts / 10)}s`,
    teardownTimeout: `${params.outboxDrainS + params.expiryWaitS + params.kybSettleS + 120}s`,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  };
}

export function describePlan(params) {
  const plan = planFor(params);
  const options = buildOptions(params);
  const lines = [
    `perfil ${params.profile}, semente ${params.seed}, ${formatSeconds(plan.totalSeconds)} de carga, reservas com prazo de ${params.reservationTtlS} s`,
    ...describeMass(params),
  ];
  for (const [name, s] of Object.entries(plan.scenarios)) {
    if (s.burst) {
      lines.push(
        `  ${name}: rajada de ${s.iterations} tentativas com ${s.maxVUs} VUs no começo, estoque ${params.lastUnitStock}, sem liberar`,
      );
      continue;
    }
    if (name === 'hot_sku') {
      lines.push(
        params.hotMode === 'contend'
          ? `  hot_sku: disputa contínua (reserva e libera), estoque ${params.hotStock}, no máximo ${s.maxVUs} unidades reservadas ao mesmo tempo (uma por VU)`
          : `  hot_sku: modo exhaust (reserva sem liberar até esgotar), estoque ${params.hotStock}`,
      );
    }
    lines.push(
      `  ${name}: pico de ${s.peakPerSecond.toFixed(2)} iterações/s, cerca de ${s.iterations} iterações, ${s.preAllocatedVUs} a ${s.maxVUs} VUs`,
    );
  }
  const rounds = kybRounds(params, plan);
  if (rounds > 0) {
    lines.push(
      `  kyb_verdicts: ${rounds} rodadas a cada ${params.kybIntervalS} s, ${params.kybStores} lojas sintéticas, ${rounds * params.kybStores} vereditos`,
    );
  }
  const preallocated = Object.values(plan.scenarios).reduce(
    (total, s) => total + s.preAllocatedVUs,
    0,
  );
  lines.push(`  VUs pré-alocados: ${preallocated} (teto ${MAX_PREALLOCATED_VUS})`);
  const limits = [];
  for (const [metric, conditions] of Object.entries(options.thresholds)) {
    for (const c of conditions) {
      const condition = typeof c === 'string' ? c : `${c.threshold} (aborta)`;
      if (!isProbe(metric, typeof c === 'string' ? c : c.threshold))
        limits.push(`${metric} ${condition}`);
    }
  }
  lines.push(`  limites: ${limits.join('; ')}`);
  return lines;
}
