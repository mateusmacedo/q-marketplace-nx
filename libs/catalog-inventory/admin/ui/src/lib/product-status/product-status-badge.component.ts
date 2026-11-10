import { ChangeDetectionStrategy, Component, computed, input, type Signal } from '@angular/core';
import { BadgeComponent } from '@coreui/angular';

import { type ProductStatus, type ProductStatusLook, productStatusLook } from './product-status';

/** Badge sólido do status do produto, sempre com o texto, nunca só a cor. */
@Component({
  selector: 'app-product-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent],
  template: `<c-badge [color]="look().color" [textColor]="look().textColor ?? ''">{{ look().label }}</c-badge>`,
})
export class ProductStatusBadgeComponent {
  readonly status = input.required<ProductStatus | string>();

  readonly look: Signal<ProductStatusLook> = computed(() => productStatusLook(this.status()));
}
