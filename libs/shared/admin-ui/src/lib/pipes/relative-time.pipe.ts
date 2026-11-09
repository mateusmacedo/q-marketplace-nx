import { Pipe, PipeTransform } from '@angular/core';
import { formatRelative, type Instant } from '@marketplace/shared-web-util';

/**
 * Distância até agora ("há 1 hora"): `{{ product.updatedAt | relativeTime }}`. É pura: o texto é calculado quando o
 * valor muda, sem relógio rodando na tela.
 */
@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  transform(instant: Instant | null | undefined, now?: Instant): string {
    return formatRelative(instant, now ?? Date.now());
  }
}
