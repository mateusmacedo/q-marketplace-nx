import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, DeferBlockBehavior, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, type SessionGrants } from '@marketplace/shared-admin-ui';

import { HomeDashboardComponent } from './home-dashboard.component';
import type { HomeLinks } from './home-links';

const API = '/api/catalog-inventory';
const STORE_LINKS: HomeLinks = { storeProducts: '/loja/produtos' };
const INDICATOR = { value: 2, previous: 3, change: -1, months: [{ month: '2026-11-01', value: 2 }] };
const ACTIVITY = { granularity: 'month', from: '2026-01-01', to: '2026-12-31', points: [] };

class FakeSession extends AdminSession {
  override readonly grants = signal<SessionGrants>({ capabilities: [], permissions: {} });
  override readonly storeId = signal<string | undefined>(undefined);
  override readonly tenantId = signal<string | undefined>('t1');
}

describe('página inicial do catálogo', () => {
  let http: HttpTestingController;
  let session: FakeSession;
  let fixture: ComponentFixture<HomeDashboardComponent>;

  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const titles = () =>
    Array.from(fixture.nativeElement.querySelectorAll('c-widget-stat-a'), widget =>
      text((widget as Element).querySelector('.card-body > div > div:last-child'))
    );
  const render = (grants: SessionGrants, links: HomeLinks) => {
    session.grants.set(grants);
    fixture = TestBed.createComponent(HomeDashboardComponent);
    fixture.componentRef.setInput('links', links);
    fixture.detectChanges();
  };
  const answer = async (url: string, body: object) => {
    const request = await vi.waitFor(() => {
      TestBed.tick();
      return http.expectOne(request => request.urlWithParams === url);
    });
    request.flush(body);
    await new Promise(resolve => setTimeout(resolve));
    fixture.detectChanges();
  };

  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    session = new FakeSession();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useValue: session }
      ],
      deferBlockBehavior: DeferBlockBehavior.Manual
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('mostra ao lojista os números da loja, o card Catálogo com o rodapé e os produtos atualizados por último', async () => {
    render({ capabilities: ['MANAGE_OWN_PRODUCTS'], permissions: {} }, STORE_LINKS);

    await answer(`${API}/stats/store?granularity=month`, {
      counts: { active: 2, total: 4 },
      active: INDICATOR,
      draft: INDICATOR,
      outOfStock: INDICATOR,
      blocked: INDICATOR,
      activity: ACTIVITY
    });
    await answer(`${API}/products?sort=-updatedAt&limit=6`, { items: [] });

    expect(titles()).toEqual(['Produtos ativos', 'Em rascunho', 'Sem estoque', 'Bloqueados']);
    expect(fixture.nativeElement.querySelector('a[cDropdownItem]')?.getAttribute('href')).toBe(
      '/loja/produtos?status=ACTIVE'
    );
    expect(text(fixture.nativeElement.querySelector('app-catalog-activity-card h2'))).toBe('Catálogo');
    expect(fixture.nativeElement.querySelectorAll('app-catalog-activity-card c-card-footer c-col')).toHaveLength(5);
    expect(text(fixture.nativeElement.querySelector('app-recent-products-card'))).toContain(
      'Nenhum produto cadastrado ainda.'
    );
  });

  it('recarrega a série no período escolhido sem tirar os indicadores da tela', async () => {
    render({ capabilities: ['MANAGE_OWN_PRODUCTS'], permissions: {} }, {});
    await answer(`${API}/stats/store?granularity=month`, { active: INDICATOR, activity: ACTIVITY });

    (fixture.nativeElement.querySelector('#catalog-activity-year') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(titles()).toHaveLength(4);

    await answer(`${API}/stats/store?granularity=year`, {
      active: INDICATOR,
      activity: { granularity: 'year', from: '2022-01-01', to: '2026-12-31', points: [] }
    });
    expect(text(fixture.nativeElement.querySelector('app-catalog-activity-card .small'))).toBe('2022 a 2026');
  });

  it('na falha do período escolhido, volta o seletor à série do gráfico e avisa com Tentar de novo', async () => {
    render({ capabilities: ['MANAGE_OWN_PRODUCTS'], permissions: {} }, {});
    await answer(`${API}/stats/store?granularity=month`, { active: INDICATOR, activity: ACTIVITY });
    const radio = (value: string) => fixture.nativeElement.querySelector(`#catalog-activity-${value}`) as HTMLInputElement;
    const card = () => fixture.nativeElement.querySelector('app-catalog-activity-card') as HTMLElement;

    radio('day').click();
    fixture.detectChanges();
    const failed = await vi.waitFor(() => {
      TestBed.tick();
      return http.expectOne(`${API}/stats/store?granularity=day`);
    });
    failed.flush({ title: 'Indisponível', status: 503, detail: 'Serviço fora do ar.' }, { status: 503, statusText: 'Unavailable' });
    await new Promise(resolve => setTimeout(resolve));
    fixture.detectChanges();

    await answer(`${API}/stats/store?granularity=month`, { active: INDICATOR, activity: ACTIVITY });
    expect(radio('month').checked).toBe(true);
    expect(radio('day').checked).toBe(false);
    expect(text(card().querySelector('c-alert'))).toContain(
      'Não foi possível carregar o gráfico no período Dia. O gráfico continua no período anterior.'
    );
    expect(text(card().querySelector('c-alert'))).toContain('Serviço fora do ar.');
    expect(titles()).toHaveLength(4);

    (card().querySelector('c-alert button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await answer(`${API}/stats/store?granularity=day`, {
      active: INDICATOR,
      activity: { granularity: 'day', from: '2026-11-01', to: '2026-11-30', points: [] }
    });
    expect(radio('day').checked).toBe(true);
    expect(card().querySelector('c-alert')).toBeNull();
  });

  it('mostra ao moderador a fila e as decisões do mês, sem rodapé e sem atalho para a tela que ele não abre', async () => {
    render(
      { capabilities: ['MODERATE_PRODUCTS'], permissions: { readTenantCatalog: false } },
      { moderationQueue: '/moderacao/fila' }
    );

    await answer(`${API}/stats/moderation?granularity=month`, {
      queue: INDICATOR,
      approved: INDICATOR,
      rejected: INDICATOR,
      blocked: INDICATOR,
      activity: ACTIVITY
    });

    expect(titles()).toEqual(['Na fila de moderação', 'Aprovados no mês', 'Rejeitados no mês', 'Bloqueados no mês']);
    expect(
      Array.from(fixture.nativeElement.querySelectorAll('a[cDropdownItem]'), link =>
        (link as HTMLAnchorElement).getAttribute('href')
      )
    ).toEqual(['/moderacao/fila', '/moderacao/fila']);
    expect(fixture.nativeElement.querySelector('app-catalog-activity-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-catalog-activity-card c-card-footer')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-recent-products-card')).toBeNull();
  });

  it('mostra só os números da árvore a quem administra as categorias', async () => {
    render({ capabilities: ['MANAGE_TAXONOMY'], permissions: {} }, { categories: '/plataforma/categorias' });

    await answer(`${API}/stats/categories`, { total: 9, roots: 2, inactive: 1, withProducts: 4 });

    expect(titles()).toEqual(['Categorias', 'Categorias raiz', 'Inativas', 'Com produtos']);
    expect(fixture.nativeElement.querySelector('app-catalog-activity-card')).toBeNull();
  });

  it('mostra a tabela a quem só lê o catálogo do tenant, sem indicadores', async () => {
    render({ capabilities: [], permissions: { readTenantCatalog: true } }, { tenantProducts: '/plataforma/produtos' });

    await answer(`${API}/products?sort=-updatedAt&limit=6`, { items: [] });

    expect(fixture.nativeElement.querySelector('app-home-widgets')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-recent-products-card')).not.toBeNull();
  });

  it('avisa quem não tem acesso ao catálogo, sem chamar a API', () => {
    render({ capabilities: [], permissions: {} }, {});

    expect(text(fixture.nativeElement.querySelector('app-empty-state'))).toContain(
      'Sua conta ainda não tem acesso ao catálogo.'
    );
  });
});
