import type { INavData } from '@coreui/angular';
import { allowsAccess, type SessionGrants } from '@marketplace/shared-admin-ui';

import type { AppAccess } from '../../core/auth/access';

// WHY: é o MODERATION_QUEUE_BADGE da feature de moderação, repetido para o menu não importar a feature, que só carrega
// sob demanda; o teste do menu confere que os dois valores batem.
export const QUEUE_BADGE = 'moderation-queue';

/** Item do menu com a capacidade ou a permissão exigida e a chave do badge alimentado pela feature. */
export interface AppNavItem extends INavData, AppAccess {
  readonly badgeKey?: string;
}

/**
 * Menu do painel (proposta de design, seção "Menu"). Os grupos são só organização visual: os títulos não exigem nada
 * e somem quando nenhum item do grupo sobra para o usuário.
 */
export const navItems: readonly AppNavItem[] = [
  { name: 'Início', url: '/inicio', iconComponent: { name: 'cil-home' } },
  { name: 'Minha loja', title: true },
  { name: 'Produtos', url: '/loja/produtos', iconComponent: { name: 'cil-tags' }, capability: 'MANAGE_OWN_PRODUCTS' },
  {
    name: 'Importações',
    url: '/loja/importacoes',
    iconComponent: { name: 'cil-cloud-upload' },
    capability: 'MANAGE_OWN_PRODUCTS'
  },
  {
    name: 'Situação da loja',
    url: '/loja/situacao',
    iconComponent: { name: 'cil-building' },
    capability: 'MANAGE_OWN_PRODUCTS'
  },
  { name: 'Moderação', title: true },
  {
    name: 'Fila de produtos',
    url: '/moderacao/fila',
    iconComponent: { name: 'cil-shield-alt' },
    capability: 'MODERATE_PRODUCTS',
    badgeKey: QUEUE_BADGE
  },
  { name: 'Plataforma', title: true },
  {
    name: 'Categorias',
    url: '/plataforma/categorias',
    iconComponent: { name: 'cil-sitemap' },
    capability: 'MANAGE_TAXONOMY'
  },
  {
    name: 'Motivos de moderação',
    url: '/plataforma/motivos-de-moderacao',
    iconComponent: { name: 'cil-comment-square' },
    capability: 'MANAGE_MODERATION_REASONS'
  },
  {
    name: 'Produtos de todas as lojas',
    url: '/plataforma/produtos',
    iconComponent: { name: 'cil-list' },
    permission: 'readTenantCatalog'
  }
];

/**
 * Monta o menu do usuário: tira os itens que a sessão não libera e os títulos dos grupos que ficaram vazios, e põe nos
 * itens o badge com a contagem da chave deles. O menu lateral do CoreUI ignora `capability`, `permission` e `badgeKey`.
 */
export function buildNav(
  items: readonly AppNavItem[],
  grants: SessionGrants,
  badges: Readonly<Record<string, number>> = {}
): AppNavItem[] {
  const visible = items.filter(item => item.title || allowsAccess(grants, item));
  const withoutEmptyGroups = visible.filter((item, index) => !item.title || hasEntryBeforeNextTitle(visible, index));
  return withoutEmptyGroups.map(item => {
    const count = item.badgeKey ? badges[item.badgeKey] : undefined;
    return count ? { ...item, badge: { color: 'info', text: String(count), size: 'sm' } } : item;
  });
}

function hasEntryBeforeNextTitle(items: readonly AppNavItem[], titleIndex: number): boolean {
  const next = items[titleIndex + 1];
  return !!next && !next.title;
}
