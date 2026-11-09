const LOCALE = 'pt-BR';

const currencyFormats = new Map<string, Intl.NumberFormat>();
const integerFormat = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });

/**
 * Formata o valor na moeda recebida, no padrão brasileiro ("R$ 49,90"). Aceita número ou o texto decimal exato da
 * API ("49.90"); valor ausente ou inválido vira texto vazio.
 */
export function formatMoney(amount: number | string | null | undefined, currency: string): string {
  const value = toNumber(amount);
  if (value === undefined || !currency) {
    return '';
  }
  return currencyFormat(currency).format(value);
}

/** Formata um inteiro com separador de milhar ("1.200"); valor ausente ou inválido vira texto vazio. */
export function formatInteger(value: number | string | null | undefined): string {
  const number = toNumber(value);
  return number === undefined ? '' : integerFormat.format(number);
}

function currencyFormat(currency: string): Intl.NumberFormat {
  let format = currencyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat(LOCALE, { style: 'currency', currency });
    currencyFormats.set(currency, format);
  }
  return format;
}

function toNumber(value: number | string | null | undefined): number | undefined {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const number = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(number) ? number : undefined;
}
