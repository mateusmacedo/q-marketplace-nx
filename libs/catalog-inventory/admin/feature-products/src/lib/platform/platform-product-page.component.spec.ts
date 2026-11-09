import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideCatalogInventoryApi, type ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { PlatformProductPageComponent } from './platform-product-page.component';

const API = '/api/catalog-inventory';

const BLOCKED: ProductResponse = {
  id: 'p1',
  storeId: '3f2a9c1e-1111-4111-8111-111111111111',
  title: 'Camiseta Basic',
  categoryId: 'c1',
  currency: 'BRL',
  status: 'BLOCKED',
  version: 8,
  attributes: { material: 'Algodão' },
  skus: [{ id: 's1', sellerSku: 'CAM-P' }],
  actions: ['UNBLOCK'],
  saveReturnsToModeration: false
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

describe('PlatformProductPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  const element = () => harness.routeNativeElement as HTMLElement;
  const text = (root: Element = element()) => root.textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const button = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      item => item.textContent?.replace(/\s+/g, ' ').trim() === label
    ) as HTMLButtonElement;

  beforeAll(() => {
    if (!('IntersectionObserver' in globalThis)) {
      Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: InertIntersectionObserver });
    }
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideRouter([{ path: 'plataforma/produtos/:id', component: PlatformProductPageComponent }])
      ]
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/plataforma/produtos/p1');
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(BLOCKED, { headers: { ETag: '"8"' } });
    (await vi.waitFor(() => {
      render();
      return http.expectOne(`${API}/products/p1/history`);
    })).flush([
      {
        transition: 'BLOCK',
        from: 'ACTIVE',
        to: 'BLOCKED',
        reasonLabels: [{ code: 'prohibited-item', label: 'Item proibido' }],
        at: '2026-11-05T13:00:00Z'
      }
    ]);
    (await vi.waitFor(() => http.expectOne(`${API}/categories/c1`))).flush({
      id: 'c1',
      name: 'Camisas',
      effectiveAttributes: [{ code: 'material', label: 'Material', type: 'TEXT' }]
    });
    await vi.waitFor(() => {
      render();
      expect(element().querySelector('[id="product-field-attributes.material"]')).toBeTruthy();
    });
  });

  afterEach(() => http.verify());

  it('mostra o produto de qualquer loja só leitura, com a loja, o motivo do bloqueio e as abas do template', () => {
    expect(element().querySelector('h1')?.textContent).toContain('Camiseta Basic');
    expect(text()).toContain('Loja 3f2a9c1e…');
    expect(text()).toContain('Bloqueado pela moderação');
    expect(text()).toContain('Motivos: Item proibido');
    expect(Array.from(element().querySelectorAll('[cTab]')).map(tab => tab.textContent?.trim())).toEqual([
      'Conteúdo',
      'SKUs, preço e estoque',
      'Histórico'
    ]);
    expect((element().querySelector('[id="product-field-title"]') as HTMLInputElement).disabled).toBe(true);
    expect(text()).toContain('Conteúdo só leitura.');
    expect(button(element().querySelector('form') as HTMLElement, 'Salvar')).toBeUndefined();
    expect(button(element().querySelector('app-product-header') as HTMLElement, 'Desbloquear')).toBeTruthy();
  });

  it('desbloqueia pelo menu quando o servidor oferece UNBLOCK, com o If-Match da versão lida', async () => {
    const success = vi.spyOn(TestBed.inject(ToastService), 'success');

    button(element().querySelector('app-product-header [cDropdownMenu]') as HTMLElement, 'Desbloquear').click();
    const request = await vi.waitFor(() => http.expectOne(`${API}/moderation/products/p1/unblock`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"8"');
    request.flush({ ...BLOCKED, status: 'PENDING_MODERATION', version: 9, actions: [] }, { headers: { ETag: '"9"' } });

    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-product-status-badge')?.textContent?.trim()).toBe('Em moderação');
    });
    expect(success).toHaveBeenCalledWith('Produto desbloqueado. Ele volta para a fila de moderação.');
    expect(text()).not.toContain('Bloqueado pela moderação');
  });
});
