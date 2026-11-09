import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideCatalogInventoryApi, type ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, BATCH_RUNNING_WARNING, NO_GRANTS, ToastService } from '@marketplace/shared-admin-ui';

import { ProductListPageComponent } from './product-list-page.component';

const API = '/api/catalog-inventory';
const STORE = '22222222-2222-4222-8222-222222222222';

const NOT_PUBLISHABLE = {
  action: 'SUBMIT',
  reason: { code: 'product-not-publishable', params: [], message: 'O produto ainda tem pendências para ser publicado.' }
};

const CAMISETA: ProductResponse = {
  id: 'p-1',
  title: 'Camiseta Basic',
  status: 'ACTIVE',
  version: 3,
  skus: [{}, {}, {}],
  createdAt: '2026-11-02T13:12:00Z',
  updatedAt: '2026-11-08T17:02:00Z',
  actions: ['EDIT', 'PAUSE', 'ARCHIVE', 'CHANGE_CATEGORY']
};
const TENIS: ProductResponse = {
  id: 'p-2',
  title: 'Tênis Run',
  status: 'DRAFT',
  version: 7,
  skus: [{}, {}],
  createdAt: '2026-11-05T14:00:00Z',
  updatedAt: '2026-11-07T12:15:00Z',
  actions: ['EDIT', 'SUBMIT', 'ARCHIVE'],
  lastRejection: {
    reasonCodes: ['wrong-category'],
    reasonLabels: [{ code: 'wrong-category', label: 'Categoria incorreta' }],
    note: 'Use Calçados'
  }
};
const CANECA: ProductResponse = {
  id: 'p-3',
  title: 'Caneca 300ml',
  status: 'PAUSED_BY_SELLER',
  version: 2,
  skus: [{}],
  actions: ['EDIT', 'ARCHIVE'],
  blockedActions: [{ action: 'RESUME', reason: { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada.' } }]
};

class FakeSession extends AdminSession {
  readonly grants = signal(NO_GRANTS);
  readonly storeId = signal<string | undefined>(STORE);
  readonly tenantId = signal<string | undefined>('11111111-1111-4111-8111-111111111111');
}

@Component({ template: '<h1>Conteúdo</h1>' })
class ContentStub {}

class InertIntersectionObserver {
  observe(): void {
    return undefined;
  }
  unobserve(): void {
    return undefined;
  }
  disconnect(): void {
    return undefined;
  }
  takeRecords(): [] {
    return [];
  }
}

describe('ProductListPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let page: ProductListPageComponent;

  const element = () => harness.routeNativeElement as HTMLElement;
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const paramsOf = (request: TestRequest) =>
    Object.fromEntries(new URL(request.request.urlWithParams, 'http://localhost').searchParams);
  const listRequest = () =>
    http.expectOne(request => request.method === 'GET' && request.url.startsWith(`${API}/products?`));
  const eligibilityRequest = () => http.expectOne(`${API}/stores/${STORE}/eligibility`);
  const rows = () => Array.from(element().querySelectorAll('tbody tr')) as HTMLTableRowElement[];
  const row = (title: string) => rows().find(item => item.textContent?.includes(title)) as HTMLTableRowElement;
  const buttonByText = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      button => button.textContent?.replace(/\s+/g, ' ').trim() === label
    ) as HTMLButtonElement;
  const menuItem = (title: string, label: string) => buttonByText(row(title), label);
  const render = () => TestBed.tick();
  const settle = async () => {
    await harness.fixture.whenStable();
    render();
  };
  const nextList = () =>
    vi.waitFor(() => {
      render();
      return listRequest();
    });

  async function open(url = '/loja/produtos', items: ProductResponse[] = [CAMISETA, TENIS, CANECA], canPublish = true) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([
          { path: 'loja/produtos', component: ProductListPageComponent },
          { path: 'loja/produtos/:id/conteudo', component: ContentStub }
        ]),
        { provide: AdminSession, useClass: FakeSession }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    page = await harness.navigateByUrl(url, ProductListPageComponent);
    render();
    const list = listRequest();
    eligibilityRequest().flush({ storeId: STORE, status: canPublish ? 'APPROVED' : 'PENDING', canPublish });
    list.flush({ items, nextCursor: 'c-2' });
    await settle();
    return list;
  }

  beforeAll(() => {
    if (!('IntersectionObserver' in globalThis)) {
      Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: InertIntersectionObserver });
    }
  });

  afterEach(() => http.verify());

  it('lista os produtos da URL com status, título como link, SKUs e o menu do que o servidor libera', async () => {
    const list = await open('/loja/produtos?status=ACTIVE&q=camiseta&limit=50');

    expect(paramsOf(list)).toEqual({ status: 'ACTIVE', q: 'camiseta', sort: '-updatedAt', limit: '50' });
    expect(element().querySelector('h1')?.textContent?.trim()).toBe('Produtos');
    expect(rows()).toHaveLength(3);
    const link = row('Camiseta Basic').querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/loja/produtos/p-1');
    expect(row('Camiseta Basic').textContent).toContain('Ativo');
    expect(row('Camiseta Basic').querySelector('strong')?.textContent?.trim()).toBe('3');
    expect(row('Camiseta Basic').querySelectorAll('.d-xl-table-cell')).toHaveLength(1);
    expect(Array.from(row('Camiseta Basic').querySelectorAll('[cDropdownItem]')).map(item => item.textContent?.trim())).toEqual(
      ['Editar', 'Pausar', 'Arquivar']
    );
    const resume = menuItem('Caneca 300ml', 'Reativar : A loja ainda não foi aprovada.');
    expect(resume.disabled).toBe(true);
    const active = element().querySelector('[aria-current="page"]');
    expect(active?.textContent?.trim()).toBe('Ativos');
    expect(element().querySelector('input[type="search"]')).toHaveProperty('value', 'camiseta');
  });

  it('mostra o Rejeitado com os rótulos e o complemento da última rejeição, sem o código do motivo', async () => {
    await open();

    const badge = Array.from(row('Tênis Run').querySelectorAll('c-badge')).find(item =>
      item.textContent?.includes('Rejeitado')
    );
    expect(badge?.textContent).toContain('Motivo: Categoria incorreta. Complemento: Use Calçados');
    expect(row('Tênis Run').textContent).not.toContain('wrong-category');
    expect(row('Camiseta Basic').textContent).not.toContain('Rejeitado');
  });

  it('avisa quando a loja ainda não foi aprovada', async () => {
    await open('/loja/produtos', [CAMISETA], false);

    expect(text()).toContain(
      'Sua loja ainda não foi aprovada. Você pode cadastrar produtos, mas eles só são aprovados depois do KYB/KYC.'
    );
  });

  it('não mostra o aviso da loja aprovada', async () => {
    await open('/loja/produtos', [CAMISETA], true);

    expect(text()).not.toContain('Sua loja ainda não foi aprovada');
  });

  it('mostra o estado vazio da aba com a ação de criar produto', async () => {
    await open('/loja/produtos?status=DRAFT', []);

    expect(text()).toContain('Nenhum produto em rascunho.');
    expect(buttonByText(element(), 'Criar produto')).toBeTruthy();
  });

  it('mostra o erro de carregamento com a mensagem do servidor e tenta de novo', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([{ path: 'loja/produtos', component: ProductListPageComponent }]),
        { provide: AdminSession, useClass: FakeSession }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/loja/produtos', ProductListPageComponent);
    render();
    eligibilityRequest().flush({ canPublish: true });
    listRequest().flush(
      { type: 'https://marketplace.dev/problems/service-unavailable', title: 'Indisponível', detail: 'Banco fora do ar' },
      { status: 503, statusText: 'Service Unavailable' }
    );
    await settle();

    expect(text()).toContain('Não foi possível carregar os produtos.');
    expect(text()).toContain('Banco fora do ar');
    buttonByText(element(), 'Tentar de novo').click();
    (await nextList()).flush({ items: [CAMISETA] });
    await settle();
    expect(rows()).toHaveLength(1);
  });

  it('limita a busca pelo contrato e mostra o 400 da busca embaixo do campo, sem trocar a lista pelo erro', async () => {
    const long = 'x'.repeat(101);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([{ path: 'loja/produtos', component: ProductListPageComponent }]),
        { provide: AdminSession, useClass: FakeSession }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/loja/produtos?q=${long}`, ProductListPageComponent);
    render();
    eligibilityRequest().flush({ canPublish: true });
    listRequest().flush(
      {
        type: 'https://marketplace.dev/problems/validation-failed',
        title: 'Um ou mais campos são inválidos',
        status: 400,
        errors: [{ field: 'q', message: 'A busca pode ter no máximo 100 caracteres.' }]
      },
      { status: 400, statusText: 'Bad Request' }
    );
    await settle();

    const search = element().querySelector('input[name="q"]') as HTMLInputElement;
    expect(search.getAttribute('maxlength')).toBe('100');
    expect(search.closest('form')?.textContent).toContain('A busca pode ter no máximo 100 caracteres.');
    expect(text()).not.toContain('Não foi possível carregar os produtos.');
    expect(text()).toContain('Corrija a busca para ver os produtos.');

    (search.closest('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();
    expect(search.closest('form')?.textContent).toContain('A busca aceita até 100 caracteres.');
    http.expectNone(request => request.url.startsWith(`${API}/products?`));

    buttonByText(element(), 'Limpar busca').click();
    const list = await nextList();
    expect(paramsOf(list)['q']).toBeUndefined();
    list.flush({ items: [CAMISETA] });
    await settle();
    expect(rows()).toHaveLength(1);
  });

  it('ordena pelo cabeçalho e volta à primeira página', async () => {
    await open('/loja/produtos?cursor=c-9');

    buttonByText(element().querySelector('thead') as HTMLElement, 'Produto').click();
    const list = await nextList();

    expect(paramsOf(list)).toEqual({ sort: 'title', limit: '20' });
    list.flush({ items: [CAMISETA] });
  });

  it('pausa pelo menu com o If-Match da versão lida, confirma no toast e recarrega a lista', async () => {
    await open();

    menuItem('Camiseta Basic', 'Pausar').click();
    const pause = await vi.waitFor(() => http.expectOne(`${API}/products/p-1/pause`));

    expect(pause.request.method).toBe('POST');
    expect(pause.request.headers.get('If-Match')).toBe('"3"');
    pause.flush({ ...CAMISETA, status: 'PAUSED_BY_SELLER', version: 4 });
    (await nextList()).flush({ items: [CAMISETA] });
    expect(TestBed.inject(ToastService).toasts().map(toast => toast.message)).toEqual([
      'Produto pausado. Ele sai da vitrine até você reativar.'
    ]);
  });

  it('abre o produto na aba Conteúdo pelo Editar', async () => {
    await open();

    menuItem('Camiseta Basic', 'Editar').click();

    await vi.waitFor(() => expect(harness.routeNativeElement?.textContent).toContain('Conteúdo'));
    expect(TestBed.inject(Router).url).toBe('/loja/produtos/p-1/conteudo');
  });

  it('mostra o aviso de conflito no 412 e recarrega a versão atual', async () => {
    await open();

    menuItem('Camiseta Basic', 'Pausar').click();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p-1/pause`))).flush(
      { type: 'https://marketplace.dev/problems/version-mismatch', title: 'Versão desatualizada' },
      { status: 412, statusText: 'Precondition Failed' }
    );

    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Outra pessoa alterou este registro');
    });
    buttonByText(element(), 'Carregar versão atual').click();
    (await nextList()).flush({ items: [CAMISETA] });
    await settle();
    expect(text()).not.toContain('Outra pessoa alterou este registro');
  });

  it('mostra no alerta a mensagem do servidor quando a ação é recusada, nunca o código', async () => {
    await open();

    menuItem('Tênis Run', 'Enviar para moderação').click();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p-2/submit`))).flush(
      {
        type: 'https://marketplace.dev/problems/domain-rule-violation',
        title: 'Regra de negócio violada',
        reasons: ['product-not-publishable'],
        reasonDetails: [NOT_PUBLISHABLE.reason]
      },
      { status: 422, statusText: 'Unprocessable Entity' }
    );

    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Não foi possível enviar para moderação “Tênis Run”');
    });
    expect(text()).toContain('O produto ainda tem pendências para ser publicado.');
    expect(text()).not.toContain('product-not-publishable');
  });

  it('arquiva pelo menu só depois da confirmação', async () => {
    await open();

    menuItem('Camiseta Basic', 'Arquivar').click();
    render();
    const modal = document.querySelector('app-confirm-modal') as HTMLElement;
    expect(modal.textContent).toContain('Camiseta Basic');
    expect(modal.textContent).toContain('Arquivar não pode ser desfeito.');
    http.expectNone(`${API}/products/p-1`);

    buttonByText(modal.querySelector('c-modal-footer') as HTMLElement, 'Arquivar').click();
    const archive = await vi.waitFor(() => http.expectOne(`${API}/products/p-1`));
    expect(archive.request.method).toBe('DELETE');
    expect(archive.request.headers.get('If-Match')).toBe('"3"');
    archive.flush({ ...CAMISETA, status: 'ARCHIVED' });
    (await nextList()).flush({ items: [] });
    await vi.waitFor(() => expect(page.confirming()).toBeNull());
  });

  it('pausa os selecionados em lote, mostra o resumo e as falhas, mantém as falhas selecionadas e recarrega', async () => {
    const other: ProductResponse = { ...CAMISETA, id: 'p-4', title: 'Mochila 20 L', version: 5 };
    await open('/loja/produtos', [CAMISETA, TENIS, other]);

    for (const title of ['Camiseta Basic', 'Tênis Run', 'Mochila 20 L']) {
      (row(title).querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    }
    render();
    expect(text()).toContain('3 selecionados');
    expect(buttonByText(element(), 'Pausar (2)')).toBeTruthy();
    expect(buttonByText(element(), 'Reativar (0)').disabled).toBe(true);

    buttonByText(element(), 'Pausar (2)').click();
    const batch = await vi.waitFor(() => http.expectOne(`${API}/products/batch`));
    expect(page.leaveWarning()).toBe(BATCH_RUNNING_WARNING);
    expect(batch.request.body).toEqual({
      action: 'PAUSE',
      items: [
        { id: 'p-1', version: 3 },
        { id: 'p-4', version: 5 }
      ]
    });
    batch.flush({
      results: [
        { id: 'p-1', outcome: 'SUCCEEDED', status: 'PAUSED_BY_SELLER', version: 4 },
        {
          id: 'p-4',
          outcome: 'FAILED',
          problem: {
            type: 'https://marketplace.dev/problems/version-mismatch',
            title: 'Versão desatualizada',
            detail: 'O produto mudou depois da leitura.'
          }
        }
      ]
    });

    const reload = await nextList();
    expect(page.leaveWarning()).toBeNull();
    expect(TestBed.inject(ToastService).toasts().map(toast => [toast.kind, toast.message])).toEqual([
      ['warning', '1 pausado, 1 com falha, 1 ignorado']
    ]);
    expect(page.selection.items().map(product => product.id)).toEqual(['p-2', 'p-4']);
    reload.flush({ items: [CAMISETA, TENIS, other] });
    await settle();
    expect(text()).toContain('Alguns produtos não foram processados');
    expect(text()).toContain('Mochila 20 L: O produto mudou depois da leitura.');
  });

  it('pede confirmação antes de arquivar em lote', async () => {
    await open();

    (row('Caneca 300ml').querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    render();
    buttonByText(element(), 'Arquivar (1)').click();
    render();
    const modal = document.querySelector('app-confirm-modal') as HTMLElement;
    expect(modal.querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Arquivar 1 produto');
    http.expectNone(`${API}/products/batch`);

    buttonByText(modal.querySelector('c-modal-footer') as HTMLElement, 'Arquivar').click();
    const batch = await vi.waitFor(() => http.expectOne(`${API}/products/batch`));
    expect(batch.request.body).toEqual({ action: 'ARCHIVE', items: [{ id: 'p-3', version: 2 }] });
    batch.flush({ results: [{ id: 'p-3', outcome: 'SUCCEEDED', status: 'ARCHIVED', version: 3 }] });
    (await nextList()).flush({ items: [] });
    expect(TestBed.inject(ToastService).toasts().map(toast => [toast.kind, toast.message])).toEqual([
      ['success', '1 arquivado']
    ]);
  });

  it('limpa a seleção quando muda o filtro', async () => {
    await open();

    (row('Caneca 300ml').querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    expect(page.selection.count()).toBe(1);
    await harness.navigateByUrl('/loja/produtos?status=DRAFT', ProductListPageComponent);
    (await nextList()).flush({ items: [TENIS] });
    await settle();

    expect(page.selection.count()).toBe(0);
  });
});
