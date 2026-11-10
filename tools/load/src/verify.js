// Conferências do teardown: saldo de cada grupo de SKU, reservas expiradas, estados da massa, vitrine, lojas do KYB,
// esvaziamento da outbox e latência medida pelo próprio serviço.
import { sleep } from 'k6';
import { params, plan } from './config.js';
import { kybExpected } from './kyb.js';
import { GROUP_STOCK } from './mass-plan.js';
import { expiryRequest, RESERVATION_ITEMS, storefrontMatches } from './scenarios.js';
import { SERVICE_OPERATIONS } from './lib/checks.js';
import { setupBatch } from './lib/http.js';
import { login } from './lib/identity.js';
import {
  hotFinalOnHand,
  hotFinalReserved,
  hotFinalSold,
  inventoryInvariants,
  outboxDrained,
  outboxDrainSeconds,
  outboxLag,
  outboxLagMax,
  serviceP99,
  serviceRefused,
} from './lib/metrics.js';
import { latencyBuckets, quantileFromDelta, readMetrics, sumOf } from './lib/service-metrics.js';

const EXPIRY_SAMPLE = 10;
const selected = (scenario) => params.scenarios.includes(scenario);

function invariant(id, ok, detail) {
  inventoryInvariants.add(ok, { invariant: id });
  if (!ok) console.error(`invariante violada: ${id} (${JSON.stringify(detail)})`);
}

function nonNegative(s) {
  return (
    s.onHand >= 0 && s.reserved >= 0 && s.sold >= 0 && s.available >= 0 && s.reserved <= s.onHand
  );
}

function stocks(token, skuIds) {
  return setupBatch(
    skuIds.map((skuId) => ({
      token,
      method: 'GET',
      path: `/inventory/skus/${skuId}`,
      what: 'GET /inventory/skus/{skuId}',
      expected: [200],
    })),
  ).map((res) => res.json());
}

function verifyReservationGroups(data, seller) {
  const [hot, last] = stocks(seller, [data.skus.hot, data.skus.last]);
  const initial = data.hotInitial.onHand;
  invariant('hot_non_negative', nonNegative(hot), hot);
  if (params.hotMode === 'contend') {
    invariant('hot_restored', hot.reserved === 0 && hot.sold === 0 && hot.onHand === initial, hot);
  } else {
    invariant('hot_on_hand_unchanged', hot.onHand === initial, hot);
    invariant('hot_no_oversell', hot.reserved + hot.sold <= initial, hot);
    const hotAttempts = plan.scenarios.hot_sku ? plan.scenarios.hot_sku.iterations : 0;
    if (hotAttempts >= 2 * initial) {
      // WHY: com o dobro de tentativas do estoque, sobra só se houve recusa indevida (falso insufficient-stock).
      invariant('hot_sold_out', hot.reserved === initial, hot);
    }
  }
  if (selected('last_unit')) {
    const stockSize = params.lastUnitStock;
    invariant(
      'last_unit_taken',
      last.reserved === stockSize &&
        last.available === 0 &&
        last.sold === 0 &&
        last.onHand === stockSize,
      last,
    );
    invariant(
      'last_unit_no_oversell',
      nonNegative(last) && last.reserved + last.sold <= stockSize,
      last,
    );
  }
  hotFinalOnHand.add(hot.onHand);
  hotFinalReserved.add(hot.reserved);
  hotFinalSold.add(hot.sold);

  for (const s of stocks(seller, data.skus.spread)) {
    invariant('spread_non_negative', nonNegative(s), s);
    invariant(
      'spread_restored',
      s.reserved === 0 && s.sold === 0 && s.onHand === params.spreadStock,
      s,
    );
  }
  for (const s of stocks(seller, data.skus.adjust))
    invariant('adjust_non_negative', nonNegative(s), s);

  let reservationSold = null;
  if (selected('reservation_lifecycle')) {
    reservationSold = 0;
    for (const s of stocks(seller, data.skus.reservation)) {
      invariant('reservation_non_negative', nonNegative(s), s);
      invariant(
        'reservation_balance',
        s.reserved === 0 && s.onHand + s.sold === GROUP_STOCK.reservation,
        s,
      );
      reservationSold += s.sold;
    }
  }
  return { hot, last, reservationSold };
}

function verifyExpiry(data, seller, checkout) {
  if (!selected('reservation_expiry')) return null;
  const started = Date.now();
  let levels = stocks(seller, data.skus.expiry);
  while (levels.some((s) => s.reserved > 0) && Date.now() - started < params.expiryWaitS * 1000) {
    sleep(1);
    levels = stocks(seller, data.skus.expiry);
  }
  const waited = (Date.now() - started) / 1000;
  for (const s of levels) {
    invariant(
      'expiry_released',
      s.reserved === 0 &&
        s.sold === 0 &&
        s.onHand === GROUP_STOCK.expiry &&
        s.available === s.onHand,
      s,
    );
  }
  // WHY: o teardown não sabe os ids; repetir a requisição com a mesma chave devolve a reserva original (200).
  const sample = Math.min(
    EXPIRY_SAMPLE,
    Math.floor(plan.scenarios.reservation_expiry.iterations / 2),
  );
  const requests = Array.from({ length: sample }, (_, n) => expiryRequest(data, n));
  const replays = setupBatch(
    requests.map((r) => ({
      token: checkout,
      method: 'POST',
      path: '/inventory/reservations',
      body: r.body,
      what: 'POST /inventory/reservations',
      expected: [200, 201],
      headers: { 'Idempotency-Key': r.key },
    })),
  );
  const replayed = replays.filter((res) => res.status === 200);
  const reads = setupBatch(
    replayed.map((res) => ({
      token: checkout,
      method: 'GET',
      path: `/inventory/reservations/${res.json('id')}`,
      what: 'GET /inventory/reservations/{id}',
      expected: [200],
    })),
  );
  replays.forEach((res, i) => {
    if (res.status !== 200)
      invariant('expiry_expired', false, { key: requests[i].key, status: res.status });
  });
  for (const res of reads) {
    const r = res.json();
    invariant('expiry_expired', r.status === 'EXPIRED' && r.closedAt !== null, {
      id: r.id,
      status: r.status,
      closedAt: r.closedAt,
    });
  }
  return { waited, sample };
}

function expectedStates(p) {
  if (p.role === 'moderation') return ['PENDING_MODERATION', 'BLOCKED'];
  if (p.role === 'hot')
    return params.hotMode === 'exhaust' ? ['ACTIVE', 'OUT_OF_STOCK'] : ['ACTIVE'];
  if (p.role === 'last') return selected('last_unit') ? ['OUT_OF_STOCK'] : ['ACTIVE'];
  return [p.state];
}

function verifyMass(data, seller) {
  const products = setupBatch(
    data.products.map((p) => ({
      token: seller,
      method: 'GET',
      path: `/products/${p.id}`,
      what: 'GET /products/{id}',
      expected: [200],
    })),
  );
  products.forEach((res, i) => {
    const p = data.products[i];
    const status = res.json('status');
    invariant('mass_state', expectedStates(p).includes(status), {
      index: p.index,
      role: p.role,
      planned: p.state,
      status,
    });
  });
  const shown = data.products.filter((p) => p.role === 'population' || p.role === 'moderation');
  const pages = setupBatch(
    shown.map((p) => ({
      token: null,
      method: 'GET',
      path: `/storefront/${data.tenantId}/products/${p.id}`,
      what: 'GET /storefront/{tenantId}/products/{productId}',
      expected: [200, 404],
    })),
  );
  pages.forEach((res, i) => {
    const p = shown[i];
    invariant('storefront_coherent', storefrontMatches(res, p.state), {
      index: p.index,
      planned: p.state,
      status: res.status,
    });
  });
}

function verifyKyb() {
  if (!selected('kyb_verdicts')) return null;
  const expected = kybExpected(params, plan);
  const stores = Object.keys(expected);
  const admin = login('admin').access;
  const read = () =>
    setupBatch(
      stores.map((storeId) => ({
        token: admin,
        method: 'GET',
        path: `/stores/${storeId}/eligibility`,
        what: 'GET /stores/{storeId}/eligibility',
        expected: [200],
      })),
    ).map((res) => res.json('status'));
  const started = Date.now();
  let statuses = read();
  while (
    statuses.some((s, i) => s !== expected[stores[i]]) &&
    Date.now() - started < params.kybSettleS * 1000
  ) {
    sleep(1);
    statuses = read();
  }
  stores.forEach((storeId, i) =>
    invariant('kyb_final', statuses[i] === expected[storeId], {
      storeId,
      expected: expected[storeId],
      status: statuses[i],
    }),
  );
  return {
    stores: stores.length,
    matched: statuses.filter((s, i) => s === expected[stores[i]]).length,
  };
}

export function verifyRun(data) {
  const seller = login('seller').access;
  const checkout = login('checkout').access;
  const { hot, last, reservationSold } = verifyReservationGroups(data, seller);
  const expiry = verifyExpiry(data, seller, checkout);
  verifyMass(data, seller);
  const kyb = verifyKyb();

  const started = Date.now();
  let m = readMetrics();
  while (m.ok && m.pending !== 0 && Date.now() - started < params.outboxDrainS * 1000) {
    sleep(1);
    m = readMetrics();
  }
  const drainSeconds = (Date.now() - started) / 1000;
  outboxDrained.add(m.ok && m.pending === 0);
  if (!m.ok) {
    console.error(`GET /q/metrics no teardown: HTTP ${m.status}`);
    return;
  }
  outboxLagMax.add(m.lagMax);
  outboxLag.add(m.lagMax);
  if (m.pending === 0) outboxDrainSeconds.add(drainSeconds);

  const committed =
    sumOf(m.samples, 'catalog_reservations_committed_total') - data.metricsBefore.committed;
  if (reservationSold !== null) {
    invariant(
      'reservation_sold_matches_commits',
      reservationSold === RESERVATION_ITEMS * committed,
      { reservationSold, committed },
    );
  }
  const after = latencyBuckets(m.samples);
  const serverP99 = [];
  for (const op of Object.keys(after).sort()) {
    const q = quantileFromDelta(0.99, data.metricsBefore.buckets[op], after[op]);
    if (q === null) continue;
    if (SERVICE_OPERATIONS.includes(op)) serviceP99.add(q, { operation: op });
    serverP99.push(`${op}=${(q * 1000).toFixed(1)}ms`);
  }
  const refused =
    sumOf(m.samples, 'catalog_reservations_refused_total', { reason: 'insufficient-stock' }) -
    data.metricsBefore.refused;
  if (params.hotMode === 'contend') {
    // WHY: fora do modo exhaust, só a rajada do último item pode recusar por falta de saldo, e exatamente o excedente.
    const expectedRefusals = selected('last_unit')
      ? params.lastUnitAttempts - params.lastUnitStock
      : 0;
    invariant('insufficient_stock_limited', refused === expectedRefusals, {
      refused,
      expectedRefusals,
    });
  }
  const expired =
    sumOf(m.samples, 'catalog_reservations_expired_total') - data.metricsBefore.expired;
  serviceRefused.add(refused);
  console.log(
    `resultado ${data.run}: hot (${params.hotMode}) onHand=${hot.onHand} reserved=${hot.reserved} sold=${hot.sold} (estoque inicial ${data.hotInitial.onHand}); ` +
      `último item onHand=${last.onHand} reserved=${last.reserved} sold=${last.sold}; ` +
      `ciclo de reserva sold=${reservationSold} e ${committed} confirmações no serviço; ` +
      `expiração ${expiry ? `saldo devolvido em ${expiry.waited.toFixed(1)}s, ${expiry.sample} reservas amostradas` : 'fora'}, ${expired} expiradas pelo varredor; ` +
      `KYB ${kyb ? `${kyb.matched}/${kyb.stores} lojas no último veredito` : 'fora'}; ` +
      `outbox pending=${m.pending} após ${drainSeconds.toFixed(1)}s, lag max=${m.lagMax.toFixed(3)}s p99=${m.lagP99}s; ` +
      `recusas insufficient-stock no serviço=${refused}; p99 do serviço (catalog.inventory.update.latency): ${serverP99.join(' ') || 'sem amostras'}`,
  );
}
