import type { ActivityPointResponse, ActivityResponse } from '@marketplace/catalog-inventory-admin-data-access';

/** Tamanho dos baldes da série de atividade: Dia, Mês ou Ano. */
export type ActivityGranularity = NonNullable<ActivityResponse['granularity']>;

/** Opções do grupo Dia, Mês e Ano do card "Catálogo", na ordem do template. */
export const ACTIVITY_GRANULARITIES: readonly { readonly value: ActivityGranularity; readonly label: string }[] = [
  { value: 'day', label: 'Dia' },
  { value: 'month', label: 'Mês' },
  { value: 'year', label: 'Ano' }
];

const LOCAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

// WHY: só o nome do mês é lido; o fuso UTC impede que o fuso do navegador mude o mês do instante formatado.
const monthFormat = new Intl.DateTimeFormat('pt-BR', { month: 'long', timeZone: 'UTC' });

interface LocalDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

/** Rótulo do balde no eixo e nos minigráficos: "12/10" no dia, "Outubro" no mês e "2026" no ano. */
export function bucketLabel(period: string | undefined, granularity: ActivityGranularity): string {
  const date = parseLocalDate(period);
  if (!date) {
    return period ?? '';
  }
  switch (granularity) {
    case 'day':
      return `${pad(date.day)}/${pad(date.month)}`;
    case 'year':
      return String(date.year);
    default:
      return capitalize(monthName(date));
  }
}

/**
 * Período da série no subtítulo do card: "12/10/2026 a 08/11/2026" no dia, "Janeiro a dezembro de 2026" no mês (ou
 * "Novembro de 2025 a outubro de 2026", quando passa de um ano) e "2022 a 2026" no ano.
 */
export function activityPeriod(activity: Pick<ActivityResponse, 'granularity' | 'from' | 'to'>): string {
  const from = parseLocalDate(activity.from);
  const to = parseLocalDate(activity.to);
  if (!from || !to) {
    return '';
  }
  switch (activity.granularity) {
    case 'day':
      return range(fullDate(from), fullDate(to));
    case 'year':
      return range(String(from.year), String(to.year));
    default:
      return monthRange(from, to);
  }
}

const CSV_HEADER = ['Período', 'Enviados à moderação', 'Aprovados', 'Rejeitados', 'Bloqueados'];

/** Números da série em CSV, um balde por linha, com o início do balde em ISO 8601. */
export function activityCsv(activity: Pick<ActivityResponse, 'points'>): string {
  const rows = (activity.points ?? []).map((point: ActivityPointResponse) => [
    point.period ?? '',
    point.submitted ?? 0,
    point.approved ?? 0,
    point.rejected ?? 0,
    point.blocked ?? 0
  ]);
  return [CSV_HEADER, ...rows].map(row => row.join(',')).join('\r\n') + '\r\n';
}

/** Nome do arquivo baixado: "catalogo-mes-2026-01-01-a-2026-12-31.csv". */
export function activityCsvName(activity: Pick<ActivityResponse, 'granularity' | 'from' | 'to'>): string {
  const label = ACTIVITY_GRANULARITIES.find(option => option.value === activity.granularity)?.label ?? 'periodo';
  const slug = label
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase();
  return `catalogo-${slug}-${activity.from ?? ''}-a-${activity.to ?? ''}.csv`;
}

function monthRange(from: LocalDate, to: LocalDate): string {
  if (from.year === to.year) {
    const first = capitalize(monthName(from));
    return from.month === to.month ? `${first} de ${from.year}` : `${first} a ${monthName(to)} de ${to.year}`;
  }
  return `${capitalize(monthName(from))} de ${from.year} a ${monthName(to)} de ${to.year}`;
}

function range(first: string, last: string): string {
  return first === last ? first : `${first} a ${last}`;
}

function fullDate(date: LocalDate): string {
  return `${pad(date.day)}/${pad(date.month)}/${date.year}`;
}

function monthName(date: LocalDate): string {
  return monthFormat.format(Date.UTC(2000, date.month - 1, 1));
}

function parseLocalDate(text: string | undefined): LocalDate | undefined {
  const match = LOCAL_DATE.exec(text ?? '');
  return match ? { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) } : undefined;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}
