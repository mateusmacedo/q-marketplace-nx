import type { EffectiveAttributeDto } from '@marketplace/catalog-inventory-admin-data-access';

import {
  attributeFields,
  attributeMap,
  displayAttributeValue,
  fromControlValue,
  knownAttributes,
  sameAttributes,
  toControlValue,
  variationLabel,
} from './product-attributes';

const EFFECTIVE: EffectiveAttributeDto[] = [
  {
    code: 'material',
    label: 'Material',
    type: 'OPTION',
    required: true,
    options: ['Algodão', 'Poliéster'],
  },
  { code: 'gramatura', label: 'Gramatura', type: 'INTEGER' },
  { code: 'peso', label: 'Peso', type: 'DECIMAL' },
  { code: 'organico', label: 'Orgânico', type: 'BOOLEAN' },
  { code: 'gola', label: 'Gola', type: 'TEXT' },
  {
    code: 'tamanho',
    label: 'Tamanho',
    type: 'OPTION',
    variant: true,
    required: true,
    options: ['P', 'M'],
  },
  { code: 'cor', label: 'Cor', type: 'TEXT', variant: true },
  { label: 'Sem código', type: 'TEXT' },
];

describe('atributos do produto pelo tipo do atributo efetivo', () => {
  it('separa os atributos do produto dos de variação, na ordem da categoria, e ignora o que vem sem código', () => {
    expect(
      attributeFields(EFFECTIVE, false).map((field) => [field.code, field.type, field.required]),
    ).toEqual([
      ['material', 'OPTION', true],
      ['gramatura', 'INTEGER', false],
      ['peso', 'DECIMAL', false],
      ['organico', 'BOOLEAN', false],
      ['gola', 'TEXT', false],
    ]);
    expect(attributeFields(EFFECTIVE, true).map((field) => field.code)).toEqual(['tamanho', 'cor']);
    expect(attributeFields(null, false)).toEqual([]);
  });

  it('converte o texto gravado para o controle de cada tipo e de volta para a API', () => {
    const [material, gramatura, peso, organico, gola] = attributeFields(EFFECTIVE, false);

    expect(toControlValue(gramatura, '180')).toBe(180);
    expect(toControlValue(peso, '0.25')).toBe(0.25);
    expect(toControlValue(organico, 'true')).toBe(true);
    expect(toControlValue(organico, 'false')).toBe(false);
    expect(toControlValue(organico, undefined)).toBeNull();
    expect(toControlValue(material, undefined)).toBe('');
    expect(toControlValue(gramatura, 'abc')).toBeNull();

    expect(fromControlValue(gramatura, 180)).toBe('180');
    expect(fromControlValue(peso, 0.25)).toBe('0.25');
    expect(fromControlValue(organico, false)).toBe('false');
    expect(fromControlValue(gola, '  ')).toBeUndefined();
    expect(fromControlValue(material, null)).toBeUndefined();
  });

  it('monta o mapa da API só com os campos preenchidos e compara com o gravado', () => {
    const fields = attributeFields(EFFECTIVE, false);
    const map = attributeMap(fields, {
      material: 'Algodão',
      gramatura: null,
      organico: true,
      gola: '',
      extra: 'x',
    });

    expect(map).toEqual({ material: 'Algodão', organico: 'true' });
    expect(
      knownAttributes(fields, { material: 'Algodão', organico: 'true', tamanho: 'P' }),
    ).toEqual(map);
    expect(sameAttributes(map, { organico: 'true', material: 'Algodão' })).toBe(true);
    expect(sameAttributes(map, { material: 'Algodão' })).toBe(false);
    expect(sameAttributes(map, { material: 'Poliéster', organico: 'true' })).toBe(false);
  });

  it('mostra a variação em uma linha, na ordem da categoria, com o ligado como texto', () => {
    const fields = [
      ...attributeFields(EFFECTIVE, true),
      ...attributeFields(
        [{ code: 'estampa', label: 'Estampa', type: 'BOOLEAN', variant: true }],
        true,
      ),
    ];

    expect(variationLabel(fields, { cor: 'Azul', tamanho: 'M', estampa: 'true' })).toBe(
      'M / Azul / Estampa: sim',
    );
    expect(variationLabel(fields, { antigo: 'X', tamanho: 'P' })).toBe('P / X');
    expect(variationLabel(fields, undefined)).toBe('');
    expect(displayAttributeValue(fields[2], 'false')).toBe('Estampa: não');
  });
});
