import type { Routes } from '@angular/router';
import { leaveGuard } from '@marketplace/shared-admin-ui';

/**
 * Produtos da loja do lojista, montadas em `/loja/produtos`: a lista e o produto, com uma rota por aba. O grupo leva o
 * título da lista, para o breadcrumb do produto voltar a ela; a lista só herda esse título.
 */
export const routes: Routes = [
  {
    path: '',
    data: { title: 'Produtos' },
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./product-list/product-list-page.component').then(
            (m) => m.ProductListPageComponent,
          ),
        canDeactivate: [leaveGuard],
      },
      {
        path: ':id',
        loadComponent: () =>
          import('./product/product-page.component').then((m) => m.ProductPageComponent),
        data: { title: 'Produto' },
        children: [
          { path: '', redirectTo: 'conteudo', pathMatch: 'full' },
          {
            path: 'conteudo',
            loadComponent: () =>
              import('./product/product-content-tab.component').then(
                (m) => m.ProductContentTabComponent,
              ),
            canDeactivate: [leaveGuard],
            data: { title: 'Conteúdo' },
          },
          {
            path: 'skus',
            loadComponent: () =>
              import('./product/product-skus-tab.component').then((m) => m.ProductSkusTabComponent),
            data: { title: 'SKUs, preço e estoque' },
          },
          {
            path: 'historico',
            loadComponent: () =>
              import('./product/product-history-tab.component').then(
                (m) => m.ProductHistoryTabComponent,
              ),
            data: { title: 'Histórico' },
          },
        ],
      },
    ],
  },
];

/** Produtos de todas as lojas, só leitura, montadas em `/plataforma/produtos` para admin e moderador. */
export const platformRoutes: Routes = [
  {
    path: '',
    data: { title: 'Produtos de todas as lojas' },
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./platform/platform-product-list-page.component').then(
            (m) => m.PlatformProductListPageComponent,
          ),
      },
      {
        path: ':id',
        loadComponent: () =>
          import('./platform/platform-product-page.component').then(
            (m) => m.PlatformProductPageComponent,
          ),
        data: { title: 'Produto' },
      },
    ],
  },
];
