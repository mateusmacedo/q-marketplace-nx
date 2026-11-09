import { fieldPath } from './fields';

describe('caminho do campo do 400', () => {
  it('separa propriedades, índices e chaves de mapa', () => {
    expect(fieldPath('title')).toEqual(['title']);
    expect(fieldPath('skus[0].sellerSku')).toEqual(['skus', '0', 'sellerSku']);
    expect(fieldPath('attributes[material]')).toEqual(['attributes', 'material']);
  });

  it('descarta os nós que a validação do servidor acrescenta', () => {
    expect(fieldPath('attributes[material].<map value>')).toEqual(['attributes', 'material']);
    expect(fieldPath('attributes<K>[cor].<map key>')).toEqual(['attributes', 'cor']);
    expect(fieldPath('')).toEqual([]);
  });
});
