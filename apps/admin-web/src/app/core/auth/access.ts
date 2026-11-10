import type { Permissions } from '@marketplace/catalog-inventory-admin-data-access';

/**
 * Capacidades que o painel confere nas rotas e no menu, com os nomes que o servidor devolve em `GET /me`. A lista do
 * servidor é aberta; aqui ficam só as que o painel usa, para o compilador pegar o nome digitado errado. Quem tem cada
 * capacidade é decisão do servidor.
 */
export type AppCapability =
  'MANAGE_OWN_PRODUCTS' | 'MODERATE_PRODUCTS' | 'MANAGE_TAXONOMY' | 'MANAGE_MODERATION_REASONS';

/** Permissões compostas que o servidor já resolve em `can`, para o painel não remontar a regra. */
export type AppPermission = keyof Permissions;

/** O que uma rota ou um item do menu exige. */
export interface AppAccess {
  readonly capability?: AppCapability;
  readonly permission?: AppPermission;
}
