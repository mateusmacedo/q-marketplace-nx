import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  type ProductResponse,
  provideCatalogInventoryApi,
} from '@marketplace/catalog-inventory-admin-data-access';

import type { HomeLinks } from './home-links';
import { recentProductRow } from './recent-products';
import { RecentProductsCardComponent } from './recent-products-card.component';

const API = '/api/catalog-inventory';
const LIST = `${API}/products?sort=-updatedAt&limit=6`;

const SHIRT: ProductResponse = {
  id: 'p1',
  title: 'camiseta Basic',
  status: 'ACTIVE',
  categoryId: 'c1',
  currency: 'BRL',
  updatedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
  skus: [
    {
      id: 's1',
      sellerSku: 'CAM-P',
      offer: {
        currency: 'BRL',
        salePrice: { amount: 59.9, currency: 'BRL' },
        effectivePrice: { amount: 49.9, currency: 'BRL' },
      },
      stock: { onHand: 10, available: 8 },
    },
    {
      id: 's2',
      sellerSku: 'CAM-M',
      offer: {
        currency: 'BRL',
        salePrice: { amount: 59.9, currency: 'BRL' },
        effectivePrice: { amount: 59.9, currency: 'BRL' },
      },
      stock: { onHand: 5, available: 4 },
    },
    { id: 's3', sellerSku: 'CAM-G', offer: null, stock: null },
  ],
};

describe('produtos atualizados por último', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<RecentProductsCardComponent>;

  const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim();
  const lines = (element: Element) =>
    Array.from(element.querySelectorAll(':scope > div'), text).join(' ');
  const render = (links: HomeLinks) => {
    fixture = TestBed.createComponent(RecentProductsCardComponent);
    fixture.componentRef.setInput('links', links);
    fixture.detectChanges();
  };
  const answer = async (url: string, body: object, status?: number) => {
    const request = await vi.waitFor(() => {
      TestBed.tick();
      return http.expectOne((request) => request.urlWithParams === url);
    });
    request.flush(body, status ? { status, statusText: 'Erro' } : undefined);
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('monta a linha com o estoque somado, o menor preço vigente e a inicial do título', () => {
    expect(recentProductRow(SHIRT, 'Camisetas')).toMatchObject({
      initial: 'C',
      category: 'Camisetas',
      skuCount: 3,
      available: 12,
      onHand: 15,
      availablePercent: 80,
      stockColor: 'success',
      lowestPrice: { amount: 49.9, currency: 'BRL' },
    });
    expect(recentProductRow({ id: 'p2', title: '', skus: [] })).toMatchObject({
      initial: '?',
      availablePercent: 0,
      stockColor: 'danger',
      lowestPrice: undefined,
    });
  });

  it('lista os produtos mais recentes com o link para o produto na lista da loja', async () => {
    render({ storeProducts: '/loja/produtos', tenantProducts: '/plataforma/produtos' });
    await answer(LIST, { items: [SHIRT] });
    await answer(`${API}/categories?ids=c1`, {
      items: [{ id: 'c1', name: 'Camisetas' }],
      nextCursor: null,
    });

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLElement;
    const cells = Array.from(row.querySelectorAll('td')).map((cell) => text(cell));
    expect(row.querySelector('a')?.getAttribute('href')).toBe('/loja/produtos/p1');
    expect(cells[0]).toBe('C');
    expect(lines(row.querySelectorAll('td')[1])).toBe('camiseta Basic Camisetas | 3 SKUs');
    expect(cells[2]).toBe('Ativo');
    expect(lines(row.querySelectorAll('td')[3].querySelector('div') as Element)).toBe(
      '12 disponíveis de 15 no físico',
    );
    expect(cells[4]).toMatch(/^R\$\s49,90$/);
    expect(lines(row.querySelectorAll('td')[5])).toBe('Atualizado há 1 hora');
    expect(row.querySelector('.avatar-status')?.classList).toContain('bg-success');
  });

  it('lê os nomes das categorias distintas numa chamada só, com os ids separados por vírgula', async () => {
    render({ storeProducts: '/loja/produtos' });
    await answer(LIST, {
      items: [
        SHIRT,
        { ...SHIRT, id: 'p2', title: 'Camiseta Polo' },
        { ...SHIRT, id: 'p3', title: 'Tênis Run', categoryId: 'c2' },
        { ...SHIRT, id: 'p4', title: 'Caneca', categoryId: 'c9' },
      ],
    });
    await answer(`${API}/categories?ids=c1,c2,c9`, {
      items: [
        { id: 'c1', name: 'Camisetas' },
        { id: 'c2', name: 'Tênis' },
      ],
      nextCursor: null,
    });

    const rows = Array.from(
      fixture.nativeElement.querySelectorAll('tbody tr') as NodeListOf<HTMLElement>,
    );
    expect(rows.map((row) => lines(row.querySelectorAll('td')[1]))).toEqual([
      'camiseta Basic Camisetas | 3 SKUs',
      'Camiseta Polo Camisetas | 3 SKUs',
      'Tênis Run Tênis | 3 SKUs',
      'Caneca 3 SKUs',
    ]);
  });

  it('usa a lista de todas as lojas sem a da loja e tolera a categoria que não carrega', async () => {
    render({ tenantProducts: '/plataforma/produtos' });
    await answer(LIST, { items: [{ ...SHIRT, skus: [] }] });
    await answer(`${API}/categories?ids=c1`, { title: 'Sem acesso' }, 403);

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLElement;
    expect(row.querySelector('a')?.getAttribute('href')).toBe('/plataforma/produtos/p1');
    expect(lines(row.querySelectorAll('td')[1])).toBe('camiseta Basic 0 SKUs');
    expect(text(row.querySelectorAll('td')[4])).toBe('Sem preço');
  });

  it('avisa quando não há produto', async () => {
    render({ storeProducts: '/loja/produtos' });
    await answer(LIST, { items: [] });

    expect(text(fixture.nativeElement)).toContain('Nenhum produto cadastrado ainda.');
  });

  it('mostra o erro com Tentar de novo e recarrega a lista', async () => {
    render({ storeProducts: '/loja/produtos' });
    await answer(
      LIST,
      { type: 'https://marketplace.dev/problems/http-503', detail: 'Serviço fora do ar.' },
      503,
    );

    expect(text(fixture.nativeElement)).toContain('Não foi possível carregar os produtos.');
    expect(text(fixture.nativeElement)).toContain('Serviço fora do ar.');

    fixture.nativeElement.querySelector('app-error-state button').click();
    await answer(LIST, { items: [] });
    expect(fixture.nativeElement.querySelector('app-error-state')).toBeNull();
  });

  it('não chama a lista sem uma das listas de produtos na sessão', () => {
    render({ moderationQueue: '/moderacao/fila' });

    expect(http.match(() => true)).toHaveLength(0);
  });
});
