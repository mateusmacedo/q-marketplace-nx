import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  inject,
  signal,
  untracked
} from '@angular/core';
import {
  AlertComponent,
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ProgressComponent,
  TableDirective,
  TooltipDirective
} from '@coreui/angular';
import { cilPlus, cilTrash } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  getCategoryResource,
  removeSku,
  type SkuResponse
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  type ActionMenuChoice,
  ActionMenuComponent,
  type ActionMenuItem,
  ConfirmModalComponent,
  EditConflictAlertComponent,
  EmptyStateComponent,
  MoneyPipe,
  ProblemAlertComponent,
  ToastService
} from '@marketplace/shared-admin-ui';
import {
  apiDataWithVersion,
  formatInteger,
  isEditConflict,
  plural,
  type Problem,
  toProblem
} from '@marketplace/shared-web-util';

import { availablePercent } from '../home/recent-products';
import { SkuPanelComponent } from '../sku-panel/sku-panel.component';
import { AddSkuOffcanvasComponent } from './add-sku-offcanvas.component';
import { attributeFields, variationLabel } from './product-attributes';
import { ifMatch, ProductStore } from './product-store';

/** Id do botão de um SKU na tabela, para o checklist pôr o foco nele. */
export function skuRowId(sellerSku: string): string {
  return `product-sku-${sellerSku}`;
}

const ADD_SKU_BUTTON = 'product-add-sku';

/**
 * Aba SKUs, preço e estoque: tabela com o código, a variação, o preço vigente, o disponível e a situação da oferta de
 * cada SKU. O código abre o painel do SKU; **Adicionar SKU** abre o offcanvas quando `ADD_SKU` vem em `actions`, e
 * **Remover SKU** aparece no menu quando o servidor oferece `REMOVE_SKU`, liberado ou barrado com o motivo.
 */
@Component({
  selector: 'app-product-skus-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ActionMenuComponent,
    AddSkuOffcanvasComponent,
    AlertComponent,
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ConfirmModalComponent,
    EditConflictAlertComponent,
    EmptyStateComponent,
    IconDirective,
    MoneyPipe,
    ProblemAlertComponent,
    ProgressComponent,
    SkuPanelComponent,
    TableDirective,
    TooltipDirective
  ],
  styles: '.numeric { font-variant-numeric: tabular-nums; }',
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <div class="d-flex flex-wrap align-items-start gap-2 mb-3">
          <div class="me-auto">
            <h2 class="h4 card-title mb-0" id="product-skus-heading" tabindex="-1">SKUs</h2>
            <div class="small text-body-secondary">{{ count() }} | Abra o código para ver preço, promoções e estoque.</div>
          </div>
          @if (store.rules().addSku.allowed) {
            <button (click)="addOpen.set(true)" [id]="addButtonId" cButton color="primary" type="button">
              <svg [cIcon]="icons.add" aria-hidden="true" class="me-2"></svg>Adicionar SKU
            </button>
          } @else if (store.rules().addSku.blockedReason; as reason) {
            <span [cTooltip]="reason" class="d-inline-block" tabindex="0">
              <button [id]="addButtonId" cButton color="primary" disabled type="button">
                <svg [cIcon]="icons.add" aria-hidden="true" class="me-2"></svg>Adicionar SKU
                <span class="visually-hidden">: {{ reason }}</span>
              </button>
            </span>
          }
        </div>

        @if (outOfStock()) {
          <c-alert color="warning" role="status">Sem estoque disponível.</c-alert>
        }
        @if (removeConflict()) {
          <app-edit-conflict-alert (reload)="reloadAfterConflict()" [busy]="reloading()" />
        } @else if (removeProblem(); as problem) {
          <app-problem-alert [problem]="problem" heading="Não foi possível remover o SKU" />
        }

        @if (skus().length) {
          <table [hover]="true" [responsive]="true" [striped]="true" align="middle" cTable class="mb-0 border">
            <thead class="text-nowrap text-truncate">
              <tr>
                <th class="bg-body-tertiary" scope="col">Código</th>
                <th class="bg-body-tertiary" scope="col">Variação</th>
                <th class="bg-body-tertiary text-end" scope="col">Preço vigente</th>
                <th class="bg-body-tertiary text-end" scope="col">Disponível</th>
                <th class="bg-body-tertiary" scope="col">Oferta</th>
                <th class="bg-body-tertiary" scope="col"><span class="visually-hidden">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              @for (sku of skus(); track sku.id) {
                <tr>
                  <td>
                    <button
                      (click)="openPanel(sku)"
                      [id]="rowId(sku)"
                      cButton
                      class="p-0 fw-semibold text-start"
                      color="link"
                      type="button"
                    >
                      {{ sku.sellerSku }}
                    </button>
                    <div class="small text-body-secondary">{{ shipping(sku) }}</div>
                  </td>
                  <td>{{ variation(sku) || '—' }}</td>
                  <td class="text-end numeric">
                    @if (sku.offer?.effectivePrice; as price) {
                      <strong>{{ price.amount | money: price.currency || currency() }}</strong>
                      @if (sku.offer.listPrice; as list) {
                        <div class="small text-body-secondary">
                          de <s>{{ list.amount | money: list.currency || currency() }}</s>
                        </div>
                      }
                    } @else {
                      —
                    }
                  </td>
                  <td class="text-end numeric">
                    @if (sku.stock; as stock) {
                      <strong>{{ integer(stock.available) }}</strong>
                      <div class="small text-body-secondary">de {{ integer(stock.onHand) }} no físico</div>
                      <c-progress
                        [thin]="true"
                        [value]="availablePercent(stock.available, stock.onHand)"
                        aria-label="Disponível do físico"
                        color="success"
                      />
                    } @else {
                      —
                    }
                  </td>
                  <td>
                    @if (!sku.offer) {
                      <c-badge color="secondary">Sem preço</c-badge>
                    } @else if (sku.offer.onPromotion) {
                      <c-badge color="info">Em promoção</c-badge>
                    } @else {
                      <c-badge color="success">Com preço</c-badge>
                    }
                  </td>
                  <td class="text-end">
                    <app-action-menu
                      (action)="onAction(sku, $event)"
                      [items]="menu()"
                      [label]="'Ações do SKU ' + sku.sellerSku"
                    />
                  </td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <app-empty-state message="Nenhum SKU cadastrado.">
            @if (store.rules().addSku.allowed) {
              <button (click)="addOpen.set(true)" cButton color="primary" type="button" variant="outline">
                Adicionar SKU
              </button>
            }
          </app-empty-state>
        }
      </c-card-body>
    </c-card>

    <app-add-sku-offcanvas [(visible)]="addOpen" [fields]="variantFields()" />

    <app-sku-panel
      (changed)="store.reload()"
      [(visible)]="panelOpen"
      [currency]="currency()"
      [editOffer]="store.rules().editOffer"
      [editStock]="store.rules().editStock"
      [focusPrice]="panelFocusPrice()"
      [sku]="panelSku()"
      [variation]="panelVariation()"
    />

    <app-confirm-modal
      (confirm)="remove()"
      [(visible)]="removeOpen"
      [busy]="removing()"
      [returnFocus]="removeOpener()"
      confirmColor="danger"
      confirmLabel="Remover"
      title="Remover SKU"
    >
      <p class="fw-semibold mb-2">{{ removeTarget()?.sellerSku }}</p>
      <p class="mb-0">O SKU sai do produto, com o preço e as promoções dele.</p>
    </app-confirm-modal>
  `
})
export class ProductSkusTabComponent {
  readonly store = inject(ProductStore);
  readonly #toast = inject(ToastService);
  readonly #document = inject(DOCUMENT);

  readonly icons = { add: cilPlus };
  readonly addButtonId = ADD_SKU_BUTTON;
  readonly integer = formatInteger;
  readonly availablePercent = availablePercent;

  readonly #categoryId = computed(() => this.store.product()?.categoryId);
  readonly category = getCategoryResource(() => {
    const id = this.#categoryId();
    return id ? { path: { id } } : undefined;
  });
  readonly variantFields = computed(() =>
    this.category.hasValue() ? attributeFields(this.category.value().effectiveAttributes, true) : []
  );
  readonly skus = computed(() => this.store.product()?.skus ?? []);
  readonly count = computed(() => plural(this.skus().length, 'SKU', 'SKUs'));
  readonly currency = computed(() => this.store.product()?.currency ?? '');
  /** Aviso do status calculado `OUT_OF_STOCK` (o `ACTIVE` sem saldo), que o servidor devolve no produto. */
  readonly outOfStock = computed(() => this.store.product()?.status === 'OUT_OF_STOCK');
  readonly menu = computed<ActionMenuItem[]>(() => {
    const state = this.store.rules().removeSku;
    if (state.allowed) {
      return [{ id: 'REMOVE_SKU', label: 'Remover SKU', icon: cilTrash, danger: true }];
    }
    return state.blockedReason === undefined
      ? []
      : [{ id: 'REMOVE_SKU', label: 'Remover SKU', icon: cilTrash, danger: true, disabledReason: state.blockedReason }];
  });

  readonly addOpen = signal(false);
  readonly panelOpen = signal(false);
  readonly #panelSkuId = signal<string | null>(null);
  readonly panelSku = computed(() => this.skus().find(sku => sku.id === this.#panelSkuId()) ?? null);
  readonly panelVariation = computed(() => {
    const sku = this.panelSku();
    return sku ? this.variation(sku) : '';
  });
  readonly panelFocusPrice = signal(false);

  readonly removeTarget = signal<SkuResponse | null>(null);
  readonly removeOpen = signal(false);
  readonly removeOpener = signal<HTMLElement | null>(null);
  readonly removing = signal(false);
  readonly removeProblem = signal<Problem | null>(null);
  readonly removeConflict = computed(() => isEditConflict(this.removeProblem()));
  readonly reloading = signal(false);

  constructor() {
    afterRenderEffect(() => {
      const item = this.store.focus();
      if (item?.tab !== 'skus') {
        return;
      }
      untracked(() => {
        this.store.focus.set(null);
        const sku = item.sellerSku ? this.skus().find(candidate => candidate.sellerSku === item.sellerSku) : undefined;
        if (sku && item.field === 'offer') {
          this.openPanel(sku, true);
          return;
        }
        const target = sku ? this.rowId(sku) : this.store.rules().addSku.allowed ? ADD_SKU_BUTTON : 'product-skus-heading';
        this.#document.getElementById(target)?.focus();
      });
    });
  }

  rowId(sku: SkuResponse): string {
    return skuRowId(sku.sellerSku ?? sku.id ?? '');
  }

  variation(sku: SkuResponse): string {
    return variationLabel(this.variantFields(), sku.variantAttributes);
  }

  shipping(sku: SkuResponse): string {
    const dimensions = sku.dimensions;
    if (!sku.physical) {
      return 'Sem envio físico';
    }
    return dimensions
      ? `Físico | ${dimensions.heightCm} × ${dimensions.widthCm} × ${dimensions.lengthCm} cm, ${formatInteger(dimensions.weightGrams)} g`
      : 'Físico';
  }

  openPanel(sku: SkuResponse, focusPrice = false): void {
    this.#panelSkuId.set(sku.id ?? null);
    this.panelFocusPrice.set(focusPrice);
    this.panelOpen.set(true);
  }

  onAction(sku: SkuResponse, choice: ActionMenuChoice): void {
    if (choice.id === 'REMOVE_SKU') {
      this.removeTarget.set(sku);
      this.removeOpener.set(choice.toggle);
      this.removeOpen.set(true);
    }
  }

  async remove(): Promise<void> {
    const product = this.store.product();
    const sku = this.removeTarget();
    if (!product?.id || !sku?.id || this.removing()) {
      return;
    }
    this.removing.set(true);
    this.removeProblem.set(null);
    try {
      const result = await apiDataWithVersion(
        removeSku({ path: { id: product.id, skuId: sku.id }, headers: ifMatch(this.store.version()) })
      );
      this.store.accept(result);
      this.#toast.success('SKU removido.');
    } catch (error) {
      this.removeProblem.set(toProblem(error));
    } finally {
      this.removing.set(false);
      this.removeOpen.set(false);
    }
  }

  async reloadAfterConflict(): Promise<void> {
    this.reloading.set(true);
    try {
      if (await this.store.reload()) {
        this.removeProblem.set(null);
      }
    } finally {
      this.reloading.set(false);
    }
  }
}
