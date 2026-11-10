import { convertToParamMap } from '@angular/router';

import {
  DEFAULT_PRODUCT_SORT,
  emptyListMessage,
  productListRequest,
  type ProductListQuery,
  readProductListQuery,
  rejectionSummary,
  searchError,
  selectionScope,
  shortId,
  STATUS_TABS,
} from './product-list-query';

const query = (params: Record<string, string>) => readProductListQuery(convertToParamMap(params));

const BASE: ProductListQuery = {
  status: null,
  q: '',
  sort: DEFAULT_PRODUCT_SORT,
  limit: 20,
  cursor: null,
  storeId: null,
};

describe('recorte da lista de produtos na URL', () => {
  it('começa em Todos, sem busca, pelo Atualizado mais recente, com 20 itens', () => {
    expect(query({})).toEqual(BASE);
    expect(DEFAULT_PRODUCT_SORT).toBe('-updatedAt');
  });

  it('lê status, busca, ordem, tamanho, cursor e loja', () => {
    expect(
      query({
        status: 'DRAFT',
        q: '  camiseta ',
        sort: 'title',
        limit: '50',
        cursor: 'abc',
        storeId: ' 9f0c ',
      }),
    ).toEqual({
      status: 'DRAFT',
      q: 'camiseta',
      sort: 'title',
      limit: 50,
      cursor: 'abc',
      storeId: '9f0c',
    });
  });

  it('volta ao padrão quando o status, a ordem ou o tamanho não são conhecidos', () => {
    expect(query({ status: 'QUALQUER', sort: 'price', limit: '7' })).toEqual(BASE);
    expect(query({ sort: 'toString' }).sort).toBe(DEFAULT_PRODUCT_SORT);
  });

  it('manda à API só os parâmetros preenchidos', () => {
    expect(productListRequest(BASE)).toEqual({ sort: '-updatedAt', limit: 20 });
    expect(
      productListRequest({
        ...BASE,
        status: 'OUT_OF_STOCK',
        q: 'sku-1',
        cursor: 'c2',
        storeId: 's1',
        limit: 100,
      }),
    ).toEqual({
      sort: '-updatedAt',
      limit: 100,
      status: 'OUT_OF_STOCK',
      q: 'sku-1',
      cursor: 'c2',
      storeId: 's1',
    });
  });

  it('acompanha a seleção pelo recorte, sem o cursor', () => {
    expect(selectionScope({ ...BASE, cursor: 'c2' })).toEqual(selectionScope(BASE));
    expect(selectionScope({ ...BASE, limit: 50 })).not.toEqual(selectionScope(BASE));
  });

  it('mostra as abas na ordem dos status, com Todos primeiro e os rótulos no plural', () => {
    expect(STATUS_TABS.map((tab) => tab.label)).toEqual([
      'Todos',
      'Rascunho',
      'Em moderação',
      'Ativos',
      'Sem estoque',
      'Pausados',
      'Bloqueados',
      'Arquivados',
    ]);
  });
});

describe('textos da lista de produtos', () => {
  it('descreve o estado vazio pelo recorte', () => {
    expect(emptyListMessage(BASE)).toBe('Nenhum produto cadastrado.');
    expect(emptyListMessage({ ...BASE, status: 'DRAFT' })).toBe('Nenhum produto em rascunho.');
    expect(emptyListMessage({ ...BASE, status: 'ACTIVE', storeId: 's1' })).toBe(
      'Nenhum produto ativo nesta loja.',
    );
    expect(emptyListMessage({ ...BASE, storeId: 's1' })).toBe('Nenhum produto nesta loja.');
    expect(emptyListMessage({ ...BASE, status: 'DRAFT', q: 'caneca' })).toBe(
      'Nenhum produto encontrado para “caneca”.',
    );
  });

  it('monta o tooltip do Rejeitado com os rótulos da época e o complemento, sem os códigos', () => {
    expect(
      rejectionSummary({
        reasonCodes: ['wrong-category', 'price-out-of-range'],
        reasonLabels: [
          { code: 'wrong-category', label: 'Categoria incorreta' },
          { code: 'price-out-of-range', label: 'Preço fora do padrão' },
        ],
        note: ' Troque para Camisas ',
        reason: 'Categoria incorreta; Preço fora do padrão. Troque para Camisas',
      }),
    ).toBe('Motivos: Categoria incorreta, Preço fora do padrão. Complemento: Troque para Camisas');
    expect(
      rejectionSummary({
        reasonLabels: [{ code: 'wrong-category', label: 'Categoria incorreta' }],
      }),
    ).toBe('Motivo: Categoria incorreta');
    expect(rejectionSummary({ reasonCodes: ['codigo-sem-rotulo'], note: 'Foto borrada' })).toBe(
      'Complemento: Foto borrada',
    );
    expect(rejectionSummary({ reasonCodes: ['codigo-sem-rotulo'] })).toBe(
      'Rejeitado na moderação, sem motivo informado.',
    );
    expect(rejectionSummary(null)).toBeNull();
  });

  it('lê a recusa da busca só no 400 com erro no campo q', () => {
    expect(
      searchError({ status: 400, errors: [{ field: 'q', message: 'Busca longa demais.' }] }),
    ).toBe('Busca longa demais.');
    expect(
      searchError({ status: 400, errors: [{ field: 'limit', message: 'Limite inválido.' }] }),
    ).toBeNull();
    expect(searchError({ status: 503, errors: [{ field: 'q', message: 'x' }] })).toBeNull();
    expect(searchError(null)).toBeNull();
  });

  it('mostra o início do UUID da loja', () => {
    expect(shortId('3f2a9c1e-0b5d-4c7a-9e21-7d4c2b1a0f99')).toBe('3f2a9c1e…');
    expect(shortId(undefined)).toBe('');
  });
});
