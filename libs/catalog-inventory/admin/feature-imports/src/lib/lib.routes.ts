import type { Routes } from '@angular/router';

/**
 * Importações por planilha, montadas em `/loja/importacoes`: o envio, o histórico e o resultado de um lote. O grupo
 * leva o título da tela de envio, para o breadcrumb do lote voltar a ela; a tela só herda esse título.
 */
export const routes: Routes = [
  {
    path: '',
    data: { title: 'Importações' },
    children: [
      {
        path: '',
        loadComponent: () => import('./imports-page.component').then((m) => m.ImportsPageComponent),
      },
      {
        path: ':id',
        loadComponent: () =>
          import('./import-batch-page.component').then((m) => m.ImportBatchPageComponent),
        data: { title: 'Resultado da importação' },
      },
    ],
  },
];
