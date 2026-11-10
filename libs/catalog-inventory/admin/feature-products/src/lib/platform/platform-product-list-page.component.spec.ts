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
  provideCatalogInventoryApi,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { PlatformProductListPageComponent } from './platform-product-list-page.component';

const API = '/api/catalog-inventory';
const STORE = '3f2a9c1e-0b5d-4c7a-9e21-7d4c2b1a0f99';

const BLOCKED: ProductResponse = {
  id: 'p-9',
  storeId: STORE,
  title: 'Relógio Smart',
  status: 'BLOCKED',
  version: 6,
  skus: [{}],
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-08T10:00:00Z',
  actions: ['UNBLOCK'],
};
const PENDING: ProductResponse = {
  id: 'p-8',
  storeId: '7c1d2e3f-1111-4222-8333-444455556666',
  title: 'Caneca 300ml',
  status: 'PENDING_MODERATION',
  version: 1,
  actions: ['APPROVE', 'REJECT', 'BLOCK'],
};

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

describe('PlatformProductListPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  const element = () => harness.routeNativeElement as HTMLElement;
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const paramsOf = (request: TestRequest) =>
    Object.fromEntries(new URL(request.request.urlWithParams, 'http://localhost').searchParams);
  const listRequest = () =>
    http.expectOne(
      (request) => request.method === 'GET' && request.url.startsWith(`${API}/products?`),
    );
  const rows = () => Array.from(element().querySelectorAll('tbody tr')) as HTMLTableRowElement[];
  const row = (title: string) =>
    rows().find((item) => item.textContent?.includes(title)) as HTMLTableRowElement;
  const buttonByText = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (button) => button.textContent?.replace(/\s+/g, ' ').trim() === label,
    ) as HTMLButtonElement;
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
  const storeInput = () => element().querySelector('#platform-store-filter') as HTMLInputElement;

  async function open(url = '/plataforma/produtos', items: ProductResponse[] = [BLOCKED, PENDING]) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([
          { path: 'plataforma/produtos', component: PlatformProductListPageComponent },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, PlatformProductListPageComponent);
    render();
    const list = listRequest();
    list.flush({ items });
    await settle();
    return list;
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

  it('lista os produtos de todas as lojas, só leitura, com o início do UUID da loja e o completo no tooltip', async () => {
    const list = await open(`/plataforma/produtos?storeId=${STORE}&status=BLOCKED`);

    expect(paramsOf(list)).toEqual({
      storeId: STORE,
      status: 'BLOCKED',
      sort: '-updatedAt',
      limit: '20',
    });
    expect(element().querySelector('h1')?.textContent?.trim()).toBe('Produtos de todas as lojas');
    expect(element().querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
    expect(text()).not.toContain('Novo produto');
    const store = row('Relógio Smart').querySelector('.font-monospace') as HTMLElement;
    expect(store.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('3f2a9c1e…');
    expect(store.querySelector('.visually-hidden')?.textContent?.trim()).toBe(STORE);
    expect(storeInput().value).toBe(STORE);
    expect(
      (row('Relógio Smart').querySelector('a') as HTMLAnchorElement).getAttribute('href'),
    ).toBe('/plataforma/produtos/p-9');
  });

  it('mostra no menu só o que o servidor libera e o painel apresenta: o Desbloquear', async () => {
    await open();

    expect(
      Array.from(row('Relógio Smart').querySelectorAll('[cDropdownItem]')).map((item) =>
        item.textContent?.trim(),
      ),
    ).toEqual(['Desbloquear']);
    expect(row('Caneca 300ml').querySelector('[cDropdownToggle]')).toBeNull();
  });

  it('desbloqueia pelo menu com o If-Match da versão lida e recarrega a lista', async () => {
    await open();

    buttonByText(row('Relógio Smart'), 'Desbloquear').click();
    const unblock = await vi.waitFor(() =>
      http.expectOne(`${API}/moderation/products/p-9/unblock`),
    );

    expect(unblock.request.method).toBe('POST');
    expect(unblock.request.headers.get('If-Match')).toBe('"6"');
    unblock.flush({ ...BLOCKED, status: 'PENDING_MODERATION' });
    (await nextList()).flush({ items: [] });
    await settle();
    expect(
      TestBed.inject(ToastService)
        .toasts()
        .map((toast) => toast.message),
    ).toEqual(['Produto desbloqueado. Ele volta para a fila de moderação.']);
    expect(text()).toContain('Nenhum produto cadastrado.');
  });

  it('filtra pela loja só com o ID completo e volta à primeira página', async () => {
    await open('/plataforma/produtos?cursor=c-3');

    storeInput().value = '3f2a9c1e';
    storeInput().dispatchEvent(new Event('input'));
    buttonByText(element(), 'Filtrar').click();
    render();
    expect(text()).toContain('Confira o formato deste campo. Cole o ID completo da loja.');
    http.expectNone((request) => request.url.startsWith(`${API}/products?`));

    storeInput().value = ` ${STORE} `;
    storeInput().dispatchEvent(new Event('input'));
    buttonByText(element(), 'Filtrar').click();
    const list = await nextList();
    expect(paramsOf(list)).toEqual({ storeId: STORE, sort: '-updatedAt', limit: '20' });
    list.flush({ items: [] });
    await settle();
    expect(text()).toContain('Nenhum produto nesta loja.');

    buttonByText(element(), 'Todas').click();
    (await nextList()).flush({ items: [BLOCKED] });
    await settle();
    expect(TestBed.inject(Router).url).toBe('/plataforma/produtos');
  });
});
