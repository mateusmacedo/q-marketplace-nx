import { CATALOG_API_URL } from './env';
import { accessToken } from './token';
import type { DemoUser } from './users';

/** Recusa RFC 9457 da API (`Problem` do contrato). */
export interface Problem {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly errors?: readonly { readonly field?: string; readonly message?: string }[];
  readonly reasons?: readonly string[];
  readonly reasonDetails?: readonly { readonly code?: string; readonly params?: readonly string[]; readonly message?: string }[];
}

export interface ApiResponse<T> {
  readonly status: number;
  readonly body: T;
  /** ETag da resposta, quando há; a mesma versão do campo `version` do corpo, entre aspas. */
  readonly etag: string | undefined;
}

/** Versão para o `If-Match`: o número do campo `version` do corpo ou o ETag forte como veio (`"3"`). */
export type IfMatch = number | string;

export interface RequestOptions {
  readonly body?: unknown;
  readonly ifMatch?: IfMatch;
  readonly headers?: Readonly<Record<string, string>>;
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

/** Resposta fora de 2xx, com o `Problem` do corpo e uma mensagem que já diz o que o servidor recusou. */
export class ApiError extends Error {
  constructor(
    readonly method: HttpMethod,
    readonly path: string,
    readonly user: DemoUser,
    readonly status: number,
    readonly problem: Problem | undefined,
    readonly rawBody: string
  ) {
    super(describeFailure(method, path, user, status, problem, rawBody));
    this.name = 'ApiError';
  }
}

/**
 * Cliente JSON da API do catálogo pelo gateway, autenticado como um usuário do realm. Os caminhos são os do contrato
 * (`contracts/catalog-inventory/openapi.json`), relativos à base da API: `api.get('/products/' + id)`.
 */
export class ApiClient {
  constructor(
    readonly user: DemoUser,
    private readonly baseUrl: string = CATALOG_API_URL
  ) {}

  /** Requisição com status e ETag da resposta; lança `ApiError` fora de 2xx. */
  async request<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = {
      Accept: 'application/json, application/problem+json',
      // NOTE: o gzip do gateway enfraquece o ETag das respostas grandes (W/"3"), e o If-Match recusa ETag fraco com 400.
      'Accept-Encoding': 'identity',
      Authorization: `Bearer ${await accessToken(this.user)}`,
      ...options.headers
    };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (options.ifMatch !== undefined) {
      headers['If-Match'] = typeof options.ifMatch === 'number' ? `"${options.ifMatch}"` : options.ifMatch;
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    });
    const text = await response.text();
    const body = parseJson(text);
    if (!response.ok) {
      const problem = typeof body === 'object' && body !== null ? (body as Problem) : undefined;
      throw new ApiError(method, path, this.user, response.status, problem, text);
    }
    return { status: response.status, body: body as T, etag: response.headers.get('ETag') ?? undefined };
  }

  async get<T>(path: string): Promise<T> {
    return (await this.request<T>('GET', path)).body;
  }

  async post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'body'> = {}): Promise<T> {
    return (await this.request<T>('POST', path, { ...options, body })).body;
  }

  async put<T>(path: string, body: unknown, options: Omit<RequestOptions, 'body'> = {}): Promise<T> {
    return (await this.request<T>('PUT', path, { ...options, body })).body;
  }

  async delete<T = undefined>(path: string, options: Omit<RequestOptions, 'body'> = {}): Promise<T> {
    return (await this.request<T>('DELETE', path, options)).body;
  }
}

const clients = new Map<DemoUser, ApiClient>();

/** Cliente da API autenticado como o usuário; o token fica em cache por processo. */
export function apiAs(user: DemoUser): ApiClient {
  let client = clients.get(user);
  if (!client) {
    client = new ApiClient(user);
    clients.set(user, client);
  }
  return client;
}

/**
 * Espera a recusa da chamada e devolve o `ApiError`, para o teste conferir o `Problem`; falha se a chamada passar.
 * Ex.: `const { status, problem } = await apiFailure(apiAs('seller-demo').post('/products', {}))`.
 */
export async function apiFailure(call: Promise<unknown>): Promise<ApiError> {
  try {
    await call;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('A chamada à API deveria ter sido recusada, mas passou.');
}

function parseJson(text: string): unknown {
  if (text === '') {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function describeFailure(
  method: HttpMethod,
  path: string,
  user: DemoUser,
  status: number,
  problem: Problem | undefined,
  rawBody: string
): string {
  const lines = [`${method} ${path} como ${user} respondeu ${status}`];
  if (problem) {
    lines.push(`  ${problem.title ?? ''}${problem.detail ? `: ${problem.detail}` : ''} (${problem.type ?? 'sem type'})`);
    for (const reason of problem.reasonDetails ?? []) {
      lines.push(`  motivo ${reason.code ?? '?'} [${(reason.params ?? []).join(', ')}]: ${reason.message ?? ''}`);
    }
    for (const error of problem.errors ?? []) {
      lines.push(`  campo ${error.field ?? '?'}: ${error.message ?? ''}`);
    }
  } else if (rawBody) {
    lines.push(`  ${rawBody.slice(0, 500)}`);
  }
  return lines.join('\n');
}
