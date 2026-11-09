import { checkFieldRules, fieldRules, ruleMessage } from './schema-rules';

/** Recorte de schemas como o plugin `@hey-api/schemas` exporta do contrato. */
const reasonSchema = {
  type: 'object',
  required: ['code', 'label', 'decisions'],
  properties: {
    code: { type: 'string', pattern: '^[a-z]+(-[a-z]+)*$', maxLength: 60 },
    label: { type: 'string', pattern: '\\S', maxLength: 120 },
    decisions: { type: 'array', items: { $ref: '#/components/schemas/ModerationDecision' }, minItems: 1, maxItems: 2 },
    position: { type: ['integer', 'null'], format: 'int32', minimum: 0, maximum: 9999 }
  }
} as const;

describe('limites do contrato', () => {
  it('lê os limites e a obrigatoriedade da propriedade', () => {
    expect(fieldRules(reasonSchema, 'code')).toEqual({ required: true, pattern: '^[a-z]+(-[a-z]+)*$', maxLength: 60 });
    expect(fieldRules(reasonSchema, 'decisions')).toEqual({ required: true, minItems: 1, maxItems: 2 });
    expect(fieldRules(reasonSchema, 'position')).toEqual({ required: false, minimum: 0, maximum: 9999 });
  });

  it('acusa a propriedade que o schema não tem', () => {
    expect(() => fieldRules(reasonSchema, 'note')).toThrow('O schema não tem a propriedade "note"');
  });

  it('confere obrigatório, tamanho e formato', () => {
    const label = fieldRules(reasonSchema, 'label');
    expect(checkFieldRules(label, '')).toEqual({ kind: 'required' });
    expect(checkFieldRules(label, '   ')).toEqual({ kind: 'required' });
    expect(checkFieldRules(label, 'Categoria incorreta')).toBeNull();
    expect(checkFieldRules(label, 'x'.repeat(121))).toEqual({ kind: 'maxLength', limit: 120 });

    const code = fieldRules(reasonSchema, 'code');
    expect(checkFieldRules(code, 'wrong-category')).toBeNull();
    expect(checkFieldRules(code, 'Wrong category')).toEqual({ kind: 'pattern' });
  });

  it('confere o padrão sem âncoras, como o JSON Schema', () => {
    expect(checkFieldRules({ required: false, pattern: '[A-Z]{3}' }, 'BRL')).toBeNull();
    expect(checkFieldRules({ required: false, pattern: '[A-Z]{3}' }, 'brl')).toEqual({ kind: 'pattern' });
    expect(checkFieldRules({ required: false, pattern: '(' }, 'qualquer')).toBeNull();
  });

  it('confere quantidade de itens e faixa numérica, e pula o campo opcional vazio', () => {
    const decisions = fieldRules(reasonSchema, 'decisions');
    expect(checkFieldRules(decisions, [])).toEqual({ kind: 'required' });
    expect(checkFieldRules(decisions, ['REJECT', 'BLOCK', 'REJECT'])).toEqual({ kind: 'maxItems', limit: 2 });

    const position = fieldRules(reasonSchema, 'position');
    expect(checkFieldRules(position, null)).toBeNull();
    expect(checkFieldRules(position, 10)).toBeNull();
    expect(checkFieldRules(position, -1)).toEqual({ kind: 'minimum', limit: 0 });
    expect(checkFieldRules(position, 10000)).toEqual({ kind: 'maximum', limit: 9999 });
    expect(checkFieldRules({ required: false, minItems: 2 }, ['a'])).toEqual({ kind: 'minItems', limit: 2 });
    expect(checkFieldRules({ required: false, minLength: 3 }, 'ab')).toEqual({ kind: 'minLength', limit: 3 });
  });

  it('escreve a mensagem com o limite do contrato', () => {
    expect(ruleMessage({ kind: 'required' })).toBe('Preencha este campo.');
    expect(ruleMessage({ kind: 'pattern' })).toBe('Confira o formato deste campo.');
    expect(ruleMessage({ kind: 'maxLength', limit: 5000 })).toBe('Use no máximo 5.000 caracteres.');
    expect(ruleMessage({ kind: 'minLength', limit: 3 })).toBe('Use pelo menos 3 caracteres.');
    expect(ruleMessage({ kind: 'maxItems', limit: 2 })).toBe('Escolha no máximo 2.');
    expect(ruleMessage({ kind: 'minItems', limit: 1 })).toBe('Escolha pelo menos 1.');
    expect(ruleMessage({ kind: 'maximum', limit: 1000000 })).toBe('Use um valor de até 1.000.000.');
    expect(ruleMessage({ kind: 'minimum', limit: 0 })).toBe('Use um valor a partir de 0.');
  });
});
