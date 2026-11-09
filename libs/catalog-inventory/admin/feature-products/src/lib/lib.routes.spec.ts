import { signal, type Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, type Route, type Routes } from '@angular/router';
import { AdminSession, NO_GRANTS } from '@marketplace/shared-admin-ui';

import { platformRoutes, routes } from './lib.routes';

class TestSession extends AdminSession {
  readonly grants = signal(NO_GRANTS);
  readonly storeId = signal<string | undefined>(undefined);
  readonly tenantId = signal<string | undefined>(undefined);
}

interface Screen {
  readonly route: Route;
  /** Título da rota ou, na lista, o do grupo que ela herda, como o roteador faz em `path: ''`. */
  readonly title: unknown;
  /** A aba é rota filha e usa o produto que a tela pai fornece, então só abre dentro dela. */
  readonly child: boolean;
}

function screens(list: Routes, child = false, inherited?: unknown): Screen[] {
  return list.flatMap(route => {
    const title = route.data?.['title'] ?? inherited;
    return [
      ...(route.loadComponent ? [{ route, title, child }] : []),
      ...screens(route.children ?? [], child || !!route.loadComponent, title)
    ];
  });
}

describe('rotas da feature', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AdminSession, useClass: TestSession }] })
  );

  for (const [name, list] of Object.entries({ platformRoutes, routes })) {
    it(`carrega sob demanda cada tela de ${name}, com o título da rota`, async () => {
      for (const { route, title, child } of screens(list)) {
        const component = (await route.loadComponent?.()) as Type<unknown>;
        expect(component).toBeTypeOf('function');
        expect(title).toBeTruthy();
        if (child) {
          continue;
        }
        const fixture = TestBed.createComponent(component);
        await fixture.whenStable();
        expect(fixture.nativeElement.querySelector('h1')?.textContent?.trim()).toBe(title);
      }
    });
  }

  it('põe a lista e o produto no mesmo grupo, para o breadcrumb do produto voltar à lista', () => {
    for (const [list, title] of [
      [routes, 'Produtos'],
      [platformRoutes, 'Produtos de todas as lojas']
    ] as const) {
      const [group] = list;
      expect(group.path).toBe('');
      expect(group.data?.['title']).toBe(title);
      expect(group.loadComponent).toBeUndefined();
      expect(group.children?.map(route => route.path)).toEqual(['', ':id']);
      expect(group.children?.[0].data?.['title']).toBeUndefined();
    }
  });
});
