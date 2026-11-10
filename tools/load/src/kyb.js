// Vereditos do KYB/KYC que o run.sh envia pelo Kafka durante a carga, para lojas sintéticas do tenant derivadas do
// TESTID. O k6 não fala Kafka: ele e o run.sh calculam o mesmo roteiro aqui, e o teardown confere a situação final.
import { hashSeed } from './lib/random.js';

export const VERDICTS = [
  { eventType: 'ReviewRequired', status: 'PENDING' },
  { eventType: 'SellerApproved', status: 'APPROVED' },
  { eventType: 'SellerRejected', status: 'REJECTED' },
];
// WHY: as rodadas terminam antes do fim da carga, para o teardown não ler a loja com um veredito ainda a caminho.
const ROUNDS_SHARE = 0.8;

function hex8(...parts) {
  return hashSeed(...parts)
    .toString(16)
    .padStart(8, '0');
}

export function kybStores(params) {
  return Array.from({ length: params.kybStores }, (_, s) => {
    const h = [0, 1, 2, 3].map((part) => hex8(params.testid, 'kyb', s, part)).join('');
    const variant = '89ab'[parseInt(h[16], 16) % 4];
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-${variant}${h.slice(17, 20)}-${h.slice(20, 32)}`;
  });
}

export function kybRounds(params, plan) {
  if (!params.scenarios.includes('kyb_verdicts')) return 0;
  return Math.max(1, Math.floor((ROUNDS_SHARE * plan.totalSeconds) / params.kybIntervalS));
}

const verdictOf = (store, round) => VERDICTS[(store + round) % VERDICTS.length];

export function kybSchedule(params, plan) {
  const stores = kybStores(params);
  return Array.from({ length: kybRounds(params, plan) }, (_, round) =>
    stores.map((storeId, s) => ({ storeId, eventType: verdictOf(s, round).eventType })),
  );
}

export function kybExpected(params, plan) {
  const rounds = kybRounds(params, plan);
  if (rounds === 0) return {};
  return Object.fromEntries(
    kybStores(params).map((storeId, s) => [storeId, verdictOf(s, rounds - 1).status]),
  );
}
