import { expect, test } from '@playwright/test';

import { openPanel, signInThroughKeycloak, userMenuButton } from './support/panel';
import { DEMO_USERS, storageStatePath } from './support/users';

for (const user of DEMO_USERS) {
  test(`entra pela tela do Keycloak como ${user} e guarda a sessão`, async ({ browser, page }, testInfo) => {
    const path = storageStatePath(user, testInfo.project);

    await page.goto('/');
    await signInThroughKeycloak(page, user);
    await expect(page).toHaveURL(/\/inicio$/);
    await expect(userMenuButton(page)).toBeVisible();
    await page.context().storageState({ path });

    // WHY: o storageState não guarda o sessionStorage, onde o painel põe o token; quem entra sem formulário é o cookie
    // de SSO do Keycloak, e um contexto novo prova que ele basta.
    const saved = await browser.newContext({ storageState: path });
    const reopened = await saved.newPage();
    await openPanel(reopened);
    await saved.close();
  });
}
