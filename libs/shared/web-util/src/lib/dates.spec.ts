import {
  formatDate,
  formatDateTime,
  formatRelative,
  formatShortDateTime,
  formatTime,
  fromDateTimeLocal,
  toDateTimeLocal
} from './dates';

describe('datas no fuso de São Paulo', () => {
  const instant = '2026-11-08T17:02:00Z';

  it('formata data e hora no fuso de São Paulo, três horas atrás do UTC', () => {
    expect(formatDateTime(instant)).toBe('08/11/2026 14:02');
    expect(formatShortDateTime(instant)).toBe('08/11 14:02');
    expect(formatDate(instant)).toBe('08/11/2026');
    expect(formatTime(instant)).toBe('14:02');
  });

  it('muda o dia quando o UTC já virou e São Paulo não', () => {
    expect(formatDateTime('2026-11-09T02:30:00Z')).toBe('08/11/2026 23:30');
  });

  it('aceita Date e milissegundos', () => {
    expect(formatDateTime(new Date(instant))).toBe('08/11/2026 14:02');
    expect(formatDateTime(Date.parse(instant))).toBe('08/11/2026 14:02');
  });

  it('devolve texto vazio para valor ausente ou inválido', () => {
    expect(formatDateTime(undefined)).toBe('');
    expect(formatDateTime(null)).toBe('');
    expect(formatDateTime('não é data')).toBe('');
  });

  it('usa o horário de verão nas datas em que ele valia', () => {
    expect(formatDateTime('2018-12-01T12:00:00Z')).toBe('01/12/2018 10:00');
  });
});

describe('data relativa', () => {
  const now = Date.parse('2026-11-08T15:00:00Z');

  it('descreve o passado', () => {
    expect(formatRelative('2026-11-08T14:59:30Z', now)).toBe('agora');
    expect(formatRelative('2026-11-08T14:55:00Z', now)).toBe('há 5 minutos');
    expect(formatRelative('2026-11-08T14:00:00Z', now)).toBe('há 1 hora');
    expect(formatRelative('2026-11-08T10:00:00Z', now)).toBe('há 5 horas');
    expect(formatRelative('2026-11-07T15:00:00Z', now)).toBe('ontem');
    expect(formatRelative('2026-11-05T15:00:00Z', now)).toBe('há 3 dias');
    expect(formatRelative('2026-09-08T15:00:00Z', now)).toBe('há 2 meses');
    expect(formatRelative('2024-11-08T15:00:00Z', now)).toBe('há 2 anos');
  });

  it('descreve o futuro', () => {
    expect(formatRelative('2026-11-08T17:00:00Z', now)).toBe('em 2 horas');
    expect(formatRelative('2026-11-09T15:00:00Z', now)).toBe('amanhã');
  });

  it('devolve texto vazio para instante inválido', () => {
    expect(formatRelative('', now)).toBe('');
  });
});

describe('datetime-local no fuso de São Paulo', () => {
  it('converte o horário digitado para o instante UTC sem usar o fuso do navegador', () => {
    expect(fromDateTimeLocal('2026-11-10T10:00')).toBe('2026-11-10T13:00:00.000Z');
    expect(fromDateTimeLocal('2026-11-10T23:30')).toBe('2026-11-11T02:30:00.000Z');
    expect(fromDateTimeLocal('2026-11-10T10:00:45')).toBe('2026-11-10T13:00:45.000Z');
  });

  it('respeita o deslocamento do horário de verão antigo', () => {
    expect(fromDateTimeLocal('2018-12-01T10:00')).toBe('2018-12-01T12:00:00.000Z');
  });

  it('recusa valor vazio ou fora do formato', () => {
    expect(fromDateTimeLocal('')).toBeUndefined();
    expect(fromDateTimeLocal(undefined)).toBeUndefined();
    expect(fromDateTimeLocal('10/11/2026 10:00')).toBeUndefined();
  });

  it('mostra o instante UTC como valor do campo, no fuso de São Paulo', () => {
    expect(toDateTimeLocal('2026-11-10T13:00:00Z')).toBe('2026-11-10T10:00');
    expect(toDateTimeLocal('2026-11-11T02:30:00Z')).toBe('2026-11-10T23:30');
    expect(toDateTimeLocal(undefined)).toBe('');
  });

  it('volta ao mesmo valor ida e volta', () => {
    const value = '2026-12-31T23:59';
    expect(toDateTimeLocal(fromDateTimeLocal(value))).toBe(value);
  });
});
