import type { Routes } from '@angular/router';
import { leaveGuard } from '@marketplace/shared-admin-ui';

/** Fila de moderação, montada em `/moderacao/fila`, com o detalhe do item aberto ao lado da fila. */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./moderation-queue-page.component').then((m) => m.ModerationQueuePageComponent),
    canDeactivate: [leaveGuard],
    data: { title: 'Fila de produtos' },
    children: [
      {
        path: ':id',
        loadComponent: () =>
          import('./moderation-item-page.component').then((m) => m.ModerationItemPageComponent),
        data: { title: 'Produto em moderação' },
      },
    ],
  },
];

/** Catálogo de motivos de moderação do tenant, montado em `/plataforma/motivos-de-moderacao`. */
export const reasonRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./moderation-reasons-page.component').then((m) => m.ModerationReasonsPageComponent),
    data: { title: 'Motivos de moderação' },
  },
];
