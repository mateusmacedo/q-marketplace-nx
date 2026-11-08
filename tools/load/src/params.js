// Parâmetros da suíte, com padrões e validação. Roda no k6 e no Node, que o run.sh usa no --check.
import { assertTarget } from './lib/guard.js';
import { minimumMassProducts } from './mass-plan.js';

export const PROFILES = ['smoke', 'average', 'stress', 'spike', 'soak', 'breakpoint'];
// WHY: kyb_verdicts não tem executor no k6; o run.sh envia os vereditos pelo Kafka e o teardown confere o efeito.
export const SCENARIOS = [
  'hot_sku', 'spread_reserve_release', 'stock_adjust', 'last_unit', 'catalog_browse', 'storefront', 'product_lifecycle',
  'moderation', 'offer_pricing', 'reservation_lifecycle', 'reservation_expiry', 'category_admin', 'product_import',
  'kyb_verdicts',
];
export const HOT_MODES = ['contend', 'exhaust'];
// WHY: no exhaust (o modo antigo), o estoque pequeno é o ponto: acaba em segundos e o resto vira 409, a 100/s. Na
// disputa contínua, cada iteração escreve duas vezes na mesma linha; a 100/s ela satura e esgota o pool do catálogo.
const HOT_MODE_DEFAULTS = { HOT_STOCK: { contend: '10000', exhaust: '200' }, HOT_RATE: { contend: '20', exhaust: '100' } };
const MAX_LAST_UNIT_STOCK = 500;
// WHY: catalog.reservations.max-ttl do serviço vale 2 h.
export const MAX_RESERVATION_TTL_S = 7200;
const MAX_GROUP_SKUS = 400;

// Taxa base (nível 1×, em iterações/s) e limite do p(99) de cada cenário do k6, com a variável de cada um.
export const SCENARIO_PARAMS = {
  hot_sku: { rate: 'HOT_RATE', p99: 'P99_HOT_MS' },
  spread_reserve_release: { rate: 'SPREAD_RATE', p99: 'P99_SPREAD_MS' },
  stock_adjust: { rate: 'ADJUST_RATE', p99: 'P99_ADJUST_MS' },
  catalog_browse: { rate: 'BROWSE_RATE', p99: 'P99_BROWSE_MS' },
  storefront: { rate: 'STOREFRONT_RATE', p99: 'P99_STOREFRONT_MS' },
  product_lifecycle: { rate: 'PRODUCT_RATE', p99: 'P99_PRODUCT_MS' },
  moderation: { rate: 'MODERATION_RATE', p99: 'P99_MODERATION_MS' },
  offer_pricing: { rate: 'PRICING_RATE', p99: 'P99_PRICING_MS' },
  reservation_lifecycle: { rate: 'RESERVATION_RATE', p99: 'P99_RESERVATION_MS' },
  reservation_expiry: { rate: 'EXPIRY_RATE', p99: 'P99_EXPIRY_MS' },
  category_admin: { rate: 'CATEGORY_RATE', p99: 'P99_CATEGORY_MS' },
  product_import: { rate: 'IMPORT_RATE', p99: 'P99_IMPORT_MS' },
};
// WHY: last_unit é uma rajada única no começo, fora do nível dos perfis; os demais seguem a taxa base.
export const RATED_SCENARIOS = Object.keys(SCENARIO_PARAMS);
export const K6_SCENARIOS = [...RATED_SCENARIOS, 'last_unit'];

export const DEFAULTS = Object.freeze({
  BASE_URL: 'http://localhost:8083',
  API_MANAGEMENT_URL: 'http://localhost:9083',
  WORKER_MANAGEMENT_URL: 'http://localhost:9084',
  KEYCLOAK_URL: 'http://localhost:8180',
  KEYCLOAK_REALM: 'marketplace',
  KEYCLOAK_CLIENT_ID: 'marketplace-cli',
  TENANT_ID: '11111111-1111-4111-8111-111111111111',
  STORE_ID: '22222222-2222-4222-8222-222222222222',
  ALLOW_REMOTE_TARGET: 'false',
  SCENARIOS: SCENARIOS.join(','),
  DURATION: '',
  SEED: '1',
  MASS_PRODUCTS: '120',
  MODERATION_PRODUCTS: '10',
  HOT_MODE: 'contend',
  HOT_STOCK: '',
  HOT_RATE: '',
  LAST_UNIT_STOCK: '20',
  SPREAD_SKUS: '20',
  SPREAD_STOCK: '100000',
  SPREAD_RATE: '20',
  ADJUST_SKUS: '20',
  ADJUST_RATE: '10',
  RESERVATION_SKUS: '20',
  EXPIRY_SKUS: '10',
  PRICING_SKUS: '20',
  IMPORT_SKUS: '10',
  BROWSE_RATE: '5',
  STOREFRONT_RATE: '10',
  PRODUCT_RATE: '0.5',
  MODERATION_RATE: '1',
  PRICING_RATE: '2',
  RESERVATION_RATE: '5',
  EXPIRY_RATE: '2',
  CATEGORY_RATE: '0.2',
  IMPORT_RATE: '0.1',
  MAX_VUS: '400',
  RESERVATION_TTL_S: '3600',
  EXPIRY_TTL_S: '5',
  EXPIRY_WAIT_S: '60',
  IMPORT_WAIT_S: '30',
  KYB_STORES: '6',
  KYB_INTERVAL_S: '5',
  KYB_SETTLE_S: '30',
  P99_HOT_MS: '500',
  P99_SPREAD_MS: '500',
  P99_ADJUST_MS: '500',
  P99_BROWSE_MS: '500',
  P99_STOREFRONT_MS: '500',
  P99_PRODUCT_MS: '500',
  P99_MODERATION_MS: '500',
  P99_PRICING_MS: '500',
  P99_RESERVATION_MS: '500',
  P99_EXPIRY_MS: '500',
  P99_CATEGORY_MS: '500',
  P99_IMPORT_MS: '500',
  P99_LAST_UNIT_MS: '500',
  OUTBOX_LAG_MAX_S: '10',
  OUTBOX_DRAIN_S: '30',
  PROBE_INTERVAL_S: '5',
  REMOTE_WRITE: 'auto',
  K6_PROMETHEUS_RW_SERVER_URL: 'http://localhost:9090/api/v1/write',
});

const TESTID_FORMAT = /^[a-z0-9]+(-[a-z0-9]+)*$/;
// WHY: o TESTID entra nos códigos de categoria (até 60 caracteres, com "carga-" e "-f1-n2") e nos seller SKUs (até
// 64, com "CARGA-" e sufixos como "-P12345-3").
const TESTID_MAX = 40;
const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_FORMAT = /^[A-Za-z0-9._-]{1,64}$/;

function fail(name, rule, value) {
  throw new Error(`params: ${name} ${rule} (recebido "${value}")`);
}

export function parseParams(source) {
  const env = { ...source };
  const effective = {};
  const read = (name) => {
    const raw = env[name];
    const value = raw === undefined || raw === '' ? DEFAULTS[name] : String(raw);
    effective[name] = value;
    return value;
  };
  const integer = (name, min, max) => {
    const raw = read(name);
    const value = Number(raw);
    if (!/^\d+$/.test(raw) || value < min || value > max) fail(name, `deve ser inteiro de ${min} a ${max}`, raw);
    return value;
  };
  const decimal = (name, min, max) => {
    const raw = read(name);
    const value = Number(raw);
    if (!/^\d+(\.\d+)?$/.test(raw) || value <= min || value > max) fail(name, `deve ser número maior que ${min} e até ${max}`, raw);
    return value;
  };
  const choice = (name, options) => {
    const raw = read(name);
    if (!options.includes(raw)) fail(name, `deve ser ${options.join(', ')}`, raw);
    return raw;
  };
  const formatted = (name, format, rule) => {
    const raw = read(name);
    if (!format.test(raw)) fail(name, rule, raw);
    return raw;
  };

  const profile = env.PROFILE ?? '';
  if (!PROFILES.includes(profile)) fail('PROFILE', `deve ser um de ${PROFILES.join(', ')}`, profile);
  const testid = env.TESTID === undefined || env.TESTID === '' ? null : String(env.TESTID);
  if (testid !== null && (!TESTID_FORMAT.test(testid) || testid.length > TESTID_MAX)) {
    fail('TESTID', `deve ter até ${TESTID_MAX} caracteres em minúsculas, dígitos e hífens`, testid);
  }

  const allowRemote = choice('ALLOW_REMOTE_TARGET', ['true', 'false']) === 'true';
  const target = (name) => {
    try {
      return assertTarget(read(name), name, allowRemote);
    } catch (error) {
      throw new Error(`params: ${error.message}`);
    }
  };

  const scenariosRaw = read('SCENARIOS');
  const scenarios = scenariosRaw.split(',').map((s) => s.trim()).filter(Boolean);
  if (scenarios.length === 0 || scenarios.some((s) => !SCENARIOS.includes(s)) || new Set(scenarios).size !== scenarios.length) {
    fail('SCENARIOS', `deve listar sem repetir um ou mais de ${SCENARIOS.join(', ')}`, scenariosRaw);
  }

  const durationRaw = read('DURATION');
  if (durationRaw !== '' && !/^[1-9]\d*[smh]$/.test(durationRaw)) fail('DURATION', 'deve ser duração como 30s, 10m ou 1h', durationRaw);

  const hotMode = choice('HOT_MODE', HOT_MODES);
  for (const [name, byMode] of Object.entries(HOT_MODE_DEFAULTS)) {
    if (env[name] === undefined || env[name] === '') env[name] = byMode[hotMode];
  }
  const rates = {};
  const p99Ms = {};
  for (const [name, keys] of Object.entries(SCENARIO_PARAMS)) {
    rates[name] = decimal(keys.rate, 0, 5000);
    p99Ms[name] = integer(keys.p99, 1, 60000);
  }
  p99Ms.last_unit = integer('P99_LAST_UNIT_MS', 1, 60000);
  const hotStock = integer('HOT_STOCK', 1, 1000000);
  const lastUnitStock = integer('LAST_UNIT_STOCK', 1, MAX_LAST_UNIT_STOCK);

  const params = {
    profile,
    testid,
    allowRemote,
    baseUrl: target('BASE_URL'),
    managementUrls: [target('API_MANAGEMENT_URL'), target('WORKER_MANAGEMENT_URL')],
    keycloakUrl: target('KEYCLOAK_URL'),
    keycloakRealm: formatted('KEYCLOAK_REALM', NAME_FORMAT, 'deve casar ^[A-Za-z0-9._-]{1,64}$'),
    keycloakClientId: formatted('KEYCLOAK_CLIENT_ID', NAME_FORMAT, 'deve casar ^[A-Za-z0-9._-]{1,64}$'),
    tenantId: formatted('TENANT_ID', UUID_FORMAT, 'deve ser um UUID'),
    storeId: formatted('STORE_ID', UUID_FORMAT, 'deve ser um UUID'),
    scenarios,
    duration: durationRaw === '' ? null : durationRaw,
    seed: integer('SEED', 0, 2147483647),
    massProducts: integer('MASS_PRODUCTS', 1, 2000),
    moderationProducts: integer('MODERATION_PRODUCTS', 0, 200),
    hotMode,
    hotStock,
    lastUnitStock,
    lastUnitAttempts: 2 * lastUnitStock,
    spreadSkus: integer('SPREAD_SKUS', 0, MAX_GROUP_SKUS),
    spreadStock: integer('SPREAD_STOCK', 1, 1000000000),
    adjustSkus: integer('ADJUST_SKUS', 0, MAX_GROUP_SKUS),
    reservationSkus: integer('RESERVATION_SKUS', 0, MAX_GROUP_SKUS),
    expirySkus: integer('EXPIRY_SKUS', 0, MAX_GROUP_SKUS),
    pricingSkus: integer('PRICING_SKUS', 0, MAX_GROUP_SKUS),
    importSkus: integer('IMPORT_SKUS', 0, MAX_GROUP_SKUS),
    rates,
    maxVus: integer('MAX_VUS', 1, 5000),
    reservationTtlS: integer('RESERVATION_TTL_S', 60, MAX_RESERVATION_TTL_S),
    expiryTtlS: integer('EXPIRY_TTL_S', 1, 60),
    expiryWaitS: integer('EXPIRY_WAIT_S', 1, 600),
    importWaitS: integer('IMPORT_WAIT_S', 5, 600),
    kybStores: integer('KYB_STORES', 0, 50),
    kybIntervalS: integer('KYB_INTERVAL_S', 2, 300),
    kybSettleS: integer('KYB_SETTLE_S', 1, 600),
    p99Ms,
    outboxLagMaxS: decimal('OUTBOX_LAG_MAX_S', 0, 3600),
    outboxDrainS: integer('OUTBOX_DRAIN_S', 1, 600),
    probeIntervalS: integer('PROBE_INTERVAL_S', 1, 60),
    remoteWrite: choice('REMOTE_WRITE', ['auto', 'on', 'off']),
    remoteWriteUrl: target('K6_PROMETHEUS_RW_SERVER_URL'),
  };

  const needs = (scenario, value, minimum, name) => {
    if (scenarios.includes(scenario) && value < minimum) fail(name, `deve ser ao menos ${minimum} com ${scenario}`, value);
  };
  needs('spread_reserve_release', params.spreadSkus, 1, 'SPREAD_SKUS');
  needs('stock_adjust', params.adjustSkus, 1, 'ADJUST_SKUS');
  needs('reservation_lifecycle', params.reservationSkus, 2, 'RESERVATION_SKUS');
  needs('reservation_expiry', params.expirySkus, 1, 'EXPIRY_SKUS');
  needs('offer_pricing', params.pricingSkus, 1, 'PRICING_SKUS');
  needs('product_import', params.importSkus, 2, 'IMPORT_SKUS');
  needs('moderation', params.moderationProducts, 1, 'MODERATION_PRODUCTS');
  needs('kyb_verdicts', params.kybStores, 1, 'KYB_STORES');
  const minimum = minimumMassProducts(params);
  if (params.massProducts < minimum) {
    fail('MASS_PRODUCTS', `deve ser ao menos ${minimum} (produtos dos grupos de SKU e da moderação, mais um por estado)`, params.massProducts);
  }

  return Object.freeze({ ...params, effective: Object.freeze(effective) });
}
