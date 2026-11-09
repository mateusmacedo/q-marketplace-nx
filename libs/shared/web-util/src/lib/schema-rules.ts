/** Schema de objeto do contrato, como o plugin `@hey-api/schemas` exporta (`CreateProductRequestSchema`). */
export interface ObjectSchema {
  readonly required?: readonly string[];
  readonly properties?: Readonly<Record<string, unknown>>;
}

/**
 * Limites de um campo lidos do contrato (`required`, `pattern`, `maxLength` e os demais), para avisar antes do envio.
 * O 400 do servidor continua sendo a palavra final.
 */
export interface FieldRules {
  readonly required: boolean;
  readonly pattern?: string;
  readonly maxLength?: number;
  readonly minLength?: number;
  readonly maxItems?: number;
  readonly minItems?: number;
  readonly maximum?: number;
  readonly minimum?: number;
}

/** Limite que o valor desrespeita; `limit` é o número do contrato, quando a regra tem um. */
export interface RuleViolation {
  readonly kind: 'required' | 'pattern' | 'maxLength' | 'minLength' | 'maxItems' | 'minItems' | 'maximum' | 'minimum';
  readonly limit?: number;
}

const NUMERIC_KEYS = ['maxLength', 'minLength', 'maxItems', 'minItems', 'maximum', 'minimum'] as const;

/**
 * Limites da propriedade no schema do contrato. Lança erro quando a propriedade não existe no schema, para que um nome
 * errado apareça no teste da tela, e não como um campo sem limite.
 */
export function fieldRules(schema: ObjectSchema, property: string): FieldRules {
  const source = schema.properties?.[property];
  if (!isRecord(source)) {
    throw new Error(`O schema não tem a propriedade "${property}"`);
  }
  const rules: { -readonly [K in keyof FieldRules]: FieldRules[K] } = {
    required: schema.required?.includes(property) === true
  };
  if (typeof source['pattern'] === 'string') {
    rules.pattern = source['pattern'];
  }
  for (const key of NUMERIC_KEYS) {
    const value = source[key];
    if (typeof value === 'number') {
      rules[key] = value;
    }
  }
  return rules;
}

/** Primeiro limite que o valor desrespeita, ou `null`. Campo vazio e opcional não é conferido. */
export function checkFieldRules(rules: FieldRules, value: unknown): RuleViolation | null {
  if (isEmpty(value)) {
    return rules.required ? { kind: 'required' } : null;
  }
  if (typeof value === 'string') {
    return checkText(rules, value);
  }
  if (Array.isArray(value)) {
    return exceeds(value.length, rules.maxItems, rules.minItems, 'maxItems', 'minItems');
  }
  if (typeof value === 'number') {
    return exceeds(value, rules.maximum, rules.minimum, 'maximum', 'minimum');
  }
  return null;
}

/** Texto do limite desrespeitado, para mostrar embaixo do campo. */
export function ruleMessage(violation: RuleViolation): string {
  const limit = violation.limit === undefined ? '' : NUMBER_FORMAT.format(violation.limit);
  switch (violation.kind) {
    case 'required':
      return 'Preencha este campo.';
    case 'pattern':
      return 'Confira o formato deste campo.';
    case 'maxLength':
      return `Use no máximo ${limit} caracteres.`;
    case 'minLength':
      return `Use pelo menos ${limit} caracteres.`;
    case 'maxItems':
      return `Escolha no máximo ${limit}.`;
    case 'minItems':
      return `Escolha pelo menos ${limit}.`;
    case 'maximum':
      return `Use um valor de até ${limit}.`;
    case 'minimum':
      return `Use um valor a partir de ${limit}.`;
  }
}

const NUMBER_FORMAT = new Intl.NumberFormat('pt-BR');
const PATTERNS = new Map<string, RegExp | null>();

function checkText(rules: FieldRules, value: string): RuleViolation | null {
  const length = exceeds(value.length, rules.maxLength, rules.minLength, 'maxLength', 'minLength');
  if (length) {
    return length;
  }
  const pattern = rules.pattern === undefined ? null : compile(rules.pattern);
  return pattern && !pattern.test(value) ? { kind: 'pattern' } : null;
}

function exceeds(
  value: number,
  max: number | undefined,
  min: number | undefined,
  maxKind: RuleViolation['kind'],
  minKind: RuleViolation['kind']
): RuleViolation | null {
  if (max !== undefined && value > max) {
    return { kind: maxKind, limit: max };
  }
  if (min !== undefined && value < min) {
    return { kind: minKind, limit: min };
  }
  return null;
}

/**
 * WHY: o `pattern` do JSON Schema vale em qualquer parte do texto, sem âncoras. O `\S` dos campos obrigatórios só exige
 * um caractere visível; ancorar, como o `Validators.pattern` do Angular faz, recusaria todo texto com mais de um. O
 * padrão que o servidor confere no texto inteiro fica mais frouxo aqui, e o 400 decide.
 */
function compile(pattern: string): RegExp | null {
  if (!PATTERNS.has(pattern)) {
    let compiled: RegExp | null;
    try {
      compiled = new RegExp(pattern, 'u');
    } catch {
      compiled = null;
    }
    PATTERNS.set(pattern, compiled);
  }
  return PATTERNS.get(pattern) ?? null;
}

function isEmpty(value: unknown): boolean {
  return (
    value === null ||
    value === undefined ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
