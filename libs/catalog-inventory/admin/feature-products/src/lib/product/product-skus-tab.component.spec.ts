import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, inject } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import {
  type CategoryResponse,
  provideCatalogInventoryApi,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { ProductSkusTabComponent } from './product-skus-tab.component';
import { ProductStore } from './product-store';

const API = '/api/catalog-inventory';

const CAMISAS: CategoryResponse = {
  id: 'c1',
  name: 'Camisas',
  effectiveAttributes: [
    { code: 'material', label: 'Material', type: 'TEXT' },
    {
      code: 'tamanho',
      label: 'Tamanho',
      type: 'OPTION',
      variant: true,
      required: true,
      options: ['P', 'M'],
    },
    { code: 'cor', label: 'Cor', type: 'TEXT', variant: true },
  ],
};

const PRODUCT: ProductResponse = {
  id: 'p1',
  title: 'Camiseta Basic',
  categoryId: 'c1',
  currency: 'BRL',
  status: 'DRAFT',
  version: 3,
  actions: ['EDIT', 'ADD_SKU', 'REMOVE_SKU', 'EDIT_OFFER', 'EDIT_STOCK'],
  saveReturnsToModeration: false,
  skus: [
    {
      id: 's1',
      sellerSku: 'CAM-P',
      variantAttributes: { cor: 'Azul', tamanho: 'P' },
      physical: true,
      dimensions: { heightCm: 2, widthCm: 30, lengthCm: 40, weightGrams: 1200 },
      offer: {
        currency: 'BRL',
        listPrice: { amount: 59.9, currency: 'BRL' },
        salePrice: { amount: 49.9, currency: 'BRL' },
        effectivePrice: { amount: 39.9, currency: 'BRL' },
        onPromotion: true,
        version: 2,
      },
      stock: { onHand: 8, reserved: 3, available: 5, sold: 41, version: 1 },
    },
    {
      id: 's2',
      sellerSku: 'CAM-M',
      variantAttributes: { tamanho: 'M' },
      physical: false,
      offer: null,
      stock: null,
    },
  ],
};

@Component({
  imports: [ProductSkusTabComponent],
  providers: [ProductStore],
  template: '<app-product-skus-tab />',
})
class Host {
  readonly store = inject(ProductStore);
}

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

describe('ProductSkusTabComponent', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;
  let store: ProductStore;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = (root: Element = element()) => root.textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const rows = () => Array.from(element().querySelectorAll('tbody tr')) as HTMLTableRowElement[];
  const button = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim() === label,
    ) as HTMLButtonElement;
  const offcanvas = () => element().querySelector('app-add-sku-offcanvas') as HTMLElement;
  const input = (label: string) => {
    const target = (Array.from(offcanvas().querySelectorAll('label')) as HTMLLabelElement[]).find(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim().startsWith(label),
    );
    return offcanvas().querySelector(`[id="${target?.htmlFor}"]`) as HTMLInputElement &
      HTMLSelectElement;
  };
  const type = (target: HTMLInputElement, value: string) => {
    target.value = value;
    target.dispatchEvent(new Event('input'));
    render();
  };

  async function open(product: ProductResponse = PRODUCT) {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    store = fixture.componentInstance.store;
    store.accept({ data: product, version: `"${product.version}"` });
    render();
    (await vi.waitFor(() => http.expectOne(`${API}/categories/c1`))).flush(CAMISAS);
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('P / Azul');
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

  it('lista código, variação, preço vigente, disponível e a situação da oferta de cada SKU', async () => {
    await open();

    expect(rows()).toHaveLength(2);
    expect(text(rows()[0])).toContain('CAM-P');
    expect(text(rows()[0])).toContain('Físico | 2 × 30 × 40 cm, 1.200 g');
    expect(text(rows()[0])).toContain('P / Azul');
    expect(text(rows()[0])).toContain('R$ 39,90');
    expect(text(rows()[0])).toContain('de R$ 59,90');
    expect(rows()[0].querySelectorAll('td')[3].querySelector('strong')?.textContent?.trim()).toBe(
      '5',
    );
    expect(text(rows()[0])).toContain('de 8 no físico');
    expect(text(rows()[0])).toContain('Em promoção');
    expect(text(rows()[1])).toContain('Sem envio físico');
    expect(text(rows()[1])).toContain('Sem preço');
    expect(text()).toContain('2 SKUs');
    expect(text()).not.toContain('Sem estoque disponível.');
  });

  it('desenha a barra do estoque com o disponível em porcentagem do físico', async () => {
    await open();

    await vi.waitFor(() => {
      render();
      const progress = rows()[0].querySelector('c-progress');
      expect(progress?.getAttribute('aria-valuenow')).toBe('63');
      expect(progress?.getAttribute('aria-valuemax')).toBe('100');
    });
  });

  it('avisa sem estoque no produto que o servidor devolve como OUT_OF_STOCK', async () => {
    await open({ ...PRODUCT, status: 'OUT_OF_STOCK' });

    expect(text()).toContain('Sem estoque disponível.');
  });

  it('adiciona SKU com a variação, o físico e as dimensões, com o If-Match, e relê o produto', async () => {
    await open();
    const success = vi.spyOn(TestBed.inject(ToastService), 'success');

    button(element().querySelector('c-card') as HTMLElement, 'Adicionar SKU').click();
    render();
    type(input('Código do SKU'), 'CAM-G');
    input('Tamanho').value = 'M';
    input('Tamanho').dispatchEvent(new Event('change'));
    type(input('Cor'), 'Verde');
    input('SKU físico').click();
    render();
    type(input('Altura'), '2');
    type(input('Largura'), '30');
    type(input('Comprimento'), '40');
    type(input('Peso'), '300');
    (offcanvas().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();

    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1/skus`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    expect(request.request.body).toEqual({
      sellerSku: 'CAM-G',
      variantAttributes: { tamanho: 'M', cor: 'Verde' },
      physical: true,
      dimensions: { heightCm: 2, widthCm: 30, lengthCm: 40, weightGrams: 300 },
    });
    request.flush(null, { status: 201, statusText: 'Created' });

    const reload = await vi.waitFor(() => http.expectOne(`${API}/products/p1`));
    reload.flush({ ...PRODUCT, version: 4 }, { headers: { ETag: '"4"' } });
    await vi.waitFor(() => expect(success).toHaveBeenCalledWith('SKU adicionado.'));
    expect(store.version()).toBe('"4"');
  });

  it('confirma antes de adicionar SKU quando salvar devolve o produto à moderação e mostra a recusa do servidor', async () => {
    await open({ ...PRODUCT, status: 'ACTIVE', saveReturnsToModeration: true });

    button(element().querySelector('c-card') as HTMLElement, 'Adicionar SKU').click();
    render();
    type(input('Código do SKU'), 'CAM-P');
    (offcanvas().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();
    const modal = offcanvas().querySelector('app-confirm-modal') as HTMLElement;
    expect(text(modal)).toContain('Salvar devolve o produto à moderação');
    http.expectNone(`${API}/products/p1/skus`);

    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Salvar').click();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1/skus`))).flush(
      {
        type: 'https://marketplace.dev/problems/duplicate-seller-sku',
        title: 'SKU duplicado',
        detail: 'Já existe um SKU CAM-P na loja.',
        status: 409,
      },
      { status: 409, statusText: 'Conflict' },
    );
    await vi.waitFor(() => {
      render();
      expect(text(offcanvas())).toContain('Já existe um SKU CAM-P na loja.');
    });
  });

  it('remove SKU pelo menu depois da confirmação, com o If-Match do produto', async () => {
    await open();

    button(rows()[1], 'Remover SKU').click();
    render();
    const modal = Array.from(element().querySelectorAll('app-confirm-modal')).find((item) =>
      item.textContent?.includes('Remover SKU'),
    ) as HTMLElement;
    expect(text(modal)).toContain('CAM-M');

    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Remover').click();
    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1/skus/s2`));
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    request.flush(
      { ...PRODUCT, version: 4, skus: PRODUCT.skus?.slice(0, 1) },
      { headers: { ETag: '"4"' } },
    );

    await vi.waitFor(() => {
      render();
      expect(rows()).toHaveLength(1);
    });
    expect(store.version()).toBe('"4"');
  });

  it('mostra Remover SKU desabilitado com o motivo quando o servidor barra, e sem Adicionar sem ADD_SKU', async () => {
    await open({
      ...PRODUCT,
      status: 'ARCHIVED',
      actions: [],
      blockedActions: [
        {
          action: 'REMOVE_SKU',
          reason: {
            code: 'sku-removal-requires-draft',
            message: 'Só dá para remover SKU no rascunho.',
          },
        },
      ],
    });

    const remove = button(rows()[0], 'Remover SKU : Só dá para remover SKU no rascunho.');
    expect(remove.disabled).toBe(true);
    expect(
      button(element().querySelector('c-card') as HTMLElement, 'Adicionar SKU'),
    ).toBeUndefined();
  });

  it('abre o painel do SKU pelo código, com a oferta e o saldo do SKU', async () => {
    await open();

    button(rows()[0], 'CAM-P').click();
    render();
    (await vi.waitFor(() => http.expectOne(`${API}/offers/s1`))).flush(
      {
        skuId: 's1',
        currency: 'BRL',
        price: { salePrice: { amount: 49.9, currency: 'BRL' } },
        version: 2,
        promotions: [],
      },
      { headers: { ETag: '"2"' } },
    );
    (await vi.waitFor(() => http.expectOne(`${API}/inventory/skus/s1`))).flush(
      { skuId: 's1', onHand: 8, reserved: 3, available: 5, sold: 41, version: 1 },
      { headers: { ETag: '"1"' } },
    );
    await vi.waitFor(() => {
      render();
      expect(text(element().querySelector('app-sku-panel') as HTMLElement)).toContain('Disponível');
    });
    expect(element().querySelector('app-sku-panel [cOffcanvasTitle]')?.textContent?.trim()).toBe(
      'CAM-P',
    );
  });
});
