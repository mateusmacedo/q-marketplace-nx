import { apiData, type ApiResult } from './problem';

/** Cabeçalhos de uma resposta: o `HttpHeaders` do Angular e o `Headers` do fetch têm o mesmo `get`. */
export interface ResponseHeaders {
  get(name: string): string | null;
}

/** Dados de uma leitura ou gravação com a versão do registro, para o `If-Match` da próxima gravação. */
export interface Versioned<T> {
  readonly data: T;
  /**
   * Versão como ETag forte (`"3"`): a `version` do corpo ou, sem ela, o `ETag` forte da resposta. Ausente quando não
   * há nenhum dos dois; aí a gravação sai sem `If-Match` e o servidor responde 428 `precondition-required`.
   */
  readonly version: string | undefined;
}

/**
 * Como `apiData`, mas devolve também a versão do registro, para a tela gravar com `If-Match`. Serve à leitura (`GET`)
 * e à gravação, que responde com a versão nova. Na falha, lança o problema normalizado, como `apiData`.
 */
export async function apiDataWithVersion<T>(call: Promise<ApiResult<T>>): Promise<Versioned<T>> {
  const result = await call;
  const data = await apiData(Promise.resolve(result));
  // WHY: a `version` do corpo não muda no caminho; o cabeçalho pode chegar enfraquecido por um proxy que comprime a
  // resposta (o gzip do nginx troca "3" por W/"3"), e a API recusa If-Match fraco.
  return { data, version: bodyVersion(data) ?? responseVersion(result.response) };
}

/**
 * ETag forte de uma resposta do cliente gerado (o `response` do resultado) ou dos cabeçalhos de um `httpResource`
 * (`resource.headers()`). Devolve `undefined` sem o cabeçalho ou com ETag fraco (`W/"3"`), que a API recusa no
 * `If-Match`; prefira a `version` do corpo (`versionTag`).
 */
export function responseVersion(source: unknown): string | undefined {
  const value = headersOf(source)?.get('ETag')?.trim();
  return value && !value.startsWith('W/') ? value : undefined;
}

/**
 * ETag de uma versão que veio no corpo, como a `version` de um item da lista ou do resultado do lote, para o
 * `If-Match` de uma gravação feita sem abrir o registro.
 */
export function versionTag(version: number | string | null | undefined): string | undefined {
  if (version === null || version === undefined || version === '') {
    return undefined;
  }
  // NOTE: o contrato declara a `version` do corpo igual ao ETag forte da resposta, que é a versão entre aspas.
  return `"${version}"`;
}

/** Versão no corpo da resposta (`ProductResponse.version` e afins), como ETag forte. */
function bodyVersion(data: unknown): string | undefined {
  const version = isRecord(data) ? data['version'] : undefined;
  return typeof version === 'number' || typeof version === 'string'
    ? versionTag(version)
    : undefined;
}

function headersOf(source: unknown): ResponseHeaders | null {
  if (isHeaders(source)) {
    return source;
  }
  return isRecord(source) && isHeaders(source['headers']) ? source['headers'] : null;
}

function isHeaders(value: unknown): value is ResponseHeaders {
  return isRecord(value) && typeof value['get'] === 'function';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
