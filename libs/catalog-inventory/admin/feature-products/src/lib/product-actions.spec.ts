import type { ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';

import {
  batchActionCounts,
  PRODUCT_ACTIONS,
  PRODUCT_BATCH_PRESENTATIONS,
  productActionPresentation,
  productActionPresentations,
  productActionState,
  productEditRules,
  productMenu,
  primaryProductAction
} from './product-actions';

type Source = Pick<ProductResponse, 'actions' | 'blockedActions' | 'saveReturnsToModeration'>;

const NOT_PUBLISHABLE = {
  action: 'SUBMIT',
  reason: { code: 'product-not-publishable', params: [], message: 'O produto ainda tem pendências para ser publicado.' }
};

const draft: Source = {
  actions: ['EDIT', 'CHANGE_CATEGORY', 'ADD_SKU', 'REMOVE_SKU', 'EDIT_OFFER', 'EDIT_STOCK', 'ARCHIVE'],
  blockedActions: [NOT_PUBLISHABLE],
  saveReturnsToModeration: false
};
const active: Source = {
  actions: ['EDIT', 'CHANGE_CATEGORY', 'ADD_SKU', 'EDIT_OFFER', 'EDIT_STOCK', 'PAUSE', 'ARCHIVE'],
  saveReturnsToModeration: true
};
const paused: Source = {
  actions: ['EDIT', 'ARCHIVE'],
  blockedActions: [{ action: 'RESUME', reason: { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada no KYB/KYC.' } }]
};
const blockedForModerator: Source = { actions: ['UNBLOCK', 'BLOCK_SOMETHING_NEW'] };

const codes = (items: { action: { code: string } }[]) => items.map(item => item.action.code);

describe('ações do produto vindas do servidor', () => {
  it('monta o menu da lista com o que o servidor liberou, na ordem da tabela e com Editar primeiro', () => {
    expect(codes(productMenu(active, 'list'))).toEqual(['EDIT', 'PAUSE', 'ARCHIVE']);
    expect(codes(productMenu({ actions: ['ARCHIVE', 'SUBMIT', 'EDIT'] }, 'list'))).toEqual(['EDIT', 'SUBMIT', 'ARCHIVE']);
    expect(productMenu({}, 'list')).toEqual([]);
  });

  it('mostra a ação barrada desabilitada, com o texto do servidor', () => {
    expect(productMenu(draft, 'detail')).toEqual([
      { action: PRODUCT_ACTIONS[1], blockedReason: 'O produto ainda tem pendências para ser publicado.' },
      { action: PRODUCT_ACTIONS[4] }
    ]);
    expect(productMenu(paused, 'list').map(item => [item.action.code, item.blockedReason])).toEqual([
      ['EDIT', undefined],
      ['RESUME', 'A loja ainda não foi aprovada no KYB/KYC.'],
      ['ARCHIVE', undefined]
    ]);
  });

  it('ignora o código que não conhece e deixa o Desbloquear só na tela do produto', () => {
    expect(codes(productMenu(blockedForModerator, 'detail'))).toEqual(['UNBLOCK']);
    expect(productMenu(blockedForModerator, 'list')).toEqual([]);
  });

  it('cai no texto genérico quando a ação barrada vem sem mensagem, sem mostrar o código', () => {
    const item = productMenu({ blockedActions: [{ action: 'PAUSE', reason: { code: 'nova-regra' } }] }, 'detail')[0];
    expect(item.blockedReason).toBe('Não foi possível concluir a operação.');
  });

  it('mostra como botão a primeira entre enviar, pausar e reativar que o servidor oferece', () => {
    expect(primaryProductAction(draft)).toEqual({
      action: PRODUCT_ACTIONS[1],
      blockedReason: 'O produto ainda tem pendências para ser publicado.'
    });
    expect(primaryProductAction(active)?.action.code).toBe('PAUSE');
    expect(primaryProductAction(paused)?.action.code).toBe('RESUME');
    expect(primaryProductAction(blockedForModerator)).toBeNull();
  });

  it('conta quantos selecionados o servidor libera para cada ação do lote', () => {
    const counts = batchActionCounts([draft, active, paused, { actions: ['SUBMIT', 'ARCHIVE'] }]);
    expect(counts.map(({ action, count }) => [action.code, count])).toEqual([
      ['SUBMIT', 1],
      ['PAUSE', 1],
      ['RESUME', 0],
      ['ARCHIVE', 4]
    ]);
  });

  it('pede confirmação só para arquivar', () => {
    expect(PRODUCT_ACTIONS.filter(action => action.confirmation).map(action => action.code)).toEqual(['ARCHIVE']);
  });

  it('põe na lista da plataforma só o Desbloquear, que o moderador recebe em actions', () => {
    expect(codes(productMenu(blockedForModerator, 'platform'))).toEqual(['UNBLOCK']);
    expect(productMenu(active, 'platform')).toEqual([]);
    expect(productActionPresentations('platform')).toEqual([
      { id: 'UNBLOCK', label: 'Desbloquear', icon: PRODUCT_ACTIONS[5].icon, danger: false }
    ]);
  });

  it('entrega ao menu da lista as apresentações na ordem da tabela, com arquivar em vermelho', () => {
    const presentations = productActionPresentations('list');
    expect(presentations.map(item => [item.id, item.danger])).toEqual([
      ['EDIT', false],
      ['SUBMIT', false],
      ['PAUSE', false],
      ['RESUME', false],
      ['ARCHIVE', true]
    ]);
  });

  it('dá à barra de lote os botões das ações do endpoint em lote, com o Enviar curto', () => {
    expect(PRODUCT_BATCH_PRESENTATIONS.map(item => [item.id, item.label, item.runningLabel, item.color])).toEqual([
      ['SUBMIT', 'Enviar', 'Enviando', 'primary'],
      ['PAUSE', 'Pausar', 'Pausando', 'primary'],
      ['RESUME', 'Reativar', 'Reativando', 'primary'],
      ['ARCHIVE', 'Arquivar', 'Arquivando', 'danger']
    ]);
  });

  it('acha a apresentação pelo código e ignora o que não conhece', () => {
    expect(productActionPresentation('PAUSE')?.label).toBe('Pausar');
    expect(productActionPresentation('APPROVE')).toBeUndefined();
  });
});

describe('regras da tela do produto vindas do servidor', () => {
  it('libera salvar, categoria, SKUs, preço e estoque pelo que veio em actions', () => {
    const rules = productEditRules(draft);
    expect([rules.save, rules.changeCategory, rules.addSku, rules.removeSku, rules.editOffer, rules.editStock]).toEqual(
      Array(6).fill({ allowed: true })
    );
    expect(productEditRules(active).removeSku).toEqual({ allowed: false });
    expect(productEditRules({}).save).toEqual({ allowed: false });
  });

  it('usa o aviso de volta à moderação e mostra o checklist quando o envio é oferecido', () => {
    expect(productEditRules(active).saveReturnsToModeration).toBe(true);
    expect(productEditRules(draft).saveReturnsToModeration).toBe(false);
    expect(productEditRules(draft).showChecklist).toBe(true);
    expect(productEditRules({ actions: ['SUBMIT'] }).showChecklist).toBe(true);
    expect(productEditRules(active).showChecklist).toBe(false);
  });

  it('traz o motivo do SKU barrado no limite', () => {
    const limit = { action: 'ADD_SKU', reason: { code: 'invalid-value', message: 'Produto aceita até 100 SKUs' } };
    expect(productActionState({ blockedActions: [limit] }, 'ADD_SKU')).toEqual({
      allowed: false,
      blockedReason: 'Produto aceita até 100 SKUs'
    });
  });
});
