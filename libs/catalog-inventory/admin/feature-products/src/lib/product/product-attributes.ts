import type {
  AttributeType,
  EffectiveAttributeDto,
} from '@marketplace/catalog-inventory-admin-data-access';

/** Valor de um atributo no formulário: texto, número, ligado ou desligado, ou vazio. */
export type AttributeValue = string | number | boolean | null;

/** Atributo efetivo da categoria como campo do formulário, montado pelo tipo que o servidor declara. */
export interface AttributeField {
  readonly code: string;
  readonly label: string;
  readonly type: AttributeType;
  /** Exigido para enviar à moderação; o formulário marca o campo, e o checklist mostra a pendência. */
  readonly required: boolean;
  /** Opções do atributo `OPTION`. */
  readonly options: readonly string[];
}

/**
 * Campos dos atributos efetivos da categoria, na ordem que o servidor devolve: os do produto (`variant` falso) ou os de
 * variação, que vão para cada SKU.
 */
export function attributeFields(
  attributes: readonly EffectiveAttributeDto[] | null | undefined,
  variant: boolean,
): AttributeField[] {
  return (attributes ?? [])
    .filter((attribute) => !!attribute.code && (attribute.variant === true) === variant)
    .map((attribute) => ({
      code: attribute.code as string,
      label: attribute.label || (attribute.code as string),
      type: attribute.type ?? 'TEXT',
      required: attribute.required === true,
      options: attribute.options ?? [],
    }));
}

/** Valor gravado (sempre texto na API) convertido para o controle do campo. */
export function toControlValue(
  field: AttributeField,
  raw: string | null | undefined,
): AttributeValue {
  if (raw === null || raw === undefined || raw === '') {
    return field.type === 'TEXT' || field.type === 'OPTION' ? '' : null;
  }
  switch (field.type) {
    case 'INTEGER':
    case 'DECIMAL': {
      const number = Number(raw);
      return Number.isFinite(number) ? number : null;
    }
    case 'BOOLEAN':
      if (raw === 'true') {
        return true;
      }
      return raw === 'false' ? false : null;
    default:
      return raw;
  }
}

/** Valor do controle no texto que a API recebe; vazio fica de fora do mapa. */
export function fromControlValue(
  field: AttributeField,
  value: AttributeValue | undefined,
): string | undefined {
  if (value === null || value === undefined) {
    return undefined;
  }
  if (typeof value === 'boolean') {
    return value ? 'true' : 'false';
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? String(value) : undefined;
  }
  return value.trim() === '' ? undefined : value;
}

/** Mapa de atributos para a API a partir dos controles, só com os campos preenchidos. */
export function attributeMap(
  fields: readonly AttributeField[],
  values: Readonly<Record<string, AttributeValue | undefined>> | null | undefined,
): Record<string, string> {
  const map: Record<string, string> = {};
  for (const field of fields) {
    const value = fromControlValue(field, values?.[field.code]);
    if (value !== undefined) {
      map[field.code] = value;
    }
  }
  return map;
}

/** Os atributos gravados que os campos conhecem, sem os vazios, para comparar com o formulário. */
export function knownAttributes(
  fields: readonly AttributeField[],
  attributes: Readonly<Record<string, string>> | null | undefined,
): Record<string, string> {
  const map: Record<string, string> = {};
  for (const field of fields) {
    const value = attributes?.[field.code];
    if (value !== undefined && value !== '') {
      map[field.code] = value;
    }
  }
  return map;
}

/** Indica se os dois mapas têm os mesmos atributos com os mesmos valores. */
export function sameAttributes(
  left: Readonly<Record<string, string>> | null | undefined,
  right: Readonly<Record<string, string>> | null | undefined,
): boolean {
  const a = Object.entries(left ?? {}).filter(([, value]) => value !== '');
  const b = Object.entries(right ?? {}).filter(([, value]) => value !== '');
  return a.length === b.length && a.every(([code, value]) => right?.[code] === value);
}

/** Valor do atributo para leitura: o ligado e o desligado viram "Sim" e "Não". */
export function displayAttributeValue(
  field: AttributeField | undefined,
  raw: string | null | undefined,
): string {
  if (raw === null || raw === undefined) {
    return '';
  }
  if (field?.type === 'BOOLEAN') {
    if (raw === 'true') {
      return `${field.label}: sim`;
    }
    if (raw === 'false') {
      return `${field.label}: não`;
    }
  }
  return raw;
}

/**
 * Variação do SKU em uma linha ("M / Azul"), na ordem dos atributos de variação da categoria e, depois, os que ela não
 * conhece mais.
 */
export function variationLabel(
  fields: readonly AttributeField[],
  variantAttributes: Readonly<Record<string, string>> | null | undefined,
): string {
  const values = { ...variantAttributes };
  const parts: string[] = [];
  for (const field of fields) {
    if (values[field.code] !== undefined) {
      parts.push(displayAttributeValue(field, values[field.code]));
      delete values[field.code];
    }
  }
  parts.push(...Object.values(values));
  return parts.filter(Boolean).join(' / ');
}
