import http from 'k6/http';
import { params } from '../config.js';
import { bearer } from './identity.js';
import { serverErrors, unexpectedResponses } from './metrics.js';

export const EXPECT_RESERVE = http.expectedStatuses(201, 409);
export const EXPECT_OK = http.expectedStatuses(200);
export const EXPECT_CREATED = http.expectedStatuses(201);
export const EXPECT_ACCEPTED = http.expectedStatuses(202);
export const EXPECT_VISIBLE_OR_HIDDEN = http.expectedStatuses(200, 404);
export const EXPECT_FORBIDDEN = http.expectedStatuses(403);
// WHY: sem prazo, uma requisição presa segura o VU por 60 s, o padrão do k6, e mascara a saturação como descarte.
const TIMEOUT = '10s';
let warnings = 0;

// Requisição ao catálogo no formato do http.batch; role nulo vai sem token, como na vitrine anônima. Corpo em texto vai
// como está, com o Content-Type de extraHeaders.
export function apiRequest(
  data,
  role,
  method,
  path,
  body,
  { name, expected, headers: extraHeaders },
) {
  const headers = { 'X-Correlation-Id': `carga-${data.run}` };
  if (role !== null) headers.Authorization = `Bearer ${bearer(data, role)}`;
  if (body !== null && typeof body !== 'string') headers['Content-Type'] = 'application/json';
  Object.assign(headers, extraHeaders || {});
  const payload = body === null || typeof body === 'string' ? body : JSON.stringify(body);
  return [
    method,
    `${params.baseUrl}${path}`,
    payload,
    { headers, tags: { kind: 'api', name }, responseCallback: expected, timeout: TIMEOUT },
  ];
}

export function api(...args) {
  const [method, url, payload, requestParams] = apiRequest(...args);
  return http.request(method, url, payload, requestParams);
}

export function apiBatch(requests) {
  return http.batch(requests);
}

export function problemCode(res) {
  const type = jsonOf(res, 'type');
  return typeof type === 'string' ? type.slice(type.lastIndexOf('/') + 1) : null;
}

export function etagOf(res) {
  return res.headers.Etag || res.headers.ETag || null;
}

export function jsonOf(res, path) {
  try {
    return path === undefined ? res.json() : res.json(path);
  } catch (e) {
    return null;
  }
}

export function record(res, expected) {
  serverErrors.add(res.status === 0 || res.status >= 500);
  unexpectedResponses.add(!expected);
  if (!expected && warnings < 3) {
    warnings += 1;
    console.warn(
      `resposta inesperada em ${res.request.method} ${res.url}: HTTP ${res.status} ${String(res.body).slice(0, 300)}`,
    );
  }
}

function setupRequest(token, method, path, body, what, extraHeaders) {
  const headers = token === null ? {} : { Authorization: `Bearer ${token}` };
  if (body !== null) headers['Content-Type'] = 'application/json';
  Object.assign(headers, extraHeaders || {});
  return [
    method,
    `${params.baseUrl}${path}`,
    body === null ? null : JSON.stringify(body),
    { headers, tags: { kind: 'setup', name: what } },
  ];
}

function requireStatus(res, what, expected) {
  if (!(expected || [200, 201]).includes(res.status)) {
    throw new Error(`${what}: HTTP ${res.status} ${String(res.body).slice(0, 300)}`);
  }
  return res;
}

export function setupCall(token, method, path, body, what, expected) {
  const [m, url, payload, requestParams] = setupRequest(token, method, path, body, what);
  return requireStatus(http.request(m, url, payload, requestParams), what, expected);
}

const SETUP_CHUNK = 20;

// Passos independentes do preparo e do teardown em lotes paralelos de até SETUP_CHUNK; cada chamada traz token (nulo
// para anônimo), method, path, body, what, expected e headers opcionais.
export function setupBatch(calls) {
  const responses = [];
  for (let i = 0; i < calls.length; i += SETUP_CHUNK) {
    const chunk = calls.slice(i, i + SETUP_CHUNK);
    const results = http.batch(
      chunk.map((c) => setupRequest(c.token, c.method, c.path, c.body ?? null, c.what, c.headers)),
    );
    results.forEach((res, j) => requireStatus(res, chunk[j].what, chunk[j].expected));
    responses.push(...results);
  }
  return responses;
}
