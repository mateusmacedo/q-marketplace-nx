import type { ChartDataset } from 'chart.js';

import { activityChart, chartPalette, widgetChart, withAlpha } from './home-charts';

const MONTHS = [
  { month: '2026-09-01', value: 10 },
  { month: '2026-10-01', value: null },
  { month: '2026-11-01', value: 20 }
];

describe('gráficos da página inicial', () => {
  it('desenha o minigráfico de linha com os meses do servidor e folga na escala', () => {
    const chart = widgetChart(MONTHS, 'line', 'Produtos ativos', '#5856d6');
    const [dataset] = chart.data.datasets as ChartDataset<'line'>[];

    expect(chart.type).toBe('line');
    expect(chart.data.labels).toEqual(['Setembro', 'Outubro', 'Novembro']);
    expect(dataset.data).toEqual([10, null, 20]);
    expect(dataset.pointBackgroundColor).toBe('#5856d6');
    expect(chart.options.scales?.['y']).toMatchObject({ display: false, min: 7, max: 22 });
    expect(chart.options.elements?.line).toMatchObject({ tension: 0.4 });
  });

  it('usa as opções do template em cada posição: linha reta, área e barras', () => {
    expect(widgetChart(MONTHS, 'straight', 'Em rascunho', undefined).options.elements?.line).toMatchObject({
      tension: 0
    });

    const area = widgetChart(MONTHS, 'area', 'Sem estoque', undefined);
    expect(area.type).toBe('line');
    expect((area.data.datasets[0] as ChartDataset<'line'>).fill).toBe(true);
    expect(area.options.elements?.point).toMatchObject({ radius: 0 });

    const bars = widgetChart(MONTHS, 'bar', 'Bloqueados', undefined);
    expect(bars.type).toBe('bar');
    expect((bars.data.datasets[0] as ChartDataset<'bar'>).barPercentage).toBe(0.7);
    expect(bars.options.scales?.['y']).not.toHaveProperty('min');
  });

  it('desenha aprovados em área, enviados em linha e a média de aprovados tracejada', () => {
    const chart = activityChart(
      {
        granularity: 'day',
        points: [
          { period: '2026-11-07', submitted: 2, approved: 1 },
          { period: '2026-11-08', submitted: 3, approved: 2 }
        ]
      },
      { info: '#3399ff', success: '#1b9e3e', danger: '#e55353' }
    );
    const [approved, submitted, average] = chart.data.datasets as ChartDataset<'line'>[];

    expect(chart.data.labels).toEqual(['07/11', '08/11']);
    expect([approved.label, approved.data, approved.fill, approved.backgroundColor]).toEqual([
      'Aprovados',
      [1, 2],
      true,
      'rgba(51, 153, 255, 0.1)'
    ]);
    expect([submitted.label, submitted.data, submitted.borderColor]).toEqual([
      'Enviados à moderação',
      [2, 3],
      '#1b9e3e'
    ]);
    expect([average.label, average.data, average.borderDash]).toEqual(['Média de aprovados', [1.5, 1.5], [8, 5]]);
  });

  it('lê as cores do tema nas variáveis do CoreUI e deixa vazia a que não existe', () => {
    const root = document.createElement('div');
    root.style.setProperty('--cui-info', ' #39f ');
    document.body.append(root);

    expect(chartPalette(root)).toMatchObject({ info: '#39f', danger: undefined });
    root.remove();
  });

  it('aplica a transparência só nas cores hexadecimais', () => {
    expect(withAlpha('#39f', 0.1)).toBe('rgba(51, 153, 255, 0.1)');
    expect(withAlpha('rgb(1, 2, 3)', 0.1)).toBe('rgb(1, 2, 3)');
    expect(withAlpha(undefined, 0.1)).toBeUndefined();
  });
});
