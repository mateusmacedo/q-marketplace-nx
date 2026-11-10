import type { SessionGrants } from '@marketplace/shared-admin-ui';

import { buildNav, navItems, QUEUE_BADGE } from './_nav';

const grants = (
  capabilities: string[],
  permissions: SessionGrants['permissions'] = {},
): SessionGrants => ({
  capabilities,
  permissions,
});

const names = (session: SessionGrants, badges: Record<string, number> = {}) =>
  buildNav(navItems, session, badges).map((item) =>
    item.title ? item.name?.toUpperCase() : item.name,
  );

describe('menu por capacidade', () => {
  it('mostra o grupo da loja para quem gerencia os próprios produtos', () => {
    expect(names(grants(['MANAGE_OWN_PRODUCTS']))).toEqual([
      'Início',
      'MINHA LOJA',
      'Produtos',
      'Importações',
      'Situação da loja',
    ]);
  });

  it('mostra a fila para quem modera e os produtos de todas as lojas pela permissão do servidor', () => {
    expect(
      names(grants(['MODERATE_PRODUCTS', 'READ_SELLER_ELIGIBILITY'], { readTenantCatalog: true })),
    ).toEqual([
      'Início',
      'MODERAÇÃO',
      'Fila de produtos',
      'PLATAFORMA',
      'Produtos de todas as lojas',
    ]);
  });

  it('mostra categorias e motivos de moderação a quem tem cada capacidade', () => {
    expect(
      names(grants(['MANAGE_TAXONOMY', 'MANAGE_MODERATION_REASONS'], { readTenantCatalog: true })),
    ).toEqual([
      'Início',
      'PLATAFORMA',
      'Categorias',
      'Motivos de moderação',
      'Produtos de todas as lojas',
    ]);
    expect(names(grants(['MANAGE_TAXONOMY']))).toEqual(['Início', 'PLATAFORMA', 'Categorias']);
  });

  it('junta os grupos, esconde os vazios e ignora a capacidade que não conhece', () => {
    expect(names(grants(['MANAGE_OWN_PRODUCTS', 'MANAGE_TAXONOMY', 'CAPACIDADE_NOVA']))).toEqual([
      'Início',
      'MINHA LOJA',
      'Produtos',
      'Importações',
      'Situação da loja',
      'PLATAFORMA',
      'Categorias',
    ]);
    expect(names(grants([]))).toEqual(['Início']);
    expect(names(grants(['MANAGE_RESERVATIONS'], { readTenantCatalog: false }))).toEqual([
      'Início',
    ]);
  });

  it('não declara nome de papel nos itens', () => {
    const declared = navItems.flatMap((item) => [item.capability, item.permission]).filter(Boolean);
    expect(declared).toEqual([
      'MANAGE_OWN_PRODUCTS',
      'MANAGE_OWN_PRODUCTS',
      'MANAGE_OWN_PRODUCTS',
      'MODERATE_PRODUCTS',
      'MANAGE_TAXONOMY',
      'MANAGE_MODERATION_REASONS',
      'readTenantCatalog',
    ]);
    expect(JSON.stringify(navItems)).not.toMatch(/seller|catalog-/);
  });

  it('usa a chave do badge da fila que a feature de moderação alimenta', async () => {
    const { MODERATION_QUEUE_BADGE } =
      await import('@marketplace/catalog-inventory-admin-feature-moderation');
    expect(QUEUE_BADGE).toBe(MODERATION_QUEUE_BADGE);
  });

  it('põe a contagem da fila no badge do item', () => {
    const moderator = grants(['MODERATE_PRODUCTS']);
    const queue = buildNav(navItems, moderator, { [QUEUE_BADGE]: 3 }).find(
      (item) => item.url === '/moderacao/fila',
    );
    expect(queue?.badge).toEqual({ color: 'info', text: '3', size: 'sm' });
    expect(
      buildNav(navItems, moderator).find((item) => item.url === '/moderacao/fila')?.badge,
    ).toBeUndefined();
  });
});
