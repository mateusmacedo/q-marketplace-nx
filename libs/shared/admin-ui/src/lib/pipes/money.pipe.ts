import { Pipe, PipeTransform } from '@angular/core';
import { formatMoney } from '@marketplace/shared-web-util';

/** Valor na moeda do produto, no padrão brasileiro: `{{ offer.salePrice | money: product.currency }}`. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(
    amount: number | string | null | undefined,
    currency: string | null | undefined,
  ): string {
    return formatMoney(amount, currency ?? '');
  }
}
