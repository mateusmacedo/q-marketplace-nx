import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, type SessionGrants } from '@marketplace/shared-admin-ui';

import { HomeComponent } from './home.component';

class FakeSession extends AdminSession {
  override readonly grants = signal<SessionGrants>({ capabilities: [], permissions: {} });
  override readonly storeId = signal<string | undefined>(undefined);
  override readonly tenantId = signal<string | undefined>('t1');
}

describe('HomeComponent', () => {
  let session: FakeSession;
  let fixture: ComponentFixture<HomeComponent>;

  const render = async (grants: SessionGrants) => {
    session.grants.set(grants);
    fixture = TestBed.createComponent(HomeComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-home-dashboard')).not.toBeNull();
    });
  };

  beforeEach(() => {
    session = new FakeSession();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useValue: session }
      ]
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

  it('tem o título da tela para leitor de tela e carrega o painel do catálogo sob demanda', async () => {
    await render({ capabilities: [], permissions: {} });

    expect(fixture.nativeElement.querySelector('h1.visually-hidden')?.textContent).toBe('Início');
    expect(fixture.nativeElement.querySelector('app-empty-state')?.textContent).toContain(
      'Sua conta ainda não tem acesso ao catálogo.'
    );
  });

  it('passa ao painel só as telas do menu que a sessão abre', async () => {
    await render({ capabilities: ['MODERATE_PRODUCTS'], permissions: { readTenantCatalog: true } });

    expect(fixture.componentInstance.links()).toEqual({
      storeProducts: undefined,
      tenantProducts: '/plataforma/produtos',
      moderationQueue: '/moderacao/fila',
      categories: undefined
    });

    session.grants.set({ capabilities: ['MANAGE_OWN_PRODUCTS', 'MANAGE_TAXONOMY'], permissions: {} });
    expect(fixture.componentInstance.links()).toEqual({
      storeProducts: '/loja/produtos',
      tenantProducts: undefined,
      moderationQueue: undefined,
      categories: '/plataforma/categorias'
    });
  });
});
