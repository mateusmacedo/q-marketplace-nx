import { expect, test } from './support/fixtures';
import { openPanel, sidebarNav, userMenuButton } from './support/panel';
import type { DemoUser } from './support/users';

// WHY: o moderador também vê Produtos de todas as lojas em Plataforma, pela permissão composta can.readTenantCatalog
// que o GET /me devolve para ele (proposta de design, seção "Menu").
const MENU_BY_USER: readonly { user: DemoUser; sections: string; menu: string }[] = [
  {
    user: 'admin-demo',
    sections: 'Plataforma',
    menu: `
      - navigation:
        - /children: equal
        - link "Início"
        - text: Plataforma
        - link "Categorias"
        - link "Motivos de moderação"
        - link "Produtos de todas as lojas"
    `
  },
  {
    user: 'seller-demo',
    sections: 'Minha loja',
    menu: `
      - navigation:
        - /children: equal
        - link "Início"
        - text: Minha loja
        - link "Produtos"
        - link "Importações"
        - link "Situação da loja"
    `
  },
  {
    user: 'moderator-demo',
    sections: 'Moderação e Produtos de todas as lojas',
    menu: `
      - navigation:
        - /children: equal
        - link "Início"
        - text: Moderação
        - link /^Fila de produtos( \\d+)?$/
        - text: Plataforma
        - link "Produtos de todas as lojas"
    `
  }
];

for (const { user, sections, menu } of MENU_BY_USER) {
  test.describe(`sessão de ${user}`, () => {
    test.use({ user });

    test(`o menu lateral mostra só ${sections}`, async ({ page }) => {
      await openPanel(page);

      await expect(sidebarNav(page)).toMatchAriaSnapshot(menu);
    });
  });
}

test('duas sessões no mesmo teste ficam separadas, cada uma com o próprio menu e usuário', async ({ pageAs }) => {
  const seller = await pageAs('seller-demo');
  const moderator = await pageAs('moderator-demo', '/moderacao/fila');

  await expect(sidebarNav(seller).getByRole('link', { name: 'Situação da loja' })).toBeVisible();
  await expect(sidebarNav(seller).getByRole('link', { name: /^Fila de produtos/ })).toHaveCount(0);
  await expect(sidebarNav(moderator).getByRole('link', { name: /^Fila de produtos/ })).toBeVisible();
  await expect(sidebarNav(moderator).getByRole('link', { name: 'Situação da loja' })).toHaveCount(0);

  await userMenuButton(seller).click();
  await expect(seller.getByRole('heading', { name: 'seller-demo' })).toBeVisible();
  await userMenuButton(moderator).click();
  await expect(moderator.getByRole('heading', { name: 'moderator-demo' })).toBeVisible();
});
