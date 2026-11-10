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
import {
  BATCH_RUNNING_WARNING,
  ToastService,
  UNSAVED_CHANGES_WARNING,
} from '@marketplace/shared-admin-ui';

import { CategoriesPageComponent } from './categories-page.component';
import { routes } from './lib.routes';

const API = '/api/catalog-inventory';
const PROBLEMS = 'https://marketplace.dev/problems';

const ROOTS: CategorySummary[] = [
  {
    id: 'c-vest',
    code: 'VEST',
    name: 'Vestuário',
    active: true,
    usable: true,
    version: 1,
    level: 1,
    childCount: 2,
    attributeCount: 1,
    ancestors: [],
  },
  {
    id: 'c-casa',
    code: 'CASA',
    name: 'Casa',
    active: false,
    usable: false,
    version: 4,
    level: 1,
    childCount: 0,
    attributeCount: 0,
    ancestors: [],
  },
];

const VEST_CHILDREN: CategorySummary[] = [
  {
    id: 'c-cal',
    code: 'VEST-CAL',
    name: 'Calças',
    active: true,
    version: 2,
    level: 2,
    childCount: 0,
    attributeCount: 1,
    ancestors: [{ id: 'c-vest', name: 'Vestuário' }],
  },
  {
    id: 'c-cam',
    code: 'VEST-CAM',
    name: 'Camisas',
    active: true,
    version: 3,
    level: 2,
    childCount: 1,
    attributeCount: 3,
    ancestors: [{ id: 'c-vest', name: 'Vestuário' }],
  },
];

const VEST: CategoryResponse = {
  id: 'c-vest',
  code: 'VEST',
  name: 'Vestuário',
  active: true,
  level: 1,
  childCount: 2,
  ancestors: [],
};

describe('categorias por nível', () => {
  let http: HttpTestingController;
  let page: CategoriesPageComponent;
  /** Leituras respondidas por `drain`, sem o prefixo da API. */
  let drained: string[] = [];

  const render = () => {
    TestBed.tick();
    return document.body;
  };
  const text = () => (render().textContent ?? '').replace(/\s+/g, ' ');
  const toasts = () => TestBed.inject(ToastService).toasts();
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
  const check = (label: string) => {
    const box = document.body.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
    expect(box, label).toBeTruthy();
    box?.click();
  };
  const type = (input: HTMLInputElement, value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

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
          pending.flush({ total: 14, roots: 3 });
        } else if (url === '/categories/c-vest') {
          pending.flush(VEST, { headers: { ETag: '"1"' } });
        } else if (url.startsWith('/categories?parentId=c-vest')) {
          pending.flush({ items: VEST_CHILDREN });
        } else {
          pending.flush({ items: ROOTS });
        }
      }
    }
  }

  async function open(url = '/categorias') {
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
    await harness.navigateByUrl(url);
    page = harness.routeDebugElement?.componentInstance as CategoriesPageComponent;
  }

  afterEach(async () => {
    await drain();
    http.verify();
  });

  it('lista as raízes com código, atributos próprios, badge Inativa, subcategorias e os números da taxonomia', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    (await get('/stats/categories')).flush({ total: 14, roots: 3 });

    await shows('14 categorias | 3 raízes');
    expect(text()).toContain('VEST | 1 atributo próprio');
    expect(text()).toContain('Casa Inativa');
    expect(text()).toContain('CASA | 0 atributos próprios');
    expect(
      document.body.querySelector('a[aria-label="Abrir as 2 subcategorias de Vestuário"]'),
    ).toBeTruthy();
    expect(document.body.querySelector('a[aria-label*="subcategorias de Casa"]')).toBeNull();
    expect(text()).toContain('Selecione uma categoria.');
    expect(button('Categoria raiz')).toBeTruthy();
    const actions = button('Categoria raiz').parentElement as HTMLElement;
    expect([...actions.querySelectorAll('button')].map((item) => item.textContent?.trim())).toEqual(
      ['Selecionar', 'Categoria raiz'],
    );
    expect(actions.classList).toContain('d-flex');
  });

  it('desce um nível pela pílula, monta o breadcrumb com os ancestrais e volta pela raiz', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');

    document.body
      .querySelector<HTMLAnchorElement>('a[aria-label="Abrir as 2 subcategorias de Vestuário"]')
      ?.click();
    (await get('/categories?parentId=c-vest')).flush({ items: VEST_CHILDREN });
    (await get('/categories/c-vest')).flush(VEST);

    await shows('VEST-CAM | 3 atributos próprios');
    expect(TestBed.inject(Router).url).toBe('/categorias?parentId=c-vest');
    const crumbs = [...document.body.querySelectorAll('c-breadcrumb-item')];
    expect(crumbs.map((crumb) => crumb.textContent?.trim())).toEqual(['Raiz', 'Vestuário']);
    expect(crumbs[1].getAttribute('aria-current')).toBe('page');
    expect(button('Subcategoria')).toBeTruthy();
    expect(
      document.body.querySelector('a[aria-label="Abrir a subcategoria de Camisas"]'),
    ).toBeTruthy();

    crumbs[0].querySelector('a')?.click();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('CASA | 0 atributos próprios');
    expect(TestBed.inject(Router).url).toBe('/categorias');
  });

  it('mostra o erro do servidor com Tentar de novo', async () => {
    await open();
    (await get('/categories')).flush(
      {
        type: `${PROBLEMS}/service-unavailable`,
        title: 'Serviço indisponível',
        status: 503,
        detail: 'O catálogo está fora do ar.',
      },
      { status: 503, statusText: 'Service Unavailable' },
    );
    await shows('Não foi possível carregar as categorias.');
    expect(text()).toContain('O catálogo está fora do ar.');

    button('Tentar de novo').click();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('VEST | 1 atributo próprio');
  });

  it('seleciona todas do nível e conta em cada botão só as que aceitam a ação', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');

    button('Selecionar').click();
    render();
    document.body.querySelector<HTMLInputElement>('#app-categories-select-level')?.click();

    await shows('2 selecionados');
    expect(button('Ativar (1)')).toBeTruthy();
    expect(button('Desativar (1)')).toBeTruthy();
    expect(button('Excluir (2)')).toBeTruthy();

    button('Concluir seleção').click();
    await vi.waitFor(() => expect(text()).not.toContain('selecionados'));
    expect(page.selection.count()).toBe(0);
  });

  it('ativa em lote sem confirmação, com a versão de cada item, e conta os ignorados', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');
    button('Selecionar').click();
    render();
    check('Selecionar Vestuário');
    check('Selecionar Casa');
    button('Ativar (1)').click();

    const batch = await request(
      (req) => req.method === 'POST' && req.url === `${API}/categories/batch`,
    );
    expect(batch.request.body).toEqual({
      action: 'ACTIVATE',
      items: [{ id: 'c-casa', version: 4 }],
    });
    expect(page.leaveWarning()).toBe(BATCH_RUNNING_WARNING);
    batch.flush({ results: [{ id: 'c-casa', outcome: 'SUCCEEDED', active: true, version: 5 }] });

    await vi.waitFor(() =>
      expect(toasts().map((toast) => toast.message)).toContain('1 ativada, 1 ignorada'),
    );
    expect(page.selection.items().map((item) => item.id)).toEqual(['c-vest']);
    expect(page.leaveWarning()).toBeNull();
    await request((req) => req.method === 'GET' && req.urlWithParams === `${API}/categories`);
  });

  it('solta da seleção do lote a categoria excluída pelo detalhe', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');
    button('Selecionar').click();
    render();
    check('Selecionar Casa');
    await vi.waitFor(() => expect(page.selection.count()).toBe(1));

    await TestBed.inject(Router).navigateByUrl('/categorias/c-casa');
    (await get('/categories/c-casa')).flush(
      {
        id: 'c-casa',
        code: 'CASA',
        name: 'Casa',
        active: false,
        version: 4,
        level: 1,
        childCount: 0,
        ancestors: [],
      },
      { headers: { ETag: '"4"' } },
    );
    await drain();
    document.body.querySelector<HTMLButtonElement>('button[aria-label="Ações de Casa"]')?.click();
    button('Excluir').click();
    const modal = await dialog('Excluir categoria');
    button('Excluir', modal).click();
    (
      await request((req) => req.method === 'DELETE' && req.url === `${API}/categories/c-casa`)
    ).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await drain();

    expect(page.selection.count()).toBe(0);
  });

  it('exclui em lote depois da confirmação e mantém as recusadas selecionadas, com a mensagem do servidor', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');
    button('Selecionar').click();
    render();
    check('Selecionar Casa');
    button('Excluir (1)').click();

    const modal = await dialog('Excluir 1 categoria');
    expect(modal.textContent).toContain('Não há como desfazer.');
    button('Excluir', modal).click();

    const batch = await request(
      (req) => req.method === 'POST' && req.url === `${API}/categories/batch`,
    );
    expect(batch.request.body).toEqual({ action: 'DELETE', items: [{ id: 'c-casa', version: 4 }] });
    batch.flush({
      results: [
        {
          id: 'c-casa',
          outcome: 'FAILED',
          problem: {
            type: `${PROBLEMS}/category-has-products`,
            title: 'Conflito',
            status: 409,
            reasons: ['category-has-products'],
            reasonDetails: [
              {
                code: 'category-has-products',
                params: [],
                message:
                  'A categoria tem produtos, inclusive arquivados. Troque a categoria deles ou desative esta categoria.',
              },
            ],
          },
        },
      ],
    });

    await shows('Casa: A categoria tem produtos, inclusive arquivados.');
    expect(text()).not.toContain('category-has-products');
    expect(toasts().map((toast) => [toast.kind, toast.message])).toContainEqual([
      'warning',
      '1 com falha',
    ]);
    expect(page.selection.items().map((item) => item.id)).toEqual(['c-casa']);
  });

  it('manda ao lote de Excluir também a categoria com subcategorias e mostra a recusa do servidor', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');
    button('Selecionar').click();
    render();
    check('Selecionar Vestuário');
    button('Excluir (1)').click();

    const modal = await dialog('Excluir 1 categoria');
    button('Excluir', modal).click();

    const batch = await request(
      (req) => req.method === 'POST' && req.url === `${API}/categories/batch`,
    );
    expect(batch.request.body).toEqual({ action: 'DELETE', items: [{ id: 'c-vest', version: 1 }] });
    batch.flush({
      results: [
        {
          id: 'c-vest',
          outcome: 'FAILED',
          problem: {
            type: `${PROBLEMS}/category-has-children`,
            title: 'Conflito',
            status: 409,
            reasons: ['category-has-children'],
            reasonDetails: [
              {
                code: 'category-has-children',
                params: [],
                message: 'Exclua ou mova as subcategorias antes de excluir esta categoria.',
              },
            ],
          },
        },
      ],
    });

    await shows('Vestuário: Exclua ou mova as subcategorias antes de excluir esta categoria.');
    expect(text()).not.toContain('category-has-children');
    expect(page.selection.items().map((item) => item.id)).toEqual(['c-vest']);
  });

  it('cria a categoria raiz e abre o detalhe dela pelo id da resposta, sem reler o nível para achá-lo', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');

    button('Categoria raiz').click();
    const form = await dialog('Nova categoria raiz');
    button('Criar categoria', form).click();
    await shows('Preencha este campo.');
    http.expectNone((req) => req.method === 'POST');

    type(form.querySelector('input[formcontrolname="name"]') as HTMLInputElement, 'Esporte');
    type(form.querySelector('input[formcontrolname="code"]') as HTMLInputElement, 'ESP');
    expect(page.leaveWarning()).toBe(UNSAVED_CHANGES_WARNING);
    button('Criar categoria', form).click();

    const create = await request((req) => req.method === 'POST' && req.url === `${API}/categories`);
    expect(create.request.body).toEqual({ code: 'ESP', name: 'Esporte', attributes: [] });
    const esporte: CategoryResponse = {
      id: 'c-esp',
      code: 'ESP',
      name: 'Esporte',
      active: true,
      level: 1,
      ancestors: [],
    };
    drained = [];
    create.flush(esporte, { status: 201, statusText: 'Created' });

    const created = {
      items: [
        ...ROOTS,
        {
          id: 'c-esp',
          code: 'ESP',
          name: 'Esporte',
          active: true,
          childCount: 0,
          attributeCount: 0,
        },
      ],
    };
    await drain({ '/categories': created, '/categories/c-esp': esporte });
    expect(TestBed.inject(Router).url).toBe('/categorias/c-esp');
    expect(drained.filter((url) => url === '/categories')).toHaveLength(1);
    expect(drained).toContain('/categories/c-esp');
    expect(toasts().map((toast) => toast.message)).toContain('Categoria criada.');
    expect(page.leaveWarning()).toBeNull();
  });

  it('mostra o código duplicado embaixo do campo, com a mensagem do servidor', async () => {
    await open();
    (await get('/categories')).flush({ items: ROOTS });
    await shows('Vestuário');
    button('Categoria raiz').click();
    const form = await dialog('Nova categoria raiz');
    type(form.querySelector('input[formcontrolname="name"]') as HTMLInputElement, 'Vestuário 2');
    type(form.querySelector('input[formcontrolname="code"]') as HTMLInputElement, 'VEST');
    button('Criar categoria', form).click();
    (await request((req) => req.method === 'POST')).flush(
      {
        type: `${PROBLEMS}/duplicate-category-code`,
        title: 'Conflito',
        status: 409,
        detail: 'Já existe uma categoria com o código VEST.',
      },
      { status: 409, statusText: 'Conflict' },
    );

    await vi.waitFor(() => {
      render();
      expect(
        form.querySelector('input[formcontrolname="code"] ~ .invalid-feedback')?.textContent,
      ).toContain('Já existe uma categoria com o código VEST.');
    });
    expect(form.querySelector('.alert')).toBeNull();
  });
});
