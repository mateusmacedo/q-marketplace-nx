import { DestroyRef, DOCUMENT, inject, type Signal, signal } from '@angular/core';
import type {
  ActivityResponse,
  MonthValueResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import type { ChartData, ChartOptions } from 'chart.js';

import { bucketLabel } from './home-activity';

/** Cores do tema lidas das variáveis do CoreUI; vazia, a cor fica com o padrão do Chart.js. */
export interface ChartPalette {
  readonly primary?: string;
  readonly info?: string;
  readonly warning?: string;
  readonly danger?: string;
  readonly success?: string;
  readonly border?: string;
  readonly text?: string;
}

/** Tipo, dados e opções de um `c-chart`. */
export interface ChartConfig {
  readonly type: 'line' | 'bar';
  readonly data: ChartData;
  readonly options: ChartOptions;
}

/** Minigráfico de cada posição dos indicadores, como o dashboard do template: linha, linha reta, área e barras. */
export type WidgetChartKind = 'line' | 'straight' | 'area' | 'bar';

export const WIDGET_CHART_KINDS: readonly WidgetChartKind[] = ['line', 'straight', 'area', 'bar'];

const WHITE_LINE = 'rgba(255,255,255,.55)';
const WHITE_FILL = 'rgba(255,255,255,.2)';

/** Lê as cores do tema atual nas variáveis `--cui-*` do documento. */
export function chartPalette(root: Element): ChartPalette {
  const style = getComputedStyle(root);
  const read = (name: string) => style.getPropertyValue(name).trim() || undefined;
  return {
    primary: read('--cui-primary'),
    info: read('--cui-info'),
    warning: read('--cui-warning'),
    danger: read('--cui-danger'),
    success: read('--cui-success'),
    border: read('--cui-border-color-translucent'),
    text: read('--cui-body-color'),
  };
}

/**
 * Tema do documento, que muda quando o seletor Claro, Escuro e Automático troca o `data-coreui-theme`. Os gráficos
 * leem este sinal para refazer as cores. Chamado num contexto de injeção.
 */
export function documentTheme(): Signal<string | null> {
  const root = inject(DOCUMENT).documentElement;
  const readTheme = () => root.dataset['coreuiTheme'] ?? null;
  const theme = signal(readTheme());
  const observer = new MutationObserver(() => theme.set(readTheme()));
  observer.observe(root, { attributes: true, attributeFilter: ['data-coreui-theme'] });
  inject(DestroyRef).onDestroy(() => observer.disconnect());
  return theme.asReadonly();
}

/**
 * Minigráfico de um indicador, com as opções do dashboard do template para cada posição. A escala das linhas segue os
 * valores do servidor, com folga para os pontos; o mês sem histórico (`value` nulo) fica sem ponto.
 */
export function widgetChart(
  months: readonly MonthValueResponse[],
  kind: WidgetChartKind,
  label: string,
  pointColor: string | undefined,
): ChartConfig {
  const values = months.map((month) => month.value ?? null);
  const filled = kind === 'area' || kind === 'bar';
  const dataset = {
    label,
    data: values,
    backgroundColor: filled ? WHITE_FILL : 'transparent',
    borderColor: WHITE_LINE,
    pointBackgroundColor: pointColor,
    pointHoverBorderColor: pointColor,
    ...(kind === 'area' ? { fill: true } : {}),
    ...(kind === 'bar' ? { barPercentage: 0.7 } : {}),
  };
  return {
    type: kind === 'bar' ? 'bar' : 'line',
    data: { labels: months.map((month) => bucketLabel(month.month, 'month')), datasets: [dataset] },
    options: widgetOptions(kind, values),
  };
}

/**
 * Gráfico principal do card "Catálogo", como o "Traffic" do template: aprovados em área, enviados à moderação em linha
 * e a média de aprovados do período tracejada.
 */
export function activityChart(activity: ActivityResponse, palette: ChartPalette): ChartConfig {
  const points = activity.points ?? [];
  const granularity = activity.granularity ?? 'month';
  const approved = points.map((point) => point.approved ?? 0);
  const average = points.length
    ? Math.round((approved.reduce((sum, value) => sum + value, 0) * 10) / points.length) / 10
    : 0;
  return {
    type: 'line',
    data: {
      labels: points.map((point) => bucketLabel(point.period, granularity)),
      datasets: [
        {
          label: 'Aprovados',
          data: approved,
          backgroundColor: withAlpha(palette.info, 0.1),
          borderColor: palette.info,
          pointHoverBackgroundColor: palette.info,
          borderWidth: 2,
          fill: true,
        },
        {
          label: 'Enviados à moderação',
          data: points.map((point) => point.submitted ?? 0),
          backgroundColor: 'transparent',
          borderColor: palette.success,
          pointHoverBackgroundColor: '#fff',
        },
        {
          label: 'Média de aprovados',
          data: points.map(() => average),
          backgroundColor: 'transparent',
          borderColor: palette.danger,
          pointHoverBackgroundColor: palette.danger,
          borderWidth: 1,
          borderDash: [8, 5],
        },
      ],
    },
    options: {
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: {
          grid: { color: palette.border, drawOnChartArea: false },
          ticks: { color: palette.text },
        },
        y: {
          beginAtZero: true,
          border: { color: palette.border },
          grid: { color: palette.border },
          ticks: { color: palette.text, maxTicksLimit: 5, precision: 0 },
        },
      },
      elements: {
        line: { tension: 0.4 },
        point: { radius: 0, hitRadius: 10, hoverRadius: 4, hoverBorderWidth: 3 },
      },
    },
  };
}

/** Cor com transparência: "#3399ff" com 0,1 vira "rgba(51, 153, 255, 0.1)"; outra notação volta como veio. */
export function withAlpha(color: string | undefined, alpha: number): string | undefined {
  const hex = /^#([\da-f]{3}|[\da-f]{6})$/i.exec(color ?? '')?.[1];
  if (!hex) {
    return color;
  }
  const full = hex.length === 3 ? [...hex].map((digit) => digit + digit).join('') : hex;
  const [red, green, blue] = [0, 2, 4].map((start) =>
    Number.parseInt(full.slice(start, start + 2), 16),
  );
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}

function widgetOptions(kind: WidgetChartKind, values: readonly (number | null)[]): ChartOptions {
  const hidden = { display: false };
  if (kind === 'area') {
    return {
      plugins: { legend: hidden },
      maintainAspectRatio: false,
      scales: { x: hidden, y: hidden },
      elements: {
        line: { borderWidth: 2, tension: 0.4 },
        point: { radius: 0, hitRadius: 10, hoverRadius: 4 },
      },
    };
  }
  if (kind === 'bar') {
    return {
      plugins: { legend: hidden },
      maintainAspectRatio: false,
      scales: {
        x: { border: hidden, grid: { display: false, drawTicks: false }, ticks: hidden },
        y: { display: false, grid: hidden, ticks: hidden },
      },
    };
  }
  return {
    plugins: { legend: hidden },
    maintainAspectRatio: false,
    scales: {
      x: { border: hidden, grid: hidden, ticks: hidden },
      y: { display: false, grid: hidden, ticks: hidden, ...lineRange(values) },
    },
    elements: {
      line: { borderWidth: 1, tension: kind === 'straight' ? 0 : 0.4 },
      point: { radius: 4, hitRadius: 10, hoverRadius: 4 },
    },
  };
}

function lineRange(values: readonly (number | null)[]): { min?: number; max?: number } {
  const known = values.filter((value): value is number => value !== null);
  if (!known.length) {
    return {};
  }
  const lowest = Math.min(...known);
  const highest = Math.max(...known);
  const span = Math.max(1, highest - lowest);
  return { min: lowest - span * 0.3, max: highest + span * 0.2 };
}
