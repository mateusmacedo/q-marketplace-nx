import { expect, test } from '@playwright/test';

// Depende do Keycloak local no ar (infra/local, perfil catalog) com o realm de infra/keycloak.
test('signs in through keycloak and lands on the home page', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/realms\/marketplace\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill('seller-demo');
  await page.locator('#password').fill('seller-demo');
  await page.locator('#kc-login').click();

  await expect(page).toHaveURL(/\/inicio$/);
  await expect(page.getByRole('heading', { name: 'Painel do marketplace' })).toBeVisible();
  await page.getByRole('button', { name: 'Abrir o menu do usuário' }).click();
  await expect(page.getByText('seller-demo')).toBeVisible();
});
