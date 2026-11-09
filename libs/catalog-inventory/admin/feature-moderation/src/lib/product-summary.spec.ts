import type { EffectiveAttributeDto } from '@marketplace/catalog-inventory-admin-data-access';

import {
  attributeRows,
  categoryPath,
  historyEntries,
  sellerStatusLook,
  shortId,
  skusInStock,
  variantText
} from './product-summary';

const ATTRIBUTES: EffectiveAttributeDto[] = [
  { code: 'material', label: 'Material', type: 'OPTION' },
  { code: 'importado', label: 'Importado', type: 'BOOLEAN' },
  { code: 'tamanho', label: 'Tamanho', type: 'OPTION', variant: true },
  { code: 'cor', label: 'Cor', type: 'OPTION', variant: true }
];

describe('resumo do produto na fila', () => {
  it('mostra os atributos na ordem da categoria, com o rótulo e o booleano em texto', () => {
    expect(attributeRows({ importado: 'true', material: 'Algodão', antigo: 'x', vazio: '' }, ATTRIBUTES)).toEqual([
      { code: 'material', label: 'Material', value: 'Algodão' },
      { code: 'importado', label: 'Importado', value: 'Sim' },
      { code: 'antigo', label: 'antigo', value: 'x' }
    ]);
    expect(attributeRows(undefined, ATTRIBUTES)).toEqual([]);
    expect(attributeRows({ material: 'Linho' }, undefined)).toEqual([{ code: 'material', label: 'material', value: 'Linho' }]);
  });

  it('junta os valores da variação na ordem da categoria', () => {
    expect(variantText({ cor: 'Azul', tamanho: 'P' }, ATTRIBUTES)).toBe('P / Azul');
    expect(variantText({}, ATTRIBUTES)).toBe('');
  });

  it('monta o caminho da categoria com os ancestrais que o servidor devolve', () => {
    expect(categoryPath({ name: 'Camisas', ancestors: [{ id: 'a', name: 'Vestuário' }] })).toBe('Vestuário › Camisas');
    expect(categoryPath({ name: 'Vestuário', ancestors: [] })).toBe('Vestuário');
    expect(categoryPath(undefined)).toBe('');
  });

  it('encurta o identificador e conta os SKUs com saldo', () => {
    expect(shortId('3f2a91c0-0000-4000-8000-000000000001')).toBe('3f2a91c0…');
    expect(shortId(undefined)).toBe('');
    expect(skusInStock([{ stock: { available: 2 } }, { stock: { available: 0 } }, {}])).toBe(1);
  });

  it('lista o histórico do mais recente ao mais antigo, com os rótulos da época e o complemento separados', () => {
    const entries = historyEntries([
      { transition: 'CREATE', at: '2026-11-08T10:00:00Z', actorSide: 'STORE', byCaller: false },
      {
        transition: 'REJECT',
        at: '2026-11-08T12:00:00Z',
        actorSide: 'MODERATION',
        byCaller: true,
        reason: 'Categoria incorreta. A gola não bate.',
        reasonCodes: ['categoria-incorreta'],
        reasonLabels: [{ code: 'categoria-incorreta', label: 'Categoria incorreta' }],
        note: 'A gola não bate.'
      },
      { transition: 'SUBMIT', at: '2026-11-08T13:10:00Z', note: '  ' }
    ]);

    expect(entries.map(entry => entry.label)).toEqual(['Enviado para moderação', 'Rejeitado', 'Criado']);
    expect(entries[1]).toEqual({
      label: 'Rejeitado',
      at: '2026-11-08T12:00:00Z',
      actor: 'Você',
      reasons: ['Categoria incorreta'],
      note: 'A gola não bate.'
    });
    expect(entries[0].note).toBeUndefined();
    expect(entries[0].actor).toBeUndefined();
    expect(entries[2].actor).toBe('Loja');
    expect(historyEntries(undefined)).toEqual([]);
  });

  it('dá rótulo e cor à situação da loja e mostra como veio o status desconhecido', () => {
    expect(sellerStatusLook('APPROVED')).toEqual({ label: 'Aprovada', color: 'success' });
    expect(sellerStatusLook('PENDING').color).toBe('warning');
    expect(sellerStatusLook('SUSPENDED')).toEqual({ label: 'SUSPENDED', color: 'secondary' });
    expect(sellerStatusLook(undefined).label).toBe('Sem situação');
  });
});
