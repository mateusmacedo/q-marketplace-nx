import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./layout').then(m => m.DefaultLayoutComponent),
    children: [
      { path: '', redirectTo: 'inicio', pathMatch: 'full' },
      {
        path: 'inicio',
        loadComponent: () => import('./views/home/home.component').then(m => m.HomeComponent),
        data: { title: 'Início' }
      }
    ]
  },
  { path: '**', redirectTo: 'inicio' }
];
