import type { Locator, Page, Response } from '@playwright/test';

import {
  approveProduct,
  createCategory,
  createModerationReason,
  createPendingProduct,
  deactivateModerationReason,
  getProduct,
  type ModerationReason,
  type Product,
  rejectProduct,
  submitProduct,
  uniqueSuffix
} from './support/catalog-data';
import { expect, test as base } from './support/fixtures';
import { sidebarNav } from './support/panel';

// WHY: maxLength de `note` em DecisionRequest no contrato; o contador do modal mostra "N de 500".
const NOTE_MAX_LENGTH = 500;

/** Fila, ou o produto aberto ao lado dela, com a maior página que o contrato aceita, para percorrer menos páginas. */
function queuePath(productId?: string): string {
  return `/moderacao/fila${productId ? `/${productId}` : ''}?limit=100`;
}

interface ModerationFixtures {
  /** Cria um motivo pronto ativo pelo `admin-demo`; o fixture desativa os criados no fim do teste. */
  createReason: (decisions: readonly ('REJECT' | 'BLOCK')[]) => Promise<ModerationReason>;
}

const test = base.extend<ModerationFixtures>({
  // eslint-disable-next-line no-empty-pattern
  createReason: async ({}, use) => {
    const created: string[] = [];
    await use(async decisions => {
      const reason = await createModerationReason({ decisions });
      created.push(reason.code);
      return reason;
    });
    await Promise.all(created.map(code => deactivateModerationReason(code)));
  }
});

test('rejeitar com motivo e complemento tira o produto da fila, e o lojista vê o badge Rejeitado com o motivo e o histórico da decisão', async ({
  createReason,
  pageAs
}) => {
  const suffix = uniqueSuffix();
  const reason = await createReason(['REJECT']);
  const product = await createPendingProduct({ category: await createCategory(), title: `Produto rejeitado ${suffix}` });
  const note = `A foto principal não mostra o produto ${suffix}`;

  const moderator = await pageAs('moderator-demo', queuePath(product.id));
  await expect(moderator.getByRole('heading', { level: 1, name: product.title })).toBeVisible();
  expect(await walkQueue(moderator, [product.title])).toEqual([]);

  await decisions(moderator).getByRole('button', { name: 'Rejeitar' }).click();
  const dialog = moderator.getByRole('dialog', { name: `Rejeitar "${product.title}"` });
  await dialog.getByRole('checkbox', { name: reason.label, exact: true }).check();
  const complement = dialog.getByLabel('Complemento');
  await expect(complement).toHaveAccessibleDescription(`0 de ${NOTE_MAX_LENGTH}`);
  await complement.fill(note);
  await expect(dialog.getByText(`${note.length} de ${NOTE_MAX_LENGTH}`)).toBeVisible();
  await dialog.getByRole('button', { name: 'Rejeitar' }).click();

  await expect(moderator.getByText('Produto rejeitado. Ele voltou ao rascunho do lojista.')).toBeVisible();
  await expect(dialog).toBeHidden();
  await expect(moderator).not.toHaveURL(new RegExp(product.id));
  expect((await getProduct(product)).status).toBe('DRAFT');
  await moderator.goto(queuePath());
  expect(await walkQueue(moderator, [product.title])).toEqual([product.title]);

  const seller = await pageAs('seller-demo', `/loja/produtos?q=${suffix}`);
  const row = productRow(seller, product.title);
  await expect(row.getByText('Rascunho', { exact: true })).toBeVisible();
  // NOTE: regex em getByText e hasText casa com o texto bruto do elemento, que começa pelo espaço do template.
  const rejected = row.getByText(/^\s*Rejeitado/);
  await rejected.hover();
  await expect(seller.getByRole('tooltip')).toHaveText(`Motivo: ${reason.label}. Complemento: ${note}`);

  await seller.goto(`/loja/produtos/${product.id}/historico`);
  const rejection = seller.getByRole('listitem').filter({ hasText: /^\s*Rejeitado/ });
  await expect(rejection).toContainText('de Em moderação para Rascunho');
  await expect(rejection.getByText(reason.label, { exact: true })).toBeVisible();
  await expect(rejection.getByText('Complemento do moderador')).toBeVisible();
  await expect(rejection.getByText(note, { exact: true })).toBeVisible();
});

test('aprovar o produto reenviado depois da rejeição deixa o produto Ativo para o lojista', async ({ pageAs }) => {
  const suffix = uniqueSuffix();
  const pending = await createPendingProduct({ category: await createCategory(), title: `Produto reenviado ${suffix}` });
  await rejectProduct(pending, { note: `Falta a foto do verso ${suffix}` });
  const product = await submitProduct(pending);
  expect(product.status).toBe('PENDING_MODERATION');

  const moderator = await pageAs('moderator-demo', `/moderacao/fila/${product.id}`);
  await expect(moderator.getByRole('heading', { level: 1, name: product.title })).toBeVisible();
  await decisions(moderator).getByRole('button', { name: 'Aprovar' }).click();

  await expect(moderator.getByText('Produto aprovado.')).toBeVisible();
  await expect(moderator).not.toHaveURL(new RegExp(product.id));
  expect((await getProduct(product)).status).toBe('ACTIVE');

  const seller = await pageAs('seller-demo', `/loja/produtos?q=${suffix}`);
  const row = productRow(seller, product.title);
  await expect(row.getByText('Ativo', { exact: true })).toBeVisible();
  await expect(row.getByText(/^\s*Rejeitado/)).toHaveCount(0);
});

test('bloquear um produto ativo com um motivo de bloqueio o tira da vitrine, e Desbloquear em Produtos de todas as lojas o devolve à fila', async ({
  createReason,
  pageAs
}) => {
  const suffix = uniqueSuffix();
  const reason = await createReason(['BLOCK']);
  const product = await approveProduct(
    await createPendingProduct({ category: await createCategory(), title: `Produto bloqueado ${suffix}` })
  );
  expect(product.status).toBe('ACTIVE');

  const moderator = await pageAs('moderator-demo', `/moderacao/fila/${product.id}`);
  await expect(moderator.getByRole('heading', { level: 1, name: product.title })).toBeVisible();
  await decisions(moderator).getByRole('button', { name: 'Bloquear' }).click();
  const dialog = moderator.getByRole('dialog', { name: `Bloquear "${product.title}"` });
  await expect(dialog.getByText('O produto sai da vitrine na hora.')).toBeVisible();
  await dialog.getByRole('checkbox', { name: reason.label, exact: true }).check();
  await dialog.getByRole('button', { name: 'Bloquear' }).click();

  await expect(moderator.getByText('Produto bloqueado. Ele saiu da vitrine.')).toBeVisible();
  expect((await getProduct(product)).status).toBe('BLOCKED');

  const seller = await pageAs('seller-demo', `/loja/produtos/${product.id}/conteudo`);
  const notice = seller.getByRole('status').filter({ hasText: 'Bloqueado pela moderação' });
  await expect(notice).toContainText(`Motivos: ${reason.label}`);

  await sidebarNav(moderator).getByRole('link', { name: 'Produtos de todas as lojas' }).click();
  await moderator.getByRole('searchbox', { name: 'Buscar por título ou SKU' }).fill(suffix);
  await moderator.getByRole('button', { name: 'Buscar' }).click();
  const row = productRow(moderator, product.title);
  await expect(row.getByText('Bloqueado', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: `Ações de ${product.title}` }).click();
  await moderator.getByRole('button', { name: 'Desbloquear' }).click();

  await expect(moderator.getByText('Produto desbloqueado. Ele volta para a fila de moderação.')).toBeVisible();
  await expect(row.getByText('Em moderação', { exact: true })).toBeVisible();
  expect((await getProduct(product)).status).toBe('PENDING_MODERATION');

  // NOTE: aprovar pela API só para o produto não ficar na fila compartilhada entre execuções.
  await approveProduct(product);
});

test('aprovar em lote os dois produtos selecionados pelo título mostra o resumo do lote e tira os dois da fila', async ({
  pageAs
}) => {
  const suffix = uniqueSuffix();
  const category = await createCategory();
  const first = await createPendingProduct({ category, title: `Lote ${suffix} um` });
  const second = await createPendingProduct({ category, title: `Lote ${suffix} dois` });
  const titles = [first.title, second.title];

  const moderator = await pageAs('moderator-demo', queuePath());
  const missing = await walkQueue(moderator, titles, entry => entry.getByRole('checkbox').check());
  expect(missing).toEqual([]);

  const batchBar = moderator.getByRole('toolbar', { name: 'Ações em lote' });
  await expect(batchBar).toContainText('2 selecionados');
  await batchBar.getByRole('button', { name: 'Aprovar (2)' }).click();
  const dialog = moderator.getByRole('dialog', { name: 'Aprovar em lote' });
  await expect(dialog).toContainText('Aprovar 2 produtos?');
  await dialog.getByRole('button', { name: 'Aprovar' }).click();

  await expect(moderator.getByText('2 aprovados', { exact: true })).toBeVisible();
  await expect(dialog).toBeHidden();
  await expect(moderator.getByText('Alguns produtos não foram decididos')).toHaveCount(0);
  await expect.poll(async () => Promise.all([first, second].map(async product => (await getProduct(product)).status))).toEqual([
    'ACTIVE',
    'ACTIVE'
  ]);
  await moderator.goto(queuePath());
  expect(await walkQueue(moderator, titles)).toEqual(titles);

  const seller = await pageAs('seller-demo', `/loja/produtos?q=${suffix}`);
  for (const product of [first, second]) {
    await expect(productRow(seller, product.title).getByText('Ativo', { exact: true })).toBeVisible();
  }
});

/** Botões de decisão do detalhe do produto na fila. */
function decisions(page: Page): Locator {
  return page.getByRole('group', { name: 'Decisões' });
}

/** Linha da tabela de produtos com o título exato, na lista da loja ou na de todas as lojas. */
function productRow(page: Page, title: Product['title']): Locator {
  return page.getByRole('row').filter({ has: page.getByRole('link', { name: title, exact: true }) });
}

/**
 * Percorre a fila a partir da página aberta, até achar todos os títulos ou a fila acabar, e chama `found` com a
 * entrada de cada título na página em que ele aparece. Devolve os títulos que não estavam em nenhuma página.
 */
async function walkQueue(
  page: Page,
  titles: readonly string[],
  found: (entry: Locator) => Promise<void> = async () => undefined
): Promise<string[]> {
  // WHY: a fila é compartilhada e cresce entre execuções; o produto do teste pode não estar na primeira página.
  const list = page.getByRole('list', { name: 'Produtos na fila' });
  const empty = page.getByText('Nenhum produto aguardando moderação.');
  const next = page.getByRole('button', { name: 'Próxima' });
  let missing = [...titles];
  for (;;) {
    await expect(list.or(empty)).toBeVisible();
    if (await empty.isVisible()) {
      return missing;
    }
    await expect(list).toHaveAttribute('aria-busy', 'false');
    for (const title of missing) {
      const entry = list.getByRole('listitem').filter({ has: page.getByRole('link', { name: title, exact: true }) });
      if (await entry.count()) {
        await found(entry);
        missing = missing.filter(other => other !== title);
      }
    }
    if (!missing.length || (await next.isDisabled())) {
      return missing;
    }
    const loaded = page.waitForResponse(isQueueResponse);
    await next.click();
    await loaded;
  }
}

function isQueueResponse(response: Response): boolean {
  return response.request().method() === 'GET' && new URL(response.url()).pathname.endsWith('/moderation/products');
}
