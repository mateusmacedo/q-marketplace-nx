import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  SpinnerComponent,
  TooltipDirective
} from '@coreui/angular';
import { IconDirective } from '@coreui/icons-angular';
import { ProductStatusBadgeComponent } from '@marketplace/catalog-inventory-admin-ui';
import {
  ActionMenuComponent,
  ConfirmModalComponent,
  DateTimePipe,
  EditConflictAlertComponent,
  ProblemAlertComponent,
  ToastService
} from '@marketplace/shared-admin-ui';
import { isEditConflict, plural, type Problem, toProblem } from '@marketplace/shared-web-util';

import {
  primaryProductAction,
  type ProductActionPresentation,
  type ProductMenuAction,
  productMenu
} from '../product-actions';
import {
  actionFailureHeading,
  actionMenuItems,
  productActionPresentation,
  refusalIssues,
  runProductAction
} from './product-menu';
import { ProductModerationNoticeComponent } from './product-moderation-notice.component';
import { ProductStore } from './product-store';

/** Quantos caracteres do id da loja aparecem antes do completo no tooltip. */
const STORE_ID_PREFIX = 8;

/**
 * Cabeçalho do produto aberto: título, status, a ação principal como botão e o menu "..." com as ações que o servidor
 * devolve em `actions` (as de `blockedActions` desabilitadas com o motivo), mais o motivo da última decisão de
 * moderação e o resultado da ação. Serve à tela da loja e à de todas as lojas, que só muda o que mostra.
 */
@Component({
  selector: 'app-product-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ActionMenuComponent,
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ConfirmModalComponent,
    DateTimePipe,
    EditConflictAlertComponent,
    IconDirective,
    ProblemAlertComponent,
    ProductModerationNoticeComponent,
    ProductStatusBadgeComponent,
    SpinnerComponent,
    TooltipDirective
  ],
  template: `
    @if (store.product(); as product) {
      <c-card class="mb-4">
        <c-card-body>
          <div class="d-flex flex-wrap align-items-start gap-3">
            <div class="me-auto">
              <h1 class="h4 card-title mb-1 d-flex flex-wrap align-items-center gap-2">
                <span>{{ product.title }}</span>
                @if (product.status) {
                  <app-product-status-badge [status]="product.status" />
                }
                @if (product.lastRejection) {
                  <c-badge color="danger">Rejeitado</c-badge>
                }
              </h1>
              <div class="small text-body-secondary">
                @if (showStore() && product.storeId) {
                  Loja <span [cTooltip]="product.storeId" class="font-monospace" tabindex="0">{{ shortStore() }}…</span> |
                }
                {{ skuCount() }} | Atualizado em {{ product.updatedAt | dateTime }}
              </div>
            </div>
            <div class="d-flex align-items-center gap-2">
              @if (showPrimary() && primary(); as item) {
                @if (item.blockedReason) {
                  <span [cTooltip]="item.blockedReason" class="d-inline-block" tabindex="0">
                    <button cButton color="primary" disabled type="button">
                      <svg [cIcon]="item.action.icon" aria-hidden="true" class="me-2"></svg>{{ item.action.label }}
                      <span class="visually-hidden">: {{ item.blockedReason }}</span>
                    </button>
                  </span>
                } @else {
                  <button
                    #primaryButton
                    (click)="choose(item.action.code, primaryButton)"
                    [disabled]="running() !== null"
                    cButton
                    color="primary"
                    type="button"
                  >
                    @if (running() === item.action.code) {
                      <c-spinner aria-hidden="true" class="me-2" size="sm" />{{ item.action.runningLabel }}…
                    } @else {
                      <svg [cIcon]="item.action.icon" aria-hidden="true" class="me-2"></svg>{{ item.action.label }}
                    }
                  </button>
                }
              }
              <app-action-menu
                (action)="choose($event.id, $event.toggle)"
                [disabled]="running() !== null"
                [items]="menu()"
                [label]="'Ações de ' + product.title"
              />
            </div>
          </div>
        </c-card-body>
      </c-card>

      <app-product-moderation-notice [product]="product" />

      @if (conflict()) {
        <app-edit-conflict-alert (reload)="reloadAfterConflict()" [busy]="reloading()" />
      } @else if (problem(); as problem) {
        <app-problem-alert [heading]="heading()" [problem]="problem" [showFieldErrors]="false" />
      }
      @if (store.problem(); as problem) {
        <app-problem-alert
          [problem]="problem"
          [showFieldErrors]="false"
          heading="Não foi possível ler a versão atual do produto"
        />
      }

      <app-confirm-modal
        (confirm)="confirm()"
        [(visible)]="confirmOpen"
        [busy]="running() !== null"
        [confirmColor]="confirming()?.confirmation?.color ?? 'primary'"
        [confirmLabel]="confirming()?.confirmation?.confirmLabel ?? 'Confirmar'"
        [returnFocus]="confirmOpener()"
        [title]="confirming()?.confirmation?.title ?? ''"
      >
        <p class="fw-semibold mb-2">{{ product.title }}</p>
        <p class="mb-0">{{ confirming()?.confirmation?.message }}</p>
      </app-confirm-modal>
    }
  `
})
export class ProductHeaderComponent {
  /** Mostra a ação principal como botão ao lado do menu, como na tela do produto da loja. */
  readonly showPrimary = input(true);
  /** Mostra a loja do produto, como em Produtos de todas as lojas. */
  readonly showStore = input(false);

  readonly store = inject(ProductStore);
  readonly #toast = inject(ToastService);

  readonly menu = computed(() => {
    const product = this.store.product();
    return product ? actionMenuItems(productMenu(product, 'detail')) : [];
  });
  readonly primary = computed(() => {
    const product = this.store.product();
    return product ? primaryProductAction(product) : null;
  });
  readonly skuCount = computed(() => plural(this.store.product()?.skus?.length ?? 0, 'SKU', 'SKUs'));
  readonly shortStore = computed(() => (this.store.product()?.storeId ?? '').slice(0, STORE_ID_PREFIX));

  readonly running = signal<ProductMenuAction | null>(null);
  /** Ação à espera da confirmação no modal. */
  readonly confirming = signal<ProductActionPresentation | null>(null);
  readonly confirmOpen = signal(false);
  readonly confirmOpener = signal<HTMLElement | null>(null);
  readonly problem = signal<Problem | null>(null);
  readonly #failed = signal<ProductActionPresentation | null>(null);
  readonly heading = computed(() => {
    const action = this.#failed();
    return action ? actionFailureHeading(action) : undefined;
  });
  readonly conflict = computed(() => isEditConflict(this.problem()));
  readonly reloading = signal(false);

  /** Ação escolhida no botão principal ou no menu; as que pedem confirmação abrem o modal antes. */
  choose(code: string, opener: HTMLElement): void {
    const action = productActionPresentation(code);
    if (!action || this.running() !== null) {
      return;
    }
    if (action.confirmation) {
      this.confirmOpener.set(opener);
      this.confirming.set(action);
      this.confirmOpen.set(true);
      return;
    }
    void this.execute(action);
  }

  /** Confirmou no modal: grava e fecha, com o resultado no toast ou no alerta do topo. */
  async confirm(): Promise<void> {
    const action = this.confirming();
    if (action) {
      await this.execute(action);
    }
    this.confirmOpen.set(false);
  }

  async execute(action: ProductActionPresentation): Promise<void> {
    const product = this.store.product();
    if (!product?.id) {
      return;
    }
    this.running.set(action.code);
    this.problem.set(null);
    try {
      const result = await runProductAction(action.code, product.id, this.store.version());
      this.store.accept(result);
      if (action.successMessage) {
        this.#toast.success(action.successMessage);
      }
    } catch (error) {
      const problem = toProblem(error);
      this.#failed.set(action);
      this.problem.set(problem);
      if (action.code === 'SUBMIT' && problem.status === 422) {
        this.store.refusal.set(refusalIssues(problem, this.store.product()?.publishingIssues));
      }
    } finally {
      this.running.set(null);
    }
  }

  /** Conflito de edição: relê o produto, e o formulário de conteúdo troca o que foi digitado pela versão atual. */
  async reloadAfterConflict(): Promise<void> {
    this.reloading.set(true);
    try {
      if (await this.store.reload(true)) {
        this.problem.set(null);
      }
    } finally {
      this.reloading.set(false);
    }
  }
}
