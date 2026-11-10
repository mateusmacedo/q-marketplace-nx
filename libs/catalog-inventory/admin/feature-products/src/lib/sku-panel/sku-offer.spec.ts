import type { PromotionResponse } from '@marketplace/catalog-inventory-admin-data-access';

import { belowReserved, listedPromotions, moneyOf, promotionPhaseLabel } from './sku-offer';

describe('oferta do SKU no painel', () => {
  it('lista só as vigentes e as agendadas pela fase do servidor, da que começa primeiro à última', () => {
    const promotions: PromotionResponse[] = [
      {
        id: 'futura',
        startsAt: '2026-11-20T03:00:00Z',
        endsAt: '2026-11-25T03:00:00Z',
        phase: 'SCHEDULED',
      },
      {
        id: 'encerrada',
        startsAt: '2026-11-01T03:00:00Z',
        endsAt: '2026-11-05T03:00:00Z',
        phase: 'ENDED',
      },
      {
        id: 'vigente',
        startsAt: '2026-11-09T03:00:00Z',
        endsAt: '2026-11-12T03:00:00Z',
        phase: 'ACTIVE',
      },
      { id: 'sem-fase', startsAt: '2026-11-08T03:00:00Z', endsAt: '2026-11-30T03:00:00Z' },
    ];

    expect(listedPromotions({ promotions }).map((item) => [item.promotion.id, item.phase])).toEqual(
      [
        ['vigente', 'ACTIVE'],
        ['futura', 'SCHEDULED'],
      ],
    );
    expect(listedPromotions(null)).toEqual([]);
    expect(promotionPhaseLabel('ACTIVE')).toBe('Vigente');
    expect(promotionPhaseLabel('SCHEDULED')).toBe('Agendada');
  });

  it('monta o dinheiro com a moeda do produto e avisa o físico abaixo do reservado', () => {
    expect(moneyOf(49.9, 'BRL')).toEqual({ amount: 49.9, currency: 'BRL' });
    expect(moneyOf(0, 'BRL')).toEqual({ amount: 0, currency: 'BRL' });
    expect(moneyOf(null, 'BRL')).toBeNull();
    expect(belowReserved(2, 3)).toBe(true);
    expect(belowReserved(3, 3)).toBe(false);
    expect(belowReserved(null, 3)).toBe(false);
  });
});
