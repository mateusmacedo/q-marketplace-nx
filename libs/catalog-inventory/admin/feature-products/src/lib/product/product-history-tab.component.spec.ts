import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, inject } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import {
  provideCatalogInventoryApi,
  type ProductResponse,
  type StatusChangeResponse
} from '@marketplace/catalog-inventory-admin-data-access';

import { ProductHistoryTabComponent } from './product-history-tab.component';
import { ProductModerationNoticeComponent } from './product-moderation-notice.component';
import { ProductStore } from './product-store';

const HISTORY_URL = '/api/catalog-inventory/products/p1/history';

const PRODUCT: ProductResponse = { id: 'p1', title: 'Camiseta Basic', status: 'DRAFT', version: 3, actions: [] };

const HISTORY: StatusChangeResponse[] = [
  { transition: 'CREATE', to: 'DRAFT', actorSide: 'STORE', byCaller: true, at: '2026-11-01T13:00:00Z' },
  { transition: 'SUBMIT', from: 'DRAFT', to: 'PENDING_MODERATION', actorSide: 'STORE', byCaller: true, at: '2026-11-02T13:00:00Z' },
  {
    transition: 'REJECT',
    from: 'PENDING_MODERATION',
    to: 'DRAFT',
    actorSide: 'MODERATION',
    byCaller: false,
    reason: 'Categoria incorreta: use Calçados',
    reasonCodes: ['wrong-category'],
    reasonLabels: [{ code: 'wrong-category', label: 'Categoria incorreta' }],
    note: 'use Calçados',
    at: '2026-11-03T13:00:00Z'
  }
];

@Component({
  imports: [ProductHistoryTabComponent, ProductModerationNoticeComponent],
  providers: [ProductStore],
  template: `
    <app-product-moderation-notice [product]="store.product()" />
    <app-product-history-tab />
  `
})
class Host {
  readonly store = inject(ProductStore);
}

describe('histórico e motivo da moderação', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = (root: Element = element()) => root.textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const items = () => Array.from(element().querySelectorAll('app-product-history-tab li')) as HTMLElement[];

  function open(product: ProductResponse) {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.store.accept({ data: product, version: `"${product.version}"` });
    render();
  }

  afterEach(() => http.verify());

  it('mostra as transições da mais recente para a mais antiga, com os rótulos da época e o complemento separados', async () => {
    open({
      ...PRODUCT,
      lastRejection: {
        reason: 'Categoria incorreta: use Calçados',
        reasonCodes: ['wrong-category'],
        reasonLabels: [{ code: 'wrong-category', label: 'Categoria incorreta' }],
        note: 'use Calçados',
        at: '2026-11-03T13:00:00Z'
      }
    });
    http.expectOne(HISTORY_URL).flush(HISTORY);
    await vi.waitFor(() => {
      render();
      expect(items()).toHaveLength(3);
    });

    expect(text(items()[0])).toContain('Rejeitado de Em moderação para Rascunho');
    expect(text(items()[0])).toContain('03/11/2026 10:00');
    expect(text(items()[0])).toContain('por Moderação');
    expect(text(items()[2])).toContain('por Você');
    expect(Array.from(items()[0].querySelectorAll('c-badge')).map(badge => badge.textContent?.trim())).toEqual([
      'Categoria incorreta'
    ]);
    expect(items()[0].querySelector('p')?.textContent?.trim()).toBe('use Calçados');
    expect(items()[0].classList).toContain('list-group-item-warning');
    expect(text(items()[2])).toContain('Criado em Rascunho');
    expect(text()).not.toContain('wrong-category');
    expect(text()).not.toContain('Categoria incorreta: use Calçados');

    const notice = element().querySelector('app-product-moderation-notice') as HTMLElement;
    expect(text(notice)).toContain('Rejeitado na moderação em 03/11/2026 10:00');
    expect(text(notice)).toContain('Motivos: Categoria incorreta');
    expect(text(notice)).toContain('Complemento do moderador: use Calçados');
  });

  it('mostra a criação no status em que o produto nasceu, sem "de Rascunho para Rascunho"', async () => {
    open(PRODUCT);
    http
      .expectOne(HISTORY_URL)
      .flush([{ transition: 'CREATE', from: 'DRAFT', to: 'DRAFT', actorSide: 'STORE', byCaller: true, at: '2026-11-01T13:00:00Z' }]);
    await vi.waitFor(() => {
      render();
      expect(items()).toHaveLength(1);
    });

    expect(text(items()[0])).toContain('Criado em Rascunho');
    expect(text(items()[0])).not.toContain('para Rascunho');
  });

  it('destaca o último bloqueio no produto bloqueado', async () => {
    open({ ...PRODUCT, status: 'BLOCKED' });
    const requests = http.match(HISTORY_URL);
    expect(requests).toHaveLength(2);
    const blocked = [
      ...HISTORY,
      {
        transition: 'BLOCK',
        from: 'ACTIVE',
        to: 'BLOCKED',
        reasonLabels: [{ code: 'prohibited-item', label: 'Item proibido' }],
        at: '2026-11-05T13:00:00Z'
      } satisfies StatusChangeResponse
    ];
    requests.forEach(request => request.flush(blocked));
    await vi.waitFor(() => {
      render();
      expect(text(element().querySelector('app-product-moderation-notice') as HTMLElement)).toContain(
        'Bloqueado pela moderação em 05/11/2026 10:00'
      );
    });
    expect(text(element().querySelector('app-product-moderation-notice') as HTMLElement)).toContain('Motivos: Item proibido');
    expect(items()[0].classList).toContain('list-group-item-danger');
  });

  it('avisa no produto bloqueado que o motivo não carregou e tenta de novo', async () => {
    open({ ...PRODUCT, status: 'BLOCKED' });
    const notice = () => element().querySelector('app-product-moderation-notice') as HTMLElement;
    http.match(HISTORY_URL).forEach(request =>
      request.flush(
        { type: 'https://marketplace.dev/problems/unavailable', title: 'Indisponível', detail: 'Serviço fora do ar.', status: 503 },
        { status: 503, statusText: 'Service Unavailable' }
      )
    );
    await vi.waitFor(() => {
      render();
      expect(text(notice())).toContain('Não foi possível carregar o motivo do bloqueio.');
    });
    expect(text(notice())).toContain('Bloqueado pela moderação');
    expect(text(notice())).toContain('Serviço fora do ar.');

    (notice().querySelector('button') as HTMLButtonElement).click();
    render();
    http.expectOne(HISTORY_URL).flush([
      {
        transition: 'BLOCK',
        from: 'ACTIVE',
        to: 'BLOCKED',
        reasonLabels: [{ code: 'prohibited-item', label: 'Item proibido' }],
        at: '2026-11-05T13:00:00Z'
      } satisfies StatusChangeResponse
    ]);
    await vi.waitFor(() => {
      render();
      expect(text(notice())).toContain('Motivos: Item proibido');
    });
    expect(text(notice())).not.toContain('Não foi possível carregar o motivo');
  });

  it('mostra o erro com a mensagem do servidor e tenta de novo', async () => {
    open(PRODUCT);
    http.expectOne(HISTORY_URL).flush(
      { type: 'https://marketplace.dev/problems/not-found', title: 'Não encontrado', detail: 'Produto não encontrado.', status: 404 },
      { status: 404, statusText: 'Not Found' }
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Não foi possível carregar o histórico.');
    });

    expect(text()).toContain('Produto não encontrado.');
    (Array.from(element().querySelectorAll('button')) as HTMLButtonElement[])
      .find(button => button.textContent?.includes('Tentar de novo'))
      ?.click();
    render();
    http.expectOne(HISTORY_URL).flush([]);
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Nenhuma mudança registrada.');
    });
  });
});
