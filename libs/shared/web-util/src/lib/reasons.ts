/**
 * Motivo de uma recusa como a API devolve em `reasonDetails` (ADR 008): o código estável, os valores do caso e o texto
 * em português montado pelo servidor.
 */
export interface ReasonDetail {
  /** Código estável, sem os parâmetros ("missing-offer"); serve à lógica, como escolher a aba ou o campo. */
  readonly code?: string;
  /** Valores do caso, na ordem do texto, como o SKU, o atributo ou o limite vigente. */
  readonly params?: readonly string[];
  readonly message?: string;
}

/** Textos de apoio do problema, usados quando o motivo não traz mensagem. */
export interface ReasonFallback {
  readonly detail?: string;
  readonly title?: string;
}

type Override = (params: readonly string[]) => string | undefined;

const HTTP_CODE = /^http-(\d{3})$/;

/** Último recurso, quando nem o servidor nem o front têm texto: o código cru nunca aparece. */
export const GENERIC_REASON_MESSAGE = 'Não foi possível concluir a operação.';

/**
 * Sobrescritas do front, só para os problemas que o próprio front cria, sem resposta do servidor. As demais mensagens
 * vêm do servidor.
 */
const OVERRIDES: Readonly<Record<string, Override>> = {
  'network-error': () =>
    'Não foi possível falar com o servidor. Confira a conexão e tente de novo.',
};

/** Separa o motivo compacto de `reasons` ("missing-variant-attribute:CAM-P:cor") em código e parâmetros. */
export function parseReason(reason: string): ReasonDetail {
  const [code, ...params] = reason.split(':');
  return { code, params };
}

/**
 * Texto do motivo, nesta ordem: a sobrescrita do front, a mensagem do servidor, o `detail` e o `title` do problema e,
 * sem nenhum deles, uma mensagem genérica. O código cru nunca aparece.
 */
export function translateReason(
  reason: ReasonDetail | string,
  fallback: ReasonFallback = {},
): string {
  const detail = typeof reason === 'string' ? parseReason(reason) : reason;
  return (
    override(detail) ??
    nonBlank(detail.message) ??
    nonBlank(fallback.detail) ??
    nonBlank(fallback.title) ??
    httpMessage(detail.code) ??
    GENERIC_REASON_MESSAGE
  );
}

function override(detail: ReasonDetail): string | undefined {
  const code = detail.code ?? '';
  return Object.prototype.hasOwnProperty.call(OVERRIDES, code)
    ? OVERRIDES[code](detail.params ?? [])
    : undefined;
}

function httpMessage(code: string | undefined): string | undefined {
  const http = HTTP_CODE.exec(code ?? '');
  return http ? `O servidor respondeu com o erro HTTP ${http[1]}.` : undefined;
}

function nonBlank(text: string | undefined): string | undefined {
  return text?.trim() ? text : undefined;
}
