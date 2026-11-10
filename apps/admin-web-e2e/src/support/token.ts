import { E2E_ENV, KEYCLOAK_REALM_URL } from './env';
import type { DemoUser } from './users';

interface CachedToken {
  readonly value: string;
  readonly expiresAt: number;
}

/** Margem para não mandar um token que vence no meio da requisição. */
const EXPIRY_MARGIN_MS = 30_000;

const cache = new Map<DemoUser, Promise<CachedToken>>();

/**
 * Access token do usuário pelo password grant do cliente `marketplace-cli`, com cache por processo até perto do
 * vencimento (o realm emite tokens de 5 minutos). Pedidos simultâneos do mesmo usuário compartilham a mesma chamada.
 */
export async function accessToken(user: DemoUser): Promise<string> {
  const cached = cache.get(user);
  if (cached) {
    const token = await cached;
    if (token.expiresAt - EXPIRY_MARGIN_MS > Date.now()) {
      return token.value;
    }
    if (cache.get(user) === cached) {
      cache.delete(user);
    }
    return accessToken(user);
  }
  const request = requestToken(user);
  cache.set(user, request);
  try {
    return (await request).value;
  } catch (error) {
    cache.delete(user);
    throw error;
  }
}

async function requestToken(user: DemoUser): Promise<CachedToken> {
  const url = `${KEYCLOAK_REALM_URL}/protocol/openid-connect/token`;
  const response = await fetch(url, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: E2E_ENV.tokenClientId,
      username: user,
      password: user,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Keycloak recusou o token de ${user} (${response.status}) em ${url}: ${text}`);
  }
  const body = JSON.parse(text) as { access_token: string; expires_in: number };
  return { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
}
