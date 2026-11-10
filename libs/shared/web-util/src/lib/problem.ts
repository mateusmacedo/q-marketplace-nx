import { parseReason, type ReasonDetail, translateReason } from './reasons';

/**
 * Campo inválido de um problema de validação. O contrato não garante nenhum dos dois: sem `field`, o erro vale para o
 * formulário inteiro; sem `message`, não há o que mostrar.
 */
export interface FieldError {
  readonly field?: string;
  readonly message?: string;
}

/** Corpo de erro RFC 9457 da API (ADR 008), com as extensões `errors`, `reasons` e `reasonDetails`. */
export interface Problem {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly errors?: readonly FieldError[];
  readonly reasons?: readonly string[];
  /** Motivos da recusa com o texto do servidor, um por item de `reasons`, na mesma ordem. */
  readonly reasonDetails?: readonly ReasonDetail[];
}

const NETWORK_ERROR = 'network-error';

/**
 * Normaliza o erro de uma chamada num problema RFC 9457. Aceita o corpo do problema, o erro do `HttpClient` (com
 * `status` e o corpo em `error`) e falhas sem corpo, que viram `network-error` (status 0) ou `http-<status>`.
 */
export function toProblem(error: unknown, status?: number): Problem {
  if (isProblem(error)) {
    return { ...error, status: error.status ?? status };
  }
  if (isRecord(error) && typeof error['status'] === 'number' && 'error' in error) {
    return toProblem(error['error'], error['status']);
  }
  if (status) {
    return { type: `http-${status}`, status };
  }
  return { type: NETWORK_ERROR, status: 0 };
}

/** Resultado de uma chamada do cliente gerado sem `throwOnError`: os dados ou, na falha, o erro e a resposta. */
export interface ApiResult<T> {
  readonly data?: T;
  readonly error?: unknown;
  readonly response?: unknown;
}

/**
 * Devolve os dados da chamada do cliente gerado ou lança o problema dela já normalizado (`toProblem`), com o status
 * da resposta. A falha é reconhecida pela chave `error`, que o cliente só põe no resultado quando a chamada falha.
 */
export async function apiData<T>(call: Promise<ApiResult<T>>): Promise<T> {
  const result = await call;
  if ('error' in result) {
    throw toProblem(result.response ?? result.error);
  }
  return result.data as T;
}

/** Indica se o valor tem a forma de um problema RFC 9457: `type` ou `title` em texto. */
export function isProblem(value: unknown): value is Problem {
  return (
    isRecord(value) && (typeof value['type'] === 'string' || typeof value['title'] === 'string')
  );
}

/** Código estável do problema, o fim do `type` ("https://marketplace.dev/problems/version-mismatch"). */
export function problemCode(problem: Problem): string {
  const type = problem.type ?? '';
  const code = type.slice(type.lastIndexOf('/') + 1);
  if (code) {
    return code;
  }
  return problem.status ? `http-${problem.status}` : NETWORK_ERROR;
}

/**
 * Mensagens para o alerta do problema, uma por motivo e sem repetir texto. Cada uma cai, nesta ordem, na sobrescrita
 * do front, na `message` de `reasonDetails`, no `detail` e no `title` (`translateReason`); o código cru nunca aparece.
 */
export function problemMessages(problem: Problem): string[] {
  const details = problemReasons(problem);
  const reasons = details.length ? details : [{ code: problemCode(problem) }];
  return [...new Set(reasons.map((reason) => translateReason(reason, problem)))];
}

/** Motivos da recusa: os de `reasonDetails` ou, sem eles, os de `reasons` separados em código e parâmetros. */
export function problemReasons(problem: Problem | null | undefined): readonly ReasonDetail[] {
  if (problem?.reasonDetails?.length) {
    return problem.reasonDetails;
  }
  return (problem?.reasons ?? []).map(parseReason);
}

/**
 * Primeira mensagem de cada campo inválido, para mostrar embaixo do campo. O erro sem campo fica na chave vazia, que
 * nenhum campo tem, e o erro sem mensagem fica de fora.
 */
export function fieldErrors(problem: Problem | null | undefined): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const { field = '', message } of problem?.errors ?? []) {
    if (message) {
      errors[field] ??= message;
    }
  }
  return errors;
}

/**
 * Indica conflito de edição: a versão lida ficou para trás (412 `version-mismatch`) ou outra gravação passou na
 * frente (409 `concurrent-update`). Os outros 409 são recusas de regra e vão para o alerta do problema.
 */
export function isEditConflict(problem: Problem | null | undefined): boolean {
  if (!problem) {
    return false;
  }
  const code = problemCode(problem);
  return problem.status === 412 || code === 'version-mismatch' || code === 'concurrent-update';
}

/** Indica que o servidor não respondeu: rede fora, proxy sem o serviço ou requisição cancelada. */
export function isNetworkError(problem: Problem | null | undefined): boolean {
  return !!problem && (problem.status === 0 || problemCode(problem) === NETWORK_ERROR);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
