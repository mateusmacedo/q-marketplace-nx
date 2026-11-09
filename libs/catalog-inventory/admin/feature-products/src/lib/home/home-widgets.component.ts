import { ChangeDetectionStrategy, Component, computed, DOCUMENT, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AlertComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  DropdownComponent,
  DropdownItemDirective,
  DropdownMenuDirective,
  DropdownToggleDirective,
  GutterDirective,
  RowComponent,
  TemplateIdDirective,
  WidgetStatAComponent
} from '@coreui/angular';
import { ChartjsComponent } from '@coreui/angular-chartjs';
import { cilArrowBottom, cilArrowTop, cilOptions } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import { ErrorStateComponent, LoadingStateComponent } from '@marketplace/shared-admin-ui';
import { formatInteger, type Problem, problemMessages } from '@marketplace/shared-web-util';

import { type ChartConfig, chartPalette, documentTheme, WIDGET_CHART_KINDS, widgetChart } from './home-charts';
import type { HomeIndicator } from './home-indicators';

interface IndicatorChange {
  readonly text: string;
  readonly icon?: string[];
}

interface WidgetCard {
  readonly indicator: HomeIndicator;
  readonly value: string;
  readonly change?: IndicatorChange;
  readonly chart?: ChartConfig;
  readonly wideChart: boolean;
}

/**
 * Os 4 indicadores da página inicial, com o `c-widget-stat-a` do dashboard do template: valor com a variação entre
 * parênteses, quando o servidor a manda, dropdown com os atalhos para a lista filtrada e o minigráfico dos últimos
 * meses. O Chart.js só carrega quando o gráfico aparece (`@defer`), para não pesar as outras telas.
 */
@Component({
  selector: 'app-home-widgets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'd-block mb-4' },
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ChartjsComponent,
    ColComponent,
    DropdownComponent,
    DropdownItemDirective,
    DropdownMenuDirective,
    DropdownToggleDirective,
    ErrorStateComponent,
    GutterDirective,
    IconDirective,
    LoadingStateComponent,
    RouterLink,
    RowComponent,
    TemplateIdDirective,
    WidgetStatAComponent
  ],
  template: `
    @if (cards().length && problem()) {
      <c-alert class="d-flex flex-wrap align-items-center gap-2" color="danger" role="alert">
        <span class="me-auto">
          Não foi possível atualizar os números do catálogo. Os números abaixo são da última leitura.
          @if (problemDetail()) {
            <span class="d-block small">{{ problemDetail() }}</span>
          }
        </span>
        <button (click)="retry.emit()" cButton color="danger" size="sm" type="button" variant="outline">
          Tentar de novo
        </button>
      </c-alert>
    }
    @if (cards().length) {
      <c-row [gutter]="4">
        @for (card of cards(); track card.indicator.key) {
          <c-col sm="6" xl="3">
            <c-widget-stat-a [class.pb-3]="!card.chart" [color]="card.indicator.color" [title]="card.indicator.title">
              <ng-template cTemplateId="widgetValueTemplate">
                {{ card.value }}
                @if (card.change; as change) {
                  <span class="fs-6 fw-normal">({{ change.text }}@if (change.icon) {
                      <svg [cIcon]="change.icon" aria-hidden="true" class="ms-1"></svg>
                    })</span>
                  <span class="visually-hidden"> em relação ao fim do mês anterior</span>
                }
              </ng-template>
              <ng-template cTemplateId="widgetActionTemplate">
                @if (card.indicator.links.length) {
                  <c-dropdown alignment="end" variant="btn-group">
                    <button
                      [attr.aria-label]="'Opções de ' + card.indicator.title"
                      [caret]="false"
                      cButton
                      cDropdownToggle
                      class="p-0 text-white"
                      color="transparent"
                      type="button"
                    >
                      <svg [cIcon]="optionsIcon" aria-hidden="true" class="text-white"></svg>
                    </button>
                    <ul cDropdownMenu>
                      @for (link of card.indicator.links; track link.label) {
                        <li>
                          <a [queryParams]="link.query ?? null" [routerLink]="link.url" cDropdownItem>{{ link.label }}</a>
                        </li>
                      }
                    </ul>
                  </c-dropdown>
                }
              </ng-template>
              <ng-template cTemplateId="widgetChartTemplate">
                @if (card.chart; as chart) {
                  @defer (on immediate) {
                    <c-chart
                      [attr.aria-label]="card.indicator.title + ' nos últimos meses'"
                      [class.mx-3]="!card.wideChart"
                      [customTooltips]="false"
                      [data]="chart.data"
                      [options]="chart.options"
                      [type]="chart.type"
                      class="mt-3"
                      height="70"
                    />
                  } @placeholder {
                    <div aria-hidden="true" class="mt-3" style="height: 70px"></div>
                  }
                }
              </ng-template>
            </c-widget-stat-a>
          </c-col>
        }
      </c-row>
    } @else if (problem()) {
      <c-card>
        <c-card-body>
          <app-error-state
            (retry)="retry.emit()"
            [problem]="problem()"
            message="Não foi possível carregar os números do catálogo."
          />
        </c-card-body>
      </c-card>
    } @else if (loading()) {
      <c-card>
        <c-card-body>
          <app-loading-state [rows]="3" label="Carregando os números do catálogo…" />
        </c-card-body>
      </c-card>
    }
  `
})
export class HomeWidgetsComponent {
  readonly #document = inject(DOCUMENT);
  readonly #theme = documentTheme();

  readonly indicators = input.required<readonly HomeIndicator[]>();
  /** Primeira carga, sem números para mostrar ainda. */
  readonly loading = input(false);
  /** Falha da carga; com números de uma carga anterior, eles continuam na tela. */
  readonly problem = input<Problem | undefined>();

  readonly retry = output<void>();

  protected readonly optionsIcon = cilOptions;

  readonly problemDetail = computed(() => {
    const problem = this.problem();
    return problem ? problemMessages(problem).join(' ') : '';
  });

  readonly cards = computed<WidgetCard[]>(() => {
    this.#theme();
    const palette = chartPalette(this.#document.documentElement);
    return this.indicators().map((indicator, index) => {
      const kind = WIDGET_CHART_KINDS[index % WIDGET_CHART_KINDS.length];
      return {
        indicator,
        value: formatInteger(indicator.value),
        change: describeChange(indicator.change),
        chart: indicator.months ? widgetChart(indicator.months, kind, indicator.title, palette[indicator.color]) : undefined,
        wideChart: kind === 'area'
      };
    });
  });
}

function describeChange(change: number | undefined): IndicatorChange | undefined {
  if (change === undefined) {
    return undefined;
  }
  if (change === 0) {
    return { text: '0' };
  }
  return change > 0
    ? { text: `+${formatInteger(change)}`, icon: cilArrowTop }
    : { text: formatInteger(change), icon: cilArrowBottom };
}
