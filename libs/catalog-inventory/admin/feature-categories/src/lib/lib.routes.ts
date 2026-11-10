import type { Routes } from '@angular/router';
import { leaveGuard } from '@marketplace/shared-admin-ui';

import { CategoryEvents } from './category-events';

/** Categorias por nível, montadas em `/plataforma/categorias`, com o detalhe da categoria ao lado da lista. */
export const routes: Routes = [
  {
    path: '',
    providers: [CategoryEvents],
    loadComponent: () =>
      import('./categories-page.component').then((m) => m.CategoriesPageComponent),
    canDeactivate: [leaveGuard],
    data: { title: 'Categorias' },
    children: [
      {
        path: ':id',
        loadComponent: () =>
          import('./category-page.component').then((m) => m.CategoryPageComponent),
        canDeactivate: [leaveGuard],
        data: { title: 'Categoria' },
      },
    ],
  },
];
