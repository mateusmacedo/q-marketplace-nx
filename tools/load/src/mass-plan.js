// Plano da massa de cada execução, derivado só dos parâmetros e da SEED: árvore de categorias, produtos por estado e
// grupos de SKU por cenário. Não importa módulos do k6, porque o --check e o resumo também o usam.
import { createRandom, hashSeed } from './lib/random.js';

export const PRICE = { amount: 19.9, currency: 'BRL' };
export const LIST_PRICE = { amount: 29.9, currency: 'BRL' };
export const PROMOTION_PRICE = { amount: 14.9, currency: 'BRL' };
// WHY: cada SKU de um produto usa uma opção distinta da variante "cor"; o submit recusa dois SKUs com o mesmo mapa.
export const VARIANTS = ['c1', 'c2', 'c3', 'c4'];
export const CATEGORY_CHILDREN = 3;
export const CATEGORY_GRANDCHILDREN = 2;

export const STATES = ['DRAFT', 'PENDING_MODERATION', 'ACTIVE', 'OUT_OF_STOCK', 'PAUSED_BY_SELLER', 'BLOCKED', 'ARCHIVED'];
// WHY: OUT_OF_STOCK é ACTIVE sem saldo (status efetivo); o ACTIVE fica com o que sobra da população.
const STATE_SHARE = { DRAFT: 0.15, PENDING_MODERATION: 0.1, OUT_OF_STOCK: 0.1, PAUSED_BY_SELLER: 0.1, BLOCKED: 0.1, ARCHIVED: 0.05 };

// Grupos de SKU, cada um usado por um cenário só, para um não estragar o saldo ou o preço do outro; só o último item
// (last) tem estoque pequeno de propósito, para a recusa por falta de saldo ser intencional e limitada.
export const SKU_GROUPS = ['hot', 'last', 'spread', 'adjust', 'reservation', 'expiry', 'pricing', 'import'];
export const GROUP_STOCK = { adjust: 100, reservation: 100000, expiry: 100000, pricing: 100, import: 50 };
const POPULATION_STOCK = 10;

function groupSizes(params) {
  return {
    hot: 1,
    last: 1,
    spread: params.spreadSkus,
    adjust: params.adjustSkus,
    reservation: params.reservationSkus,
    expiry: params.expirySkus,
    pricing: params.pricingSkus,
    import: params.importSkus,
  };
}

function groupProducts(params) {
  const sizes = groupSizes(params);
  return SKU_GROUPS.reduce((total, g) => total + Math.ceil(sizes[g] / VARIANTS.length), 0);
}

export function minimumMassProducts(params) {
  return groupProducts(params) + params.moderationProducts + STATES.length;
}

function stockOf(params, role, state) {
  if (role === 'hot') return params.hotStock;
  if (role === 'last') return params.lastUnitStock;
  if (role === 'spread') return params.spreadStock;
  if (role in GROUP_STOCK) return GROUP_STOCK[role];
  return state === 'OUT_OF_STOCK' ? 0 : POPULATION_STOCK;
}

function populationStates(total) {
  const counts = {};
  let assigned = 0;
  for (const [state, share] of Object.entries(STATE_SHARE)) {
    counts[state] = Math.max(1, Math.floor(total * share));
    assigned += counts[state];
  }
  counts.ACTIVE = total - assigned;
  return STATES.flatMap((state) => Array.from({ length: counts[state] }, () => state));
}

export function massPlan(params) {
  const products = [];
  const add = (role, state, skuCount, extra) => {
    const index = products.length;
    const count = skuCount ?? 1 + Math.floor(createRandom(hashSeed(params.seed, 'mass', index)).fraction() * VARIANTS.length);
    products.push({
      index,
      role,
      state,
      skuCount: count,
      leaf: index % (CATEGORY_CHILDREN * CATEGORY_GRANDCHILDREN),
      stock: stockOf(params, role, state),
      promotion: false,
      ...extra,
    });
  };
  const sizes = groupSizes(params);
  for (const group of SKU_GROUPS) {
    for (let left = sizes[group]; left > 0; left -= VARIANTS.length) add(group, 'ACTIVE', Math.min(VARIANTS.length, left));
  }
  for (let i = 0; i < params.moderationProducts; i += 1) add('moderation', 'PENDING_MODERATION');
  let active = 0;
  for (const state of populationStates(params.massProducts - products.length)) {
    // WHY: metade dos ACTIVE da população tem promoção a 30 dias, que nunca entra em vigor durante a execução.
    const promotion = state === 'ACTIVE' && active++ % 2 === 0;
    add('population', state, undefined, { promotion });
  }
  const byState = Object.fromEntries(STATES.map((s) => [s, 0]));
  const byRole = {};
  for (const p of products) {
    byState[p.state] += 1;
    byRole[p.role] = (byRole[p.role] || 0) + p.skuCount;
  }
  const skus = products.reduce((total, p) => total + p.skuCount, 0);
  return { products, byState, skusByRole: byRole, skus };
}

export function describeMass(params) {
  const plan = massPlan(params);
  const states = STATES.map((s) => `${s} ${plan.byState[s]}`).join(', ');
  const groups = SKU_GROUPS.map((g) => `${g} ${plan.skusByRole[g] || 0}`).join(', ');
  return [
    `  massa: ${plan.products.length} produtos (${states}), ${plan.skus} SKUs; ${params.moderationProducts} na moderação`,
    `  grupos de SKU: ${groups}; categorias: 1 raiz, ${CATEGORY_CHILDREN} filhas, ${CATEGORY_CHILDREN * CATEGORY_GRANDCHILDREN} netas e 2 pais de mudança`,
  ];
}
