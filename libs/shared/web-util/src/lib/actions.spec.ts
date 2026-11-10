import { actionState, allowsAction } from './actions';
import { GENERIC_REASON_MESSAGE } from './reasons';

describe('ações calculadas pelo servidor', () => {
  const product = {
    actions: ['EDIT', 'ARCHIVE'],
    blockedActions: [
      {
        action: 'RESUME',
        reason: { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada.' },
      },
      { action: 'REMOVE_SKU' },
    ],
  };

  it('libera a ação que vem em actions', () => {
    expect(actionState(product, 'ARCHIVE')).toEqual({ allowed: true });
    expect(allowsAction(product, 'ARCHIVE')).toBe(true);
  });

  it('barra a ação de blockedActions com o texto do servidor, nunca o código', () => {
    expect(actionState(product, 'RESUME')).toEqual({
      allowed: false,
      blockedReason: 'A loja ainda não foi aprovada.',
    });
    expect(actionState(product, 'REMOVE_SKU')).toEqual({
      allowed: false,
      blockedReason: GENERIC_REASON_MESSAGE,
    });
    expect(allowsAction(product, 'RESUME')).toBe(false);
  });

  it('esconde a ação ausente das duas listas e tolera o registro sem ações', () => {
    expect(actionState(product, 'PAUSE')).toEqual({ allowed: false });
    expect(actionState(undefined, 'PAUSE')).toEqual({ allowed: false });
    expect(actionState({ actions: null, blockedActions: null }, 'PAUSE')).toEqual({
      allowed: false,
    });
    expect(allowsAction(null, 'PAUSE')).toBe(false);
  });
});
