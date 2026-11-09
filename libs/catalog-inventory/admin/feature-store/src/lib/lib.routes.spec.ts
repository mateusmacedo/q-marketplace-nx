import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal, type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, type Route, type Routes } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, NO_GRANTS } from '@marketplace/shared-admin-ui';

import { routes } from './lib.routes';

class FakeSession extends AdminSession {
  override readonly grants = signal(NO_GRANTS);
  override readonly storeId = signal<string | undefined>(undefined);
  override readonly tenantId = signal<string | undefined>(undefined);
}

function screens(list: Routes): Route[] {
  return list.flatMap(route => [...(route.loadComponent ? [route] : []), ...screens(route.children ?? [])]);
}

describe('rotas da feature', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useClass: FakeSession }
      ]
    })
  );

  for (const [name, list] of Object.entries({ routes })) {
    it(`carrega sob demanda cada tela de ${name}, com o título da rota`, async () => {
      for (const route of screens(list)) {
        const component = (await route.loadComponent?.()) as Type<unknown>;
        const fixture = TestBed.createComponent(component);
        await fixture.whenStable();
        expect(fixture.nativeElement.querySelector('h1')?.textContent?.trim()).toBe(route.data?.['title']);
      }
    });
  }
});
