import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  effect,
  inject,
  signal,
  untracked
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  AlertComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  FormTextDirective,
  SpinnerComponent,
  TooltipDirective
} from '@coreui/angular';
import {
  getCategoryResource,
  type ProductResponse,
  reviseProduct,
  ReviseProductRequestSchema
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  ConfirmModalComponent,
  controlMessage,
  EditConflictAlertComponent,
  ErrorStateComponent,
  type LeaveGuarded,
  leaveWarning,
  LoadingStateComponent,
  ProblemAlertComponent,
  schemaValidator,
  ToastService,
  warnBeforeUnload
} from '@marketplace/shared-admin-ui';
import {
  apiDataWithVersion,
  fieldRules,
  isEditConflict,
  type Problem,
  toProblem
} from '@marketplace/shared-web-util';
import { map } from 'rxjs';

import { SAVE_RETURNS_TO_MODERATION } from '../product-actions';
import { type CategoryChangeChoice, CategoryChangeModalComponent } from './category-change-modal.component';
import {
  type AttributeField,
  attributeFields,
  attributeMap,
  type AttributeValue,
  knownAttributes,
  sameAttributes,
  toControlValue
} from './product-attributes';
import { categoryPath } from './product-categories';
import { ifMatch, ProductStore } from './product-store';

/** Troca de categoria feita no formulário e ainda não salva, com o que é preciso para desfazer. */
interface CategorySwap {
  readonly previousId: string;
  readonly previousPath: string;
  readonly previousAttributes: Readonly<Record<string, string>>;
  readonly path: string;
}

type AttributeControls = Record<string, FormControl<AttributeValue>>;

/** Id do elemento de um campo do conteúdo, para o checklist pôr o foco nele (`categoryId`, `attributes.<código>`). */
export function contentFieldId(field: string): string {
  return `product-field-${field}`;
}

/**
 * Aba Conteúdo do produto: título, descrição, categoria (com o modal Trocar) e os atributos do produto, montados pelo
 * tipo de cada atributo efetivo da categoria. Grava com `PUT /products/{id}` e `If-Match`, confirma antes quando o
 * servidor avisa que salvar devolve o produto à moderação (`saveReturnsToModeration`) e fica só leitura quando `EDIT`
 * não vem em `actions`. O rascunho não salvo vai ao checklist de envio, conferido pelo servidor.
 */
@Component({
  selector: 'app-product-content-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    CategoryChangeModalComponent,
    ConfirmModalComponent,
    EditConflictAlertComponent,
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormSelectDirective,
    FormTextDirective,
    LoadingStateComponent,
    ProblemAlertComponent,
    ReactiveFormsModule,
    SpinnerComponent,
    TooltipDirective
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <h2 class="h4 card-title mb-0" id="product-content-heading" tabindex="-1">Conteúdo</h2>
        <div class="small text-body-secondary mb-3">Título, descrição, categoria e atributos do produto.</div>

        @if (!editable()) {
          <c-alert color="info" role="status">
            Conteúdo só leitura.
            @if (store.rules().save.blockedReason; as reason) {
              {{ reason }}
            }
          </c-alert>
        }
        @if (conflict()) {
          <app-edit-conflict-alert (reload)="loadCurrentVersion()" [busy]="reloading()" />
        } @else if (problem(); as problem) {
          <app-problem-alert [problem]="problem" heading="Não foi possível salvar" />
        }

        <form (ngSubmit)="save()" [formGroup]="form" novalidate>
          <div class="mb-3">
            <label [for]="ids.title" cLabel>Título</label>
            <input
              [id]="ids.title"
              [valid]="invalid(form.controls.title) ? false : undefined"
              cFormControl
              formControlName="title"
              type="text"
            />
            @if (form.controls.title.touched && message(form.controls.title); as text) {
              <div class="invalid-feedback d-block">{{ text }}</div>
            }
          </div>

          <div class="mb-3">
            <span class="form-label d-block" id="product-category-label">Categoria</span>
            <div class="d-flex flex-wrap align-items-center gap-2">
              <span aria-labelledby="product-category-label" class="me-auto">
                {{ shownCategoryPath() || 'Carregando a categoria…' }}
              </span>
              @if (editable() && store.rules().changeCategory.allowed) {
                <button
                  #changeButton
                  (click)="changeOpen.set(true)"
                  [id]="ids.category"
                  cButton
                  color="secondary"
                  size="sm"
                  type="button"
                  variant="outline"
                >
                  Trocar
                </button>
              } @else if (editable() && store.rules().changeCategory.blockedReason; as reason) {
                <span [cTooltip]="reason" class="d-inline-block" tabindex="0">
                  <button [id]="ids.category" cButton color="secondary" disabled size="sm" type="button" variant="outline">
                    Trocar<span class="visually-hidden">: {{ reason }}</span>
                  </button>
                </span>
              }
            </div>
            @if (swap(); as swap) {
              <c-alert class="mt-2 mb-0" color="info" role="status">
                Categoria trocada de <strong>{{ swap.previousPath }}</strong> para <strong>{{ swap.path }}</strong>. A
                troca só vale quando você salvar.
                <button (click)="undoCategoryChange()" cButton class="p-0 align-baseline" color="link" type="button">
                  Desfazer
                </button>
              </c-alert>
            }
          </div>

          <div class="mb-3">
            <label [for]="ids.description" cLabel>Descrição</label>
            <textarea
              [id]="ids.description"
              [valid]="invalid(form.controls.description) ? false : undefined"
              cFormControl
              formControlName="description"
              rows="5"
            ></textarea>
            @if (form.controls.description.touched && message(form.controls.description); as text) {
              <div class="invalid-feedback d-block">{{ text }}</div>
            }
          </div>

          <h3 class="h6 mt-4 mb-3">Atributos do produto</h3>
          @if (fields(); as fields) {
            <div formGroupName="attributes">
              @for (field of fields; track field.code) {
                <div class="mb-3">
                  @if (field.type === 'BOOLEAN') {
                    <c-form-check [switch]="true">
                      <input [formControlName]="field.code" [id]="attributeId(field.code)" cFormCheckInput type="checkbox" />
                      <label [for]="attributeId(field.code)" cFormCheckLabel>
                        {{ field.label }}
                        @if (field.required) {
                          <span aria-hidden="true" class="text-danger">*</span>
                          <span class="visually-hidden">(obrigatório para enviar)</span>
                        }
                      </label>
                    </c-form-check>
                  } @else {
                    <label [for]="attributeId(field.code)" cLabel>
                      {{ field.label }}
                      @if (field.required) {
                        <span aria-hidden="true" class="text-danger">*</span>
                        <span class="visually-hidden">(obrigatório para enviar)</span>
                      }
                    </label>
                    @switch (field.type) {
                      @case ('OPTION') {
                        <select [formControlName]="field.code" [id]="attributeId(field.code)" cSelect>
                          <option value="">Escolha</option>
                          @for (option of field.options; track option) {
                            <option [value]="option">{{ option }}</option>
                          }
                        </select>
                      }
                      @case ('INTEGER') {
                        <input
                          [formControlName]="field.code"
                          [id]="attributeId(field.code)"
                          cFormControl
                          inputmode="numeric"
                          step="1"
                          type="number"
                        />
                      }
                      @case ('DECIMAL') {
                        <input
                          [formControlName]="field.code"
                          [id]="attributeId(field.code)"
                          cFormControl
                          inputmode="decimal"
                          step="any"
                          type="number"
                        />
                      }
                      @default {
                        <input [formControlName]="field.code" [id]="attributeId(field.code)" cFormControl type="text" />
                      }
                    }
                  }
                  @if (attributeMessage(field); as text) {
                    <div class="invalid-feedback d-block">{{ text }}</div>
                  }
                </div>
              } @empty {
                <p class="text-body-secondary">A categoria não tem atributos do produto.</p>
              }
            </div>
            @if (hasRequired()) {
              <div cFormText class="mb-3">
                <span aria-hidden="true" class="text-danger">*</span> Obrigatório para enviar à moderação.
              </div>
            }
          } @else if (categoryProblem(); as problem) {
            <app-error-state (retry)="category.reload()" [problem]="problem" message="Não foi possível carregar os atributos da categoria." />
          } @else {
            <app-loading-state [rows]="3" label="Carregando os atributos da categoria…" />
          }

          @if (editable()) {
            <div class="d-flex justify-content-end mt-4">
              <button [disabled]="saving() || !changed() || !fields()" cButton color="primary" type="submit">
                @if (saving()) {
                  <c-spinner aria-hidden="true" class="me-2" size="sm" />
                }
                Salvar
              </button>
            </div>
          }
        </form>
      </c-card-body>
    </c-card>

    @if (store.product()?.id; as productId) {
      <app-category-change-modal
        (changed)="applyCategoryChange($event)"
        [(visible)]="changeOpen"
        [attributes]="currentAttributes()"
        [canRemoveSkus]="store.rules().removeSku.allowed"
        [currentCategoryId]="categoryId()"
        [currentPath]="shownCategoryPath()"
        [productId]="productId"
      />
    }

    <app-confirm-modal
      (confirm)="write()"
      [(visible)]="confirmOpen"
      [busy]="saving()"
      [confirmColor]="returnsToModeration.color"
      [confirmLabel]="returnsToModeration.confirmLabel"
      [title]="returnsToModeration.title"
    >
      <p class="fw-semibold mb-2">{{ form.controls.title.value }}</p>
      <p class="mb-0">{{ returnsToModeration.message }}</p>
    </app-confirm-modal>
  `
})
export class ProductContentTabComponent implements LeaveGuarded {
  readonly store = inject(ProductStore);
  readonly #toast = inject(ToastService);
  readonly #document = inject(DOCUMENT);

  readonly ids = {
    title: contentFieldId('title'),
    description: contentFieldId('description'),
    category: contentFieldId('categoryId')
  };
  readonly returnsToModeration = SAVE_RETURNS_TO_MODERATION;

  readonly form = new FormGroup({
    title: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(ReviseProductRequestSchema, 'title'))]
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(ReviseProductRequestSchema, 'description'))]
    }),
    categoryId: new FormControl('', { nonNullable: true }),
    attributes: new FormGroup<AttributeControls>({})
  });

  readonly #value = toSignal(this.form.valueChanges.pipe(map(() => this.form.getRawValue())), {
    initialValue: this.form.getRawValue()
  });
  readonly categoryId = computed(() => this.#value().categoryId);
  readonly category = getCategoryResource(() => {
    const id = this.categoryId();
    return id ? { path: { id } } : undefined;
  });
  readonly #currentCategory = computed(() => {
    const category = this.category.hasValue() ? this.category.value() : undefined;
    return category && category.id === this.categoryId() ? category : undefined;
  });
  readonly categoryProblem = computed(() => {
    const error = this.category.error();
    return error ? toProblem(error) : null;
  });
  /** Campos dos atributos do produto da categoria do formulário; `null` enquanto ela carrega. */
  readonly fields = computed<AttributeField[] | null>(() => {
    const category = this.#currentCategory();
    return category ? attributeFields(category.effectiveAttributes, false) : null;
  });
  readonly hasRequired = computed(() => !!this.fields()?.some(field => field.required));
  readonly attributes = computed(() => attributeMap(this.fields() ?? [], this.#value().attributes));
  /** Valores gravados de onde os campos de atributo são montados quando a categoria do formulário muda. */
  readonly #attributeSource = signal<Readonly<Record<string, string>>>({});
  /**
   * Atributos que o formulário representa: os campos montados ou, enquanto a categoria carrega ou falha, os valores de
   * onde eles serão montados.
   */
  readonly currentAttributes = computed(() => (this.fields() ? this.attributes() : this.#attributeSource()));
  readonly swap = signal<CategorySwap | null>(null);
  readonly shownCategoryPath = computed(() => categoryPath(this.#currentCategory()) || this.swap()?.path || '');

  readonly editable = computed(() => this.store.rules().save.allowed);
  /** Versão do produto que o formulário mostra; as mudanças são medidas contra ela. */
  readonly #baseline = signal<ProductResponse | undefined>(undefined);
  readonly attributesChanged = computed(() => {
    const baseline = this.#baseline();
    if (!baseline) {
      return false;
    }
    if (this.categoryId() !== (baseline.categoryId ?? '')) {
      return true;
    }
    const fields = this.fields();
    return !!fields && !sameAttributes(this.attributes(), knownAttributes(fields, baseline.attributes));
  });
  readonly changed = computed(() => {
    const baseline = this.#baseline();
    const value = this.#value();
    if (!baseline) {
      return false;
    }
    return (
      value.title !== (baseline.title ?? '') ||
      value.description !== (baseline.description ?? '') ||
      this.attributesChanged()
    );
  });
  readonly leaveWarning = computed(() => leaveWarning({ unsaved: this.editable() && this.changed() }));

  readonly saving = signal(false);
  readonly reloading = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly conflict = computed(() => isEditConflict(this.problem()));
  readonly changeOpen = signal(false);
  readonly confirmOpen = signal(false);

  #builtFor: string | null = null;
  #seenDiscards = -1;

  constructor() {
    warnBeforeUnload(this.leaveWarning);
    inject(DestroyRef).onDestroy(() => {
      this.store.draft.set(undefined);
    });

    effect(() => {
      const product = this.store.product();
      const discards = this.store.discards();
      untracked(() => {
        if (!product || product === this.#baseline()) {
          return;
        }
        const discard = discards !== this.#seenDiscards;
        this.#seenDiscards = discards;
        if (discard || this.#baseline()?.id !== product.id || !this.changed()) {
          this.#reset(product);
        }
      });
    });

    effect(() => {
      const fields = this.fields();
      const categoryId = this.categoryId();
      untracked(() => {
        if (fields && this.#builtFor !== categoryId) {
          this.#buildAttributes(fields, categoryId);
        }
      });
    });

    effect(() => {
      const editable = this.editable();
      this.fields();
      untracked(() => {
        if (editable) {
          this.form.enable({ emitEvent: false });
        } else {
          this.form.disable({ emitEvent: false });
        }
      });
    });

    effect(() => {
      // NOTE: enquanto os atributos da categoria nova carregam, o checklist fica com o último rascunho conferido.
      if (this.fields()) {
        this.store.draft.set(
          this.editable() && this.attributesChanged()
            ? { categoryId: this.categoryId(), attributes: this.attributes() }
            : undefined
        );
      }
    });

    afterRenderEffect(() => {
      const item = this.store.focus();
      const fields = this.fields();
      if (item?.tab !== 'conteudo') {
        return;
      }
      const target = item.field ? this.#document.getElementById(contentFieldId(item.field)) : null;
      if (target || fields) {
        (target ?? this.#document.getElementById('product-content-heading'))?.focus();
        untracked(() => this.store.focus.set(null));
      }
    });
  }

  attributeId(code: string): string {
    return contentFieldId(`attributes.${code}`);
  }

  invalid(control: FormControl<unknown>): boolean {
    return control.touched && control.invalid;
  }

  message(control: FormControl<unknown>): string | null {
    return controlMessage(control);
  }

  attributeMessage(field: AttributeField): string | null {
    const control = this.form.controls.attributes.controls[field.code];
    return control?.touched ? controlMessage(control) : null;
  }

  /** Salvar: confere os limites e, se o servidor avisa que salvar devolve o produto à moderação, pede confirmação. */
  save(): void {
    if (!this.editable() || this.saving() || !this.changed() || !this.fields()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.store.rules().saveReturnsToModeration) {
      this.confirmOpen.set(true);
      return;
    }
    void this.write();
  }

  async write(): Promise<void> {
    const product = this.store.product();
    // INVARIANT: o PUT substitui o mapa inteiro de atributos; sem os campos da categoria montados, ele os apagaria.
    if (!product?.id || this.saving() || !this.fields()) {
      return;
    }
    const returnsToModeration = this.store.rules().saveReturnsToModeration;
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.problem.set(null);
    try {
      const result = await apiDataWithVersion(
        reviseProduct({
          path: { id: product.id },
          headers: ifMatch(this.store.version()),
          body: {
            categoryId: value.categoryId,
            title: value.title,
            description: value.description,
            attributes: this.attributes()
          }
        })
      );
      this.store.accept(result);
      this.#reset(result.data);
      this.#toast.success(
        returnsToModeration ? 'Conteúdo salvo. O produto voltou para a moderação.' : 'Conteúdo salvo.'
      );
    } catch (error) {
      const problem = toProblem(error);
      const unmatched = problem.status === 400 ? applyServerErrors(this.form, problem) : (problem.errors ?? []);
      this.problem.set({ ...problem, errors: unmatched });
    } finally {
      this.saving.set(false);
      this.confirmOpen.set(false);
    }
  }

  /** Conflito de edição: lê a versão atual do produto, que substitui o que foi digitado. */
  async loadCurrentVersion(): Promise<void> {
    this.reloading.set(true);
    try {
      if (await this.store.reload(true)) {
        this.problem.set(null);
      }
    } finally {
      this.reloading.set(false);
    }
  }

  /** Aplica a troca escolhida no modal só no formulário: os atributos que somem saem, e a gravação fica para o Salvar. */
  applyCategoryChange(choice: CategoryChangeChoice): void {
    const removed = new Set((choice.change.removedAttributes ?? []).map(attribute => attribute.code));
    const current = this.currentAttributes();
    const carried = Object.fromEntries(Object.entries(current).filter(([code]) => !removed.has(code)));
    const previous = this.swap();
    this.swap.set({
      previousId: previous?.previousId ?? this.categoryId(),
      previousPath: previous?.previousPath ?? this.shownCategoryPath(),
      previousAttributes: previous?.previousAttributes ?? current,
      path: choice.path
    });
    this.#attributeSource.set(carried);
    this.form.controls.categoryId.setValue(choice.categoryId);
    this.form.markAsDirty();
  }

  undoCategoryChange(): void {
    const swap = this.swap();
    if (!swap) {
      return;
    }
    this.swap.set(null);
    this.#attributeSource.set(swap.previousAttributes);
    this.form.controls.categoryId.setValue(swap.previousId);
  }

  #reset(product: ProductResponse): void {
    this.#baseline.set(product);
    this.#attributeSource.set({ ...(product.attributes ?? {}) });
    this.#builtFor = null;
    this.swap.set(null);
    this.problem.set(null);
    this.form.reset({
      title: product.title ?? '',
      description: product.description ?? '',
      categoryId: product.categoryId ?? ''
    });
    const fields = this.fields();
    if (fields) {
      this.#buildAttributes(fields, this.categoryId());
    }
  }

  #buildAttributes(fields: readonly AttributeField[], categoryId: string): void {
    const controls: AttributeControls = {};
    const source = this.#attributeSource();
    for (const field of fields) {
      controls[field.code] = new FormControl<AttributeValue>(toControlValue(field, source[field.code]));
    }
    const group = new FormGroup<AttributeControls>(controls);
    if (!this.editable()) {
      group.disable({ emitEvent: false });
    }
    this.#builtFor = categoryId;
    this.form.setControl('attributes', group);
  }
}
