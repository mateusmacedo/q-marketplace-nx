import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  ButtonDirective,
  ColComponent,
  FormControlDirective,
  FormLabelDirective,
  RowComponent,
  SpinnerComponent,
} from '@coreui/angular';
import {
  adjustStock,
  AdjustStockRequestSchema,
  getStock,
  type StockResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  controlMessage,
  EditConflictAlertComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  NumberBlockComponent,
  ProblemAlertComponent,
  schemaValidator,
  ToastService,
} from '@marketplace/shared-admin-ui';
import {
  type ActionState,
  apiDataWithVersion,
  fieldRules,
  formatInteger,
  isEditConflict,
  type Problem,
  toProblem,
  type Versioned,
  versionTag,
} from '@marketplace/shared-web-util';

import { ifMatch } from '../product/product-store';
import { belowReserved } from './sku-offer';

let nextId = 0;

/**
 * Estoque do SKU no painel lateral: o físico, absoluto, como uma contagem da loja, com o reservado, o vendido e o
 * disponível só leitura. Lê e grava em `/inventory/skus/{skuId}` com a versão do saldo no `If-Match`, avisa quando o
 * físico digitado fica abaixo do reservado e segue `EDIT_STOCK`.
 */
@Component({
  selector: 'app-sku-stock-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    ColComponent,
    EditConflictAlertComponent,
    ErrorStateComponent,
    FormControlDirective,
    FormLabelDirective,
    LoadingStateComponent,
    NumberBlockComponent,
    ProblemAlertComponent,
    ReactiveFormsModule,
    RowComponent,
    SpinnerComponent,
  ],
  template: `
    <section [attr.aria-labelledby]="ids.heading">
      <h3 [id]="ids.heading" class="h6 text-uppercase text-body-secondary">Estoque</h3>
      @if (loading()) {
        <app-loading-state [rows]="2" label="Carregando o estoque…" />
      } @else if (loadProblem(); as problem) {
        <app-error-state (retry)="load(skuId())" [problem]="problem" message="Não foi possível carregar o estoque." />
      } @else if (stock(); as stock) {
        @if (!editStock().allowed) {
          <p class="small text-body-secondary">
            Estoque só leitura.
            @if (editStock().blockedReason; as reason) {
              {{ reason }}
            }
          </p>
        }
        @if (conflict()) {
          <app-edit-conflict-alert (reload)="reloadStock()" [busy]="reloading()" />
        } @else if (problem(); as problem) {
          <app-problem-alert [problem]="problem" heading="Não foi possível salvar o estoque" />
        }
        <c-row class="g-3 mb-3">
          <c-col [xs]="4">
            <app-number-block [value]="integer(stock.reserved)" color="warning" label="Reservado" />
          </c-col>
          <c-col [xs]="4">
            <app-number-block [value]="integer(stock.sold)" color="info" label="Vendido" />
          </c-col>
          <c-col [xs]="4">
            <app-number-block [value]="integer(stock.available)" color="success" label="Disponível" />
          </c-col>
        </c-row>
        <form (ngSubmit)="save()" [formGroup]="form" novalidate>
          <label [for]="ids.onHand" cLabel>Físico</label>
          <div class="d-flex flex-wrap align-items-start gap-2">
            <div class="flex-grow-1">
              <input
                [attr.aria-describedby]="warning() ? ids.warning : null"
                [id]="ids.onHand"
                formControlName="onHand"
                [valid]="onHand.touched && onHand.invalid ? false : undefined"
                cFormControl
                class="text-end"
                inputmode="numeric"
                step="1"
                type="number"
              />
            </div>
            @if (editStock().allowed) {
              <button [disabled]="saving()" cButton color="primary" type="submit">
                @if (saving()) {
                  <c-spinner aria-hidden="true" class="me-2" size="sm" />
                }
                Salvar estoque
              </button>
            }
          </div>
          @if (onHand.touched && message(); as text) {
            <div class="invalid-feedback d-block">{{ text }}</div>
          }
          @if (warning()) {
            <div [id]="ids.warning" class="form-text text-warning-emphasis" role="status">
              O valor fica abaixo do reservado ({{ reservedText() }}).
            </div>
          }
        </form>
      }
    </section>
  `,
})
export class SkuStockSectionComponent {
  readonly skuId = input.required<string>();
  readonly editStock = input<ActionState>({ allowed: false });

  /** O saldo mudou: a tabela de SKUs relê o produto. */
  readonly changed = output<void>();

  readonly #toast = inject(ToastService);
  readonly #prefix = `app-sku-stock-${nextId++}`;
  readonly ids = {
    heading: `${this.#prefix}-heading`,
    onHand: `${this.#prefix}-on-hand`,
    warning: `${this.#prefix}-warning`,
  };

  readonly stock = signal<StockResponse | null>(null);
  readonly #version = signal<string | undefined>(undefined);
  readonly loading = signal(true);
  readonly loadProblem = signal<Problem | null>(null);
  readonly reloading = signal(false);

  readonly form = new FormGroup({
    onHand: new FormControl<number | null>(null, [
      schemaValidator(fieldRules(AdjustStockRequestSchema, 'onHand')),
    ]),
  });
  readonly onHand = this.form.controls.onHand;
  readonly #onHandValue = toSignal(this.onHand.valueChanges, { initialValue: this.onHand.value });
  readonly warning = computed(() => belowReserved(this.#onHandValue(), this.stock()?.reserved));
  readonly reservedText = computed(() => {
    const reserved = this.stock()?.reserved ?? 0;
    return `${formatInteger(reserved)} ${reserved === 1 ? 'unidade' : 'unidades'}`;
  });

  readonly saving = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly conflict = computed(() => isEditConflict(this.problem()));

  readonly integer = formatInteger;

  #request = 0;

  constructor() {
    effect(() => {
      const id = this.skuId();
      untracked(() => void this.load(id));
    });
    effect(() => {
      const editable = this.editStock().allowed;
      untracked(() => (editable ? this.onHand.enable() : this.onHand.disable()));
    });
  }

  message(): string | null {
    return controlMessage(this.onHand);
  }

  async load(skuId: string): Promise<void> {
    const request = ++this.#request;
    this.loading.set(true);
    this.loadProblem.set(null);
    try {
      const result = await apiDataWithVersion(getStock({ path: { skuId } }));
      if (request === this.#request) {
        this.#accept(result);
      }
    } catch (error) {
      if (request === this.#request) {
        this.loadProblem.set(toProblem(error));
      }
    } finally {
      if (request === this.#request) {
        this.loading.set(false);
      }
    }
  }

  /** Depois do conflito de edição: o saldo atual substitui o que foi digitado. */
  async reloadStock(): Promise<void> {
    this.reloading.set(true);
    try {
      await this.load(this.skuId());
      this.problem.set(null);
    } finally {
      this.reloading.set(false);
    }
  }

  async save(): Promise<void> {
    if (!this.editStock().allowed || this.saving()) {
      return;
    }
    const onHand = this.onHand.value;
    if (this.onHand.invalid || onHand === null) {
      this.onHand.markAsTouched();
      return;
    }
    this.saving.set(true);
    this.problem.set(null);
    try {
      const result = await apiDataWithVersion(
        adjustStock({
          path: { skuId: this.skuId() },
          headers: ifMatch(this.#version()),
          body: { onHand },
        }),
      );
      this.#acceptWrite(result);
      this.#toast.success('Estoque salvo.');
      this.changed.emit();
    } catch (error) {
      const problem = toProblem(error);
      const unmatched =
        problem.status === 400 ? applyServerErrors(this.form, problem) : (problem.errors ?? []);
      this.problem.set({ ...problem, errors: unmatched });
    } finally {
      this.saving.set(false);
    }
  }

  /** Resultado de uma gravação: a leitura em andamento começou antes dela e é descartada. */
  #acceptWrite(result: Versioned<StockResponse>): void {
    this.#request++;
    this.loading.set(false);
    this.#accept(result);
  }

  #accept(result: Versioned<StockResponse>): void {
    this.stock.set(result.data);
    this.#version.set(result.version ?? versionTag(result.data.version));
    this.onHand.reset(result.data.onHand ?? null);
  }
}
