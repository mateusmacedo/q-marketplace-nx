// Massa de cada execução, criada pela API no setup a partir do plano de mass-plan.js, com códigos derivados do TESTID:
// sellerSku e Idempotency-Key valem para sempre e nunca se repetem entre execuções.
import { fail } from 'k6';
import { params } from './config.js';
import { kybStores } from './kyb.js';
import { setupBatch, setupCall } from './lib/http.js';
import { login, USERS } from './lib/identity.js';
import { massSetupSeconds } from './lib/metrics.js';
import { latencyBuckets, readMetrics, sumOf } from './lib/service-metrics.js';
import {
  CATEGORY_CHILDREN,
  CATEGORY_GRANDCHILDREN,
  LIST_PRICE,
  PRICE,
  PROMOTION_PRICE,
  SKU_GROUPS,
  VARIANTS,
  massPlan,
} from './mass-plan.js';

// WHY: o run.sh espera esta frase no log do k6 para começar os vereditos do KYB só depois do preparo.
export const READY_MARK = 'massa pronta';
export const BRAND = { marca: 'Carga' };
export const COLOR = {
  code: 'cor',
  label: 'Cor',
  type: 'OPTION',
  required: true,
  variant: true,
  options: VARIANTS,
};
const DIMENSIONS = { heightCm: 3, widthCm: 25, lengthCm: 30, weightGrams: 200 };
const DAY_MS = 86400000;
const PROMOTION_START_DAYS = 30;
const PRICING_PROMOTION_START_DAYS = 7;
const APPROVED_STATES = ['ACTIVE', 'OUT_OF_STOCK', 'PAUSED_BY_SELLER', 'BLOCKED', 'ARCHIVED'];

export function stock(token, skuId) {
  return setupCall(
    token,
    'GET',
    `/inventory/skus/${skuId}`,
    null,
    'GET /inventory/skus/{skuId}',
    [200],
  ).json();
}

export function skuRequest(sellerSku, variant, physical) {
  return {
    sellerSku,
    variantAttributes: { cor: variant },
    physical,
    ...(physical ? { dimensions: DIMENSIONS } : {}),
  };
}

function createCategories(run, admin) {
  const root = setupCall(
    admin,
    'POST',
    '/categories',
    {
      code: `carga-${run}`,
      name: `Carga ${run}`,
      attributes: [{ code: 'marca', label: 'Marca', type: 'TEXT', required: true, variant: false }],
    },
    'POST /categories',
  ).json('id');
  const childCodes = Array.from({ length: CATEGORY_CHILDREN }, (_, c) => `carga-${run}-f${c + 1}`);
  const adminCodes = [`carga-${run}-adm-a`, `carga-${run}-adm-b`];
  const level2 = setupBatch([
    ...childCodes.map((code) => ({
      token: admin,
      method: 'POST',
      path: '/categories',
      body: { parentId: root, code, name: code, attributes: [COLOR] },
      what: 'POST /categories',
    })),
    ...adminCodes.map((code) => ({
      token: admin,
      method: 'POST',
      path: '/categories',
      body: { parentId: root, code, name: code },
      what: 'POST /categories',
    })),
  ]).map((res) => res.json('id'));
  const children = level2.slice(0, CATEGORY_CHILDREN);
  const leafCodes = childCodes.flatMap((code) =>
    Array.from({ length: CATEGORY_GRANDCHILDREN }, (_, n) => `${code}-n${n + 1}`),
  );
  const leafIds = setupBatch(
    leafCodes.map((code, i) => ({
      token: admin,
      method: 'POST',
      path: '/categories',
      body: {
        parentId: children[Math.floor(i / CATEGORY_GRANDCHILDREN)],
        code,
        name: code,
        attributes: [
          { code: 'material', label: 'Material', type: 'TEXT', required: false, variant: false },
        ],
      },
      what: 'POST /categories',
    })),
  ).map((res) => res.json('id'));
  return {
    root,
    children,
    leaves: leafIds.map((id, i) => ({ id, code: leafCodes[i] })),
    adminParents: level2.slice(CATEGORY_CHILDREN),
  };
}

function createProducts(run, seller, plan, categories) {
  const sellerSkus = plan.products.map((p) =>
    Array.from({ length: p.skuCount }, (_, j) => `CARGA-${run}-M${p.index}-${j + 1}`),
  );
  const created = setupBatch(
    plan.products.map((p) => ({
      token: seller,
      method: 'POST',
      path: '/products',
      body: {
        categoryId: categories.leaves[p.leaf].id,
        currency: 'BRL',
        title: `Carga ${run} ${p.role} ${p.state} ${p.index}`,
        description: `Massa de carga ${run}`,
        attributes: { ...BRAND, ...(p.index % 3 === 0 ? { material: 'Algodão' } : {}) },
        skus: sellerSkus[p.index].map((sku, j) => skuRequest(sku, VARIANTS[j], j % 2 === 0)),
      },
      what: 'POST /products',
      expected: [201],
    })),
  );
  return plan.products.map((p, i) => {
    const idBySellerSku = Object.fromEntries(
      created[i].json('skus').map((s) => [s.sellerSku, s.id]),
    );
    const skus = sellerSkus[i].map((s) => idBySellerSku[s]);
    if (skus.some((id) => !id)) fail(`produto ${p.index} criado sem todos os SKUs pedidos`);
    return { ...p, id: created[i].json('id'), skus, sellerSkus: sellerSkus[i] };
  });
}

function transitions(products, tokens) {
  const seller = tokens.seller.access;
  const moderator = tokens.moderator.access;
  const now = Date.now();
  const allSkus = products.flatMap((p) => p.skus.map((skuId) => ({ p, skuId })));
  setupBatch(
    allSkus.map(({ skuId }) => ({
      token: seller,
      method: 'PUT',
      path: `/offers/${skuId}`,
      body: { listPrice: LIST_PRICE, salePrice: PRICE },
      what: 'PUT /offers/{skuId}',
      expected: [201],
    })),
  );
  setupBatch(
    allSkus
      .filter(({ p }) => p.stock > 0)
      .map(({ p, skuId }) => ({
        token: seller,
        method: 'PUT',
        path: `/inventory/skus/${skuId}`,
        body: { onHand: p.stock },
        what: 'PUT /inventory/skus/{skuId}',
        expected: [200],
      })),
  );
  const startsAt = new Date(now + PROMOTION_START_DAYS * DAY_MS).toISOString();
  const endsAt = new Date(now + (PROMOTION_START_DAYS + 7) * DAY_MS).toISOString();
  setupBatch(
    allSkus
      .filter(({ p }) => p.promotion)
      .map(({ skuId }) => ({
        token: seller,
        method: 'POST',
        path: `/offers/${skuId}/promotions`,
        body: { salePrice: PROMOTION_PRICE, startsAt, endsAt },
        what: 'POST /offers/{skuId}/promotions',
        expected: [201],
      })),
  );

  const last = {};
  const step = (selected, call) => {
    const responses = setupBatch(selected.map(call));
    selected.forEach((p, i) => {
      last[p.index] = responses[i];
    });
  };
  step(
    products.filter((p) => p.state !== 'DRAFT'),
    (p) => ({
      token: seller,
      method: 'POST',
      path: `/products/${p.id}/submit`,
      what: 'POST /products/{id}/submit',
      expected: [200],
    }),
  );
  step(
    products.filter((p) => APPROVED_STATES.includes(p.state)),
    (p) => ({
      token: moderator,
      method: 'POST',
      path: `/moderation/products/${p.id}/approve`,
      what: 'POST /moderation/products/{id}/approve',
      expected: [200],
    }),
  );
  step(
    products.filter((p) => p.state === 'PAUSED_BY_SELLER'),
    (p) => ({
      token: seller,
      method: 'POST',
      path: `/products/${p.id}/pause`,
      what: 'POST /products/{id}/pause',
      expected: [200],
    }),
  );
  step(
    products.filter((p) => p.state === 'BLOCKED'),
    (p) => ({
      token: moderator,
      method: 'POST',
      path: `/moderation/products/${p.id}/block`,
      body: { note: 'Massa de carga: bloqueado' },
      what: 'POST /moderation/products/{id}/block',
      expected: [200],
    }),
  );
  step(
    products.filter((p) => p.state === 'ARCHIVED'),
    (p) => ({
      token: seller,
      method: 'DELETE',
      path: `/products/${p.id}`,
      what: 'DELETE /products/{id}',
      expected: [200],
    }),
  );
  for (const p of products) {
    const status = last[p.index] ? last[p.index].json('status') : 'DRAFT';
    if (status !== p.state)
      fail(`massa: produto ${p.index} (${p.role}) ficou ${status}, planejado ${p.state}`);
  }
}

export function createMass() {
  const started = Date.now();
  const run = params.testid;
  const tokens = {};
  for (const role of Object.keys(USERS)) tokens[role] = login(role);

  const eligibility = setupCall(
    tokens.seller.access,
    'GET',
    `/stores/${params.storeId}/eligibility`,
    null,
    'GET eligibility',
    [200],
  );
  if (eligibility.json('status') !== 'APPROVED') {
    fail(
      `loja ${params.storeId} está ${eligibility.json('status')}; rode por tools/load/run.sh, que envia o veredito SellerApproved pelo Kafka`,
    );
  }

  const plan = massPlan(params);
  const categories = createCategories(run, tokens.admin.access);
  const products = createProducts(run, tokens.seller.access, plan, categories);
  transitions(products, tokens);

  const skus = Object.fromEntries(
    SKU_GROUPS.map((g) => [g, products.filter((p) => p.role === g).flatMap((p) => p.skus)]),
  );
  const importSellerSkus = products.filter((p) => p.role === 'import').flatMap((p) => p.sellerSkus);
  const seconds = (Date.now() - started) / 1000;
  massSetupSeconds.add(seconds);

  const before = readMetrics({ kind: 'setup' });
  if (!before.ok) fail(`GET /q/metrics: HTTP ${before.status}`);
  const hotInitial = stock(tokens.seller.access, skus.hot[0]);
  console.log(
    `${READY_MARK}: execução ${run} (perfil ${params.profile}, semente ${params.seed}), ${products.length} produtos e ` +
      `${plan.skus} SKUs em ${seconds.toFixed(1)} s; SKU disputado ${skus.hot[0]}`,
  );
  return {
    run,
    tenantId: params.tenantId,
    tokens,
    categories,
    products: products.map((p) => ({
      id: p.id,
      index: p.index,
      role: p.role,
      state: p.state,
      skus: p.skus,
    })),
    skus: { ...skus, hot: skus.hot[0], last: skus.last[0] },
    importSellerSkus,
    kybStores: kybStores(params),
    pricingPromotionBase: started + PRICING_PROMOTION_START_DAYS * DAY_MS,
    hotInitial,
    initialStock: Object.fromEntries(
      products
        .filter((p) => p.role !== 'population' && p.role !== 'moderation')
        .map((p) => [p.role, p.stock]),
    ),
    metricsBefore: {
      buckets: latencyBuckets(before.samples),
      refused: sumOf(before.samples, 'catalog_reservations_refused_total', {
        reason: 'insufficient-stock',
      }),
      committed: sumOf(before.samples, 'catalog_reservations_committed_total'),
      expired: sumOf(before.samples, 'catalog_reservations_expired_total'),
    },
  };
}
