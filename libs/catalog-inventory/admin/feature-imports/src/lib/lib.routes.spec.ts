import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, type Route, type Routes } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';

import { routes } from './lib.routes';

/** Cada tela com o título dela ou, na tela de envio, o do grupo que ela herda, como o roteador faz em `path: ''`. */
function screens(list: Routes, inherited?: unknown): { route: Route; title: unknown }[] {
  return list.flatMap(route => {
    const title = route.data?.['title'] ?? inherited;
    return [...(route.loadComponent ? [{ route, title }] : []), ...screens(route.children ?? [], title)];
  });
}

describe('rotas da feature', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    })
  );

  for (const [name, list] of Object.entries({ routes })) {
    it(`carrega sob demanda cada tela de ${name}, com o título da rota`, async () => {
      for (const { route, title } of screens(list)) {
        const component = (await route.loadComponent?.()) as Type<unknown>;
        const fixture = TestBed.createComponent(component);
        // NOTE: `whenStable` esperaria as chamadas à API, que ficam pendentes no `HttpTestingController`.
        fixture.detectChanges();
        expect(title).toBeTruthy();
        expect(fixture.nativeElement.querySelector('h1')?.textContent?.trim()).toBe(title);
        fixture.destroy();
      }
      // WHY: a primeira carga de cada tela compila o componente sob demanda, e com outras suítes em paralelo passa
      // perto dos 5 segundos padrão.
    }, 20_000);
  }
});
