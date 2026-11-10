import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  inject,
  input,
  model,
} from '@angular/core';
import {
  AlertComponent,
  ButtonDirective,
  ButtonGroupComponent,
  CardBodyComponent,
  CardComponent,
  CardFooterComponent,
  ColComponent,
  GutterDirective,
  ProgressComponent,
  RowComponent,
} from '@coreui/angular';
import { ChartjsComponent } from '@coreui/angular-chartjs';
import { cilCloudDownload } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import type {
  ActivityResponse,
  StatusCountsResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { LoadingStateComponent } from '@marketplace/shared-admin-ui';
import { formatInteger, type Problem, problemMessages } from '@marketplace/shared-web-util';

import {
  ACTIVITY_GRANULARITIES,
  type ActivityGranularity,
  activityCsv,
  activityCsvName,
  activityPeriod,
} from './home-activity';
import { activityChart, chartPalette, documentTheme } from './home-charts';
import { type StatusShare, statusShares } from './home-indicators';

/** Período escolhido no seletor cuja série não carregou, com o problema da leitura. */
export interface PeriodFailure {
  readonly granularity: ActivityGranularity;
  readonly problem: Problem;
}

/**
 * Card "Catálogo" da página inicial, o "Traffic" do dashboard do template: período no subtítulo, o grupo Dia, Mês e
 * Ano, o botão que baixa os números em CSV, o gráfico de aprovados e enviados à moderação e, quando há contagem da
 * loja, o rodapé com os 5 números e a parte de cada um. O Chart.js só carrega quando o gráfico aparece (`@defer`).
 */
@Component({
  selector: 'app-catalog-activity-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'd-block' },
  imports: [
    AlertComponent,
    ButtonDirective,
    ButtonGroupComponent,
    CardBodyComponent,
    CardComponent,
    CardFooterComponent,
    ChartjsComponent,
    ColComponent,
    GutterDirective,
    IconDirective,
    LoadingStateComponent,
    ProgressComponent,
    RowComponent,
  ],
  template: `
    <c-card class="my-4">
      <c-card-body [attr.aria-busy]="loading()">
        <c-row>
          <c-col sm="5">
            <h2 class="h4 card-title mb-0" id="catalog-activity-title">Catálogo</h2>
            <div class="small text-body-secondary">{{ period() }}</div>
          </c-col>
          <c-col class="d-none d-md-block" sm="7">
            <button
              (click)="download()"
              [disabled]="!activity()"
              aria-label="Baixar os números em CSV"
              cButton
              class="float-end"
              color="primary"
              type="button"
            >
              <svg [cIcon]="downloadIcon" aria-hidden="true"></svg>
            </button>
            <c-button-group aria-label="Período do gráfico" class="float-end me-3" role="group">
              @for (option of granularities; track option.value) {
                <input
                  (change)="granularity.set(option.value)"
                  [checked]="granularity() === option.value"
                  [id]="'catalog-activity-' + option.value"
                  [value]="option.value"
                  class="btn-check"
                  name="catalog-activity-granularity"
                  type="radio"
                />
                <label [for]="'catalog-activity-' + option.value" cButton color="secondary" variant="outline">
                  {{ option.label }}
                </label>
              }
            </c-button-group>
          </c-col>
        </c-row>
        @if (failure(); as failure) {
          <c-alert class="d-flex flex-wrap align-items-center gap-2 mt-3 mb-0" color="danger" role="alert">
            <span class="me-auto">
              {{ failureText() }}
              @if (failureDetail()) {
                <span class="d-block small">{{ failureDetail() }}</span>
              }
            </span>
            <button
              (click)="granularity.set(failure.granularity)"
              cButton
              color="danger"
              size="sm"
              type="button"
              variant="outline"
            >
              Tentar de novo
            </button>
          </c-alert>
        }
        @if (chart(); as chart) {
          @defer (on immediate) {
            <c-chart
              [customTooltips]="false"
              [data]="chart.data"
              [height]="300"
              [options]="chart.options"
              [type]="chart.type"
              aria-label="Produtos aprovados e enviados à moderação por período, com a média de aprovados"
              style="margin-top: 40px"
            />
          } @placeholder {
            <div aria-hidden="true" style="height: 300px; margin-top: 40px"></div>
          }
        } @else {
          <app-loading-state [rows]="4" class="d-block mt-4" label="Carregando o gráfico do catálogo…" />
        }
      </c-card-body>
      @if (shares(); as shares) {
        <c-card-footer>
          <c-row [gutter]="4" [lg]="4" [sm]="2" [xl]="5" [xs]="1" class="mb-2 text-center">
            @for (share of shares; track share.label; let last = $last) {
              <c-col [class.d-none]="last" [class.d-xl-block]="last">
                <div class="text-body-secondary">{{ share.label }}</div>
                <div class="fw-semibold text-truncate">{{ countText(share) }} ({{ share.percent }}%)</div>
                <c-progress [attr.aria-label]="share.label" [color]="share.color" [value]="share.percent" class="mt-2" thin />
              </c-col>
            }
          </c-row>
        </c-card-footer>
      }
    </c-card>
  `,
})
export class CatalogActivityCardComponent {
  readonly #document = inject(DOCUMENT);
  readonly #theme = documentTheme();

  /** Série de transições do conjunto da sessão; ausente durante a primeira carga. */
  readonly activity = input<ActivityResponse | undefined>();
  /** Produtos da loja por status, para o rodapé; ausente fora do conjunto da loja. */
  readonly counts = input<StatusCountsResponse | undefined>();
  /** Série de outro período em carga; a anterior continua no gráfico até ela chegar. */
  readonly loading = input(false);
  readonly granularity = model<ActivityGranularity>('month');
  /** Período escolhido que falhou ao carregar; o seletor já voltou ao período da série que está no gráfico. */
  readonly failure = input<PeriodFailure | null>(null);

  protected readonly granularities = ACTIVITY_GRANULARITIES;
  protected readonly downloadIcon = cilCloudDownload;

  readonly failureText = computed(() => {
    const failure = this.failure();
    const label = ACTIVITY_GRANULARITIES.find(
      (option) => option.value === failure?.granularity,
    )?.label;
    return label
      ? `Não foi possível carregar o gráfico no período ${label}. O gráfico continua no período anterior.`
      : '';
  });
  readonly failureDetail = computed(() => {
    const problem = this.failure()?.problem;
    return problem ? problemMessages(problem).join(' ') : '';
  });

  readonly period = computed(() => {
    const activity = this.activity();
    return activity ? activityPeriod(activity) : '';
  });

  readonly chart = computed(() => {
    const activity = this.activity();
    this.#theme();
    return activity
      ? activityChart(activity, chartPalette(this.#document.documentElement))
      : undefined;
  });

  readonly shares = computed(() => {
    const counts = this.counts();
    return counts ? statusShares(counts) : undefined;
  });

  protected countText(share: StatusShare): string {
    return `${formatInteger(share.count)} ${share.count === 1 ? 'produto' : 'produtos'}`;
  }

  /** Baixa a série que está no gráfico, em CSV com BOM, para a planilha abrir os acentos. */
  download(): void {
    const activity = this.activity();
    if (!activity) {
      return;
    }
    const url = URL.createObjectURL(
      new Blob(['﻿', activityCsv(activity)], { type: 'text/csv;charset=utf-8' }),
    );
    const anchor = this.#document.createElement('a');
    anchor.href = url;
    anchor.download = activityCsvName(activity);
    anchor.click();
    URL.revokeObjectURL(url);
  }
}
