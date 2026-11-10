import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import {
  provideCatalogInventoryApi,
  type StockResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';
import type { ActionState } from '@marketplace/shared-web-util';

import { SkuStockSectionComponent } from './sku-stock-section.component';

const STOCK_URL = '/api/catalog-inventory/inventory/skus/s1';
const STOCK: StockResponse = {
  skuId: 's1',
  onHand: 8,
  reserved: 3,
  available: 5,
  sold: 1200,
  version: 4,
};

@Component({
  imports: [SkuStockSectionComponent],
  template: `<app-sku-stock-section (changed)="changes = changes + 1" [editStock]="editStock()" skuId="s1" />`,
})
class Host {
  readonly editStock = signal<ActionState>({ allowed: true });
  changes = 0;
}

describe('SkuStockSectionComponent', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const blocks = () =>
    Array.from(element().querySelectorAll('app-number-block')).map((block) =>
      Array.from(block.querySelectorAll('div > div')).map((part) => part.textContent?.trim()),
    );
  const onHand = () => element().querySelector('input[type="number"]') as HTMLInputElement;
  const type = (value: string) => {
    onHand().value = value;
    onHand().dispatchEvent(new Event('input'));
    render();
  };
  const submit = () => {
    (element().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();
  };
  const stockRequest = (method = 'GET') =>
    vi.waitFor(() =>
      http.expectOne((request) => request.url === STOCK_URL && request.method === method),
    );

  async function open(editStock: ActionState = { allowed: true }) {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.editStock.set(editStock);
    render();
    (await stockRequest()).flush(STOCK, { headers: { ETag: '"4"' } });
    await vi.waitFor(() => {
      render();
      expect(onHand()).toBeTruthy();
    });
  }

  afterEach(() => http.verify());

  it('mostra o físico editável e o reservado, o vendido e o disponível em blocos', async () => {
    await open();

    expect(onHand().valueAsNumber).toBe(8);
    expect(blocks()).toEqual([
      ['Reservado', '3'],
      ['Vendido', '1.200'],
      ['Disponível', '5'],
    ]);
  });

  it('avisa quando o físico digitado fica abaixo do reservado', async () => {
    await open();

    type('2');
    expect(text()).toContain('O valor fica abaixo do reservado (3 unidades).');
    type('3');
    expect(text()).not.toContain('abaixo do reservado');
  });

  it('grava o físico com o If-Match do saldo e avisa a tabela', async () => {
    await open();
    const success = vi.spyOn(TestBed.inject(ToastService), 'success');

    type('12');
    submit();
    const request = await stockRequest('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"4"');
    expect(request.request.body).toEqual({ onHand: 12 });
    request.flush({ ...STOCK, onHand: 12, available: 9, version: 5 }, { headers: { ETag: '"5"' } });

    await vi.waitFor(() => expect(success).toHaveBeenCalledWith('Estoque salvo.'));
    render();
    expect(fixture.componentInstance.changes).toBe(1);
    expect(blocks()[2]).toEqual(['Disponível', '9']);
  });

  it('mostra a recusa do servidor e o conflito de edição com a versão atual', async () => {
    await open();

    type('1');
    submit();
    (await stockRequest('PUT')).flush(
      {
        type: 'https://marketplace.dev/problems/stock-below-reserved',
        title: 'Estoque inválido',
        status: 409,
        reasonDetails: [
          {
            code: 'stock-below-reserved',
            message: 'O físico não pode ficar abaixo das 3 unidades reservadas.',
          },
        ],
      },
      { status: 409, statusText: 'Conflict' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('O físico não pode ficar abaixo das 3 unidades reservadas.');
    });
    expect(text()).not.toContain('stock-below-reserved');

    type('20');
    submit();
    (await stockRequest('PUT')).flush(
      {
        type: 'https://marketplace.dev/problems/version-mismatch',
        title: 'Versão divergente',
        status: 412,
      },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Outra pessoa alterou este registro');
    });
    (Array.from(element().querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => button.textContent?.includes('Carregar versão atual'))
      ?.click();
    (await stockRequest()).flush(
      { ...STOCK, onHand: 30, version: 6 },
      { headers: { ETag: '"6"' } },
    );
    await vi.waitFor(() => {
      render();
      expect(onHand().valueAsNumber).toBe(30);
    });
  });

  it('fica só leitura sem EDIT_STOCK', async () => {
    await open({ allowed: false });

    expect(onHand().disabled).toBe(true);
    expect(text()).toContain('Estoque só leitura.');
    expect(text()).not.toContain('Salvar estoque');
  });
});
