import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  RowComponent,
} from '@coreui/angular';
import { map } from 'rxjs';

import { ImportBatchComponent } from './import-batch.component';

/**
 * Resultado de um lote de importação (`/loja/importacoes/:id`), numa rota que pode ser salva: o andamento, os números
 * e as linhas, consultados enquanto o lote está em andamento.
 */
@Component({
  selector: 'app-import-batch-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    ImportBatchComponent,
    RouterLink,
    RowComponent,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <c-row class="g-3 align-items-center mb-3">
          <c-col [sm]="8">
            <h1 class="h4 card-title mb-0">Resultado da importação</h1>
            <div class="small text-body-secondary">Andamento, números e resultado de cada linha da planilha.</div>
          </c-col>
          <c-col [sm]="4">
            <a cButton class="float-sm-end" color="secondary" routerLink=".." variant="outline">Todas as importações</a>
          </c-col>
        </c-row>
        @if (id(); as importId) {
          <app-import-batch [importId]="importId" />
        }
      </c-card-body>
    </c-card>
  `,
})
export class ImportBatchPageComponent {
  readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id'))));
}
