import { readFile } from 'node:fs/promises';

import type { Page } from '@playwright/test';

import { apiAs } from './support/api-client';
import {
  approveProduct,
  type Category,
  createCategory,
  createDraftProduct,
  createPendingProduct,
  DEFAULT_DIMENSIONS,
  getProduct,
  getStoreEligibility,
  PRODUCT_ATTRIBUTE,
  type Product,
  uniqueSuffix,
  VARIANT_ATTRIBUTE
} from './support/catalog-data';
import { expect, test } from './support/fixtures';
import { openPanel, sidebarNav } from './support/panel';

test.use({ user: 'seller-demo' });

test.describe('loja do seller-demo', () => {
  test('a situação da loja mostra Aprovada com os efeitos que o servidor calcula', async ({ page }) => {
    const eligibility = await getStoreEligibility();
    expect(eligibility).toMatchObject({ status: 'APPROVED', canPublish: true, visibleInStorefront: true });

    await openPanel(page);
    await sidebarNav(page).getByRole('link', { name: 'Situação da loja' }).click();

    await expect(page).toHaveURL(/\/loja\/situacao$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Situação da loja' })).toBeVisible();
    await expect(page.getByText('Aprovada', { exact: true })).toBeVisible();
    await expect(page.getByText('Produtos aprovados vão para a vitrine, e os pausados podem ser reativados.')).toBeVisible();
    await expect(page.getByText('Os produtos ativos aparecem na vitrine.')).toBeVisible();
  });

  test('o Início mostra os indicadores da loja e o rascunho recém-criado entre os atualizados por último', async ({
    page
  }) => {
    const category = await createCategory();
    const draft = await createDraftProduct({ category, title: `Início E2E ${uniqueSuffix()}` });

    await openPanel(page, '/inicio');

    for (const title of ['Produtos ativos', 'Em rascunho', 'Sem estoque', 'Bloqueados']) {
      await expect(page.getByRole('button', { name: `Opções de ${title}` })).toBeVisible();
    }
    await expect(page.getByText('Produtos atualizados por último')).toBeVisible();
    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: draft.title, exact: true }) });
    await expect(row).toContainText('Rascunho');
    await expect(row).toContainText(`${category.name} | 0 SKUs`);
    await expect(row).toContainText('Sem preço');

    await page.getByRole('button', { name: 'Opções de Em rascunho' }).click();
    await page.getByRole('link', { name: 'Ver os rascunhos' }).click();
    await expect(page).toHaveURL(/\/loja\/produtos\?status=DRAFT$/);
    await expect(page.getByRole('navigation', { name: 'Status dos produtos' }).getByRole('link', { name: 'Rascunho', exact: true }))
      .toHaveAttribute('aria-current', 'page');

    await openPanel(page, '/inicio');
    await page.getByRole('link', { name: draft.title, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/loja/produtos/${draft.id}/conteudo$`));
    await expect(productHeading(page)).toContainText(draft.title);
  });

  test('novo produto pelo modal resolve as pendências do servidor e vai para a moderação', async ({ page }) => {
    const category = await createCategory();
    const suffix = uniqueSuffix();
    const title = `Novo E2E ${suffix}`;
    const sellerSku = `E2E-${suffix}`;

    await openPanel(page, '/loja/produtos');
    await page.getByRole('button', { name: 'Novo produto' }).click();
    const modal = page.getByRole('dialog', { name: 'Novo produto' });
    await modal.getByLabel('Categoria').selectOption({ label: category.name });
    await modal.getByLabel('Título').fill(title);
    await modal.getByLabel('Moeda').fill('BRL');
    await modal.getByRole('button', { name: 'Criar produto' }).click();

    await expect(page).toHaveURL(/\/loja\/produtos\/[0-9a-f-]{36}\/conteudo$/);
    const productId = productIdFromUrl(page);
    await expect(productHeading(page)).toContainText(title);
    await expect(productHeading(page)).toContainText('Rascunho');
    const created = await getProduct(productId);
    expect(created).toMatchObject({ title, status: 'DRAFT', categoryId: category.id });
    expect(issueCodes(created)).toEqual(['no-sku', 'missing-attribute']);
    await expect(page.getByText('Para enviar à moderação', { exact: true })).toBeVisible();
    for (const message of issueMessages(created)) {
      await expect(checklistItem(page, message)).toBeVisible();
    }
    await expect(page.getByText('Conferido pelo servidor com as regras de publicação.')).toBeVisible();

    await page.getByLabel(PRODUCT_ATTRIBUTE.label).fill('Acme');
    await page.getByRole('button', { name: 'Salvar', exact: true }).click();
    await expect.poll(async () => (await getProduct(productId)).attributes[PRODUCT_ATTRIBUTE.code]).toBe('Acme');
    await expect(checklistItem(page, issueMessage(created, 'missing-attribute'))).toHaveCount(0);
    await expect(checklistItem(page, issueMessage(created, 'no-sku'))).toBeVisible();

    await page.getByRole('link', { name: 'SKUs, preço e estoque' }).click();
    await page.getByRole('button', { name: 'Adicionar SKU', exact: true }).first().click();
    const addSku = page.getByRole('dialog', { name: 'Adicionar SKU' });
    await addSku.getByLabel('Código do SKU').fill(sellerSku);
    await addSku.getByLabel(VARIANT_ATTRIBUTE.label).selectOption('P');
    await addSku.getByLabel('SKU físico').check();
    await addSku.getByLabel('Altura (cm)').fill(String(DEFAULT_DIMENSIONS.heightCm));
    await addSku.getByLabel('Largura (cm)').fill(String(DEFAULT_DIMENSIONS.widthCm));
    await addSku.getByLabel('Comprimento (cm)').fill(String(DEFAULT_DIMENSIONS.lengthCm));
    await addSku.getByLabel('Peso (g)').fill(String(DEFAULT_DIMENSIONS.weightGrams));
    await addSku.getByRole('button', { name: 'Adicionar', exact: true }).click();
    await expect(addSku).toBeHidden();

    const skuButton = page.getByRole('button', { name: sellerSku, exact: true });
    await expect(skuButton).toBeVisible();
    const withSku = await getProduct(productId);
    expect(withSku.skus.map(sku => sku.sellerSku)).toEqual([sellerSku]);
    expect(issueCodes(withSku)).not.toContain('no-sku');
    expect(issueCodes(withSku).length).toBeGreaterThan(0);
    for (const message of issueMessages(withSku)) {
      await expect(checklistItem(page, message)).toBeVisible();
    }

    await skuButton.click();
    const panel = page.getByRole('dialog', { name: sellerSku });
    await panel.getByLabel('De', { exact: true }).fill('79.90');
    await panel.getByLabel('Por', { exact: true }).fill('59.90');
    await panel.getByRole('button', { name: 'Salvar preço' }).click();
    await expect(panel.getByText(/Vigente agora: R\$\s59,90/)).toBeVisible();
    await panel.getByLabel('Físico', { exact: true }).fill('5');
    await panel.getByRole('button', { name: 'Salvar estoque' }).click();
    await expect(page.getByText('Estoque salvo.')).toBeVisible();
    await expect.poll(async () => (await getProduct(productId)).skus[0]?.stock?.onHand).toBe(5);
    await panel.getByRole('button', { name: 'Fechar' }).click();

    const skuRow = page.getByRole('row').filter({ has: skuButton });
    await expect(skuRow).toContainText(/R\$\s59,90/);
    await expect(skuRow).toContainText('Com preço');
    await expect(page.getByText('Tudo pronto para enviar.')).toBeVisible();
    expect((await getProduct(productId)).publishingIssues ?? []).toEqual([]);

    await page.getByRole('button', { name: 'Enviar para moderação', exact: true }).click();

    await expect(page.getByText('Produto enviado para moderação.')).toBeVisible();
    await expect(productHeading(page)).toContainText('Em moderação');
    expect((await getProduct(productId)).status).toBe('PENDING_MODERATION');
  });

  test('a busca pelo título acha os produtos, e o lote pausa e reativa os dois ativos', async ({ page }) => {
    const category = await createCategory();
    const base = `Lote E2E ${uniqueSuffix()}`;
    const products = await Promise.all([`${base} A`, `${base} B`].map(title => createActiveProduct(category, title)));

    await openPanel(page, '/loja/produtos');
    await page.getByRole('searchbox', { name: 'Buscar por título ou SKU' }).fill(base);
    await page.getByRole('button', { name: 'Buscar', exact: true }).click();

    await expect(page).toHaveURL(/[?&]q=/);
    const table = page.getByRole('table');
    await expect(table.getByRole('row')).toHaveCount(1 + products.length);
    for (const product of products) {
      await expect(productRow(page, product)).toContainText('Ativo');
    }

    await selectProducts(page, products);
    const batch = page.getByRole('toolbar', { name: 'Ações em lote' });
    await expect(batch).toContainText('2 selecionados');
    await batch.getByRole('button', { name: 'Pausar (2)' }).click();

    await expect(page.getByText('2 pausados', { exact: true })).toBeVisible();
    for (const product of products) {
      await expect(productRow(page, product)).toContainText('Pausado');
    }
    await expect.poll(() => statusesOf(products)).toEqual(['PAUSED_BY_SELLER', 'PAUSED_BY_SELLER']);
    await expect(batch).toBeHidden();

    await selectProducts(page, products);
    await batch.getByRole('button', { name: 'Reativar (2)' }).click();

    await expect(page.getByText('2 reativados', { exact: true })).toBeVisible();
    for (const product of products) {
      await expect(productRow(page, product)).toContainText('Ativo');
    }
    await expect.poll(() => statusesOf(products)).toEqual(['ACTIVE', 'ACTIVE']);
  });

  test('a planilha no formato do modelo cria a linha válida, recusa a inválida com a mensagem do servidor e leva ao produto', async ({
    page
  }) => {
    const category = await createCategory();
    const suffix = uniqueSuffix();
    const valid = { ref: `ref-${suffix}-a`, title: `Importado E2E ${suffix}`, sku: `IMP-${suffix}-A`, variant: 'P' };
    const invalid = { ref: `ref-${suffix}-b`, title: `Recusado E2E ${suffix}`, sku: `IMP-${suffix}-B`, variant: 'XG' };

    await openPanel(page, '/loja/importacoes');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Baixar modelo CSV' }).first().click();
    const template = await readFile(await (await download).path(), 'utf8');
    const columns = template.trim().split(',');
    expect(columns).toContain('seller_sku');

    const csv = csvFrom(columns, [csvLine(category, valid), csvLine(category, invalid)]);
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Enviar planilha' }).click();
    await (await chooser).setFiles({ name: `importacao-${suffix}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) });

    await expect(page.getByText(`A planilha importacao-${suffix}.csv foi recebida e será processada em instantes.`)).toBeVisible();
    const createdRow = page.getByRole('row').filter({ hasText: valid.sku });
    const failedRow = page.getByRole('row').filter({ hasText: invalid.sku });
    await expect(createdRow).toContainText('Criada');
    await expect(failedRow).toContainText('Falha');

    await page.getByRole('link', { name: /^Lote de / }).click();
    await expect(page).toHaveURL(/\/loja\/importacoes\/[0-9a-f-]{36}$/);
    await expect(page.getByText('Concluída', { exact: true })).toBeVisible();
    const report = await apiAs('seller-demo').get<ImportReport>(`/products/imports/${lastPathSegment(page)}`);
    const createdLine = lineOf(report, valid.sku);
    const failedLine = lineOf(report, invalid.sku);
    expect(createdLine).toMatchObject({ outcome: 'CREATED', productId: expect.any(String) });
    expect(failedLine).toMatchObject({ outcome: 'FAILED', message: expect.any(String) });
    expect(failedLine.reasonDetails?.length).toBeGreaterThan(0);
    await expect(createdRow).toContainText('Criada');
    await expect(failedRow).toContainText('Falha');
    await expect(failedRow).toContainText(String(failedLine.message));
    for (const message of reasonMessages(failedLine)) {
      await expect(failedRow).toContainText(message);
    }
    await expect(failedRow.getByRole('link')).toHaveCount(0);

    await createdRow.getByRole('link', { name: valid.sku }).click();
    await expect(page).toHaveURL(new RegExp(`/loja/produtos/${createdLine.productId}/conteudo$`));
    await expect(productHeading(page)).toContainText(valid.title);
    await expect(productHeading(page)).toContainText('Rascunho');
  });
});

interface ImportLine {
  readonly lineNumber: number;
  readonly sellerSku?: string;
  readonly outcome: string;
  readonly productId?: string;
  readonly message?: string;
  readonly reasonDetails?: readonly { readonly code?: string; readonly message?: string }[];
}

interface ImportReport {
  readonly id: string;
  readonly status: string;
  readonly lines: readonly ImportLine[];
}

interface CsvProduct {
  readonly ref: string;
  readonly title: string;
  readonly sku: string;
  readonly variant: string;
}

function productHeading(page: Page) {
  return page.getByRole('heading', { level: 1 });
}

/** Item do checklist "Para enviar à moderação": um botão por pendência, com o texto do servidor. */
function checklistItem(page: Page, message: string) {
  return page.getByRole('button', { name: message, exact: true });
}

function productRow(page: Page, product: Product) {
  return page.getByRole('row').filter({ has: page.getByRole('link', { name: product.title, exact: true }) });
}

async function selectProducts(page: Page, products: readonly Product[]): Promise<void> {
  for (const product of products) {
    await page.getByRole('checkbox', { name: `Selecionar ${product.title}`, exact: true }).check();
  }
}

/** Produto ativo: pronto, enviado e aprovado pelo moderador pela API. */
async function createActiveProduct(category: Category, title: string): Promise<Product> {
  const active = await approveProduct(await createPendingProduct({ category, title }));
  expect(active.status).toBe('ACTIVE');
  return active;
}

async function statusesOf(products: readonly Product[]): Promise<string[]> {
  return Promise.all(products.map(async product => (await getProduct(product)).status));
}

function issueCodes(product: Product): string[] {
  return (product.publishingIssues ?? []).map(issue => issue.code);
}

function issueMessages(product: Product): string[] {
  return (product.publishingIssues ?? []).map(issue => issue.message);
}

function issueMessage(product: Product, code: string): string {
  const issue = product.publishingIssues?.find(item => item.code === code);
  if (!issue) {
    throw new Error(`O produto ${product.id} não tem a pendência ${code}`);
  }
  return issue.message;
}

function lastPathSegment(page: Page): string {
  return new URL(page.url()).pathname.split('/').filter(Boolean).pop() ?? '';
}

/** Id do produto na rota `/loja/produtos/:id/<aba>`. */
function productIdFromUrl(page: Page): string {
  return new URL(page.url()).pathname.split('/')[3] ?? '';
}

/** Mensagens de cada motivo da recusa da linha, na ordem do servidor. */
function reasonMessages(line: ImportLine): string[] {
  return (line.reasonDetails ?? []).map(reason => String(reason.message));
}

function lineOf(report: ImportReport, sellerSku: string): ImportLine {
  const line = report.lines.find(item => item.sellerSku === sellerSku);
  if (!line) {
    throw new Error(`O lote ${report.id} não tem a linha do SKU ${sellerSku}`);
  }
  return line;
}

/** Valores de uma linha da planilha por coluna do modelo; o atributo de variação recebe a opção dada. */
function csvLine(category: Category, product: CsvProduct): Readonly<Record<string, string>> {
  return {
    product_ref: product.ref,
    category_code: category.code,
    title: product.title,
    description: 'Produto importado pela suíte e2e',
    attributes: `${PRODUCT_ATTRIBUTE.code}=Acme`,
    seller_sku: product.sku,
    variant_attributes: `${VARIANT_ATTRIBUTE.code}=${product.variant}`,
    physical: 'true',
    height_cm: String(DEFAULT_DIMENSIONS.heightCm),
    width_cm: String(DEFAULT_DIMENSIONS.widthCm),
    length_cm: String(DEFAULT_DIMENSIONS.lengthCm),
    weight_g: String(DEFAULT_DIMENSIONS.weightGrams),
    currency: 'BRL',
    list_price: '79.90',
    sale_price: '59.90',
    on_hand: '5'
  };
}

/** CSV na ordem das colunas do modelo baixado, com a quebra CRLF do modelo; coluna sem valor fica vazia. */
function csvFrom(columns: readonly string[], lines: readonly Readonly<Record<string, string>>[]): string {
  const rows = lines.map(line => columns.map(column => line[column] ?? '').join(','));
  return [columns.join(','), ...rows].join('\r\n') + '\r\n';
}
