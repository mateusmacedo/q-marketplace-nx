import type { StoreStatsResponse } from '@marketplace/catalog-inventory-admin-data-access';

import { categoryIndicators, moderationIndicators, statusShares, storeIndicators } from './home-indicators';
import type { HomeLinks } from './home-links';

const ALL_LINKS: HomeLinks = {
  storeProducts: '/loja/produtos',
  tenantProducts: '/plataforma/produtos',
  moderationQueue: '/moderacao/fila',
  categories: '/plataforma/categorias'
};

const MONTHS = [
  { month: '2026-10-01', value: 4 },
  { month: '2026-11-01', value: 5 }
];

const STORE: StoreStatsResponse = {
  active: { value: 14, previous: null, change: null, months: MONTHS },
  draft: { value: 5, previous: 6, change: -1, months: MONTHS },
  outOfStock: { value: 1, months: [{ month: '2026-11-01', value: 1 }] },
  blocked: { value: 1, previous: 1, change: 0, months: [] }
};

describe('indicadores da página inicial', () => {
  it('monta os 4 cards da loja na ordem e nas cores do dashboard, com a variação só quando o servidor a manda', () => {
    const cards = storeIndicators(STORE, ALL_LINKS);

    expect(cards.map(card => [card.title, card.color, card.value, card.change])).toEqual([
      ['Produtos ativos', 'primary', 14, undefined],
      ['Em rascunho', 'info', 5, -1],
      ['Sem estoque', 'warning', 1, undefined],
      ['Bloqueados', 'danger', 1, 0]
    ]);
    expect(cards[0].months).toEqual(MONTHS);
    expect(cards[3].months).toBeUndefined();
  });

  it('leva o dropdown da loja à lista filtrada pelo status', () => {
    const [active, draft] = storeIndicators(STORE, ALL_LINKS);

    expect(active.links).toEqual([
      { label: 'Ver os ativos', url: '/loja/produtos', query: { status: 'ACTIVE' } },
      { label: 'Ver os pausados', url: '/loja/produtos', query: { status: 'PAUSED_BY_SELLER' } }
    ]);
    expect(draft.links).toEqual([{ label: 'Ver os rascunhos', url: '/loja/produtos', query: { status: 'DRAFT' } }]);
  });

  it('tira o atalho para a tela que a sessão não abre', () => {
    const cards = moderationIndicators(
      { queue: { value: 3 }, approved: { value: 2 }, rejected: { value: 1 }, blocked: { value: 0 } },
      { moderationQueue: '/moderacao/fila' }
    );

    expect(cards.map(card => card.title)).toEqual([
      'Na fila de moderação',
      'Aprovados no mês',
      'Rejeitados no mês',
      'Bloqueados no mês'
    ]);
    expect(cards.map(card => card.links.map(link => link.label))).toEqual([['Abrir a fila'], [], ['Abrir a fila'], []]);
  });

  it('mostra os números das categorias sem variação nem minigráfico', () => {
    const cards = categoryIndicators({ total: 12, roots: 3, inactive: 1, withProducts: 7 }, ALL_LINKS);

    expect(cards.map(card => [card.title, card.value, card.change, card.months])).toEqual([
      ['Categorias', 12, undefined, undefined],
      ['Categorias raiz', 3, undefined, undefined],
      ['Inativas', 1, undefined, undefined],
      ['Com produtos', 7, undefined, undefined]
    ]);
    expect(cards[3].links).toEqual([{ label: 'Ver os produtos', url: '/plataforma/produtos' }]);
  });

  it('tolera indicador ausente na resposta, com valor zero', () => {
    expect(storeIndicators({}, {}).map(card => card.value)).toEqual([0, 0, 0, 0]);
  });

  it('calcula a parte de cada status no total da loja, sem os arquivados', () => {
    const shares = statusShares({
      active: 14,
      draft: 5,
      pendingModeration: 1,
      blocked: 1,
      pausedBySeller: 4,
      outOfStock: 1,
      total: 26
    });

    expect(shares.map(share => [share.label, share.count, share.percent])).toEqual([
      ['Ativos', 14, 54],
      ['Em rascunho', 5, 19],
      ['Em moderação', 1, 4],
      ['Bloqueados', 1, 4],
      ['Pausados', 4, 15]
    ]);
    expect(statusShares({ total: 0 }).every(share => share.percent === 0)).toBe(true);
  });
});
