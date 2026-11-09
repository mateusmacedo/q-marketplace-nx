import path from 'node:path';

import { workspaceRoot } from '@nx/devkit';

/**
 * Endereços e identificadores da stack local (`infra/local`, perfil `catalog`) e do realm de `infra/keycloak`. Os
 * endereços podem ser trocados por variável de ambiente; tenant e loja vêm do realm.
 */
export const E2E_ENV = {
  /** Gateway: serve o painel buildado e a API de cada contexto em `/api/<contexto>`. */
  gatewayUrl: process.env['E2E_GATEWAY_URL'] ?? 'http://localhost:8080',
  keycloakUrl: process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180',
  realm: 'marketplace',
  /** Cliente público com password grant, o mesmo do roteiro do README; o painel usa outro, com code flow. */
  tokenClientId: 'marketplace-cli',
  tenantId: '11111111-1111-4111-8111-111111111111',
  /** Loja do `seller-demo`. */
  sellerStoreId: '22222222-2222-4222-8222-222222222222',
  composeFile: process.env['E2E_COMPOSE_FILE'] ?? path.join(workspaceRoot, 'infra/local/docker-compose.yml')
} as const;

/** Base da API do catálogo pelo gateway; os caminhos do contrato entram depois dela (`/products`). */
export const CATALOG_API_URL = `${E2E_ENV.gatewayUrl}/api/catalog-inventory`;

export const KEYCLOAK_REALM_URL = `${E2E_ENV.keycloakUrl}/realms/${E2E_ENV.realm}`;
