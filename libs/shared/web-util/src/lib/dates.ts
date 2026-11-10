/** Fuso das datas do painel até o tenant ter fuso próprio (ADR 028). */
// WHY: cópia pendente (ADR 031); saída: fuso do tenant informado pelo servidor.
export const SAO_PAULO_TIME_ZONE = 'America/Sao_Paulo';

/** Instante absoluto: `Date`, milissegundos desde a época ou texto ISO 8601 com fuso, como a API devolve. */
export type Instant = Date | number | string;

interface WallTime {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

// WHY: en-US com h23 garante as partes numéricas e a meia-noite como 00, sem depender do fuso do navegador.
const wallTimeFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: SAO_PAULO_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

const relativeFormat = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'always' });
const relativeDayFormat = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });

const DATETIME_LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?$/;

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Data e hora no fuso de São Paulo: "08/11/2026 14:02". Instante inválido vira texto vazio. */
export function formatDateTime(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time
    ? `${pad(time.day)}/${pad(time.month)}/${time.year} ${pad(time.hour)}:${pad(time.minute)}`
    : '';
}

/** Forma curta, sem o ano, das colunas de lista: "08/11 14:02". */
export function formatShortDateTime(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time ? `${pad(time.day)}/${pad(time.month)} ${pad(time.hour)}:${pad(time.minute)}` : '';
}

/** Só a data: "08/11/2026". */
export function formatDate(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time ? `${pad(time.day)}/${pad(time.month)}/${time.year}` : '';
}

/** Só a hora: "14:02". */
export function formatTime(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time ? `${pad(time.hour)}:${pad(time.minute)}` : '';
}

/**
 * Distância até agora em texto: "agora", "há 5 minutos", "há 1 hora", "ontem", "há 3 dias", "em 2 horas". Abaixo de
 * um minuto, nos dois sentidos, é "agora".
 */
export function formatRelative(
  instant: Instant | null | undefined,
  now: Instant = Date.now(),
): string {
  const target = toMillis(instant);
  const reference = toMillis(now);
  if (target === undefined || reference === undefined) {
    return '';
  }
  const diff = target - reference;
  if (Math.abs(diff) < MINUTE) {
    return 'agora';
  }
  const minutes = Math.round(diff / MINUTE);
  if (Math.abs(minutes) < 60) {
    return relativeFormat.format(minutes, 'minute');
  }
  const hours = Math.round(diff / HOUR);
  if (Math.abs(hours) < 24) {
    return relativeFormat.format(hours, 'hour');
  }
  const days = Math.round(diff / DAY);
  if (Math.abs(days) === 1) {
    return relativeDayFormat.format(days, 'day');
  }
  if (Math.abs(days) < 30) {
    return relativeFormat.format(days, 'day');
  }
  const months = Math.round(days / 30);
  if (Math.abs(months) < 12) {
    return relativeFormat.format(months, 'month');
  }
  return relativeFormat.format(Math.round(days / 365), 'year');
}

/**
 * Valor do `<input type="datetime-local">` para o instante, no fuso de São Paulo: "2026-11-10T10:00". Instante
 * inválido vira texto vazio, que deixa o campo em branco.
 */
export function toDateTimeLocal(instant: Instant | null | undefined): string {
  const time = wallTimeOf(instant);
  return time
    ? `${time.year}-${pad(time.month)}-${pad(time.day)}T${pad(time.hour)}:${pad(time.minute)}`
    : '';
}

/**
 * Lê o valor do `<input type="datetime-local">` como horário de São Paulo e devolve o instante UTC em ISO 8601
 * ("2026-11-10T13:00:00.000Z"). Valor vazio ou fora do formato devolve `undefined`.
 */
export function fromDateTimeLocal(value: string | null | undefined): string | undefined {
  const match = DATETIME_LOCAL.exec(value ?? '');
  if (!match) {
    return undefined;
  }
  const [, year, month, day, hour, minute, second] = match;
  const local = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second ?? 0),
  );
  if (Number.isNaN(local)) {
    return undefined;
  }
  // WHY: `new Date('2026-11-10T10:00')` usaria o fuso da máquina (ADR 028). O deslocamento de São Paulo é medido no
  // próprio fuso, e a segunda passada acerta a data em que o deslocamento mudou (horário de verão até 2019).
  const firstGuess = local - offsetAt(local);
  return new Date(local - offsetAt(firstGuess)).toISOString();
}

function wallTimeOf(instant: Instant | null | undefined): WallTime | undefined {
  const millis = toMillis(instant);
  return millis === undefined ? undefined : wallTime(millis);
}

function wallTime(millis: number): WallTime {
  const values: Record<string, number> = {};
  for (const part of wallTimeFormat.formatToParts(millis)) {
    if (part.type !== 'literal') {
      values[part.type] = Number(part.value);
    }
  }
  return {
    year: values['year'],
    month: values['month'],
    day: values['day'],
    hour: values['hour'],
    minute: values['minute'],
    second: values['second'],
  };
}

/** Diferença entre o relógio de São Paulo e o UTC no instante, em milissegundos (-3 h hoje). */
function offsetAt(millis: number): number {
  const time = wallTime(millis);
  const asUtc = Date.UTC(time.year, time.month - 1, time.day, time.hour, time.minute, time.second);
  return asUtc - Math.floor(millis / SECOND) * SECOND;
}

function toMillis(instant: Instant | null | undefined): number | undefined {
  if (instant === null || instant === undefined || instant === '') {
    return undefined;
  }
  const millis =
    instant instanceof Date
      ? instant.getTime()
      : typeof instant === 'number'
        ? instant
        : Date.parse(instant);
  return Number.isFinite(millis) ? millis : undefined;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}
