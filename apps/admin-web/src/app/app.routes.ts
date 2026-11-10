import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { capabilityGuard, permissionGuard } from './core/auth/capability.guard';

/**
 * Rotas do painel (proposta de design, seção "Rotas"). Cada feature vem sob demanda pelas rotas que ela exporta, e a
 * guarda de cada grupo impede até o download da feature para quem a sessão (`GET /me`) não libera.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./layout').then((m) => m.DefaultLayoutComponent),
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'inicio', pathMatch: 'full' },
      {
        path: 'inicio',
        loadComponent: () => import('./views/home/home.component').then((m) => m.HomeComponent),
        data: { title: 'Início' },
      },
      {
        path: 'loja',
        canMatch: [capabilityGuard('MANAGE_OWN_PRODUCTS')],
        children: [
          { path: '', redirectTo: 'produtos', pathMatch: 'full' },
          {
            path: 'produtos',
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-products').then((m) => m.routes),
          },
          {
            path: 'importacoes',
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-imports').then((m) => m.routes),
          },
          {
            path: 'situacao',
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-store').then((m) => m.routes),
          },
        ],
      },
      {
        path: 'moderacao',
        canMatch: [capabilityGuard('MODERATE_PRODUCTS')],
        children: [
          { path: '', redirectTo: 'fila', pathMatch: 'full' },
          {
            path: 'fila',
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-moderation').then(
                (m) => m.routes,
              ),
          },
        ],
      },
      {
        path: 'plataforma',
        children: [
          { path: '', redirectTo: 'categorias', pathMatch: 'full' },
          {
            path: 'categorias',
            canMatch: [capabilityGuard('MANAGE_TAXONOMY')],
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-categories').then(
                (m) => m.routes,
              ),
          },
          {
            path: 'motivos-de-moderacao',
            canMatch: [capabilityGuard('MANAGE_MODERATION_REASONS')],
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-moderation').then(
                (m) => m.reasonRoutes,
              ),
          },
          {
            path: 'produtos',
            canMatch: [permissionGuard('readTenantCatalog')],
            loadChildren: () =>
              import('@marketplace/catalog-inventory-admin-feature-products').then(
                (m) => m.platformRoutes,
              ),
          },
        ],
      },
    ],
  },
  { path: '**', redirectTo: 'inicio' },
];
