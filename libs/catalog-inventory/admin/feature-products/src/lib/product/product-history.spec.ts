import type { StatusChangeResponse } from '@marketplace/catalog-inventory-admin-data-access';

import {
  decisionOf,
  lastTransition,
  moderationDecision,
  newestFirst,
  transitionLabel,
} from './product-history';

const HISTORY: StatusChangeResponse[] = [
  { transition: 'CREATE', to: 'DRAFT', at: '2026-11-01T10:00:00Z' },
  { transition: 'SUBMIT', from: 'DRAFT', to: 'PENDING_MODERATION', at: '2026-11-02T10:00:00Z' },
  {
    transition: 'BLOCK',
    from: 'ACTIVE',
    to: 'BLOCKED',
    reason: 'Item proibido: réplica',
    reasonCodes: ['prohibited-item'],
    reasonLabels: [{ code: 'prohibited-item', label: 'Item proibido' }],
    note: 'réplica',
    at: '2026-11-03T10:00:00Z',
  },
  { transition: 'UNBLOCK', from: 'BLOCKED', to: 'PENDING_MODERATION', at: '2026-11-04T10:00:00Z' },
  {
    transition: 'BLOCK',
    from: 'ACTIVE',
    to: 'BLOCKED',
    reasonCodes: ['price-out-of-range'],
    reasonLabels: [{ code: 'price-out-of-range', label: 'Preço fora do padrão' }],
    at: '2026-11-05T10:00:00Z',
  },
];

describe('histórico do produto', () => {
  it('dá o rótulo de cada transição e um genérico para a que o painel não conhece', () => {
    expect(transitionLabel('REJECT')).toBe('Rejeitado');
    expect(transitionLabel('REVIEW_REQUIRED')).toBe('Voltou para a moderação');
    expect(transitionLabel('MERGE')).toBe('Mudança de status');
    expect(transitionLabel(undefined)).toBe('Mudança de status');
  });

  it('separa os rótulos da época e o complemento só na rejeição e no bloqueio, sem usar o texto derivado', () => {
    expect(moderationDecision(HISTORY[2])).toEqual({ labels: ['Item proibido'], note: 'réplica' });
    expect(moderationDecision(HISTORY[1])).toBeNull();
    expect(decisionOf([{ code: 'sem-rotulo' }, { code: 'x', label: ' ' }], '  ')).toEqual({
      labels: [],
      note: null,
    });
  });

  it('acha o último bloqueio e põe o histórico da mais recente para a mais antiga', () => {
    expect(lastTransition(HISTORY, 'BLOCK')?.at).toBe('2026-11-05T10:00:00Z');
    expect(lastTransition(HISTORY, 'ARCHIVE')).toBeUndefined();
    expect(newestFirst(HISTORY).map((change) => change.transition)).toEqual([
      'BLOCK',
      'UNBLOCK',
      'BLOCK',
      'SUBMIT',
      'CREATE',
    ]);
    expect(HISTORY[0].transition).toBe('CREATE');
  });
});
