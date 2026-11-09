import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  inject,
  input,
  output,
  signal,
  untracked
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  BadgeComponent,
  ButtonDirective,
  FormControlDirective,
  FormLabelDirective,
  InputGroupComponent,
  InputGroupTextDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  SpinnerComponent
} from '@coreui/angular';
import {
  cancelPromotion,
  defineOffer,
  getOffer,
  MoneyDtoSchema,
  type OfferResponse,
  PromotionRequestSchema,
  type PromotionResponse,
  schedulePromotion
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  ConfirmModalComponent,
  controlMessage,
  DateTimePipe,
  EditConflictAlertComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  MoneyPipe,
  ProblemAlertComponent,
  schemaValidator,
  ToastService
} from '@marketplace/shared-admin-ui';
import {
  type ActionState,
  apiData,
  apiDataWithVersion,
  fieldRules,
  fromDateTimeLocal,
  isEditConflict,
  type Problem,
  toProblem,
  type Versioned,
  versionTag
} from '@marketplace/shared-web-util';

import { ifMatch } from '../product/product-store';
import { listedPromotions, moneyOf, promotionPhaseLabel } from './sku-offer';

let nextId = 0;

/** Id do campo "Por" do painel, para o checklist pôr o foco no preço do SKU. */
export const SALE_PRICE_FIELD = 'sku-panel-sale-price';

/**
 * Preço e promoções do SKU no painel lateral. Lê a oferta em `GET /offers/{skuId}` (404 quando o SKU ainda não tem
 * oferta) e grava em `PUT /offers/{skuId}`: a primeira gravação cria a oferta, e as seguintes levam o `If-Match`. As
 * promoções vigentes e agendadas aparecem com o cancelamento, e **Agendar** abre o formulário no próprio painel, com
 * as datas lidas no fuso de São Paulo e enviadas em UTC. Tudo segue `EDIT_OFFER`.
 */
@Component({
  selector: 'app-sku-offer-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    ButtonDirective,
    ConfirmModalComponent,
    DateTimePipe,
    EditConflictAlertComponent,
    ErrorStateComponent,
    FormControlDirective,
    FormLabelDirective,
    InputGroupComponent,
    InputGroupTextDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    MoneyPipe,
    ProblemAlertComponent,
    ReactiveFormsModule,
    SpinnerComponent
  ],
  styles: '.numeric { font-variant-numeric: tabular-nums; }',
  template: `
    <section [attr.aria-labelledby]="ids.price" class="mb-4">
      <h3 [id]="ids.price" class="h6 text-uppercase text-body-secondary">Preço</h3>
      @if (loading()) {
        <app-loading-state [rows]="2" label="Carregando o preço…" />
      } @else if (loadProblem(); as problem) {
        <app-error-state (retry)="load(skuId())" [problem]="problem" message="Não foi possível carregar o preço." />
      } @else {
        @if (!editOffer().allowed) {
          <p class="small text-body-secondary">
            Preço e promoções só leitura.
            @if (editOffer().blockedReason; as reason) {
              {{ reason }}
            }
          </p>
        }
        @if (priceConflict()) {
          <app-edit-conflict-alert (reload)="reloadOffer()" [busy]="reloading()" />
        } @else if (priceProblem(); as problem) {
          <app-problem-alert [problem]="problem" heading="Não foi possível salvar o preço" />
        }
        <form (ngSubmit)="savePrice()" [formGroup]="priceForm" novalidate>
          <div class="row g-3">
            <div class="col-sm-6">
              <label [for]="ids.listPrice" cLabel>De</label>
              <c-input-group>
                <span cInputGroupText>{{ currency() }}</span>
                <input
                  [id]="ids.listPrice"
                  [valid]="invalid(priceForm.controls.listPrice) ? false : undefined"
                  cFormControl
                  class="text-end numeric"
                  formControlName="listPrice"
                  inputmode="decimal"
                  step="0.01"
                  type="number"
                />
              </c-input-group>
              <div class="form-text">Opcional, aparece riscado.</div>
              @if (message(priceForm.controls.listPrice); as text) {
                <div class="invalid-feedback d-block">{{ text }}</div>
              }
            </div>
            <div class="col-sm-6">
              <label [for]="ids.salePrice" cLabel>Por</label>
              <c-input-group>
                <span cInputGroupText>{{ currency() }}</span>
                <input
                  [id]="ids.salePrice"
                  [valid]="invalid(priceForm.controls.salePrice) ? false : undefined"
                  cFormControl
                  class="text-end numeric"
                  formControlName="salePrice"
                  inputmode="decimal"
                  step="0.01"
                  type="number"
                />
              </c-input-group>
              @if (message(priceForm.controls.salePrice); as text) {
                <div class="invalid-feedback d-block">{{ text }}</div>
              }
            </div>
          </div>
          <div class="d-flex flex-wrap align-items-center gap-2 mt-3">
            <div class="me-auto small" role="status">
              @if (offer()?.effectivePrice?.salePrice; as effective) {
                Vigente agora: <strong class="numeric">{{ effective.amount | money: effective.currency || currency() }}</strong>
                @if (offer()?.effectivePrice?.promotionId) {
                  (promoção)
                }
              } @else {
                O SKU ainda não tem preço.
              }
            </div>
            @if (editOffer().allowed) {
              <button [disabled]="savingPrice()" cButton color="primary" type="submit">
                @if (savingPrice()) {
                  <c-spinner aria-hidden="true" class="me-2" size="sm" />
                }
                Salvar preço
              </button>
            }
          </div>
        </form>
      }
    </section>

    @if (!loading() && !loadProblem()) {
      <section [attr.aria-labelledby]="ids.promotions" class="mb-4">
        <div class="d-flex align-items-center mb-2">
          <h3 [id]="ids.promotions" class="h6 text-uppercase text-body-secondary mb-0 me-auto">Promoções</h3>
          @if (editOffer().allowed && !scheduling()) {
            <button (click)="startScheduling()" cButton color="primary" size="sm" type="button" variant="outline">
              + Agendar
            </button>
          }
        </div>
        @if (promotionConflict()) {
          <app-edit-conflict-alert (reload)="reloadOffer()" [busy]="reloading()" />
        } @else if (promotionProblem(); as problem) {
          <app-problem-alert [problem]="problem" [heading]="promotionHeading()" />
        }
        @if (scheduling()) {
          <form (ngSubmit)="schedule()" [formGroup]="promotionForm" class="border rounded p-3 mb-3" novalidate>
            <div class="row g-3">
              <div class="col-sm-6">
                <label [for]="ids.startsAt" cLabel>Início</label>
                <input [id]="ids.startsAt" cFormControl formControlName="startsAt" type="datetime-local" />
                @if (message(promotionForm.controls.startsAt); as text) {
                  <div class="invalid-feedback d-block">{{ text }}</div>
                }
              </div>
              <div class="col-sm-6">
                <label [for]="ids.endsAt" cLabel>Fim</label>
                <input [id]="ids.endsAt" cFormControl formControlName="endsAt" type="datetime-local" />
                @if (message(promotionForm.controls.endsAt); as text) {
                  <div class="invalid-feedback d-block">{{ text }}</div>
                }
              </div>
              <div class="col-sm-6">
                <label [for]="ids.promotionPrice" cLabel>Preço</label>
                <c-input-group>
                  <span cInputGroupText>{{ currency() }}</span>
                  <input
                    [id]="ids.promotionPrice"
                    cFormControl
                    class="text-end numeric"
                    formControlName="salePrice"
                    inputmode="decimal"
                    step="0.01"
                    type="number"
                  />
                </c-input-group>
                @if (message(promotionForm.controls.salePrice); as text) {
                  <div class="invalid-feedback d-block">{{ text }}</div>
                }
              </div>
            </div>
            <div class="form-text">Horário de São Paulo.</div>
            <div class="d-flex justify-content-end gap-2 mt-3">
              <button (click)="stopScheduling()" [disabled]="savingPromotion()" cButton color="secondary" type="button">
                Cancelar
              </button>
              <button [disabled]="savingPromotion()" cButton color="primary" type="submit">
                @if (savingPromotion()) {
                  <c-spinner aria-hidden="true" class="me-2" size="sm" />
                }
                Agendar
              </button>
            </div>
          </form>
        }
        @if (promotions().length) {
          <ul cListGroup>
            @for (item of promotions(); track item.promotion.id) {
              <li cListGroupItem class="d-flex flex-wrap align-items-center gap-2">
                <div class="me-auto">
                  <div class="small">
                    {{ item.promotion.startsAt | dateTime }} a {{ item.promotion.endsAt | dateTime }}
                  </div>
                  <strong class="numeric">
                    {{ item.promotion.salePrice?.amount | money: item.promotion.salePrice?.currency || currency() }}
                  </strong>
                  <c-badge [color]="item.phase === 'ACTIVE' ? 'success' : 'info'" class="ms-2">
                    {{ phaseLabel(item.phase) }}
                  </c-badge>
                </div>
                @if (editOffer().allowed) {
                  <button
                    #cancelButton
                    (click)="askCancel(item.promotion, cancelButton)"
                    cButton
                    color="danger"
                    size="sm"
                    type="button"
                    variant="outline"
                  >
                    Cancelar
                  </button>
                }
              </li>
            }
          </ul>
        } @else if (!scheduling()) {
          <p class="small text-body-secondary mb-0">Nenhuma promoção vigente ou agendada.</p>
        }
      </section>
    }

    <app-confirm-modal
      (confirm)="cancel()"
      [(visible)]="cancelOpen"
      [busy]="cancelling()"
      [returnFocus]="cancelOpener()"
      cancelLabel="Voltar"
      confirmColor="danger"
      confirmLabel="Cancelar promoção"
      title="Cancelar promoção"
    >
      @if (cancelTarget(); as promotion) {
        <p class="fw-semibold mb-2">
          {{ promotion.startsAt | dateTime }} a {{ promotion.endsAt | dateTime }},
          {{ promotion.salePrice?.amount | money: promotion.salePrice?.currency || currency() }}
        </p>
      }
      <p class="mb-0">A promoção deixa de valer e sai da lista.</p>
    </app-confirm-modal>
  `
})
export class SkuOfferSectionComponent {
  readonly skuId = input.required<string>();
  /** Moeda do produto, a mesma de todas as ofertas dele. */
  readonly currency = input.required<string>();
  readonly editOffer = input<ActionState>({ allowed: false });
  /** Põe o foco no preço "por" assim que ele aparece, como quando o checklist aponta o preço do SKU. */
  readonly focusPrice = input(false);

  /** A oferta mudou: a tabela de SKUs relê o produto. */
  readonly changed = output<void>();

  readonly #toast = inject(ToastService);
  readonly #document = inject(DOCUMENT);
  readonly #prefix = `app-sku-offer-${nextId++}`;
  readonly ids = {
    price: `${this.#prefix}-price`,
    listPrice: `${this.#prefix}-list-price`,
    salePrice: SALE_PRICE_FIELD,
    promotions: `${this.#prefix}-promotions`,
    startsAt: `${this.#prefix}-starts-at`,
    endsAt: `${this.#prefix}-ends-at`,
    promotionPrice: `${this.#prefix}-promotion-price`
  };

  readonly #amountRules = fieldRules(MoneyDtoSchema, 'amount');

  readonly offer = signal<OfferResponse | null>(null);
  readonly #version = signal<string | undefined>(undefined);
  readonly loading = signal(true);
  readonly loadProblem = signal<Problem | null>(null);
  readonly reloading = signal(false);
  readonly promotions = computed(() => listedPromotions(this.offer()));

  readonly priceForm = new FormGroup({
    listPrice: new FormControl<number | null>(null, [schemaValidator({ ...this.#amountRules, required: false })]),
    salePrice: new FormControl<number | null>(null, [schemaValidator(this.#amountRules)])
  });
  readonly savingPrice = signal(false);
  readonly priceProblem = signal<Problem | null>(null);
  readonly priceConflict = computed(() => isEditConflict(this.priceProblem()));

  readonly promotionForm = new FormGroup({
    startsAt: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(PromotionRequestSchema, 'startsAt'))]
    }),
    endsAt: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(PromotionRequestSchema, 'endsAt'))]
    }),
    salePrice: new FormControl<number | null>(null, [schemaValidator(this.#amountRules)])
  });
  readonly scheduling = signal(false);
  readonly savingPromotion = signal(false);
  readonly promotionProblem = signal<Problem | null>(null);
  readonly #promotionAction = signal<'schedule' | 'cancel'>('schedule');
  readonly promotionHeading = computed(() =>
    this.#promotionAction() === 'schedule' ? 'Não foi possível agendar a promoção' : 'Não foi possível cancelar a promoção'
  );
  readonly promotionConflict = computed(() => isEditConflict(this.promotionProblem()));

  readonly cancelTarget = signal<PromotionResponse | null>(null);
  readonly cancelOpen = signal(false);
  readonly cancelOpener = signal<HTMLElement | null>(null);
  readonly cancelling = signal(false);

  readonly phaseLabel = promotionPhaseLabel;

  #request = 0;
  #focused = false;

  constructor() {
    effect(() => {
      const id = this.skuId();
      untracked(() => void this.load(id));
    });
    effect(() => {
      const editable = this.editOffer().allowed;
      untracked(() => (editable ? this.priceForm.enable() : this.priceForm.disable()));
    });
    afterRenderEffect(() => {
      if (this.focusPrice() && !this.loading() && !this.#focused) {
        this.#focused = true;
        this.#document.getElementById(SALE_PRICE_FIELD)?.focus();
      }
    });
  }

  invalid(control: FormControl<unknown>): boolean {
    return control.touched && control.invalid;
  }

  message(control: FormControl<unknown>): string | null {
    return control.touched ? controlMessage(control) : null;
  }

  /**
   * Lê a oferta; sem oferta (404), o formulário fica vazio e a primeira gravação a cria. Com `keepPrice`, como depois de
   * agendar uma promoção, o preço digitado e ainda não salvo continua no formulário.
   */
  async load(skuId: string, keepPrice = false): Promise<void> {
    const request = ++this.#request;
    this.loading.set(true);
    this.loadProblem.set(null);
    try {
      const result = await apiDataWithVersion(getOffer({ path: { skuId } }));
      if (request === this.#request) {
        this.#accept(result, keepPrice);
      }
    } catch (error) {
      const problem = toProblem(error);
      if (request !== this.#request) {
        return;
      }
      if (problem.status === 404) {
        this.#accept(null, keepPrice);
      } else {
        this.loadProblem.set(problem);
      }
    } finally {
      if (request === this.#request) {
        this.loading.set(false);
      }
    }
  }

  /** Depois do conflito de edição: a versão atual da oferta substitui o que foi digitado no preço. */
  async reloadOffer(): Promise<void> {
    this.reloading.set(true);
    try {
      await this.load(this.skuId());
      this.priceProblem.set(null);
      this.promotionProblem.set(null);
    } finally {
      this.reloading.set(false);
    }
  }

  async savePrice(): Promise<void> {
    if (!this.editOffer().allowed || this.savingPrice()) {
      return;
    }
    if (this.priceForm.invalid) {
      this.priceForm.markAllAsTouched();
      return;
    }
    const value = this.priceForm.getRawValue();
    const salePrice = moneyOf(value.salePrice, this.currency());
    if (!salePrice) {
      return;
    }
    const creating = this.offer() === null;
    this.savingPrice.set(true);
    this.priceProblem.set(null);
    try {
      const call = defineOffer({
        path: { skuId: this.skuId() },
        ...(creating ? {} : { headers: ifMatch(this.#version()) }),
        body: { salePrice, listPrice: moneyOf(value.listPrice, this.currency()) }
      });
      if (creating) {
        await apiData(call);
        await this.load(this.skuId());
      } else {
        this.#acceptWrite((await apiDataWithVersion(call)) as Versioned<OfferResponse>, false);
      }
      this.#toast.success('Preço salvo.');
      this.changed.emit();
    } catch (error) {
      const problem = toProblem(error);
      const unmatched =
        problem.status === 400
          ? applyServerErrors(this.priceForm, problem, {
              fields: { 'salePrice.amount': 'salePrice', 'listPrice.amount': 'listPrice' }
            })
          : (problem.errors ?? []);
      this.priceProblem.set({ ...problem, errors: unmatched });
    } finally {
      this.savingPrice.set(false);
    }
  }

  startScheduling(): void {
    this.promotionForm.reset();
    this.promotionProblem.set(null);
    this.scheduling.set(true);
    setTimeout(() => this.#document.getElementById(this.ids.startsAt)?.focus());
  }

  stopScheduling(): void {
    this.scheduling.set(false);
    this.promotionProblem.set(null);
  }

  async schedule(): Promise<void> {
    if (!this.editOffer().allowed || this.savingPromotion()) {
      return;
    }
    if (this.promotionForm.invalid) {
      this.promotionForm.markAllAsTouched();
      return;
    }
    const value = this.promotionForm.getRawValue();
    const salePrice = moneyOf(value.salePrice, this.currency());
    const startsAt = fromDateTimeLocal(value.startsAt);
    const endsAt = fromDateTimeLocal(value.endsAt);
    if (!salePrice || !startsAt || !endsAt) {
      this.promotionForm.markAllAsTouched();
      return;
    }
    this.#promotionAction.set('schedule');
    this.savingPromotion.set(true);
    this.promotionProblem.set(null);
    try {
      await apiData(
        schedulePromotion({
          path: { skuId: this.skuId() },
          headers: ifMatch(this.#version()),
          body: { salePrice, startsAt, endsAt }
        })
      );
      await this.load(this.skuId(), true);
      this.scheduling.set(false);
      this.#toast.success('Promoção agendada.');
      this.changed.emit();
    } catch (error) {
      const problem = toProblem(error);
      const unmatched =
        problem.status === 400
          ? applyServerErrors(this.promotionForm, problem, { fields: { 'salePrice.amount': 'salePrice' } })
          : (problem.errors ?? []);
      this.promotionProblem.set({ ...problem, errors: unmatched });
    } finally {
      this.savingPromotion.set(false);
    }
  }

  askCancel(promotion: PromotionResponse, opener: HTMLElement): void {
    this.cancelTarget.set(promotion);
    this.cancelOpener.set(opener);
    this.cancelOpen.set(true);
  }

  async cancel(): Promise<void> {
    const promotion = this.cancelTarget();
    if (!promotion?.id || this.cancelling()) {
      return;
    }
    this.#promotionAction.set('cancel');
    this.cancelling.set(true);
    this.promotionProblem.set(null);
    try {
      const result = await apiDataWithVersion(
        cancelPromotion({
          path: { skuId: this.skuId(), promotionId: promotion.id },
          headers: ifMatch(this.#version())
        })
      );
      this.#acceptWrite(result, true);
      this.#toast.success('Promoção cancelada.');
      this.changed.emit();
    } catch (error) {
      this.promotionProblem.set(toProblem(error));
    } finally {
      this.cancelling.set(false);
      this.cancelOpen.set(false);
    }
  }

  /** Resultado de uma gravação: a leitura em andamento começou antes dela e é descartada. */
  #acceptWrite(result: Versioned<OfferResponse>, keepPrice: boolean): void {
    this.#request++;
    this.loading.set(false);
    this.#accept(result, keepPrice);
  }

  /** Guarda a oferta e a versão; o preço digitado e não salvo só continua com `keepPrice`. */
  #accept(result: Versioned<OfferResponse> | null, keepPrice = false): void {
    this.offer.set(result?.data ?? null);
    this.#version.set(result ? (result.version ?? versionTag(result.data.version)) : undefined);
    if (keepPrice && this.priceForm.dirty) {
      return;
    }
    this.priceForm.reset({
      listPrice: result?.data.price?.listPrice?.amount ?? null,
      salePrice: result?.data.price?.salePrice?.amount ?? null
    });
  }
}
