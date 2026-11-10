import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  type CategoryResponse,
  type CategorySummary,
  provideCatalogInventoryApi,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { routes } from './lib.routes';

const API = '/api/catalog-inventory';
const PROBLEMS = 'https://marketplace.dev/problems';

const SHIRTS: CategoryResponse = {
  id: 'c-cam',
  parentId: 'c-vest',
  code: 'VEST-CAM',
  name: 'Camisas',
  active: true,
  usable: true,
  version: 3,
  level: 2,
  childCount: 0,
  ancestors: [{ id: 'c-vest', code: 'VEST', name: 'Vestuário' }],
  attributes: [
    {
      code: 'gola',
      label: 'Gola',
      type: 'OPTION',
      required: false,
      variant: false,
      options: ['V', 'Redonda'],
    },
  ],
  effectiveAttributes: [
    {
      code: 'material',
      label: 'Material',
      type: 'TEXT',
      required: true,
      variant: false,
      categoryId: 'c-vest',
    },
    { code: 'gola', label: 'Gola', type: 'OPTION', options: ['V', 'Redonda'], categoryId: 'c-cam' },
  ],
};

const VEST: CategoryResponse = {
  id: 'c-vest',
  code: 'VEST',
  name: 'Vestuário',
  active: true,
  level: 1,
  childCount: 1,
  ancestors: [],
};

const LEVEL: CategorySummary[] = [
  {
    id: 'c-cam',
    code: 'VEST-CAM',
    name: 'Camisas',
    active: true,
    version: 3,
    level: 2,
    childCount: 0,
    attributeCount: 1,
    ancestors: [{ id: 'c-vest', name: 'Vestuário' }],
  },
];

const TREE: CategorySummary[] = [
  { id: 'c-vest', code: 'VEST', name: 'Vestuário', level: 1, usable: true, ancestors: [] },
  {
    id: 'c-cam',
    code: 'VEST-CAM',
    name: 'Camisas',
    level: 2,
    usable: true,
    ancestors: [{ id: 'c-vest', name: 'Vestuário' }],
  },
  {
    id: 'c-cam-polo',
    code: 'VEST-CAM-POLO',
    name: 'Polo',
    level: 3,
    usable: true,
    ancestors: [
      { id: 'c-vest', name: 'Vestuário' },
      { id: 'c-cam', name: 'Camisas' },
    ],
  },
  { id: 'c-casa', code: 'CASA', name: 'Casa', level: 1, usable: false, ancestors: [] },
];

describe('detalhe da categoria', () => {
  let http: HttpTestingController;
  /** Leituras respondidas por `drain`, sem o prefixo da API. */
  let drained: string[] = [];

  const render = () => {
    TestBed.tick();
    return document.body;
  };
  const text = () => (render().textContent ?? '').replace(/\s+/g, ' ');
  const toasts = () =>
    TestBed.inject(ToastService)
      .toasts()
      .map((toast) => toast.message);
  const request = (match: (req: TestRequest['request']) => boolean): Promise<TestRequest> =>
    vi.waitFor(() => {
      TestBed.tick();
      return http.expectOne(match);
    });
  const get = (url: string) =>
    request((req) => req.method === 'GET' && req.urlWithParams === `${API}${url}`);
  const shows = (fragment: string) =>
    vi.waitFor(() => {
      expect(text()).toContain(fragment);
    });
  const button = (label: string, root: ParentNode = document.body): HTMLButtonElement => {
    render();
    const found = [...root.querySelectorAll('button')].filter(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim() === label,
    );
    expect(found.length, `botão "${label}"`).toBeGreaterThan(0);
    return found[found.length - 1];
  };
  const dialog = (title: string): Promise<HTMLElement> =>
    vi.waitFor(() => {
      render();
      const found = [...document.body.querySelectorAll<HTMLElement>('.modal, .offcanvas')].find(
        (item) => item.textContent?.includes(title),
      );
      expect(found, `janela "${title}"`).toBeTruthy();
      return found as HTMLElement;
    });
  const field = (root: ParentNode, name: string, index = 0) =>
    [...root.querySelectorAll<HTMLInputElement>(`input[formcontrolname="${name}"]`)][index];
  const type = (input: HTMLInputElement, value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const menu = (choice: string) => {
    document.body
      .querySelector<HTMLButtonElement>('button[aria-label="Ações de Camisas"]')
      ?.click();
    button(choice).click();
  };
  const problem = (status: number, code: string, message: string) => ({
    type: `${PROBLEMS}/${code}`,
    title: 'Recusado',
    status,
    reasons: [code],
    reasonDetails: [{ code, params: [], message }],
  });

  /** Responde as leituras pendentes com respostas padrão, ou com a da URL em `answers`. */
  async function drain(answers: Readonly<Record<string, unknown>> = {}) {
    for (let round = 0; round < 8; round++) {
      await new Promise((resolve) => setTimeout(resolve, 5));
      TestBed.tick();
      for (const pending of http.match(() => true)) {
        const url = pending.request.urlWithParams.slice(API.length);
        drained.push(url);
        if (url in answers) {
          pending.flush(answers[url] as object);
        } else if (url === '/stats/categories') {
          pending.flush({ total: 3, roots: 2 });
        } else if (/^\/categories\/[^/?]+$/.test(url) && url !== '/categories/tree') {
          pending.flush(
            url === '/categories/c-vest' ? VEST : { ...SHIRTS, id: url.split('/')[2] },
            { headers: { ETag: '"9"' } },
          );
        } else {
          pending.flush({ items: LEVEL });
        }
      }
    }
  }

  async function open(category: CategoryResponse = SHIRTS) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([{ path: 'categorias', children: routes }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/categorias/${category.id}?parentId=c-vest`);
    (await get(`/categories/${category.id}`)).flush(category, {
      headers: { ETag: `"${category.version}"` },
    });
    await drain();
    await shows(`nível ${category.level}`);
  }

  afterEach(async () => {
    await drain();
    http.verify();
  });

  it('mostra situação, caminho, código e nível da API, com os atributos próprios e os herdados com a origem', async () => {
    await open();

    expect(text()).toContain('Camisas Ativa');
    expect(text()).toContain('Vestuário › Camisas | VEST-CAM | nível 2');
    const [own, inherited] = [...document.body.querySelectorAll('app-category-attributes-table')];
    expect(own.textContent).toContain('Gola');
    expect(own.textContent).toContain('V, Redonda');
    expect(own.textContent).not.toContain('Material');
    expect(inherited.textContent).toContain('Material');
    expect(inherited.textContent).toContain('Obrigatório');
    expect(inherited.textContent).toContain('Vestuário');
    expect(document.body.querySelector('a[aria-current="true"]')?.textContent).toContain('Camisas');
  });

  it('avisa quando um ancestral inativo impede produtos novos e esconde Excluir com subcategorias', async () => {
    await open({ ...SHIRTS, usable: false, childCount: 2 });

    expect(text()).toContain('não aceita produtos novos, porque uma categoria acima está inativa');
    expect(text()).toContain('Para excluir, primeiro exclua ou mova as subcategorias.');
    const items = [...document.body.querySelectorAll('app-category-page .dropdown-item')].map(
      (item) => item.textContent?.trim(),
    );
    expect(items).toEqual(['Mover para…', 'Nova subcategoria', 'Desativar']);
  });

  it('edita com o If-Match da versão lida e atualiza o detalhe e a lista', async () => {
    await open();
    button('Editar').click();
    const form = await dialog('Editar Camisas');
    expect(field(form, 'name').value).toBe('Camisas');
    expect(field(form, 'code').disabled).toBe(true);
    type(field(form, 'name'), 'Camisas sociais');
    button('Salvar', form).click();

    const save = await request(
      (req) => req.method === 'PUT' && req.url === `${API}/categories/c-cam`,
    );
    expect(save.request.headers.get('If-Match')).toBe('"3"');
    expect(save.request.body).toEqual({
      name: 'Camisas sociais',
      active: true,
      attributes: [
        {
          code: 'gola',
          label: 'Gola',
          type: 'OPTION',
          required: false,
          variant: false,
          options: ['V', 'Redonda'],
        },
      ],
    });
    save.flush({ ...SHIRTS, name: 'Camisas sociais', version: 4 }, { headers: { ETag: '"4"' } });

    await shows('Camisas sociais Ativa');
    expect(toasts()).toContain('Categoria salva.');
    await request((req) => req.urlWithParams === `${API}/categories?parentId=c-vest`);
  });

  it('abre o conflito no 412 e troca o que foi digitado pela versão atual', async () => {
    await open();
    button('Editar').click();
    const form = await dialog('Editar Camisas');
    type(field(form, 'name'), 'Camisas novas');
    button('Salvar', form).click();
    (await request((req) => req.method === 'PUT')).flush(
      problem(412, 'version-mismatch', 'A versão mudou.'),
      {
        status: 412,
        statusText: 'Precondition Failed',
      },
    );

    await shows('Outra pessoa alterou este registro depois que você abriu a tela.');
    expect(field(form, 'name').value).toBe('Camisas novas');
    button('Carregar versão atual', form).click();
    (await get('/categories/c-cam')).flush(
      { ...SHIRTS, name: 'Camisas de algodão', version: 5 },
      { headers: { ETag: '"5"' } },
    );

    await vi.waitFor(() => {
      render();
      expect(field(form, 'name').value).toBe('Camisas de algodão');
    });
    expect(text()).not.toContain('Outra pessoa alterou');
    await shows('Camisas de algodão Ativa');

    button('Salvar', form).click();
    const retry = await request((req) => req.method === 'PUT');
    expect(retry.request.headers.get('If-Match')).toBe('"5"');
    retry.flush(
      { ...SHIRTS, name: 'Camisas de algodão', version: 6 },
      { headers: { ETag: '"6"' } },
    );
  });

  it('leva os erros do 400 aos campos e as recusas do 422 ao alerta do formulário', async () => {
    await open();
    button('Editar').click();
    const form = await dialog('Editar Camisas');
    button('Salvar', form).click();
    (await request((req) => req.method === 'PUT')).flush(
      {
        type: `${PROBLEMS}/validation`,
        title: 'Requisição inválida',
        status: 400,
        errors: [
          { field: 'name', message: 'O nome passou do tamanho.' },
          { field: 'attributes[0].options[1]', message: 'A opção está vazia.' },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    await shows('O nome passou do tamanho.');
    expect(form.querySelector('.alert')?.textContent).toContain(
      'Atributo 1, opções: A opção está vazia.',
    );

    type(field(form, 'name'), 'Camisas');
    button('Salvar', form).click();
    (await request((req) => req.method === 'PUT')).flush(
      problem(422, 'attribute-conflict', 'O atributo gola já existe no caminho de destino.'),
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await shows('O atributo gola já existe no caminho de destino.');
    expect(text()).not.toContain('attribute-conflict');
  });

  it('cria a subcategoria e abre o detalhe dela no nível do pai', async () => {
    await open();
    menu('Nova subcategoria');
    const form = await dialog('Nova subcategoria');
    expect(form.textContent).toContain('Dentro de Vestuário › Camisas');
    type(field(form, 'name'), 'Polo');
    type(field(form, 'code'), 'VEST-CAM-POLO');
    button('Adicionar atributo', form).click();
    render();
    type(field(form, 'label'), 'Manga');
    type(field(form, 'code', 1), 'manga');
    button('Criar categoria', form).click();

    const create = await request((req) => req.method === 'POST' && req.url === `${API}/categories`);
    expect(create.request.body).toEqual({
      parentId: 'c-cam',
      code: 'VEST-CAM-POLO',
      name: 'Polo',
      attributes: [
        { code: 'manga', label: 'Manga', type: 'TEXT', required: false, variant: false },
      ],
    });
    drained = [];
    create.flush(
      { ...SHIRTS, id: 'c-polo', parentId: 'c-cam', code: 'VEST-CAM-POLO', name: 'Polo', level: 3 },
      { status: 201, statusText: 'Created' },
    );

    await drain({
      '/categories?parentId=c-cam': {
        items: [{ id: 'c-polo', code: 'VEST-CAM-POLO', name: 'Polo', childCount: 0 }],
      },
    });
    expect(TestBed.inject(Router).url).toBe('/categorias/c-polo?parentId=c-cam');
    expect(drained.filter((url) => url === '/categories?parentId=c-cam')).toHaveLength(1);
    expect(toasts()).toContain('Categoria criada.');
  });

  it('move com o If-Match, mostra a recusa do servidor no modal e oferece Raiz sem a própria, as descendentes e o pai', async () => {
    await open();
    menu('Mover para…');
    (await get('/categories/tree')).flush({ items: TREE });

    const modal = await vi.waitFor(async () => {
      const found = await dialog('Mover Camisas');
      expect(found.querySelector('select')).toBeTruthy();
      return found;
    });
    const select = modal.querySelector('select') as HTMLSelectElement;
    expect([...select.options].map((option) => option.textContent?.trim())).toEqual([
      'Escolha o novo pai ou a raiz',
      'Raiz',
      'Casa (inativa)',
    ]);

    button('Mover', modal).click();
    await shows('Escolha o novo pai ou a raiz.');

    select.value = 'c-casa';
    select.dispatchEvent(new Event('change'));
    button('Mover', modal).click();
    const refused = await request(
      (req) => req.method === 'PUT' && req.url === `${API}/categories/c-cam/parent`,
    );
    expect(refused.request.headers.get('If-Match')).toBe('"3"');
    expect(refused.request.body).toEqual({ parentId: 'c-casa' });
    refused.flush(
      problem(
        422,
        'category-too-deep',
        'A árvore de categorias aceita até 6 níveis e passaria disso nesse lugar.',
      ),
      {
        status: 422,
        statusText: 'Unprocessable Content',
      },
    );
    await shows('A árvore de categorias aceita até 6 níveis e passaria disso nesse lugar.');

    select.value = '__root__';
    select.dispatchEvent(new Event('change'));
    button('Mover', modal).click();
    const moved = await request(
      (req) => req.method === 'PUT' && req.url === `${API}/categories/c-cam/parent`,
    );
    expect(moved.request.body).toEqual({ parentId: null });
    moved.flush(
      { ...SHIRTS, parentId: undefined, ancestors: [], level: 1, version: 4 },
      { headers: { ETag: '"4"' } },
    );

    await shows('VEST-CAM | nível 1');
    expect(toasts()).toContain('"Camisas" movida para a raiz.');
  });

  it('desativa depois da confirmação, com o aviso das subcategorias', async () => {
    await open({ ...SHIRTS, childCount: 1 });
    menu('Desativar');
    const modal = await dialog('Desativar categoria');
    expect(modal.textContent?.replace(/\s+/g, ' ')).toContain(
      'Camisas e as subcategorias deixam de aceitar produtos novos.',
    );
    button('Desativar', modal).click();

    const save = await request(
      (req) => req.method === 'PUT' && req.url === `${API}/categories/c-cam`,
    );
    expect(save.request.headers.get('If-Match')).toBe('"3"');
    expect(save.request.body).toMatchObject({ name: 'Camisas', active: false });
    save.flush(
      { ...SHIRTS, childCount: 1, active: false, usable: false, version: 4 },
      { headers: { ETag: '"4"' } },
    );

    await shows('Camisas Inativa');
    expect(toasts()).toContain('"Camisas" desativada.');
  });

  it('ativa sem modal e mostra o conflito no card quando a versão mudou', async () => {
    await open({ ...SHIRTS, active: false });
    menu('Ativar');
    (await request((req) => req.method === 'PUT')).flush(
      problem(412, 'version-mismatch', 'A versão mudou.'),
      {
        status: 412,
        statusText: 'Precondition Failed',
      },
    );
    await shows('Outra pessoa alterou este registro');
    button('Carregar versão atual').click();
    (await get('/categories/c-cam')).flush(
      { ...SHIRTS, active: true, version: 6 },
      { headers: { ETag: '"6"' } },
    );
    await shows('Camisas Ativa');
    expect(text()).not.toContain('Outra pessoa alterou');
  });

  it('mantém o detalhe de outra categoria aberta antes da resposta da ativação, sem levar a recusa ou o card de uma para a outra', async () => {
    const heading = () =>
      document.body.querySelector('app-category-page h2')?.textContent?.replace(/\s+/g, ' ').trim();
    await open({ ...SHIRTS, active: false });
    menu('Ativar');
    (await request((req) => req.method === 'PUT')).flush(
      problem(412, 'version-mismatch', 'A versão mudou.'),
      {
        status: 412,
        statusText: 'Precondition Failed',
      },
    );
    await shows('Outra pessoa alterou este registro');

    await TestBed.inject(Router).navigateByUrl('/categorias/c-vest?parentId=c-vest');
    (await get('/categories/c-vest')).flush(VEST, { headers: { ETag: '"1"' } });
    await drain();
    await vi.waitFor(() => expect(heading()).toContain('Vestuário'));
    expect(text()).not.toContain('Outra pessoa alterou este registro');

    await TestBed.inject(Router).navigateByUrl('/categorias/c-cam?parentId=c-vest');
    (await get('/categories/c-cam')).flush(
      { ...SHIRTS, active: false },
      { headers: { ETag: '"3"' } },
    );
    await drain();
    await vi.waitFor(() => expect(heading()).toContain('Camisas'));
    menu('Ativar');
    const put = await request((req) => req.method === 'PUT');
    await TestBed.inject(Router).navigateByUrl('/categorias/c-vest?parentId=c-vest');
    (await get('/categories/c-vest')).flush(VEST, { headers: { ETag: '"1"' } });
    put.flush({ ...SHIRTS, active: true, version: 6 }, { headers: { ETag: '"6"' } });
    await drain();

    await vi.waitFor(() => expect(heading()).toContain('Vestuário'));
    expect(toasts()).not.toContain('"Camisas" ativada.');
  });

  it('exclui só a vazia: mostra a recusa category-has-products com a saída e, depois, abre a categoria pai', async () => {
    await open();
    menu('Excluir');
    const modal = await dialog('Excluir categoria');
    expect(modal.textContent?.replace(/\s+/g, ' ')).toContain(
      'Camisas (VEST-CAM) será excluída definitivamente',
    );
    button('Excluir', modal).click();

    const refused = await request(
      (req) => req.method === 'DELETE' && req.url === `${API}/categories/c-cam`,
    );
    expect(refused.request.headers.get('If-Match')).toBe('"3"');
    refused.flush(
      problem(
        409,
        'category-has-products',
        'A categoria tem produtos, inclusive arquivados. Troque a categoria deles ou desative esta categoria.',
      ),
      { status: 409, statusText: 'Conflict' },
    );
    await shows('A categoria tem produtos, inclusive arquivados.');
    expect(button('Desativar a categoria', modal)).toBeTruthy();

    button('Excluir', modal).click();
    (await request((req) => req.method === 'DELETE')).flush(null, {
      status: 204,
      statusText: 'No Content',
    });

    await drain();
    expect(TestBed.inject(Router).url).toBe('/categorias/c-vest?parentId=c-vest');
    expect(toasts()).toContain('"Camisas" excluída.');
    await shows('Vestuário Ativa');
  });
});
