import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal, type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, type Route, type Routes } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, leaveGuard, type SessionGrants } from '@marketplace/shared-admin-ui';

import { reasonRoutes, routes } from './lib.routes';

class TestSession extends AdminSession {
  readonly grants = signal<SessionGrants>({ capabilities: [], permissions: {} });
  readonly storeId = signal<string | undefined>(undefined);
  readonly tenantId = signal<string | undefined>(undefined);
}

function screens(list: Routes): Route[] {
  return list.flatMap((route) => [
    ...(route.loadComponent ? [route] : []),
    ...screens(route.children ?? []),
  ]);
}

describe('rotas da feature', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useClass: TestSession },
      ],
    }),
  );

  for (const [name, list] of Object.entries({ routes, reasonRoutes })) {
    it(`carrega sob demanda cada tela de ${name}, com o título da rota`, async () => {
      for (const route of screens(list)) {
        const component = (await route.loadComponent?.()) as Type<unknown>;
        const fixture = TestBed.createComponent(component);
        TestBed.tick();
        expect(fixture.nativeElement.querySelector('h1')?.textContent?.trim()).toBe(
          route.data?.['title'],
        );
      }
    });
  }

  it('pergunta antes de sair da fila, que pode ter um lote em andamento', () => {
    expect(routes[0].canDeactivate).toEqual([leaveGuard]);
  });
});
