import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  provideCatalogInventoryApi,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { ProductPageComponent } from './product-page.component';
import { ProductStore } from './product-store';

const API = '/api/catalog-inventory';

const ACTIVE: ProductResponse = {
  id: 'p1',
  title: 'Camiseta Basic',
  status: 'ACTIVE',
  version: 3,
  currency: 'BRL',
  skus: [{ id: 's1', sellerSku: 'CAM-P' }],
  updatedAt: '2026-11-08T17:02:00Z',
  actions: ['EDIT', 'PAUSE', 'ARCHIVE', 'ADD_SKU', 'EDIT_OFFER', 'EDIT_STOCK'],
  saveReturnsToModeration: true,
};

const DRAFT: ProductResponse = {
  id: 'p1',
  title: 'Camiseta Basic',
  status: 'DRAFT',
  version: 5,
  skus: [],
  actions: ['EDIT', 'ARCHIVE'],
  blockedActions: [
    {
      action: 'SUBMIT',
      reason: {
        code: 'product-not-publishable',
        message: 'O produto ainda tem pendências para ser publicado.',
      },
    },
  ],
  publishingIssues: [
    { code: 'no-sku', params: [], message: 'Adicione ao menos um SKU.', field: 'skus' },
    {
      code: 'missing-attribute',
      params: ['material'],
      message: 'Preencha Material.',
      field: 'attributes.material',
    },
  ],
};

@Component({ template: '<p>Aba de conteúdo</p>' })
class ContentStub {}

@Component({ template: '<p>Aba de SKUs</p>' })
class SkusStub {}

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

describe('ProductPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let toast: ToastService;

  const element = () => harness.routeNativeElement as HTMLElement;
  const store = () =>
    harness.fixture.debugElement
      .query(By.directive(ProductPageComponent))
      .injector.get(ProductStore);
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const button = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim() === label,
    ) as HTMLButtonElement;
  const menuLabels = () =>
    Array.from(element().querySelectorAll('app-product-header [cDropdownItem]')).map((item) =>
      item.textContent?.replace(/\s+/g, ' ').trim(),
    );
  const productRequest = () => vi.waitFor(() => http.expectOne(`${API}/products/p1`));
  const flushProduct = (
    request: TestRequest,
    product: ProductResponse,
    etag = `"${product.version}"`,
  ) => request.flush(product, { headers: { ETag: etag } });

  async function open(product: ProductResponse, url = '/loja/produtos/p1') {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([
          {
            path: 'loja/produtos/:id',
            component: ProductPageComponent,
            children: [
              { path: '', redirectTo: 'conteudo', pathMatch: 'full' },
              { path: 'conteudo', component: ContentStub },
              { path: 'skus', component: SkusStub },
            ],
          },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    toast = TestBed.inject(ToastService);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    flushProduct(await productRequest(), product);
    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-product-header h1')).toBeTruthy();
    });
  }

  beforeAll(() => {
    if (!('IntersectionObserver' in globalThis)) {
      Object.defineProperty(globalThis, 'IntersectionObserver', {
        configurable: true,
        value: InertIntersectionObserver,
      });
    }
  });

  afterEach(() => http.verify());

  it('mostra o título, o status, a ação principal, o menu com as ações do servidor e as abas em rotas', async () => {
    await open(ACTIVE);

    expect(element().querySelector('h1')?.textContent).toContain('Camiseta Basic');
    expect(element().querySelector('app-product-status-badge')?.textContent?.trim()).toBe('Ativo');
    expect(button(element(), 'Pausar')).toBeTruthy();
    expect(menuLabels()).toEqual(['Pausar', 'Arquivar']);
    expect(text()).toContain('1 SKU | Atualizado em 08/11/2026 14:02');
    const tabs = Array.from(element().querySelectorAll('c-nav a')) as HTMLAnchorElement[];
    expect(tabs.map((tab) => [tab.textContent?.trim(), tab.getAttribute('href')])).toEqual([
      ['Conteúdo', '/loja/produtos/p1/conteudo'],
      ['SKUs, preço e estoque', '/loja/produtos/p1/skus'],
      ['Histórico', '/loja/produtos/p1/historico'],
    ]);
    expect(tabs[0].classList).toContain('active');
    expect(element().querySelector('app-submission-checklist')).toBeNull();
  });

  it('pausa pelo botão principal com o If-Match da versão lida e confirma no toast', async () => {
    await open(ACTIVE);
    const success = vi.spyOn(toast, 'success');

    button(element(), 'Pausar').click();
    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1/pause`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    flushProduct(request, {
      ...ACTIVE,
      status: 'PAUSED_BY_SELLER',
      version: 4,
      actions: ['EDIT', 'RESUME', 'ARCHIVE'],
    });

    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-product-status-badge')?.textContent?.trim()).toBe(
        'Pausado',
      );
    });
    expect(success).toHaveBeenCalledWith('Produto pausado. Ele sai da vitrine até você reativar.');
    expect(button(element(), 'Reativar')).toBeTruthy();
    expect(store().version()).toBe('"4"');
  });

  it('arquiva pelo menu só depois da confirmação com o nome do produto', async () => {
    await open(ACTIVE);

    button(
      element().querySelector('app-product-header [cDropdownMenu]') as HTMLElement,
      'Arquivar',
    ).click();
    render();
    const modal = element().querySelector('app-confirm-modal') as HTMLElement;
    expect(modal.textContent).toContain('Camiseta Basic');
    expect(modal.textContent).toContain('Arquivar não pode ser desfeito.');
    http.expectNone(`${API}/products/p1`);

    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Arquivar').click();
    const request = await vi.waitFor(() => http.expectOne((req) => req.method === 'DELETE'));
    expect(request.request.url).toBe(`${API}/products/p1`);
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    flushProduct(request, { ...ACTIVE, status: 'ARCHIVED', version: 4, actions: [] });

    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-product-status-badge')?.textContent?.trim()).toBe(
        'Arquivado',
      );
    });
  });

  it('abre o aviso de conflito no 412 e carrega a versão atual', async () => {
    await open(ACTIVE);

    button(element(), 'Pausar').click();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1/pause`))).flush(
      {
        type: 'https://marketplace.dev/problems/version-mismatch',
        title: 'Versão divergente',
        status: 412,
      },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Outra pessoa alterou este registro depois que você abriu a tela.');
    });

    button(element(), 'Carregar versão atual').click();
    flushProduct(await productRequest(), { ...ACTIVE, version: 6, title: 'Camiseta Basic 2' });
    await vi.waitFor(() => {
      render();
      expect(text()).not.toContain('Outra pessoa alterou');
    });
    expect(element().querySelector('h1')?.textContent).toContain('Camiseta Basic 2');
  });

  it('mostra o checklist com as pendências do servidor, o envio barrado e leva à aba do campo', async () => {
    await open(DRAFT);

    const checklist = element().querySelector('app-submission-checklist') as HTMLElement;
    expect(checklist.textContent).toContain('Adicione ao menos um SKU.');
    expect(checklist.textContent).toContain('Preencha Material.');
    const submit = button(
      element(),
      'Enviar para moderação : O produto ainda tem pendências para ser publicado.',
    );
    expect(submit.disabled).toBe(true);

    button(checklist, 'Adicione ao menos um SKU.').click();
    await vi.waitFor(() => {
      render();
      expect(TestBed.inject(Router).url).toBe('/loja/produtos/p1/skus');
    });
    expect(store().focus()).toMatchObject({ tab: 'skus' });
  });

  it('mostra no alerta e no checklist os motivos da recusa do envio (422)', async () => {
    await open({
      ...DRAFT,
      actions: ['EDIT', 'SUBMIT', 'ARCHIVE'],
      blockedActions: [],
      publishingIssues: [],
    });

    button(element(), 'Enviar para moderação').click();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1/submit`))).flush(
      {
        type: 'https://marketplace.dev/problems/product-not-publishable',
        title: 'Produto não publicável',
        status: 422,
        reasons: ['missing-offer:CAM-P'],
        reasonDetails: [
          { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.' },
        ],
      },
      { status: 422, statusText: 'Unprocessable Content' },
    );

    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-problem-alert')?.textContent).toContain(
        'Não foi possível enviar para moderação',
      );
    });
    const checklist = element().querySelector('app-submission-checklist') as HTMLElement;
    expect(checklist.textContent).toContain('Defina o preço do SKU CAM-P.');
    expect(checklist.textContent).toContain('Motivos da recusa do envio à moderação.');
    expect(text()).not.toContain('missing-offer');
  });

  it('mostra o erro de carregamento com a mensagem do servidor e tenta de novo', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([
          { path: 'loja/produtos/:id', component: ProductPageComponent, children: [] },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/loja/produtos/p1');
    (await productRequest()).flush(
      {
        type: 'https://marketplace.dev/problems/not-found',
        title: 'Não encontrado',
        detail: 'Produto não encontrado.',
        status: 404,
      },
      { status: 404, statusText: 'Not Found' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Não foi possível abrir o produto.');
    });
    expect(text()).toContain('Produto não encontrado.');

    button(element(), 'Tentar de novo').click();
    flushProduct(await productRequest(), ACTIVE);
    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-product-header h1')?.textContent).toContain(
        'Camiseta Basic',
      );
    });
  });
});
