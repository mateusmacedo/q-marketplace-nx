import { Pipe, PipeTransform } from '@angular/core';
import {
  formatDate,
  formatDateTime,
  formatShortDateTime,
  formatTime,
  type Instant
} from '@marketplace/shared-web-util';

/** Forma da data: `full` "08/11/2026 14:02", `short` "08/11 14:02", `date` "08/11/2026" e `time` "14:02". */
export type DateTimeFormat = 'full' | 'short' | 'date' | 'time';

const FORMATS: Readonly<Record<DateTimeFormat, (instant: Instant | null | undefined) => string>> = {
  full: formatDateTime,
  short: formatShortDateTime,
  date: formatDate,
  time: formatTime
};

/** Data e hora no fuso de São Paulo (ADR 028): `{{ product.updatedAt | dateTime: 'short' }}`. */
@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(instant: Instant | null | undefined, format: DateTimeFormat = 'full'): string {
    return FORMATS[format](instant);
  }
}
