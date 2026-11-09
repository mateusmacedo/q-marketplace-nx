import { formatInteger, formatMoney } from './money';

describe('moeda', () => {
  it('formata no padrão brasileiro com a moeda recebida', () => {
    expect(formatMoney(49.9, 'BRL')).toBe('R$ 49,90');
    expect(formatMoney('1234.5', 'BRL')).toBe('R$ 1.234,50');
    expect(formatMoney(10, 'USD')).toBe('US$ 10,00');
  });

  it('devolve texto vazio sem valor, com valor inválido ou sem moeda', () => {
    expect(formatMoney(undefined, 'BRL')).toBe('');
    expect(formatMoney('', 'BRL')).toBe('');
    expect(formatMoney('abc', 'BRL')).toBe('');
    expect(formatMoney(10, '')).toBe('');
  });

  it('formata inteiros com separador de milhar', () => {
    expect(formatInteger(1200)).toBe('1.200');
    expect(formatInteger('5000')).toBe('5.000');
    expect(formatInteger(null)).toBe('');
  });
});
