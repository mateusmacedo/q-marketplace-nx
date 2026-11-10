import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  ProgressComponent,
  RowComponent,
  SpinnerComponent,
  TableDirective,
} from '@coreui/angular';
import { cilChevronRight, cilCloudDownload, cilCloudUpload } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  importProducts,
  type ImportSummaryResponse,
  listProductImportsResource,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  CursorPaginationComponent,
  DateTimePipe,
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  ProblemAlertComponent,
  RelativeTimePipe,
  ToastService,
} from '@marketplace/shared-admin-ui';
import { apiData, type Problem, toPageSize, toProblem } from '@marketplace/shared-web-util';

import { ImportBatchComponent } from './import-batch.component';
import {
  countLines,
  isImportRunning,
  locationId,
  progressPercent,
  statusPresentation,
} from './import-presentation';
import { downloadTemplate } from './import-template';
import { headersOf } from './import-tracking';

/**
 * Importações da loja (`/loja/importacoes`): o modelo CSV, o envio da planilha, o andamento do lote enviado e o
 * histórico dos lotes, do mais recente ao mais antigo, com o cursor e o tamanho da página na URL. O painel não confere
 * o tamanho nem as linhas do arquivo antes do envio: o servidor recusa e a tela mostra os motivos dele.
 */
@Component({
  selector: 'app-imports-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    CursorPaginationComponent,
    DateTimePipe,
    EmptyStateComponent,
    ErrorStateComponent,
    IconDirective,
    ImportBatchComponent,
    LoadingStateComponent,
    ProblemAlertComponent,
    ProgressComponent,
    RelativeTimePipe,
    RouterLink,
    RowComponent,
    SpinnerComponent,
    TableDirective,
  ],
  styles: '.numeric { font-variant-numeric: tabular-nums; }',
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <c-row class="g-3 align-items-center">
          <c-col [sm]="6">
            <h1 class="h4 card-title mb-0">Importações</h1>
            <div class="small text-body-secondary">Crie produtos e atualize preço e estoque com uma planilha CSV.</div>
          </c-col>
          <c-col [sm]="6">
            <div class="float-sm-end d-flex flex-wrap gap-2">
              <button (click)="downloadTemplate()" cButton color="secondary" type="button" variant="outline">
                <svg [cIcon]="icons.download" aria-hidden="true" class="me-2"></svg>Baixar modelo CSV
              </button>
              <button (click)="file.click()" [disabled]="sending()" cButton color="primary" type="button">
                @if (sending()) {
                  <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />Enviando…
                } @else {
                  <svg [cIcon]="icons.upload" aria-hidden="true" class="me-2"></svg>Enviar planilha
                }
              </button>
              <input
                #file
                (change)="send($event)"
                accept=".csv,text/csv"
                aria-label="Planilha CSV"
                class="d-none"
                type="file"
              />
            </div>
          </c-col>
        </c-row>
        <app-problem-alert
          [problem]="uploadProblem()"
          [showFieldErrors]="false"
          class="d-block mt-3"
          heading="Não foi possível enviar a planilha"
        />
      </c-card-body>
    </c-card>

    @if (current(); as id) {
      <c-card class="mb-4">
        <c-card-body>
          <app-import-batch (settled)="history.reload()" [importId]="id" [linkToResult]="true" />
        </c-card-body>
      </c-card>
    }

    <c-card class="mb-4">
      <c-card-body>
        <h2 class="h4 card-title mb-0">Histórico</h2>
        <div class="small text-body-secondary mb-3">Lotes enviados pela loja, do mais recente ao mais antigo.</div>
        @if (historyProblem(); as problem) {
          <app-error-state (retry)="history.reload()" [problem]="problem" message="Não foi possível carregar o histórico." />
        } @else if (page(); as historyPage) {
          @if (rows().length) {
            <table [hover]="true" [responsive]="true" [striped]="true" align="middle" cTable class="mb-0 border">
              <thead class="text-nowrap text-truncate">
                <tr>
                  <th class="bg-body-tertiary" scope="col">Enviado em</th>
                  <th class="bg-body-tertiary" scope="col">Situação</th>
                  <th class="bg-body-tertiary text-end" scope="col">Linhas</th>
                  <th class="bg-body-tertiary text-end" scope="col">Falhas</th>
                  <th class="bg-body-tertiary" scope="col"><span class="visually-hidden">Abrir</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.batch.id) {
                  <tr>
                    <td class="text-nowrap">
                      <a [routerLink]="[row.batch.id]" class="fw-semibold">{{ row.batch.createdAt | dateTime }}</a>
                      <div class="small text-body-secondary">{{ row.batch.createdAt | relativeTime }}</div>
                    </td>
                    <td>
                      <c-badge [color]="row.status.color">{{ row.status.label }}</c-badge>
                    </td>
                    <td class="text-end text-nowrap numeric">
                      @if (row.running) {
                        <strong>{{ row.batch.processedLines ?? 0 }}</strong> de {{ row.lines }}
                        <c-progress
                          [value]="row.percent"
                          aria-label="Linhas processadas"
                          class="mt-1"
                          color="info"
                          thin
                        />
                      } @else {
                        {{ row.lines }}
                      }
                    </td>
                    <td class="text-end numeric">
                      <strong [class.text-danger]="!!row.batch.counts?.failed">{{ row.batch.counts?.failed ?? 0 }}</strong>
                    </td>
                    <td class="text-end">
                      <a [routerLink]="[row.batch.id]" aria-hidden="true" class="text-body-secondary" tabindex="-1">
                        <svg [cIcon]="icons.open"></svg>
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          } @else if (!cursor()) {
            <app-empty-state message="Nenhuma planilha enviada ainda. Baixe o modelo CSV, preencha e envie.">
              <button (click)="downloadTemplate()" cButton color="primary" type="button" variant="outline">
                Baixar modelo CSV
              </button>
            </app-empty-state>
          } @else {
            <app-empty-state message="Nenhum lote nesta página." />
          }
          @if (rows().length || cursor()) {
            <app-cursor-pagination
              (cursorChange)="goTo($event)"
              (limitChange)="resize($event)"
              [cursor]="cursor()"
              [disabled]="history.isLoading()"
              [limit]="pageSize()"
              [nextCursor]="historyPage.nextCursor"
            />
          }
        } @else {
          <app-loading-state label="Carregando o histórico…" />
        }
      </c-card-body>
    </c-card>
  `,
})
export class ImportsPageComponent {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #document = inject(DOCUMENT);
  readonly #toast = inject(ToastService);

  readonly icons = { download: cilCloudDownload, upload: cilCloudUpload, open: cilChevronRight };

  readonly #query = toSignal(this.#route.queryParamMap, { requireSync: true });
  readonly cursor = computed(() => this.#query().get('cursor') || undefined);
  readonly pageSize = computed(() => toPageSize(this.#query().get('limit')));

  readonly history = listProductImportsResource(() => ({
    query: { cursor: this.cursor(), limit: this.pageSize() },
  }));
  readonly page = computed(() => (this.history.hasValue() ? this.history.value() : undefined));
  readonly historyProblem = computed(() => {
    const error = this.history.error();
    return error ? toProblem(error) : null;
  });
  readonly rows = computed(() =>
    (this.page()?.items ?? []).map((batch: ImportSummaryResponse) => ({
      batch,
      status: statusPresentation(batch.status),
      running: isImportRunning(batch.status),
      percent: progressPercent(batch.processedLines, batch.totalLines),
      lines: countLines(batch.totalLines ?? 0),
    })),
  );

  /**
   * Lote acompanhado acima do histórico: o enviado nesta visita ou, ao abrir a tela, o mais recente que o histórico
   * mostra em andamento, para o andamento não ficar parado na linha do histórico.
   */
  readonly current = signal<string | undefined>(undefined);
  readonly sending = signal(false);
  readonly uploadProblem = signal<Problem | null>(null);

  constructor() {
    effect(() => {
      const running = this.rows().find((row) => row.running)?.batch.id;
      if (running) {
        untracked(() => {
          if (!this.current()) {
            this.current.set(running);
          }
        });
      }
    });
  }

  downloadTemplate(): void {
    downloadTemplate(this.#document);
  }

  async send(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.uploadProblem.set(null);
    try {
      const result = await importProducts({ body: file });
      await apiData(Promise.resolve(result));
      this.#toast.success(
        `A planilha ${file.name} foi recebida e será processada em instantes.`,
        'Planilha enviada',
      );
      this.current.set(locationId(headersOf(result.response)?.get('Location')));
      this.history.reload();
    } catch (error) {
      this.uploadProblem.set(toProblem(error));
    } finally {
      this.sending.set(false);
    }
  }

  goTo(cursor: string | undefined): void {
    void this.#navigate({ cursor: cursor ?? null });
  }

  resize(limit: number): void {
    void this.#navigate({ cursor: null, limit });
  }

  #navigate(queryParams: Record<string, string | number | null>): Promise<boolean> {
    return this.#router.navigate([], {
      relativeTo: this.#route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }
}
