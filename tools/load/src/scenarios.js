import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { params } from './config.js';
import {
  api, apiBatch, apiRequest, EXPECT_ACCEPTED, EXPECT_CREATED, EXPECT_FORBIDDEN, EXPECT_OK, EXPECT_RESERVE,
  EXPECT_VISIBLE_OR_HIDDEN, etagOf, jsonOf, problemCode, record,
} from './lib/http.js';
import {
  hotAccepted, hotRefused, inventoryInvariants, lastUnitAccepted, lastUnitRefused, outboxLag, outboxLagMax,
  outboxPending, outcomes, serverErrors, spreadRefused, unexpectedResponses,
} from './lib/metrics.js';
import { createRandom, hashSeed } from './lib/random.js';
import { readMetrics } from './lib/service-metrics.js';
import { BRAND, skuRequest } from './mass.js';
import { GROUP_STOCK, LIST_PRICE, PRICE, PROMOTION_PRICE } from './mass-plan.js';

const ADJUST_INITIAL = GROUP_STOCK.adjust;
// WHY: com dois itens por reserva do ciclo, o teardown confere sold contra o dobro das confirmações do serviço.
export const RESERVATION_ITEMS = 2;
const RESERVATION_LIFECYCLE_TTL_S = 600;
const RESERVATION_EXTEND_TTL_S = 900;
const PROMOTION_SLOT_MS = 600000;
const PROMOTION_LENGTH_MS = 300000;
export const IMPORT_NEW_LINES = 2;
export const IMPORT_EXISTING_LINES = 2;
const IMPORT_HEADER =
  'product_ref,category_code,title,description,attributes,seller_sku,variant_attributes,physical,height_cm,width_cm,length_cm,weight_g,currency,list_price,sale_price,on_hand';
const BROWSE_ROLES = ['seller', 'admin', 'moderator'];
const LIST_STATUSES = [null, 'DRAFT', 'PENDING_MODERATION', 'ACTIVE', 'PAUSED_BY_SELLER', 'BLOCKED', 'ARCHIVED'];
let pools = null;
let invariantLogs = 0;

const iteration = () => exec.scenario.iterationInTest;

// WHY: a semente depende só do cenário e do número da iteração no teste, não do VU que a executa; a mesma SEED
// repete os mesmos sorteios em qualquer execução do perfil.
function iterationRandom() {
  return createRandom(hashSeed(params.seed, exec.scenario.name, exec.scenario.iterationInTest));
}

function poolsOf(data) {
  if (pools === null) {
    pools = {
      storefront: data.products.filter((p) => p.role === 'population' || p.role === 'moderation'),
      moderation: data.products.filter((p) => p.role === 'moderation'),
    };
  }
  return pools;
}

function step(res, ok, label) {
  record(res, ok);
  check(res, { [label]: () => ok });
  return ok;
}

const pick = (random, list) => list[random.int(0, list.length - 1)];
const lastSegment = (url) => (url ? url.slice(url.lastIndexOf('/') + 1) : null);
const iso = (ms) => new Date(ms).toISOString();

function reservationBody(data, label, skuIds) {
  return {
    orderRef: `carga-${data.run}-${label}`,
    ttlSeconds: params.reservationTtlS,
    items: skuIds.map((skuId) => ({ skuId, quantity: 1, expectedUnitPrice: PRICE })),
  };
}

// WHY: só o último item e o modo exhaust do disputado esperam falta de saldo; nos demais grupos, o estoque folgado
// torna o 409 insufficient-stock uma resposta inesperada.
const exhaust = params.hotMode === 'exhaust';
const isOutOfStock = (res) => res.status === 409 && problemCode(res) === 'insufficient-stock';

function reserve(data, label, skuIds, expected) {
  return api(data, 'checkout', 'POST', '/inventory/reservations', reservationBody(data, label, skuIds),
    'POST /inventory/reservations', expected ?? EXPECT_RESERVE, { 'Idempotency-Key': `carga-${data.run}-${label}` });
}

function release(data, res, label) {
  const released = api(data, 'checkout', 'POST', `/inventory/reservations/${res.json('id')}/release`, null,
    'POST /inventory/reservations/{id}/release', EXPECT_OK);
  record(released, released.status === 200);
  check(released, { [label]: (r) => r.status === 200 });
}

export function hotSku(data) {
  const res = reserve(data, `hot-${exec.scenario.iterationInTest}`, [data.skus.hot], exhaust ? EXPECT_RESERVE : EXPECT_CREATED);
  const accepted = res.status === 201;
  const refused = isOutOfStock(res);
  if (accepted) hotAccepted.add(1);
  if (refused) hotRefused.add(1);
  if (exhaust) {
    record(res, accepted || refused);
    check(res, { 'hot_sku: 201 ou 409 insufficient-stock': () => accepted || refused });
    return;
  }
  record(res, accepted);
  check(res, { 'hot_sku: reserva 201': () => accepted });
  // WHY: liberar logo em seguida mantém a disputa pela mesma linha sem esgotar o saldo nem recusar por estoque.
  if (accepted) release(data, res, 'hot_sku: liberação 200');
}

// Rajada no começo: o dobro de tentativas do estoque, sem liberar; o primeiro leva, o seguinte falha de forma limpa.
export function lastUnit(data) {
  const res = reserve(data, `last-${exec.scenario.iterationInTest}`, [data.skus.last]);
  const accepted = res.status === 201;
  const refused = isOutOfStock(res);
  lastUnitAccepted.add(accepted ? 1 : 0);
  lastUnitRefused.add(refused ? 1 : 0);
  record(res, accepted || refused);
  check(res, { 'last_unit: 201 ou 409 insufficient-stock': () => accepted || refused });
}

export function spreadReserveRelease(data) {
  const random = iterationRandom();
  const count = random.int(1, Math.min(3, data.skus.spread.length));
  // WHY: a ordem dos itens fica aleatória de propósito; o serviço é que deve travar na ordem canônica.
  const skuIds = random.pickDistinct(data.skus.spread, count);
  const res = reserve(data, `spread-${exec.scenario.iterationInTest}`, skuIds, exhaust ? EXPECT_RESERVE : EXPECT_CREATED);
  if (res.status !== 201) {
    const refused = isOutOfStock(res);
    if (refused) spreadRefused.add(1);
    record(res, exhaust && refused);
    check(res, { [exhaust ? 'spread: reserva 201 ou 409 insufficient-stock' : 'spread: reserva 201']: () => exhaust && refused });
    return;
  }
  record(res, true);
  release(data, res, 'spread: liberação 200');
}

export function stockAdjust(data) {
  // WHY: rodízio pelos SKUs do grupo para que ajustes simultâneos caiam em SKUs distintos.
  const skuId = data.skus.adjust[exec.scenario.iterationInTest % data.skus.adjust.length];
  const onHand = ADJUST_INITIAL + iterationRandom().int(0, 899);
  const res = api(data, 'seller', 'PUT', `/inventory/skus/${skuId}`, { onHand }, 'PUT /inventory/skus/{skuId}', EXPECT_OK);
  record(res, res.status === 200);
  check(res, { 'stock_adjust: 200': (r) => r.status === 200 });
}

function categoryRead(data, role, random) {
  const c = data.categories;
  switch (random.int(0, 3)) {
    case 0:
      return { path: '/categories?limit=20', name: 'GET /categories', expected: EXPECT_OK, label: 'catalog_browse: raízes 200', ok: (r) => r.status === 200 };
    case 1: {
      const parent = pick(random, [c.root, ...c.children]);
      return {
        path: `/categories?parentId=${parent}&limit=20`,
        name: 'GET /categories',
        expected: EXPECT_OK,
        label: 'catalog_browse: filhas 200',
        ok: (r) => r.status === 200 && (jsonOf(r, 'items') || []).length > 0,
      };
    }
    case 2: {
      const leaf = pick(random, c.leaves);
      return {
        path: `/categories/${leaf.id}`,
        name: 'GET /categories/{id}',
        expected: EXPECT_OK,
        label: 'catalog_browse: categoria 200',
        ok: (r) => r.status === 200 && (jsonOf(r, 'effectiveAttributes') || []).length === 3,
      };
    }
    default: {
      if (role === 'moderator') {
        // WHY: a recusa por papel vem do @RolesAllowed, com 403 sem corpo, como no contrato OpenAPI.
        return {
          path: `/stores/${params.storeId}/eligibility`,
          name: 'GET /stores/{storeId}/eligibility',
          expected: EXPECT_FORBIDDEN,
          label: 'catalog_browse: loja 403 para o moderador',
          ok: (r) => r.status === 403,
        };
      }
      const storeId = role === 'admin' && data.kybStores.length > 0 && random.fraction() < 0.5 ? pick(random, data.kybStores) : params.storeId;
      return {
        path: `/stores/${storeId}/eligibility`,
        name: 'GET /stores/{storeId}/eligibility',
        expected: EXPECT_OK,
        label: 'catalog_browse: loja 200',
        ok: (r) => r.status === 200 && jsonOf(r, 'storeId') === storeId,
      };
    }
  }
}

export function catalogBrowse(data) {
  const random = iterationRandom();
  const role = BROWSE_ROLES[iteration() % BROWSE_ROLES.length];
  const product = pick(random, data.products);
  const skuId = pick(random, product.skus);
  const status = pick(random, LIST_STATUSES);
  const query = `limit=20${status ? `&status=${status}` : ''}${role === 'seller' ? '' : `&storeId=${params.storeId}`}`;
  const detail = pick(random, [
    [`/products/${product.id}/skus/${skuId}`, 'GET /products/{id}/skus/{skuId}', (r) => jsonOf(r, 'id') === skuId],
    [`/products/${product.id}/history`, 'GET /products/{id}/history', (r) => Array.isArray(jsonOf(r))],
    [`/offers/${skuId}`, 'GET /offers/{skuId}', (r) => jsonOf(r, 'skuId') === skuId],
    [`/inventory/skus/${skuId}`, 'GET /inventory/skus/{skuId}', (r) => jsonOf(r, 'skuId') === skuId],
  ]);
  const category = categoryRead(data, role, random);
  const [list, item, sub, cat] = apiBatch([
    apiRequest(data, role, 'GET', `/products?${query}`, null, 'GET /products', EXPECT_OK),
    apiRequest(data, role, 'GET', `/products/${product.id}`, null, 'GET /products/{id}', EXPECT_OK),
    apiRequest(data, role, 'GET', detail[0], null, detail[1], EXPECT_OK),
    apiRequest(data, role, 'GET', category.path, null, category.name, category.expected),
  ]);
  step(list, list.status === 200 && Array.isArray(jsonOf(list, 'items')), 'catalog_browse: lista 200');
  step(item, item.status === 200 && jsonOf(item, 'id') === product.id, 'catalog_browse: produto 200');
  step(sub, sub.status === 200 && detail[2](sub), 'catalog_browse: detalhe 200');
  step(cat, category.ok(cat), category.label);
  const next = jsonOf(list, 'nextCursor');
  if (next && random.fraction() < 0.3) {
    const page = api(data, role, 'GET', `/products?${query}&cursor=${encodeURIComponent(next)}`, null, 'GET /products', EXPECT_OK);
    step(page, page.status === 200 && Array.isArray(jsonOf(page, 'items')), 'catalog_browse: próxima página 200');
  }
}

// Vitrine anônima: 200 só com status gravado ACTIVE (sem saldo, OUT_OF_STOCK e inStock=false); senão 404.
export function storefrontMatches(res, state) {
  if (state !== 'ACTIVE' && state !== 'OUT_OF_STOCK') return res.status === 404;
  const body = res.status === 200 ? jsonOf(res) : null;
  return body !== null && body.status === state && body.skus.every((s) => s.inStock === (state === 'ACTIVE'));
}

export function storefront(data) {
  const product = pick(iterationRandom(), poolsOf(data).storefront);
  const visible = product.state === 'ACTIVE' || product.state === 'OUT_OF_STOCK';
  const res = api(data, null, 'GET', `/storefront/${data.tenantId}/products/${product.id}`, null,
    'GET /storefront/{tenantId}/products/{productId}', EXPECT_VISIBLE_OR_HIDDEN);
  if (step(res, storefrontMatches(res, product.state), visible ? 'storefront: visível 200' : 'storefront: oculto 404')) {
    outcomes.add(1, { outcome: visible ? 'storefront_visible' : 'storefront_hidden' });
  }
}

export function productLifecycle(data) {
  const n = iteration();
  const leaf = data.categories.leaves[n % data.categories.leaves.length];
  const sellerSku = (j) => `CARGA-${data.run}-P${n}-${j}`;
  const seller = (method, path, body, name, expected, headers) => api(data, 'seller', method, path, body, name, expected, headers);
  const created = seller('POST', '/products', {
    categoryId: leaf.id,
    currency: 'BRL',
    title: `Ciclo ${data.run} ${n}`,
    description: 'Ciclo de vida sob carga',
    attributes: BRAND,
    skus: [skuRequest(sellerSku(1), 'c1', false), skuRequest(sellerSku(2), 'c2', false)],
  }, 'POST /products', EXPECT_CREATED);
  if (!step(created, created.status === 201 && jsonOf(created, 'status') === 'DRAFT', 'product_lifecycle: criação 201')) return;
  const id = created.json('id');
  const skuIds = created.json('skus').map((s) => s.id);
  const prepared = apiBatch([
    ...skuIds.map((s) => apiRequest(data, 'seller', 'PUT', `/offers/${s}`, { listPrice: LIST_PRICE, salePrice: PRICE }, 'PUT /offers/{skuId}', EXPECT_CREATED)),
    ...skuIds.map((s) => apiRequest(data, 'seller', 'PUT', `/inventory/skus/${s}`, { onHand: 10 }, 'PUT /inventory/skus/{skuId}', EXPECT_OK)),
  ]);
  const prepareOk = prepared.map((r, i) => (i < skuIds.length
    ? step(r, r.status === 201, 'product_lifecycle: oferta 201')
    : step(r, r.status === 200, 'product_lifecycle: estoque 200')));
  if (prepareOk.includes(false)) return;

  const added = seller('POST', `/products/${id}/skus`, skuRequest(sellerSku(3), 'c3', false), 'POST /products/{id}/skus', EXPECT_CREATED);
  const extra = (jsonOf(added, 'skus') || []).find((s) => s.sellerSku === sellerSku(3));
  if (!step(added, added.status === 201 && extra !== undefined && etagOf(added) !== null, 'product_lifecycle: SKU extra 201')) return;
  const revisedSku = seller('PUT', `/products/${id}/skus/${extra.id}`, { physical: false, variantAttributes: { cor: 'c4' } },
    'PUT /products/{id}/skus/{skuId}', EXPECT_OK, { 'If-Match': etagOf(added) });
  if (!step(revisedSku, revisedSku.status === 200, 'product_lifecycle: revisão do SKU 200')) return;
  const removed = seller('DELETE', `/products/${id}/skus/${extra.id}`, null, 'DELETE /products/{id}/skus/{skuId}', EXPECT_OK);
  if (!step(removed, removed.status === 200 && (jsonOf(removed, 'skus') || []).length === 2 && etagOf(removed) !== null,
    'product_lifecycle: remoção do SKU 200')) return;
  const revised = seller('PUT', `/products/${id}`, {
    categoryId: leaf.id,
    title: `Ciclo ${data.run} ${n} revisado`,
    description: 'Revisado sob carga',
    attributes: { ...BRAND, material: 'Aço' },
  }, 'PUT /products/{id}', EXPECT_OK, { 'If-Match': etagOf(removed) });
  if (!step(revised, revised.status === 200, 'product_lifecycle: revisão 200')) return;
  const submitted = seller('POST', `/products/${id}/submit`, null, 'POST /products/{id}/submit', EXPECT_OK);
  if (!step(submitted, submitted.status === 200 && jsonOf(submitted, 'status') === 'PENDING_MODERATION', 'product_lifecycle: envio 200')) return;

  if (n % 2 === 0) {
    const approved = api(data, 'moderator', 'POST', `/moderation/products/${id}/approve`, null, 'POST /moderation/products/{id}/approve', EXPECT_OK);
    if (!step(approved, approved.status === 200 && jsonOf(approved, 'status') === 'ACTIVE', 'product_lifecycle: aprovação 200')) return;
    const paused = seller('POST', `/products/${id}/pause`, null, 'POST /products/{id}/pause', EXPECT_OK);
    if (!step(paused, paused.status === 200 && jsonOf(paused, 'status') === 'PAUSED_BY_SELLER', 'product_lifecycle: pausa 200')) return;
    const resumed = seller('POST', `/products/${id}/resume`, null, 'POST /products/{id}/resume', EXPECT_OK);
    if (!step(resumed, resumed.status === 200 && jsonOf(resumed, 'status') === 'ACTIVE', 'product_lifecycle: retomada 200')) return;
    outcomes.add(1, { outcome: 'product_approved' });
  } else {
    const rejected = api(data, 'moderator', 'POST', `/moderation/products/${id}/reject`, { note: 'Carga: recusa de teste' },
      'POST /moderation/products/{id}/reject', EXPECT_OK);
    if (!step(rejected, rejected.status === 200 && jsonOf(rejected, 'status') === 'DRAFT', 'product_lifecycle: recusa 200')) return;
    outcomes.add(1, { outcome: 'product_rejected' });
  }
  if (n % 3 === 2) {
    const archived = seller('DELETE', `/products/${id}`, null, 'DELETE /products/{id}', EXPECT_OK);
    if (step(archived, archived.status === 200 && jsonOf(archived, 'status') === 'ARCHIVED', 'product_lifecycle: arquivamento 200')) {
      outcomes.add(1, { outcome: 'product_archived' });
    }
  }
}

export function moderation(data) {
  const pool = poolsOf(data).moderation;
  const product = pool[iteration() % pool.length];
  const [queue, current] = apiBatch([
    apiRequest(data, 'moderator', 'GET', '/moderation/products?limit=20', null, 'GET /moderation/products', EXPECT_OK),
    apiRequest(data, 'moderator', 'GET', `/products/${product.id}`, null, 'GET /products/{id}', EXPECT_OK),
  ]);
  step(queue, queue.status === 200 && Array.isArray(jsonOf(queue, 'items')), 'moderation: fila 200');
  const status = jsonOf(current, 'status');
  if (!step(current, current.status === 200 && (status === 'BLOCKED' || status === 'PENDING_MODERATION'), 'moderation: produto 200')) return;
  // WHY: o desbloqueio devolve à moderação; o par bloqueio e desbloqueio mantém o produto fora da vitrine.
  const unblock = status === 'BLOCKED';
  const ifMatch = { 'If-Match': etagOf(current) };
  const res = unblock
    ? api(data, 'moderator', 'POST', `/moderation/products/${product.id}/unblock`, null, 'POST /moderation/products/{id}/unblock', EXPECT_OK, ifMatch)
    : api(data, 'moderator', 'POST', `/moderation/products/${product.id}/block`, { note: 'Carga: bloqueio de teste' },
      'POST /moderation/products/{id}/block', EXPECT_OK, ifMatch);
  const expected = unblock ? 'PENDING_MODERATION' : 'BLOCKED';
  if (step(res, res.status === 200 && jsonOf(res, 'status') === expected, unblock ? 'moderation: desbloqueio 200' : 'moderation: bloqueio 200')) {
    outcomes.add(1, { outcome: unblock ? 'moderation_unblocked' : 'moderation_blocked' });
  }
}

export function offerPricing(data) {
  const n = iteration();
  const skuId = data.skus.pricing[n % data.skus.pricing.length];
  const current = api(data, 'seller', 'GET', `/offers/${skuId}`, null, 'GET /offers/{skuId}', EXPECT_OK);
  if (!step(current, current.status === 200 && etagOf(current) !== null, 'offer_pricing: oferta 200')) return;
  const salePrice = { amount: Number((15 + (n % 1000) / 100).toFixed(2)), currency: 'BRL' };
  const changed = api(data, 'seller', 'PUT', `/offers/${skuId}`, { listPrice: LIST_PRICE, salePrice }, 'PUT /offers/{skuId}', EXPECT_OK,
    { 'If-Match': etagOf(current) });
  if (!step(changed, changed.status === 200 && jsonOf(changed, 'price.salePrice.amount') === salePrice.amount, 'offer_pricing: troca de preço 200')) return;
  // WHY: cada iteração tem a própria janela, no futuro e sem tocar a de outra; nenhuma entra em vigor durante a carga.
  const startsAt = data.pricingPromotionBase + n * PROMOTION_SLOT_MS;
  const promotion = api(data, 'seller', 'POST', `/offers/${skuId}/promotions`,
    { salePrice: PROMOTION_PRICE, startsAt: iso(startsAt), endsAt: iso(startsAt + PROMOTION_LENGTH_MS) },
    'POST /offers/{skuId}/promotions', EXPECT_CREATED);
  const promotionId = lastSegment(promotion.headers.Location);
  if (!step(promotion, promotion.status === 201 && promotionId !== null, 'offer_pricing: promoção 201')) return;
  const cancelled = api(data, 'seller', 'DELETE', `/offers/${skuId}/promotions/${promotionId}`, null,
    'DELETE /offers/{skuId}/promotions/{promotionId}', EXPECT_OK);
  const left = jsonOf(cancelled, 'promotions') || [];
  if (step(cancelled, cancelled.status === 200 && !left.some((p) => p.id === promotionId), 'offer_pricing: cancelamento 200')) {
    outcomes.add(1, { outcome: 'promotion_cancelled' });
  }
}

export function reservationLifecycle(data) {
  const n = iteration();
  const key = `carga-${data.run}-res-${n}`;
  const body = {
    orderRef: key,
    ttlSeconds: RESERVATION_LIFECYCLE_TTL_S,
    items: iterationRandom().pickDistinct(data.skus.reservation, RESERVATION_ITEMS)
      .map((skuId) => ({ skuId, quantity: 1, expectedUnitPrice: PRICE })),
  };
  const checkout = (method, path, payload, name, expected, headers) => api(data, 'checkout', method, path, payload, name, expected, headers);
  const created = checkout('POST', '/inventory/reservations', body, 'POST /inventory/reservations', EXPECT_CREATED, { 'Idempotency-Key': key });
  if (!step(created, created.status === 201 && jsonOf(created, 'status') === 'ACTIVE', 'reservation_lifecycle: reserva 201')) return;
  const id = created.json('id');
  if (n % 5 === 0) {
    const replay = checkout('POST', '/inventory/reservations', body, 'POST /inventory/reservations', EXPECT_OK, { 'Idempotency-Key': key });
    const replayed = replay.status === 200 && replay.headers['Idempotent-Replayed'] === 'true' && jsonOf(replay, 'id') === id;
    if (step(replay, replayed, 'reservation_lifecycle: repetição idempotente 200')) outcomes.add(1, { outcome: 'reservation_replayed' });
  }
  const read = checkout('GET', `/inventory/reservations/${id}`, null, 'GET /inventory/reservations/{id}', EXPECT_OK);
  step(read, read.status === 200 && jsonOf(read, 'status') === 'ACTIVE', 'reservation_lifecycle: consulta 200');
  const extended = checkout('POST', `/inventory/reservations/${id}/extend`, { ttlSeconds: RESERVATION_EXTEND_TTL_S },
    'POST /inventory/reservations/{id}/extend', EXPECT_OK);
  step(extended, extended.status === 200 && Date.parse(jsonOf(extended, 'expiresAt')) > Date.parse(created.json('expiresAt')),
    'reservation_lifecycle: prorrogação 200');
  // WHY: a reserva fecha mesmo se um passo anterior falhou, para o saldo do grupo continuar conferível.
  const release = n % 4 === 3;
  const closed = checkout('POST', `/inventory/reservations/${id}/${release ? 'release' : 'commit'}`, null,
    `POST /inventory/reservations/{id}/${release ? 'release' : 'commit'}`, EXPECT_OK);
  const status = release ? 'RELEASED' : 'COMMITTED';
  if (step(closed, closed.status === 200 && jsonOf(closed, 'status') === status, release ? 'reservation_lifecycle: liberação 200' : 'reservation_lifecycle: confirmação 200')) {
    outcomes.add(1, { outcome: release ? 'reservation_released' : 'reservation_committed' });
  }
}

// Mesma requisição na carga e no teardown, que a repete para recuperar a reserva pela Idempotency-Key.
export function expiryRequest(data, n) {
  const key = `carga-${data.run}-exp-${n}`;
  return {
    key,
    body: {
      orderRef: key,
      ttlSeconds: params.expiryTtlS,
      items: [{ skuId: data.skus.expiry[n % data.skus.expiry.length], quantity: 1, expectedUnitPrice: PRICE }],
    },
  };
}

export function reservationExpiry(data) {
  const { key, body } = expiryRequest(data, iteration());
  const res = api(data, 'checkout', 'POST', '/inventory/reservations', body, 'POST /inventory/reservations', EXPECT_CREATED, { 'Idempotency-Key': key });
  if (step(res, res.status === 201 && jsonOf(res, 'status') === 'ACTIVE', 'reservation_expiry: reserva curta 201')) {
    outcomes.add(1, { outcome: 'reservation_expiring' });
  }
}

export function categoryAdmin(data) {
  const n = iteration();
  const [from, to] = n % 2 === 0 ? data.categories.adminParents : [...data.categories.adminParents].reverse();
  const note = { code: 'nota', label: 'Nota', type: 'TEXT', required: false, variant: false };
  const created = api(data, 'admin', 'POST', '/categories', { parentId: from, code: `carga-${data.run}-c${n}`, name: `Categoria ${n}`, attributes: [note] },
    'POST /categories', EXPECT_CREATED);
  if (!step(created, created.status === 201 && etagOf(created) !== null, 'category_admin: criação 201')) return;
  const id = created.json('id');
  // WHY: o PUT substitui os atributos próprios; sem attributes, a categoria perderia "nota".
  const revised = api(data, 'admin', 'PUT', `/categories/${id}`, {
    name: `Categoria ${n} revisada`,
    active: true,
    attributes: [note, { code: 'selo', label: 'Selo', type: 'BOOLEAN', required: false, variant: false }],
  }, 'PUT /categories/{id}', EXPECT_OK, { 'If-Match': etagOf(created) });
  if (!step(revised, revised.status === 200 && (jsonOf(revised, 'attributes') || []).length === 2, 'category_admin: revisão 200')) return;
  const moved = api(data, 'admin', 'PUT', `/categories/${id}/parent`, { parentId: to }, 'PUT /categories/{id}/parent', EXPECT_OK,
    { 'If-Match': etagOf(revised) });
  if (step(moved, moved.status === 200 && jsonOf(moved, 'parentId') === to, 'category_admin: mudança de pai 200')) {
    outcomes.add(1, { outcome: 'category_moved' });
  }
}

function importCsv(data, n) {
  const leaf = data.categories.leaves[n % data.categories.leaves.length];
  const lines = [IMPORT_HEADER];
  for (let j = 1; j <= IMPORT_NEW_LINES; j += 1) {
    lines.push(`imp-${n},${leaf.code},Importado ${data.run} ${n},,marca=Carga,CARGA-${data.run}-I${n}-${j},cor=c${j},false,,,,,BRL,29.90,19.90,5`);
  }
  // WHY: o onHand é único por iteração e linha, então cada SKU existente sempre muda e a linha sai UPDATED.
  for (let j = 0; j < IMPORT_EXISTING_LINES; j += 1) {
    const sellerSku = data.importSellerSkus[(n * IMPORT_EXISTING_LINES + j) % data.importSellerSkus.length];
    lines.push(`,,,,,${sellerSku},,,,,,,BRL,29.90,19.90,${1000 + n * 10 + j}`);
  }
  return `${lines.join('\n')}\n`;
}

function importMatches(report) {
  const total = IMPORT_NEW_LINES + IMPORT_EXISTING_LINES;
  const c = report && report.counts;
  return report !== null && report.status === 'COMPLETED' && report.totalLines === total && report.processedLines === total &&
    c.created === IMPORT_NEW_LINES && c.updated === IMPORT_EXISTING_LINES && c.unchanged === 0 && c.failed === 0;
}

export function productImport(data) {
  const n = iteration();
  const posted = api(data, 'seller', 'POST', '/products/imports', importCsv(data, n), 'POST /products/imports', EXPECT_ACCEPTED,
    { 'Content-Type': 'text/csv' });
  const id = jsonOf(posted, 'id');
  let report = null;
  if (step(posted, posted.status === 202 && id !== null, 'product_import: envio 202')) {
    // WHY: o worker processa um lote por segundo, em série; a consulta espera a vez com prazo IMPORT_WAIT_S.
    const deadline = Date.now() + params.importWaitS * 1000;
    while (Date.now() < deadline) {
      sleep(1);
      const res = api(data, 'seller', 'GET', `/products/imports/${id}`, null, 'GET /products/imports/{id}', EXPECT_OK);
      if (!step(res, res.status === 200, 'product_import: consulta 200')) break;
      report = jsonOf(res);
      if (report.status === 'COMPLETED') break;
    }
  }
  const ok = importMatches(report);
  inventoryInvariants.add(ok, { invariant: 'import_completed' });
  check(report, { 'product_import: COMPLETED com as contagens esperadas': () => ok });
  if (ok) {
    outcomes.add(1, { outcome: 'import_completed' });
  } else if (invariantLogs < 3) {
    invariantLogs += 1;
    console.error(`invariante violada: import_completed (${JSON.stringify(report).slice(0, 400)})`);
  }
}

export function outboxProbe() {
  const m = readMetrics();
  serverErrors.add(!m.ok && (m.status === 0 || m.status >= 500));
  unexpectedResponses.add(!m.ok);
  if (!m.ok) return;
  outboxLagMax.add(m.lagMax);
  outboxLag.add(m.lagMax);
  if (m.pending !== null) outboxPending.add(m.pending);
}
