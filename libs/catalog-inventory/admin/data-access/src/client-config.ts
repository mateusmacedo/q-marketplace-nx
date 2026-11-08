import type { CreateClientConfig } from './generated/client.gen';

// WHY: o dev server (proxy.conf.json) e o ingress levam este prefixo ao catálogo; o painel nunca chama a API
// por outra origem, então não há CORS (ADR 027).
export const createClientConfig: CreateClientConfig = (config) => ({
  ...config,
  baseUrl: '/api/catalog-inventory',
});
