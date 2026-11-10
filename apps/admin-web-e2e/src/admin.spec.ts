import { randomUUID } from 'node:crypto';

import type { Locator, Page } from '@playwright/test';

import { apiAs, ApiError, apiFailure } from './support/api-client';
import {
  type Category,
  createCategory,
  createDraftProduct,
  createModerationReason,
  deactivateModerationReason,
  type ModerationReason,
  uniqueSuffix,
} from './support/catalog-data';
import { E2E_ENV } from './support/env';
import { expect, test } from './support/fixtures';
import { openPanel } from './support/panel';

test.use({ user: 'admin-demo' });

const admin = apiAs('admin-demo');

/** Categorias e motivos que o teste em andamento deixou, desfeitos no fim dele para as listas não crescerem. */
const categoriesToDelete: string[] = [];
const reasonsToDeactivate: string[] = [];

test.afterEach(async () => {
  // WHY: a ordem inversa exclui a subcategoria antes da pai, que recusaria com category-has-children.
  for (const id of categoriesToDelete.splice(0).reverse()) {
    await deleteCategoryIfPresent(id);
  }
  for (const code of reasonsToDeactivate.splice(0)) {
    await deactivateModerationReason(code);
  }
});

test.describe('categorias', () => {
  test('cria a categoria raiz pelo formulário lateral, com um atributo obrigatório e um de variação com opções', async ({
    page,
  }) => {
    const suffix = uniqueSuffix();
    const name = `Categoria E2E ${suffix}`;
    const code = `teste-${suffix}`;
    await openPanel(page, '/plataforma/categorias');

    await page.getByRole('button', { name: 'Categoria raiz' }).click();
    const form = page.getByRole('dialog', { name: 'Nova categoria raiz' });
    await form.getByLabel('Nome', { exact: true }).fill(name);
    await form.getByLabel('Código', { exact: true }).fill(code);
    await form.getByRole('button', { name: 'Adicionar atributo' }).click();
    const brand = attributeFields(form, 0);
    await brand.label.fill('Marca');
    await brand.code.fill('marca');
    await brand.required.check();
    await form.getByRole('button', { name: 'Adicionar atributo' }).click();
    const size = attributeFields(form, 1);
    await size.label.fill('Tamanho');
    await size.code.fill('tamanho');
    await size.type.selectOption({ label: 'Opção' });
    await size.required.check();
    await size.variant.check();
    const options = form.getByLabel('Opções', { exact: true });
    for (const option of ['P', 'M', 'G']) {
      await options.fill(option);
      await options.press('Enter');
    }
    await form.getByRole('button', { name: 'Criar categoria' }).click();

    await expect(page.getByText('Categoria criada.')).toBeVisible();
    await expect(form).toBeHidden();
    await expect(page).toHaveURL(/\/plataforma\/categorias\/[0-9a-f-]{36}$/);
    const id = idFromUrl(page, 'categorias');
    categoriesToDelete.push(id);

    await expect(categoryHeading(page, name)).toHaveText(`${name} Ativa`);
    await expect(page.getByText(`${name} | ${code} | nível 1`)).toBeVisible();
    await expect(attributesTable(page, 'próprios')).toMatchAriaSnapshot(`
      - table:
        - rowgroup:
          - row "Atributo Tipo Regras"
        - rowgroup:
          - /children: equal
          - row "Marca marca Texto Obrigatório"
          - row "Tamanho tamanho Opção P, M, G Obrigatório Variante do SKU"
    `);
    const saved = await admin.get<Category>(`/categories/${id}`);
    expect(saved).toMatchObject({ code, name, parentId: null, active: true });
    expect(saved.attributes).toMatchObject([
      { code: 'marca', label: 'Marca', type: 'TEXT', required: true, variant: false },
      {
        code: 'tamanho',
        label: 'Tamanho',
        type: 'OPTION',
        required: true,
        variant: true,
        options: ['P', 'M', 'G'],
      },
    ]);
  });

  test('cria a subcategoria pelo detalhe da pai, e ela herda os atributos da pai', async ({
    page,
  }) => {
    const parent = await createCategory();
    categoriesToDelete.push(parent.id);
    const suffix = uniqueSuffix();
    const name = `Subcategoria E2E ${suffix}`;
    const code = `teste-${suffix}`;
    await openPanel(page, `/plataforma/categorias/${parent.id}`);

    await page.getByRole('button', { name: `Ações de ${parent.name}` }).click();
    await page.getByRole('button', { name: 'Nova subcategoria' }).click();
    const form = page.getByRole('dialog', { name: 'Nova subcategoria' });
    await expect(form).toContainText(`Dentro de ${parent.name}`);
    await form.getByLabel('Nome', { exact: true }).fill(name);
    await form.getByLabel('Código', { exact: true }).fill(code);
    await form.getByRole('button', { name: 'Criar categoria' }).click();

    await expect(page.getByText('Categoria criada.')).toBeVisible();
    await expect(page).toHaveURL(
      new RegExp(`/plataforma/categorias/[0-9a-f-]{36}\\?parentId=${parent.id}$`),
    );
    const id = idFromUrl(page, 'categorias');
    categoriesToDelete.push(id);

    await expect(categoryHeading(page, name)).toHaveText(`${name} Ativa`);
    await expect(page.getByText(`${parent.name} › ${name} | ${code} | nível 2`)).toBeVisible();
    // NOTE: os formulários laterais fechados continuam no DOM com o mesmo texto vazio; vale o do card do detalhe.
    await expect(page.getByText('Nenhum atributo próprio.').filter({ visible: true })).toHaveCount(
      1,
    );
    await expect(attributesTable(page, 'herdados')).toMatchAriaSnapshot(`
      - table:
        - rowgroup:
          - row "Atributo Tipo Regras Herdado de"
        - rowgroup:
          - /children: equal
          - row "Marca marca Texto Obrigatório ${parent.name}"
          - row "Tamanho tamanho Opção P, M, G Obrigatório Variante do SKU ${parent.name}"
    `);
    expect(await admin.get<Category>(`/categories/${id}`)).toMatchObject({
      code,
      name,
      parentId: parent.id,
      attributes: [],
    });
  });

  test('edita o nome, desativa com confirmação e reativa a categoria', async ({ page }) => {
    const category = await createCategory();
    categoriesToDelete.push(category.id);
    const renamed = `Categoria E2E renomeada ${uniqueSuffix()}`;
    await openPanel(page, `/plataforma/categorias/${category.id}`);

    await page.getByRole('button', { name: 'Editar', exact: true }).click();
    const form = page.getByRole('dialog', { name: `Editar ${category.name}` });
    await expect(form.getByLabel('Código', { exact: true }).first()).toBeDisabled();
    await form.getByLabel('Nome', { exact: true }).fill(renamed);
    await form.getByRole('button', { name: 'Salvar' }).click();

    await expect(page.getByText('Categoria salva.')).toBeVisible();
    await expect(form).toBeHidden();
    await expect(categoryHeading(page, renamed)).toHaveText(`${renamed} Ativa`);
    const edited = await admin.get<Category>(`/categories/${category.id}`);
    expect(edited).toMatchObject({ name: renamed, code: category.code, active: true });
    expect(edited.attributes).toEqual(category.attributes);

    await page.getByRole('button', { name: `Ações de ${renamed}` }).click();
    await page.getByRole('button', { name: 'Desativar', exact: true }).click();
    const confirm = page.getByRole('dialog', { name: 'Desativar categoria' });
    await expect(confirm).toContainText(`${renamed} deixa de aceitar produtos novos.`);
    await confirm.getByRole('button', { name: 'Desativar', exact: true }).click();

    await expect(confirm).toBeHidden();
    await expect(page.getByText(`"${renamed}" desativada.`)).toBeVisible();
    await expect(categoryHeading(page, renamed)).toHaveText(`${renamed} Inativa`);
    expect(await admin.get<Category>(`/categories/${category.id}`)).toMatchObject({
      active: false,
      usable: false,
    });

    await page.getByRole('button', { name: `Ações de ${renamed}` }).click();
    await page.getByRole('button', { name: 'Ativar', exact: true }).click();

    await expect(page.getByText(`"${renamed}" ativada.`)).toBeVisible();
    await expect(categoryHeading(page, renamed)).toHaveText(`${renamed} Ativa`);
    expect(await admin.get<Category>(`/categories/${category.id}`)).toMatchObject({
      active: true,
      usable: true,
    });
  });

  test('exclui em lote um ramo vazio inteiro, a categoria pai e a filha numa vez só', async ({
    page,
  }) => {
    // WHY: o ramo fica dentro de uma categoria só deste teste, para o nível aberto não ser a raiz compartilhada.
    const container = await createCategory({ attributes: [] });
    const parent = await createCategory({ parentId: container.id, attributes: [] });
    const child = await createCategory({ parentId: parent.id, attributes: [] });
    categoriesToDelete.push(container.id, parent.id, child.id);
    await openPanel(page, `/plataforma/categorias?parentId=${container.id}`);

    await page.getByRole('button', { name: 'Selecionar', exact: true }).click();
    await page.getByRole('checkbox', { name: `Selecionar ${parent.name}`, exact: true }).check();
    await page.getByRole('link', { name: `Abrir a subcategoria de ${parent.name}` }).click();
    await expect(page).toHaveURL(new RegExp(`\\?parentId=${parent.id}$`));
    await page.getByRole('checkbox', { name: `Selecionar ${child.name}`, exact: true }).check();
    const bar = page.getByRole('toolbar', { name: 'Ações em lote' });
    await expect(bar).toContainText('2 selecionados');
    await bar.getByRole('button', { name: 'Excluir (2)' }).click();
    const confirm = page.getByRole('dialog', { name: 'Excluir 2 categorias' });
    await confirm.getByRole('button', { name: 'Excluir', exact: true }).click();

    await expect(page.getByText('2 excluídas')).toBeVisible();
    await expect(page.getByText('Algumas categorias não foram processadas')).toBeHidden();
    expect((await apiFailure(admin.get(`/categories/${parent.id}`))).status).toBe(404);
    expect((await apiFailure(admin.get(`/categories/${child.id}`))).status).toBe(404);

    await openPanel(page, `/plataforma/categorias?parentId=${container.id}`);
    await expect(page.getByText('Nenhuma subcategoria neste nível.')).toBeVisible();
  });

  test('recusa excluir a categoria com produto e mostra o texto do servidor, sem o código do motivo', async ({
    page,
  }) => {
    // NOTE: a categoria fica no banco: com produto, nem arquivado, ela não pode mais ser excluída.
    const category = await createCategory();
    await createDraftProduct({ category });
    const refusal = await apiFailure(
      admin.delete(`/categories/${category.id}`, { ifMatch: category.version }),
    );
    const hasProducts = refusal.problem?.reasonDetails?.find(
      (reason) => reason.code === 'category-has-products',
    );
    expect(refusal.status).toBe(409);
    expect(hasProducts?.message).toMatch(/\S/);
    expect(hasProducts?.message).not.toContain('category-has-products');
    await openPanel(page, `/plataforma/categorias/${category.id}`);

    await page.getByRole('button', { name: `Ações de ${category.name}` }).click();
    await page.getByRole('button', { name: 'Excluir', exact: true }).click();
    const confirm = page.getByRole('dialog', { name: 'Excluir categoria' });
    await confirm.getByRole('button', { name: 'Excluir', exact: true }).click();

    const alert = confirm.getByRole('alert');
    await expect(alert).toContainText('Não foi possível excluir');
    await expect(alert).toContainText(hasProducts?.message ?? '');
    await expect(confirm).not.toContainText('category-has-products');
    await expect(confirm.getByRole('button', { name: 'Desativar a categoria' })).toBeVisible();
    expect(await admin.get<Category>(`/categories/${category.id}`)).toMatchObject({
      id: category.id,
      active: true,
    });
  });
});

test.describe('motivos de moderação', () => {
  test('cria o motivo de rejeição, edita o rótulo, desativa com confirmação e reativa em Mostrar inativos', async ({
    page,
  }) => {
    const suffix = uniqueSuffix();
    const code = `teste-${suffix}`;
    const label = `Motivo E2E ${suffix}`;
    const relabeled = `Motivo E2E revisto ${suffix}`;
    await openPanel(page, '/plataforma/motivos-de-moderacao');

    await page.getByRole('button', { name: 'Novo motivo' }).click();
    const form = page.getByRole('dialog', { name: 'Novo motivo' });
    await form.getByLabel('Código', { exact: true }).fill(code);
    await form.getByLabel('Rótulo', { exact: true }).fill(label);
    await form.getByLabel('Rejeitar', { exact: true }).check();
    await form.getByRole('button', { name: 'Criar motivo' }).click();
    reasonsToDeactivate.push(code);

    await expect(page.getByText('Motivo criado. Ele já aparece para o moderador.')).toBeVisible();
    await expect(form).toBeHidden();
    await expect(reasonRow(page, code)).toContainText(`${label}${code}`);
    await expect(reasonRow(page, code)).toContainText('Rejeitar');
    await expect(reasonRow(page, code)).toContainText('Ativo');
    expect(await getReason(code)).toMatchObject({ label, decisions: ['REJECT'], active: true });

    await page.getByRole('button', { name: `Ações de ${label}` }).click();
    await page.getByRole('button', { name: 'Editar', exact: true }).click();
    const edit = page.getByRole('dialog', { name: 'Editar motivo' });
    await expect(edit.getByLabel('Rótulo', { exact: true })).toHaveValue(label);
    await expect(edit.getByLabel('Código', { exact: true })).toHaveAttribute('readonly', '');
    await edit.getByLabel('Rótulo', { exact: true }).fill(relabeled);
    await edit.getByRole('button', { name: 'Salvar' }).click();

    await expect(page.getByText('Motivo atualizado.')).toBeVisible();
    await expect(edit).toBeHidden();
    await expect(reasonRow(page, code)).toContainText(relabeled);
    expect(await getReason(code)).toMatchObject({
      label: relabeled,
      decisions: ['REJECT'],
      active: true,
    });

    await page.getByRole('button', { name: `Ações de ${relabeled}` }).click();
    await page.getByRole('button', { name: 'Desativar', exact: true }).click();
    const confirm = page.getByRole('dialog', { name: `Desativar "${relabeled}"?` });
    await confirm.getByRole('button', { name: 'Desativar', exact: true }).click();

    await expect(
      page.getByText('Motivo desativado. Ele não aparece mais para o moderador.'),
    ).toBeVisible();
    await expect(confirm).toBeHidden();
    await expect(reasonRow(page, code)).toBeHidden();
    expect(await getReason(code)).toMatchObject({ active: false });

    await page.getByLabel('Mostrar inativos').check();
    await expect(reasonRow(page, code)).toContainText('Inativo');
    await page.getByRole('button', { name: `Ações de ${relabeled}` }).click();
    await page.getByRole('button', { name: 'Reativar', exact: true }).click();

    await expect(
      page.getByText('Motivo reativado. Ele volta a aparecer para o moderador.'),
    ).toBeVisible();
    await expect(reasonRow(page, code)).toContainText('Ativo');
    await expect(reasonRow(page, code)).not.toContainText('Inativo');
    expect(await getReason(code)).toMatchObject({ label: relabeled, active: true });
  });

  test('recusa criar o motivo com código repetido e mostra a mensagem do servidor', async ({
    page,
  }) => {
    const existing = await createModerationReason({ decisions: ['REJECT'] });
    reasonsToDeactivate.push(existing.code);
    const attempt = {
      code: existing.code,
      label: `Motivo E2E repetido ${uniqueSuffix()}`,
      decisions: ['BLOCK'],
    };
    const refusal = await apiFailure(admin.post('/moderation/reasons', attempt));
    expect(refusal.status).toBe(409);
    expect(refusal.problem?.detail).toMatch(/\S/);
    await openPanel(page, '/plataforma/motivos-de-moderacao');

    await page.getByRole('button', { name: 'Novo motivo' }).click();
    const form = page.getByRole('dialog', { name: 'Novo motivo' });
    await form.getByLabel('Código', { exact: true }).fill(attempt.code);
    await form.getByLabel('Rótulo', { exact: true }).fill(attempt.label);
    await form.getByLabel('Bloquear', { exact: true }).check();
    await form.getByRole('button', { name: 'Criar motivo' }).click();

    const alert = form.getByRole('alert');
    await expect(alert).toContainText('Não foi possível salvar o motivo');
    await expect(alert).toContainText(refusal.problem?.detail ?? '');
    await expect(form).not.toContainText('duplicate-moderation-reason-code');
    await expect(page.getByLabel('Mostrar inativos')).toBeChecked();
    expect(await getReason(existing.code)).toMatchObject({
      label: existing.label,
      decisions: ['REJECT'],
      version: existing.version,
    });
  });
});

test.describe('produtos de todas as lojas', () => {
  test('mostra o produto criado no teste só para leitura e filtra pela loja', async ({ page }) => {
    const category = await createCategory();
    const title = `Produto E2E ${uniqueSuffix()}`;
    const product = await createDraftProduct({ category, title });
    await openPanel(page, '/plataforma/produtos');

    await page.getByLabel('Buscar por título ou SKU').fill(title);
    await page.getByRole('button', { name: 'Buscar', exact: true }).click();
    const row = page
      .getByRole('row')
      .filter({ has: page.getByRole('link', { name: title, exact: true }) });
    await expect(row).toBeVisible();
    await expect(row).toContainText(E2E_ENV.sellerStoreId);
    await expect(row).toContainText('Rascunho');
    await expect(row.getByRole('button', { name: `Ações de ${title}` })).toHaveCount(0);
    await expect(row.getByRole('checkbox')).toHaveCount(0);

    const store = page.getByLabel('Loja', { exact: true });
    await store.fill(E2E_ENV.sellerStoreId);
    await page.getByRole('button', { name: 'Filtrar' }).click();
    await expect(page).toHaveURL(new RegExp(`storeId=${E2E_ENV.sellerStoreId}`));
    await expect(row).toBeVisible();

    await store.fill(randomUUID());
    await page.getByRole('button', { name: 'Filtrar' }).click();
    await expect(row).toBeHidden();
    await expect(page.getByText(`Nenhum produto encontrado para “${title}”.`)).toBeVisible();

    await page.getByRole('button', { name: 'Todas', exact: true }).click();
    await expect(page).not.toHaveURL(/storeId=/);
    await expect(row).toBeVisible();

    await row.getByRole('link', { name: title, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/plataforma/produtos/${product.id}$`));
    await expect(
      page.getByRole('status').filter({ hasText: 'Conteúdo só leitura.' }),
    ).toBeVisible();
    await expect(page.getByLabel('Título', { exact: true })).toHaveValue(title);
    await expect(page.getByLabel('Título', { exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Salvar' })).toHaveCount(0);
  });
});

/**
 * Campos do cartão "Atributo N" (a partir de 0) do formulário da categoria, pela ordem dos rótulos: o código da
 * categoria vem antes do código de cada atributo.
 */
function attributeFields(form: Locator, index: number) {
  return {
    label: form.getByLabel('Rótulo', { exact: true }).nth(index),
    code: form.getByLabel('Código', { exact: true }).nth(index + 1),
    type: form.getByLabel('Tipo', { exact: true }).nth(index),
    required: form.getByLabel('Obrigatório', { exact: true }).nth(index),
    variant: form.getByLabel('Variante do SKU', { exact: true }).nth(index),
  };
}

/** Título do detalhe da categoria, que termina no badge Ativa ou Inativa. */
function categoryHeading(page: Page, name: string): Locator {
  return page.getByRole('heading', { level: 2, name: new RegExp(`^${name} `) });
}

/** Tabela de atributos próprios ou herdados do detalhe; só a dos herdados tem a coluna "Herdado de". */
function attributesTable(page: Page, kind: 'próprios' | 'herdados'): Locator {
  const origin = page.getByRole('columnheader', { name: 'Herdado de' });
  return page.getByRole('table').filter(kind === 'herdados' ? { has: origin } : { hasNot: origin });
}

/** Linha do motivo na lista, achada pelo código, que não muda quando o rótulo muda. */
function reasonRow(page: Page, code: string): Locator {
  return page.getByRole('row').filter({ hasText: code });
}

function idFromUrl(page: Page, section: string): string {
  const id = new RegExp(`/${section}/([0-9a-f-]{36})`).exec(page.url())?.[1];
  if (!id) {
    throw new Error(`A URL ${page.url()} não traz o id de ${section}`);
  }
  return id;
}

function getReason(code: string): Promise<ModerationReason> {
  return admin.get<ModerationReason>(`/moderation/reasons/${encodeURIComponent(code)}`);
}

async function deleteCategoryIfPresent(id: string): Promise<void> {
  try {
    const current = await admin.get<Category>(`/categories/${id}`);
    await admin.delete(`/categories/${id}`, { ifMatch: current.version });
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) {
      throw error;
    }
  }
}
