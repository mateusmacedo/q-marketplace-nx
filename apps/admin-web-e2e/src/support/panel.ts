import { expect, type Locator, type Page } from '@playwright/test';

import type { DemoUser } from './users';

/** Preenche e envia a tela de login do Keycloak (tema `marketplace`); a página já deve estar nela. */
export async function signInThroughKeycloak(page: Page, user: DemoUser): Promise<void> {
  await expect(page.getByRole('heading', { name: 'Entrar na sua conta' })).toBeVisible();
  await page.getByLabel('Nome de usuário ou e-mail').fill(user);
  await page.getByLabel('Senha', { exact: true }).fill(user);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

/**
 * Abre a rota do painel e espera o painel nela, com o cabeçalho montado. Com o `storageState` do usuário, o guard do
 * painel passa pelo Keycloak, que reconhece o cookie de SSO e devolve sem formulário; o token fica no sessionStorage
 * da aba, então as próximas navegações na mesma página não voltam ao Keycloak.
 */
export async function openPanel(page: Page, path = '/inicio'): Promise<void> {
  await page.goto(path);
  await expect(page).toHaveURL(url => isPanelRoute(url, path));
  await expect(userMenuButton(page)).toBeVisible();
}

/** Menu lateral do painel, com os títulos dos grupos como texto e os itens como links. */
export function sidebarNav(page: Page): Locator {
  // NOTE: o menu lateral do CoreUI não tem nome acessível e o cabeçalho tem outras navegações sem nome; o elemento
  // c-sidebar é o único jeito estável de separar o menu lateral delas.
  return page.locator('c-sidebar').getByRole('navigation');
}

export function userMenuButton(page: Page): Locator {
  return page.getByRole('button', { name: 'Abrir o menu do usuário' });
}

function isPanelRoute(url: URL, path: string): boolean {
  const target = new URL(path, url.origin);
  return url.pathname === target.pathname && url.search === target.search;
}
