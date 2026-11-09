import { ChangeDetectionStrategy, Component, computed, effect, input, model, output, signal, untracked } from '@angular/core';
import {
  ButtonCloseDirective,
  ButtonDirective,
  FormLabelDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  ModalBodyComponent,
  ModalComponent,
  ModalFooterComponent,
  ModalHeaderComponent,
  ModalTitleDirective,
  SpinnerComponent
} from '@coreui/angular';
import { cilMinus, cilPlus, cilX } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  type CategoryChangeResponse,
  checkProductResource
} from '@marketplace/catalog-inventory-admin-data-access';
import { type CategoryOption, CategorySelectComponent } from '@marketplace/catalog-inventory-admin-ui';
import { ErrorStateComponent, LoadingStateComponent, ProblemAlertComponent } from '@marketplace/shared-admin-ui';
import { type Problem, toProblem, translateReason } from '@marketplace/shared-web-util';

import { loadUsableCategories } from './product-categories';

/** Troca escolhida no modal: a categoria nova, o caminho dela e o efeito que o servidor calculou. */
export interface CategoryChangeChoice {
  readonly categoryId: string;
  readonly path: string;
  readonly change: CategoryChangeResponse;
}

const NO_CHANGE: CategoryChangeResponse = { removedAttributes: [], newRequired: [], blockingSkus: [] };

let nextId = 0;

/**
 * Modal "Trocar categoria" do conteúdo do produto: o `form-select` com o caminho das categorias usáveis, sem a atual,
 * e a prévia do efeito que o servidor calcula em `POST /products/{id}/check` (atributos que somem, obrigatórios novos e
 * SKUs que impedem a troca). Trocar só muda o formulário; a gravação fica para o Salvar.
 */
@Component({
  selector: 'app-category-change-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonCloseDirective,
    ButtonDirective,
    CategorySelectComponent,
    ErrorStateComponent,
    FormLabelDirective,
    IconDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    ModalBodyComponent,
    ModalComponent,
    ModalFooterComponent,
    ModalHeaderComponent,
    ModalTitleDirective,
    ProblemAlertComponent,
    SpinnerComponent
  ],
  template: `
    <c-modal [(visible)]="visible" [attr.aria-labelledby]="titleId" size="lg">
      <c-modal-header>
        <h5 [id]="titleId" cModalTitle>Trocar categoria</h5>
        <button (click)="close()" aria-label="Fechar" cButtonClose></button>
      </c-modal-header>
      <c-modal-body>
        <p class="mb-3">
          <span class="text-body-secondary">Categoria atual:</span>
          {{ currentPath() }}
        </p>
        <label [for]="selectId" cLabel>Nova categoria</label>
        @if (options(); as options) {
          <app-category-select
            [(value)]="selected"
            [excluded]="[currentCategoryId()]"
            [options]="options"
            [selectId]="selectId"
            placeholder="Escolha a nova categoria"
          />
        } @else if (optionsProblem(); as problem) {
          <app-error-state (retry)="loadOptions()" [problem]="problem" message="Não foi possível carregar as categorias." />
        } @else {
          <app-loading-state [rows]="1" label="Carregando as categorias…" />
        }

        @if (selected()) {
          <h6 class="mt-4 mb-2">Efeito da troca</h6>
          @if (check.isLoading()) {
            <div class="text-body-secondary" role="status">
              <c-spinner aria-hidden="true" class="me-2" size="sm" />Conferindo o efeito da troca…
            </div>
          } @else if (checkProblem(); as problem) {
            <app-problem-alert [problem]="problem" heading="Não foi possível conferir a troca" />
          } @else if (change(); as change) {
            @if (hasEffect()) {
              <ul cListGroup flush>
                @for (attribute of change.removedAttributes ?? []; track $index) {
                  <li cListGroupItem class="d-flex align-items-start">
                    <svg [cIcon]="icons.removed" aria-hidden="true" class="me-2 mt-1 text-danger flex-shrink-0"></svg>
                    <span>
                      <strong>{{ attribute.label }}</strong>
                      @if (attribute.value) {
                        ({{ attribute.value }})
                      }
                      deixa de existir.
                      @if (attribute.reason) {
                        <span class="d-block small text-body-secondary">{{ reasonText(attribute.reason) }}</span>
                      }
                    </span>
                  </li>
                }
                @for (attribute of change.newRequired ?? []; track $index) {
                  <li cListGroupItem class="d-flex align-items-start">
                    <svg [cIcon]="icons.required" aria-hidden="true" class="me-2 mt-1 text-warning flex-shrink-0"></svg>
                    <span>
                      <strong>{{ attribute.label }}</strong>,
                      {{ attribute.variant ? 'obrigatório em cada SKU' : 'obrigatório no produto' }}. Entra no checklist
                      de envio.
                    </span>
                  </li>
                }
                @for (sku of change.blockingSkus ?? []; track $index) {
                  <li cListGroupItem class="d-flex align-items-start">
                    <svg [cIcon]="icons.blocking" aria-hidden="true" class="me-2 mt-1 text-danger flex-shrink-0"></svg>
                    <span>
                      <strong>{{ sku.sellerSku }}</strong>:
                      @for (reason of sku.reasons ?? []; track $index) {
                        {{ reasonText(reason) }}
                      }
                    </span>
                  </li>
                }
              </ul>
            } @else {
              <p class="mb-0">A troca não muda os atributos nem os SKUs.</p>
            }
            @if (change.blockingSkus?.length) {
              <p class="mt-3 mb-0 fw-semibold">
                @if (canRemoveSkus()) {
                  Remova esses SKUs, troque a categoria e crie os SKUs de novo.
                } @else {
                  Escolha uma categoria com as mesmas variações ou arquive o produto e crie outro.
                }
              </p>
            }
          }
        }
      </c-modal-body>
      <c-modal-footer>
        <button (click)="close()" cButton color="secondary" type="button">Cancelar</button>
        <button (click)="confirm()" [disabled]="!canConfirm()" cButton color="primary" type="button">Trocar</button>
      </c-modal-footer>
    </c-modal>
  `
})
export class CategoryChangeModalComponent {
  readonly visible = model(false);
  readonly productId = input.required<string>();
  readonly currentCategoryId = input.required<string>();
  readonly currentPath = input('');
  /** Atributos do rascunho, conferidos com a categoria nova. */
  readonly attributes = input<Readonly<Record<string, string>>>({});
  /** O servidor libera remover SKU (`REMOVE_SKU`), o que muda a orientação dos SKUs que impedem a troca. */
  readonly canRemoveSkus = input(false);

  readonly changed = output<CategoryChangeChoice>();

  readonly titleId = `app-category-change-${nextId++}`;
  readonly selectId = `${this.titleId}-select`;
  readonly icons = { removed: cilMinus, required: cilPlus, blocking: cilX };

  readonly options = signal<CategoryOption[] | null>(null);
  readonly optionsProblem = signal<Problem | null>(null);
  readonly selected = signal<string | null>(null);

  readonly check = checkProductResource(() => {
    const categoryId = this.selected();
    return this.visible() && categoryId
      ? { path: { id: this.productId() }, body: { categoryId, attributes: { ...this.attributes() } } }
      : undefined;
  });
  readonly checkProblem = computed(() => {
    const error = this.check.error();
    return error ? toProblem(error) : null;
  });
  readonly change = computed(() => (this.check.hasValue() ? (this.check.value().categoryChange ?? NO_CHANGE) : null));
  readonly hasEffect = computed(() => {
    const change = this.change();
    return !!(change?.removedAttributes?.length || change?.newRequired?.length || change?.blockingSkus?.length);
  });
  readonly canConfirm = computed(
    () => !!this.selected() && !this.check.isLoading() && this.change() !== null && !this.change()?.blockingSkus?.length
  );

  #loading = false;

  constructor() {
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.selected.set(null);
          if (!this.options()) {
            void this.loadOptions();
          }
        });
      }
    });
  }

  async loadOptions(): Promise<void> {
    if (this.#loading) {
      return;
    }
    this.#loading = true;
    this.optionsProblem.set(null);
    try {
      this.options.set(await loadUsableCategories());
    } catch (error) {
      this.optionsProblem.set(toProblem(error));
    } finally {
      this.#loading = false;
    }
  }

  reasonText(reason: Parameters<typeof translateReason>[0]): string {
    return translateReason(reason);
  }

  confirm(): void {
    const categoryId = this.selected();
    const change = this.change();
    if (!categoryId || !change || !this.canConfirm()) {
      return;
    }
    const path = this.options()?.find(option => option.id === categoryId)?.path ?? '';
    this.changed.emit({ categoryId, path, change });
    this.visible.set(false);
  }

  close(): void {
    this.visible.set(false);
  }
}
