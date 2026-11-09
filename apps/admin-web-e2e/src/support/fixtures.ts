import { test as base, type BrowserContext, type Page } from '@playwright/test';

import { openPanel } from './panel';
import { storageStatePath, type DemoUser } from './users';

export interface PanelFixtures {
  /**
   * Usuário da `page` padrão do teste, por `test.use({ user: 'seller-demo' })`: o contexto nasce com a sessão dele.
   * Sem ele, a página abre sem sessão.
   */
  user: DemoUser | undefined;
  /**
   * Abre uma página logada como o usuário, num contexto próprio, já na rota (padrão `/inicio`). Cada chamada cria um
   * contexto isolado, fechado no fim do teste: serve para ter dois usuários no mesmo teste.
   */
  pageAs: (user: DemoUser, path?: string) => Promise<Page>;
}

export const test = base.extend<PanelFixtures>({
  user: [undefined, { option: true }],
  storageState: async ({ user, storageState }, use, testInfo) => {
    await use(user ? storageStatePath(user, testInfo.project) : storageState);
  },
  pageAs: async ({ browser }, use, testInfo) => {
    const contexts: BrowserContext[] = [];
    await use(async (user, path = '/inicio') => {
      const context = await browser.newContext({ storageState: storageStatePath(user, testInfo.project) });
      contexts.push(context);
      const page = await context.newPage();
      await openPanel(page, path);
      return page;
    });
    await Promise.all(contexts.map(context => context.close()));
  }
});

export { expect } from '@playwright/test';
