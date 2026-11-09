import type { Routes } from '@angular/router';

/** Situação da loja no KYB/KYC, montada em `/loja/situacao`. */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./store-status-page.component').then(m => m.StoreStatusPageComponent),
    data: { title: 'Situação da loja' }
  }
];
