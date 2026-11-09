import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi, type ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { NewProductModalComponent } from './new-product-modal.component';

const API = '/api/catalog-inventory';
const VESTUARIO = { id: 'c-1', code: 'vestuario', name: 'Vestuário' };

@Component({
  imports: [NewProductModalComponent],
  template: `<app-new-product-modal (created)="created.push($event)" [(visible)]="visible" />`
})
class Host {
  readonly visible = signal(false);
  readonly created: ProductResponse[] = [];
}

describe('NewProductModalComponent', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const paramsOf = (request: TestRequest) =>
    Object.fromEntries(new URL(request.request.urlWithParams, 'http://localhost').searchParams);
  const treeRequest = () =>
    vi.waitFor(() => http.expectOne(request => request.url.startsWith(`${API}/categories/tree?`)));
  const select = () => element().querySelector('#new-product-category') as HTMLSelectElement;
  const input = (id: string) => element().querySelector(id) as HTMLInputElement;
  const type = (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
  };
  const choose = (value: string) => {
    select().value = value;
    select().dispatchEvent(new Event('change'));
  };
  const submit = () =>
    (Array.from(element().querySelectorAll('c-modal-footer button')) as HTMLButtonElement[])
      .find(button => button.textContent?.trim() === 'Criar produto')
      ?.click();
  const settle = async () => {
    await fixture.whenStable();
    TestBed.tick();
  };
  const loaded = () =>
    vi.waitFor(() => {
      TestBed.tick();
      expect(select().disabled).toBe(false);
    });

  async function openWithCategories() {
    fixture.componentInstance.visible.set(true);
    TestBed.tick();
    const first = await treeRequest();
    expect(paramsOf(first)).toEqual({ usable: 'true' });
    first.flush({
      items: [
        VESTUARIO,
        { id: 'c-2', code: 'camisas', name: 'Camisas', ancestors: [{ id: 'c-1', name: 'Vestuário' }], usable: true }
      ],
      nextCursor: 'k-2'
    });
    const second = await treeRequest();
    expect(paramsOf(second)).toEqual({ usable: 'true', cursor: 'k-2' });
    second.flush({ items: [{ id: 'c-3', code: 'calcas', name: 'Calças', ancestors: [{ id: 'c-1', name: 'Vestuário' }] }] });
    await loaded();
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('carrega todas as páginas de categorias usáveis e mostra o caminho de cada uma, na ordem da árvore', async () => {
    await openWithCategories();

    expect(Array.from(select().options).map(option => option.textContent?.trim())).toEqual([
      'Escolha a categoria',
      'Vestuário',
      'Vestuário › Calças',
      'Vestuário › Camisas'
    ]);
  });

  it('não cria sem categoria, título e moeda, e avisa em cada campo', async () => {
    await openWithCategories();

    submit();
    await settle();

    expect(element().querySelector('#new-product-category-error')?.textContent?.trim()).toBe('Preencha este campo.');
    expect(element().querySelector('#new-product-title-error')?.textContent?.trim()).toBe('Preencha este campo.');
    expect(element().querySelector('#new-product-currency-error')?.textContent?.trim()).toBe('Preencha este campo.');
    http.expectNone(`${API}/products`);
  });

  it('cria o produto, confirma no toast, fecha e entrega o produto criado para abrir a aba Conteúdo', async () => {
    await openWithCategories();

    choose('c-2');
    type('#new-product-title-input', '  Camiseta Basic ');
    type('#new-product-currency', 'brl');
    expect(input('#new-product-currency').value).toBe('BRL');
    submit();
    const create = await vi.waitFor(() => http.expectOne(`${API}/products`));

    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ categoryId: 'c-2', title: 'Camiseta Basic', currency: 'BRL' });
    create.flush({ id: 'p-1', title: 'Camiseta Basic', status: 'DRAFT', version: 0 }, { status: 201, statusText: 'Created' });
    await vi.waitFor(() => expect(fixture.componentInstance.created).toHaveLength(1));

    expect(fixture.componentInstance.created[0].id).toBe('p-1');
    expect(fixture.componentInstance.visible()).toBe(false);
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(1);
  });

  it('põe os erros do 400 embaixo de cada campo', async () => {
    await openWithCategories();

    choose('c-1');
    type('#new-product-title-input', 'Camiseta');
    type('#new-product-currency', 'BRX');
    submit();
    (await vi.waitFor(() => http.expectOne(`${API}/products`))).flush(
      {
        type: 'https://marketplace.dev/problems/validation',
        title: 'Requisição inválida',
        errors: [{ field: 'currency', message: 'Moeda não reconhecida' }]
      },
      { status: 400, statusText: 'Bad Request' }
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(element().querySelector('#new-product-currency-error')?.textContent?.trim()).toBe('Moeda não reconhecida');
    });
    expect(text()).not.toContain('Não foi possível criar o produto');
    expect(fixture.componentInstance.visible()).toBe(true);
  });

  it('mostra no alerta as mensagens de reasonDetails no 422, sem o código', async () => {
    await openWithCategories();

    choose('c-1');
    type('#new-product-title-input', 'Camiseta');
    type('#new-product-currency', 'BRL');
    submit();
    (await vi.waitFor(() => http.expectOne(`${API}/products`))).flush(
      {
        type: 'https://marketplace.dev/problems/domain-rule-violation',
        title: 'Regra de negócio violada',
        reasons: ['category-inactive'],
        reasonDetails: [{ code: 'category-inactive', params: [], message: 'A categoria foi desativada.' }]
      },
      { status: 422, statusText: 'Unprocessable Entity' }
    );

    await vi.waitFor(() => {
      TestBed.tick();
      expect(text()).toContain('Não foi possível criar o produto');
    });
    expect(text()).toContain('A categoria foi desativada.');
    expect(text()).not.toContain('category-inactive');
  });

  it('mostra a falha ao carregar as categorias e tenta de novo', async () => {
    fixture.componentInstance.visible.set(true);
    TestBed.tick();
    (await treeRequest()).flush(
      { type: 'https://marketplace.dev/problems/service-unavailable', title: 'Indisponível' },
      { status: 503, statusText: 'Service Unavailable' }
    );
    await loaded();
    expect(text()).toContain('Não foi possível carregar as categorias');

    (Array.from(element().querySelectorAll('button')) as HTMLButtonElement[])
      .find(button => button.textContent?.trim() === 'Tentar de novo')
      ?.click();
    (await treeRequest()).flush({ items: [VESTUARIO] });
    await vi.waitFor(() => {
      TestBed.tick();
      expect(select().options).toHaveLength(2);
    });
    expect(text()).not.toContain('Não foi possível carregar as categorias');
  });
});
