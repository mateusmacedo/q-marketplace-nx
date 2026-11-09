import { activityCsv, activityCsvName, activityPeriod, bucketLabel } from './home-activity';

describe('série de atividade da página inicial', () => {
  it('rotula o balde pelo tamanho dele', () => {
    expect(bucketLabel('2026-10-12', 'day')).toBe('12/10');
    expect(bucketLabel('2026-03-01', 'month')).toBe('Março');
    expect(bucketLabel('2026-01-01', 'year')).toBe('2026');
    expect(bucketLabel('ontem', 'day')).toBe('ontem');
  });

  it('escreve o período do subtítulo', () => {
    expect(activityPeriod({ granularity: 'day', from: '2026-10-12', to: '2026-11-08' })).toBe('12/10/2026 a 08/11/2026');
    expect(activityPeriod({ granularity: 'month', from: '2026-01-01', to: '2026-12-31' })).toBe(
      'Janeiro a dezembro de 2026'
    );
    expect(activityPeriod({ granularity: 'month', from: '2025-11-01', to: '2026-10-31' })).toBe(
      'Novembro de 2025 a outubro de 2026'
    );
    expect(activityPeriod({ granularity: 'month', from: '2026-11-01', to: '2026-11-30' })).toBe('Novembro de 2026');
    expect(activityPeriod({ granularity: 'year', from: '2022-01-01', to: '2026-12-31' })).toBe('2022 a 2026');
    expect(activityPeriod({ granularity: 'month' })).toBe('');
  });

  it('gera o CSV dos números com um balde por linha', () => {
    const csv = activityCsv({
      points: [
        { period: '2026-10-01', submitted: 3, approved: 2, rejected: 1, blocked: 0 },
        { period: '2026-11-01', approved: 4 }
      ]
    });

    expect(csv).toBe(
      'Período,Enviados à moderação,Aprovados,Rejeitados,Bloqueados\r\n' +
        '2026-10-01,3,2,1,0\r\n' +
        '2026-11-01,0,4,0,0\r\n'
    );
  });

  it('dá ao arquivo o período e a granularidade, sem acento', () => {
    expect(activityCsvName({ granularity: 'month', from: '2026-01-01', to: '2026-12-31' })).toBe(
      'catalogo-mes-2026-01-01-a-2026-12-31.csv'
    );
    expect(activityCsvName({ granularity: 'day', from: '2026-10-12', to: '2026-11-08' })).toBe(
      'catalogo-dia-2026-10-12-a-2026-11-08.csv'
    );
  });
});
