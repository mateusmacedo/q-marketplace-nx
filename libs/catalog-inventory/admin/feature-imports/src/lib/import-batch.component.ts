import { ChangeDetectionStrategy, Component, computed, effect, input, output, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AlertComponent,
  BadgeComponent,
  ButtonDirective,
  ColComponent,
  ProgressComponent,
  RowComponent
} from '@coreui/angular';
import type { ImportResponse } from '@marketplace/catalog-inventory-admin-data-access';
import {
  DateTimePipe,
  ErrorStateComponent,
  LoadingStateComponent,
  NumberBlockComponent
} from '@marketplace/shared-admin-ui';
import { problemMessages } from '@marketplace/shared-web-util';

import { ImportLinesComponent } from './import-lines.component';
import { countLines, isImportRunning, progressPercent, statusPresentation } from './import-presentation';
import { trackImport } from './import-tracking';

/**
 * Um lote de importação: situação, andamento pela barra de `processedLines` e `totalLines`, os números em bloco e o
 * resultado de cada linha. Consulta o lote enquanto ele está em andamento e para ao sair da tela.
 */
@Component({
  selector: 'app-import-batch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    BadgeComponent,
    ButtonDirective,
    ColComponent,
    DateTimePipe,
    ErrorStateComponent,
    ImportLinesComponent,
    LoadingStateComponent,
    NumberBlockComponent,
    ProgressComponent,
    RouterLink,
    RowComponent
  ],
  styles: '.numeric { font-variant-numeric: tabular-nums; }',
  template: `
    @if (tracking.report(); as report) {
      <div class="d-flex flex-wrap align-items-center gap-2">
        <h2 class="h5 mb-0">
          @if (linkToResult()) {
            <a [routerLink]="[report.id]">Lote de {{ report.createdAt | dateTime }}</a>
          } @else {
            Lote de {{ report.createdAt | dateTime }}
          }
        </h2>
        <c-badge [color]="status().color">{{ status().label }}</c-badge>
        <span class="ms-auto small text-body-secondary numeric">
          <strong class="text-body">{{ processed() }}</strong> de {{ totalLabel() }}
        </span>
      </div>
      <c-progress
        [animated]="tracking.running()"
        [color]="tracking.running() ? 'info' : 'success'"
        [value]="percent()"
        [variant]="tracking.running() ? 'striped' : undefined"
        aria-label="Linhas processadas"
        class="mt-2"
        thin
      />
      @if (tracking.problem()) {
        <c-alert class="d-flex flex-wrap align-items-center gap-2 mt-3 mb-0" color="warning" role="alert">
          <span class="me-auto">Não foi possível atualizar o andamento. {{ refreshMessage() }}</span>
          <button (click)="tracking.retry()" [disabled]="tracking.loading()" cButton color="warning" size="sm" type="button">
            Tentar de novo
          </button>
        </c-alert>
      }
      <c-row class="mt-3">
        <c-col [md]="3" [xs]="6">
          <app-number-block [value]="report.counts?.created ?? 0" class="d-block mb-3" color="success" label="Criadas" />
        </c-col>
        <c-col [md]="3" [xs]="6">
          <app-number-block [value]="report.counts?.updated ?? 0" class="d-block mb-3" color="info" label="Atualizadas" />
        </c-col>
        <c-col [md]="3" [xs]="6">
          <app-number-block
            [value]="report.counts?.unchanged ?? 0"
            class="d-block mb-3"
            color="secondary"
            label="Sem mudança"
          />
        </c-col>
        <c-col [md]="3" [xs]="6">
          <app-number-block [value]="report.counts?.failed ?? 0" class="d-block mb-3" color="danger" label="Falhas" />
        </c-col>
      </c-row>
      @if (report.lines; as lines) {
        <app-import-lines [lines]="lines" />
      }
    } @else if (tracking.problem(); as problem) {
      <app-error-state (retry)="tracking.retry()" [problem]="problem" message="Não foi possível carregar a importação." />
    } @else {
      <app-loading-state [rows]="4" label="Carregando a importação…" />
    }
  `
})
export class ImportBatchComponent {
  readonly importId = input.required<string>();
  /** Faz do título o link para o resultado do lote (`/loja/importacoes/:id`), como na tela das importações. */
  readonly linkToResult = input(false);
  /** O lote chegou a um status terminal; emitido uma vez por lote. */
  readonly settled = output<ImportResponse>();

  readonly tracking = trackImport(this.importId);

  readonly status = computed(() => statusPresentation(this.tracking.report()?.status));
  readonly processed = computed(() => this.tracking.report()?.processedLines ?? 0);
  readonly total = computed(() => this.tracking.report()?.totalLines ?? 0);
  readonly totalLabel = computed(() => countLines(this.total()));
  readonly percent = computed(() => progressPercent(this.processed(), this.total()));
  readonly refreshMessage = computed(() => {
    const problem = this.tracking.problem();
    return problem ? problemMessages(problem).join(' ') : '';
  });

  #settledId: string | undefined;

  constructor() {
    effect(() => {
      const report = this.tracking.report();
      if (report?.id && !isImportRunning(report.status) && report.id !== this.#settledId) {
        this.#settledId = report.id;
        untracked(() => this.settled.emit(report));
      }
    });
  }
}
