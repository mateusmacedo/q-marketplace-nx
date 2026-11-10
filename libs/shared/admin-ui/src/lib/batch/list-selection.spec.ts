import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { abortOnDestroy, batchFailures } from './batch-run';
import { ListSelection } from './list-selection';

interface Product {
  readonly id: string;
  readonly title: string;
  readonly actions?: readonly string[];
  readonly version?: number;
}

const product = (id: string, actions: readonly string[] = [], version = 1): Product => ({
  id,
  title: `Produto ${id}`,
  actions,
  version,
});

describe('ListSelection', () => {
  const page1 = [
    product('p1', ['PAUSE', 'ARCHIVE']),
    product('p2', ['ARCHIVE']),
    product('p3', ['SUBMIT']),
  ];
  const page2 = [product('p4', ['PAUSE']), product('p5', ['RESUME'])];
  let selection: ListSelection<Product>;

  beforeEach(() => {
    selection = new ListSelection<Product>({ id: (item) => item.id });
    selection.setPage(page1, { status: 'ACTIVE', cursor: undefined });
  });

  it('marca a página pela caixa do cabeçalho, com estado parcial', () => {
    expect(selection.pageChecked()).toBe(false);
    expect(selection.pageIndeterminate()).toBe(false);

    selection.toggle(page1[0]);
    expect(selection.pageIndeterminate()).toBe(true);
    expect(selection.isSelected(page1[0])).toBe(true);

    selection.togglePage();
    expect(selection.pageChecked()).toBe(true);
    expect(selection.pageIndeterminate()).toBe(false);
    expect(selection.count()).toBe(3);

    selection.togglePage();
    expect(selection.count()).toBe(0);
  });

  it('mantém a seleção ao trocar de página e a limpa ao mudar o recorte', () => {
    selection.toggle(page1[0]);
    selection.setPage(page2, { status: 'ACTIVE', cursor: 'abc' });
    selection.toggle(page2[0]);
    expect(selection.items().map((item) => item.id)).toEqual(['p1', 'p4']);
    expect(selection.pageIndeterminate()).toBe(true);

    selection.setPage(page1, { status: 'ACTIVE', cursor: undefined });
    expect(selection.count()).toBe(2);

    selection.setPage(page1, { status: 'ACTIVE', limit: 50 });
    expect(selection.count()).toBe(0);
  });

  it('troca os selecionados pela versão recém-carregada da página', () => {
    selection.toggle(page1[0]);
    selection.setPage([product('p1', ['RESUME'], 2), page1[1]], { status: 'ACTIVE' });
    expect(selection.items()).toEqual([product('p1', ['RESUME'], 2)]);
  });

  it('conta as ações pelo actions de cada item e monta os botões da barra', () => {
    selection.togglePage();
    selection.setPage(page2, { status: 'ACTIVE', cursor: 'abc' });
    selection.toggle(page2[0]);

    expect(selection.countFor('ARCHIVE')).toBe(2);
    expect(selection.accepting('PAUSE').map((item) => item.id)).toEqual(['p1', 'p4']);
    expect(selection.countFor('UNKNOWN')).toBe(0);
    expect(
      selection.batchActions([
        { id: 'PAUSE', label: 'Pausar' },
        { id: 'RESUME', label: 'Reativar' },
        { id: 'ARCHIVE', label: 'Arquivar', color: 'danger' },
      ]),
    ).toEqual([
      { id: 'PAUSE', label: 'Pausar', count: 2 },
      { id: 'RESUME', label: 'Reativar', count: 0 },
      { id: 'ARCHIVE', label: 'Arquivar', color: 'danger', count: 2 },
    ]);
  });

  it('aceita a regra da tela sem actions, como Categorias', () => {
    const categories = new ListSelection<{ id: string; active: boolean }>({
      id: (item) => item.id,
      accepts: (item, action) => (action === 'ACTIVATE' ? !item.active : item.active),
    });
    categories.setPage([
      { id: 'c1', active: true },
      { id: 'c2', active: false },
    ]);
    categories.togglePage(true);
    expect(categories.countFor('ACTIVATE')).toBe(1);
    expect(categories.countFor('DEACTIVATE')).toBe(1);
  });

  it('tira os gravados no fim do lote e mantém as falhas', () => {
    selection.togglePage();
    selection.completeBatch({ succeeded: [{ item: page1[0] }, { item: page1[2] }] });
    expect(selection.items().map((item) => item.id)).toEqual(['p2']);

    selection.clear();
    expect(selection.count()).toBe(0);
  });

  it('tira pelo id o item decidido ou excluído fora do lote, mesmo fora da página', () => {
    selection.togglePage();
    selection.setPage(page1.slice(1), { status: 'ACTIVE' });
    selection.remove([page1[0].id, 'desconhecido']);

    expect(selection.items().map((item) => item.id)).toEqual(['p2', 'p3']);
  });
});

describe('resultado do lote na tela', () => {
  it('monta as falhas com o nome do item e a mensagem do servidor', () => {
    const failures = batchFailures(
      [
        {
          item: product('p1'),
          problem: {
            status: 422,
            reasonDetails: [
              { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada.' },
            ],
          },
        },
      ],
      (item) => item.id,
      (item) => item.title,
    );
    expect(failures).toEqual([
      { id: 'p1', label: 'Produto p1', messages: ['A loja ainda não foi aprovada.'] },
    ]);
  });

  it('aborta o sinal quando a tela é destruída', () => {
    @Component({ template: '' })
    class BatchScreen {
      readonly signal = abortOnDestroy();
    }
    const fixture = TestBed.createComponent(BatchScreen);
    const signal = fixture.componentInstance.signal;
    expect(signal.aborted).toBe(false);
    fixture.destroy();
    expect(signal.aborted).toBe(true);
  });
});
