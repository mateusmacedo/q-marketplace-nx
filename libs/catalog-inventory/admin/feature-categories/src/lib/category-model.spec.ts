import type { CategoryResponse, CategorySummary } from '@marketplace/catalog-inventory-admin-data-access';
import { batchSummary } from '@marketplace/shared-web-util';

import {
  acceptsCategoryAction,
  CATEGORY_BATCH_VERBS,
  attributeTypeLabel,
  canDelete,
  categoryBatchSize,
  categoryFieldLabel,
  categoryPath,
  categoryTrail,
  inheritedAttributeRows,
  isCategoryBatchAction,
  loadAllPages,
  ownAttributeRows,
  parentPath,
  resourceProblem,
  toCategoryNode
} from './category-model';

const SHIRTS: CategoryResponse = {
  id: 'c-cam',
  parentId: 'c-vest',
  code: 'VEST-CAM',
  name: 'Camisas',
  active: true,
  usable: true,
  version: 3,
  level: 2,
  childCount: 0,
  ancestors: [{ id: 'c-vest', code: 'VEST', name: 'Vestuário' }],
  attributes: [{ code: 'gola', label: 'Gola', type: 'OPTION', options: ['V', 'Redonda'] }],
  effectiveAttributes: [
    { code: 'material', label: 'Material', type: 'TEXT', required: true, categoryId: 'c-vest' },
    { code: 'gola', label: 'Gola', type: 'OPTION', options: ['V', 'Redonda'], categoryId: 'c-cam' }
  ]
};

describe('apoio da tela de categorias', () => {
  it('monta o caminho com os ancestrais que o servidor devolve', () => {
    expect(categoryTrail(SHIRTS)).toEqual([
      { id: 'c-vest', name: 'Vestuário' },
      { id: 'c-cam', name: 'Camisas' }
    ]);
    expect(categoryPath(SHIRTS)).toBe('Vestuário › Camisas');
    expect(parentPath(SHIRTS)).toBe('Vestuário');
    expect(parentPath({ ancestors: [] })).toBeNull();
  });

  it('separa os atributos herdados, com a categoria de origem, dos próprios', () => {
    expect(ownAttributeRows(SHIRTS)).toEqual([
      { code: 'gola', label: 'Gola', type: 'OPTION', required: false, variant: false, options: ['V', 'Redonda'] }
    ]);
    expect(inheritedAttributeRows(SHIRTS)).toEqual([
      { code: 'material', label: 'Material', type: 'TEXT', required: true, variant: false, options: [], origin: 'Vestuário' }
    ]);
  });

  it('mostra o rótulo do tipo e cai no valor do contrato quando não conhece o tipo', () => {
    expect(attributeTypeLabel('BOOLEAN')).toBe('Sim ou não');
    expect(attributeTypeLabel('COLOR')).toBe('COLOR');
    expect(attributeTypeLabel(undefined)).toBe('');
  });

  it('conta para o lote as inativas em Ativar, as ativas em Desativar e todas em Excluir', () => {
    const active: CategorySummary = { id: 'a', active: true, childCount: 2 };
    const inactive: CategorySummary = { id: 'b', active: false, childCount: 0 };
    expect(acceptsCategoryAction(active, 'ACTIVATE')).toBe(false);
    expect(acceptsCategoryAction(inactive, 'ACTIVATE')).toBe(true);
    expect(acceptsCategoryAction(active, 'DEACTIVATE')).toBe(true);
    expect(acceptsCategoryAction(inactive, 'DEACTIVATE')).toBe(false);
    expect(acceptsCategoryAction(active, 'DELETE')).toBe(true);
    expect(acceptsCategoryAction(inactive, 'DELETE')).toBe(true);
    expect(acceptsCategoryAction(inactive, 'ARCHIVE')).toBe(false);
    expect(canDelete({ childCount: 0 })).toBe(true);
    expect(canDelete({ childCount: 1 })).toBe(false);
    expect(isCategoryBatchAction('DELETE')).toBe(true);
    expect(isCategoryBatchAction('toString')).toBe(false);
  });

  it('resume o lote no feminino, como a categoria', () => {
    expect(batchSummary({ succeeded: 1, failed: 0, ignored: 1 }, CATEGORY_BATCH_VERBS.DELETE)).toBe(
      '1 excluída, 1 ignorada'
    );
    expect(batchSummary({ succeeded: 2, failed: 0, ignored: 3 }, CATEGORY_BATCH_VERBS.DEACTIVATE)).toBe(
      '2 desativadas, 3 ignoradas'
    );
  });

  it('lê o tamanho do grupo do lote no contrato', () => {
    expect(categoryBatchSize()).toBeGreaterThan(0);
  });

  it('segue o cursor até a última página', async () => {
    const pages: Record<string, { items: CategorySummary[]; nextCursor?: string }> = {
      first: { items: [{ id: 'a' }], nextCursor: 'k1' },
      k1: { items: [{ id: 'b' }], nextCursor: 'k2' },
      k2: { items: [{ id: 'c' }] }
    };
    const asked: (string | undefined)[] = [];
    const items = await loadAllPages(async cursor => {
      asked.push(cursor);
      return pages[cursor ?? 'first'];
    });
    expect(items.map(item => item.id)).toEqual(['a', 'b', 'c']);
    expect(asked).toEqual([undefined, 'k1', 'k2']);
  });

  it('passa a categoria da lista ao seletor de admin/ui com os campos calculados', () => {
    expect(toCategoryNode({ id: 'c-cam', name: 'Camisas', ancestors: [{ id: 'c-vest', name: 'Vestuário' }], level: 2, usable: false, childCount: 1 })).toEqual({
      id: 'c-cam',
      name: 'Camisas',
      ancestors: [{ id: 'c-vest', name: 'Vestuário' }],
      level: 2,
      usable: false,
      childCount: 1
    });
  });

  it('nomeia os campos do 400 que não têm controle no formulário', () => {
    expect(categoryFieldLabel('attributes[1].options[0]')).toBe('Atributo 2, opções');
    expect(categoryFieldLabel('attributes[0]')).toBe('Atributo 1');
    expect(categoryFieldLabel('attributes')).toBe('Atributos');
    expect(categoryFieldLabel('outro')).toBe('outro');
  });

  it('recupera o problema que o resource embrulhou', () => {
    const problem = { type: 'https://marketplace.dev/problems/not-found', title: 'Não encontrado', status: 404 };
    expect(resourceProblem(Object.assign(new Error('embrulho'), { cause: problem }))).toEqual(problem);
    expect(resourceProblem(undefined)).toBeNull();
    expect(resourceProblem(new Error('falha'))?.status).toBe(0);
  });
});
