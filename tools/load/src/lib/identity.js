// Tokens reais do Keycloak local por grant_type=password, com renovação por VU.
import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';
import { params } from '../config.js';
import { createRandom, hashSeed } from './random.js';

export const USERS = { admin: 'admin-demo', seller: 'seller-demo', moderator: 'moderator-demo', checkout: 'checkout-demo' };

const TOKEN_URL = `${params.keycloakUrl}/realms/${params.keycloakRealm}/protocol/openid-connect/token`;
const vuTokens = {};
let renewMarginMs = null;

function toToken(res) {
  const body = res.json();
  return { access: body.access_token, refresh: body.refresh_token, expiresAt: Date.now() + body.expires_in * 1000 };
}

export function login(role) {
  const res = http.post(
    TOKEN_URL,
    { grant_type: 'password', client_id: params.keycloakClientId, username: USERS[role], password: USERS[role] },
    { tags: { kind: 'auth', name: 'POST token' } },
  );
  if (res.status !== 200) fail(`login de ${USERS[role]} no Keycloak falhou: HTTP ${res.status}`);
  return toToken(res);
}

// WHY: o token do realm local dura 5 min; cada VU renova o seu com folga própria para não renovar todos juntos.
function renewMargin() {
  if (renewMarginMs === null) {
    renewMarginMs = 60000 + createRandom(hashSeed(params.seed, 'token', exec.vu.idInTest)).fraction() * 60000;
  }
  return renewMarginMs;
}

export function bearer(data, role) {
  let token = vuTokens[role] || data.tokens[role];
  if (token.expiresAt - Date.now() < renewMargin()) {
    const res = http.post(
      TOKEN_URL,
      { grant_type: 'refresh_token', client_id: params.keycloakClientId, refresh_token: token.refresh },
      { tags: { kind: 'auth', name: 'POST token' } },
    );
    token = res.status === 200 ? toToken(res) : login(role);
  }
  vuTokens[role] = token;
  return token.access;
}
