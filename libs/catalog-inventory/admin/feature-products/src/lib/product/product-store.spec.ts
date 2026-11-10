import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';

import { ifMatch, ProductStore } from './product-store';

const API = '/api/catalog-inventory';

describe('ProductStore', () => {
  let http: HttpTestingController;
  let store: ProductStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        ProductStore,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProductStore);
  });

  afterEach(() => http.verify());

  it('lê o produto com a versão do ETag e as regras que o servidor calculou', async () => {
    const loaded = store.load('p1');
    expect(store.loading()).toBe(true);
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      { id: 'p1', version: 3, actions: ['EDIT', 'ADD_SKU'], saveReturnsToModeration: true },
      { headers: { ETag: '"3"' } },
    );

    await expect(loaded).resolves.toBe(true);
    expect(store.version()).toBe('"3"');
    expect(store.loading()).toBe(false);
    expect(store.rules().save.allowed).toBe(true);
    expect(store.rules().addSku.allowed).toBe(true);
    expect(store.rules().editOffer.allowed).toBe(false);
    expect(store.rules().saveReturnsToModeration).toBe(true);
  });

  it('ignora a resposta de uma leitura trocada por outra mais nova', async () => {
    const first = store.load('p1');
    const second = store.load('p2');
    const requests = await vi.waitFor(() => {
      const found = [http.expectOne(`${API}/products/p1`), http.expectOne(`${API}/products/p2`)];
      return found;
    });
    requests[1].flush({ id: 'p2', version: 1 }, { headers: { ETag: '"1"' } });
    requests[0].flush({ id: 'p1', version: 9 }, { headers: { ETag: '"9"' } });

    await expect(second).resolves.toBe(true);
    await expect(first).resolves.toBe(false);
    expect(store.product()?.id).toBe('p2');
    expect(store.version()).toBe('"1"');
  });

  it('descarta a leitura em andamento quando uma gravação mais nova é aceita', async () => {
    store.accept({ data: { id: 'p1', version: 3, status: 'ACTIVE', actions: [] }, version: '"3"' });
    const reloaded = store.reload();
    const read = await vi.waitFor(() => http.expectOne(`${API}/products/p1`));
    expect(store.loading()).toBe(true);

    store.accept({
      data: { id: 'p1', version: 4, status: 'PAUSED_BY_SELLER', actions: [] },
      version: '"4"',
    });
    expect(store.loading()).toBe(false);
    read.flush({ id: 'p1', version: 3, status: 'ACTIVE' }, { headers: { ETag: '"3"' } });

    await expect(reloaded).resolves.toBe(false);
    expect(store.product()?.status).toBe('PAUSED_BY_SELLER');
    expect(store.version()).toBe('"4"');
    expect(store.loading()).toBe(false);
  });

  it('guarda o problema da leitura e, ao pedir a versão atual, avisa o formulário para descartar o digitado', async () => {
    const failed = store.load('p1');
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      { type: 'https://marketplace.dev/problems/not-found', title: 'Não encontrado', status: 404 },
      { status: 404, statusText: 'Not Found' },
    );
    await expect(failed).resolves.toBe(false);
    expect(store.problem()).toMatchObject({ status: 404 });

    const reloaded = store.reload(true);
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush({ id: 'p1', version: 4 });
    await expect(reloaded).resolves.toBe(true);
    expect(store.discards()).toBe(1);
    expect(store.problem()).toBeUndefined();
    expect(store.version()).toBe('"4"');
  });

  it('usa a versão do corpo quando a gravação não traz ETag e limpa a recusa do envio', () => {
    store.refusal.set([{ code: 'missing-offer', message: 'Defina o preço.' }]);
    store.accept({ data: { id: 'p1', version: 7, actions: [] }, version: undefined });

    expect(store.version()).toBe('"7"');
    expect(store.refusal()).toBeNull();
    expect(ifMatch('"7"')).toEqual({ 'If-Match': '"7"' });
    expect(ifMatch(undefined)).toBeUndefined();
  });

  it('relê o produto quando a gravação volta sem as ações, porque as leituras depois dela falharam', async () => {
    store.accept({ data: { id: 'p1', version: 5, status: 'PENDING_MODERATION' }, version: '"5"' });
    expect(store.version()).toBe('"5"');

    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      { id: 'p1', version: 5, status: 'PENDING_MODERATION', actions: ['ARCHIVE'] },
      { headers: { ETag: '"5"' } },
    );
    await vi.waitFor(() => expect(store.product()?.actions).toEqual(['ARCHIVE']));
    expect(store.loading()).toBe(false);
  });
});
