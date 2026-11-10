import { expect, test } from '@playwright/test';

import { signInThroughKeycloak, userMenuButton } from './support/panel';

// Depende do Keycloak local no ar (infra/local, perfil catalog) com o realm de infra/keycloak.
test.use({ storageState: { cookies: [], origins: [] } });

test('entra pela tela do Keycloak e chega à página inicial com o próprio usuário', async ({
  page,
}) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/realms\/marketplace\/protocol\/openid-connect\/auth/);
  await signInThroughKeycloak(page, 'seller-demo');

  await expect(page).toHaveURL(/\/inicio$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Início' })).toBeAttached();
  await userMenuButton(page).click();
  await expect(page.getByRole('heading', { name: 'seller-demo' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sair' })).toBeVisible();
});
