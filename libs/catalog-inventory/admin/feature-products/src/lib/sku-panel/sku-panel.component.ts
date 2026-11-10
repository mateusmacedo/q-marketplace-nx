import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  effect,
  inject,
  input,
  model,
  output,
  ViewEncapsulation,
} from '@angular/core';
import {
  ButtonCloseDirective,
  OffcanvasBodyComponent,
  OffcanvasComponent,
  OffcanvasHeaderComponent,
  OffcanvasTitleDirective,
} from '@coreui/angular';
import type { SkuResponse } from '@marketplace/catalog-inventory-admin-data-access';
import type { ActionState } from '@marketplace/shared-web-util';

import { SkuOfferSectionComponent } from './sku-offer-section.component';
import { SkuStockSectionComponent } from './sku-stock-section.component';

let nextId = 0;

/**
 * Painel do SKU (proposta de design, seção "5. Painel do SKU"): offcanvas à direita com o preço, as promoções e o
 * estoque de um SKU, cada parte gravada à parte, com a própria versão. Preço e promoções seguem `EDIT_OFFER`, e o
 * estoque, `EDIT_STOCK`. Mudar oferta ou estoque não devolve o produto à moderação (ADR 014), então não há esse aviso.
 */
@Component({
  selector: 'app-sku-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // WHY: no CoreUI 5.7.42 o `c-offcanvas` põe cabeçalho e corpo num `div` do foco preso, fora do flex do painel; sem
  // `display: contents` o corpo não rola, e o estoque some embaixo das promoções e do formulário de agendar.
  encapsulation: ViewEncapsulation.None,
  styles: 'app-sku-panel .offcanvas > div:not(.cdk-focus-trap-anchor) { display: contents; }',
  imports: [
    ButtonCloseDirective,
    OffcanvasBodyComponent,
    OffcanvasComponent,
    OffcanvasHeaderComponent,
    OffcanvasTitleDirective,
    SkuOfferSectionComponent,
    SkuStockSectionComponent,
  ],
  template: `
    <c-offcanvas
      (visibleChange)="visible.set($event)"
      [attr.aria-labelledby]="titleId"
      [style.--cui-offcanvas-width]="'min(32rem, 92vw)'"
      [visible]="visible()"
      placement="end"
    >
      <c-offcanvas-header>
        <div>
          <h5 [id]="titleId" cOffcanvasTitle>{{ sku()?.sellerSku }}</h5>
          @if (variation()) {
            <small class="text-body-secondary">{{ variation() }}</small>
          }
        </div>
        <button (click)="visible.set(false)" aria-label="Fechar" cButtonClose></button>
      </c-offcanvas-header>
      <c-offcanvas-body>
        @if (visible() && sku()?.id; as skuId) {
          <app-sku-offer-section
            (changed)="changed.emit()"
            [currency]="currency()"
            [editOffer]="editOffer()"
            [focusPrice]="focusPrice()"
            [skuId]="skuId"
          />
          <app-sku-stock-section (changed)="changed.emit()" [editStock]="editStock()" [skuId]="skuId" />
        }
      </c-offcanvas-body>
    </c-offcanvas>
  `,
})
export class SkuPanelComponent {
  readonly visible = model(false);
  readonly sku = input<SkuResponse | null | undefined>();
  /** Variação do SKU em uma linha ("M / Azul"), abaixo do código. */
  readonly variation = input('');
  /** Moeda do produto. */
  readonly currency = input.required<string>();
  readonly editOffer = input<ActionState>({ allowed: false });
  readonly editStock = input<ActionState>({ allowed: false });
  /** Põe o foco no preço ao abrir, como quando o checklist aponta o preço do SKU. */
  readonly focusPrice = input(false);

  /** Preço, promoções ou estoque mudaram. */
  readonly changed = output<void>();

  readonly titleId = `app-sku-panel-${nextId++}`;

  readonly #document = inject(DOCUMENT);
  #opener: HTMLElement | null = null;
  #wasVisible = false;

  constructor() {
    effect(() => {
      const visible = this.visible();
      if (visible && !this.#wasVisible) {
        const active = this.#document.activeElement;
        this.#opener =
          active instanceof HTMLElement && active !== this.#document.body ? active : null;
      }
      if (!visible && this.#wasVisible) {
        const target = this.#opener;
        // WHY: espera a transição do offcanvas do CoreUI, que tira o foco do painel ao terminar de fechar.
        setTimeout(() => target?.focus());
      }
      this.#wasVisible = visible;
    });
  }
}
