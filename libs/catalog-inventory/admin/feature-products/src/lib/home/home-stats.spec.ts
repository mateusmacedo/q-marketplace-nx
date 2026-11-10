import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';

import type { ActivityGranularity } from './home-activity';
import { type HomeStats, homeStats, type HomeStatsSet, homeStatsSet } from './home-stats';

const API = '/api/catalog-inventory';

describe('números da página inicial', () => {
  let http: HttpTestingController;
  let set: ReturnType<typeof signal<HomeStatsSet | undefined>>;
  let granularity: ReturnType<typeof signal<ActivityGranularity>>;
  let stats: HomeStats;

  const answer = async (url: string, body: object, status?: number) => {
    TestBed.tick();
    const request = await vi.waitFor(() =>
      http.expectOne((request) => request.urlWithParams === url),
    );
    if (status) {
      request.flush(body, { status, statusText: 'Erro' });
    } else {
      request.flush(body);
    }
    await vi.waitFor(() => {
      TestBed.tick();
      expect(stats.loading()).toBe(false);
    });
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    http = TestBed.inject(HttpTestingController);
    set = signal<HomeStatsSet | undefined>('store');
    granularity = signal<ActivityGranularity>('month');
    stats = runInInjectionContext(TestBed.inject(Injector), () => homeStats(set, granularity));
  });

  afterEach(() => http.verify());

  it('escolhe o conjunto pela capacidade da sessão, com a loja na frente', () => {
    const session = (...capabilities: string[]) => ({
      hasCapability: (capability: string) => capabilities.includes(capability),
    });

    expect(
      homeStatsSet(session('MANAGE_TAXONOMY', 'MANAGE_OWN_PRODUCTS', 'MODERATE_PRODUCTS')),
    ).toBe('store');
    expect(homeStatsSet(session('MANAGE_TAXONOMY', 'MODERATE_PRODUCTS'))).toBe('moderation');
    expect(homeStatsSet(session('MANAGE_TAXONOMY'))).toBe('categories');
    expect(homeStatsSet(session('MANAGE_RESERVATIONS'))).toBeUndefined();
  });

  it('carrega os números da loja na granularidade escolhida', async () => {
    await answer(`${API}/stats/store?granularity=month`, { counts: { total: 3 } });

    expect(stats.value()).toEqual({ set: 'store', stats: { counts: { total: 3 } } });
    expect(stats.problem()).toBeUndefined();
  });

  it('mantém os números na tela enquanto a série de outro período carrega', async () => {
    await answer(`${API}/stats/store?granularity=month`, { counts: { total: 3 } });
    expect(stats.value()?.set).toBe('store');

    granularity.set('day');
    TestBed.tick();
    expect(stats.value()).toEqual({ set: 'store', stats: { counts: { total: 3 } } });
    expect(stats.loading()).toBe(true);

    await answer(`${API}/stats/store?granularity=day`, { counts: { total: 4 } });
    expect(stats.value()).toEqual({ set: 'store', stats: { counts: { total: 4 } } });
  });

  it('usa o endpoint da moderação e o das categorias para os outros conjuntos', async () => {
    set.set('moderation');
    await answer(`${API}/stats/moderation?granularity=month`, { queue: { value: 2 } });
    expect(stats.value()).toEqual({ set: 'moderation', stats: { queue: { value: 2 } } });

    set.set('categories');
    TestBed.tick();
    expect(stats.value()).toBeUndefined();
    await answer(`${API}/stats/categories`, { total: 9 });
    expect(stats.value()).toEqual({ set: 'categories', stats: { total: 9 } });
  });

  it('não chama nada sem conjunto', async () => {
    set.set(undefined);
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve));

    http.expectNone(() => true);
    expect(stats.value()).toBeUndefined();
    expect(stats.loading()).toBe(false);
    stats.reload();
  });

  it('expõe o problema da falha e tenta de novo', async () => {
    await answer(
      `${API}/stats/store?granularity=month`,
      { type: 'https://marketplace.dev/problems/http-503', title: 'Serviço indisponível' },
      503,
    );

    expect(stats.problem()).toMatchObject({ status: 503, title: 'Serviço indisponível' });

    stats.reload();
    await answer(`${API}/stats/store?granularity=month`, { counts: { total: 1 } });
    expect(stats.problem()).toBeUndefined();
    expect(stats.value()).toEqual({ set: 'store', stats: { counts: { total: 1 } } });
  });
});
