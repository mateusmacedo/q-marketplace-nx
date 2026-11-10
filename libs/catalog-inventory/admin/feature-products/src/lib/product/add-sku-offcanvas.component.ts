import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  FormTextDirective,
} from '@coreui/angular';
import {
  addSku,
  DimensionsDtoSchema,
  SkuRequestSchema,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  ConfirmModalComponent,
  controlMessage,
  FormOffcanvasComponent,
  schemaValidator,
  ToastService,
} from '@marketplace/shared-admin-ui';
import { apiData, fieldRules, type Problem, toProblem } from '@marketplace/shared-web-util';

import { SAVE_RETURNS_TO_MODERATION } from '../product-actions';
import {
  type AttributeField,
  attributeMap,
  type AttributeValue,
  toControlValue,
} from './product-attributes';
import { ifMatch, ProductStore } from './product-store';

type DimensionKey = 'heightCm' | 'widthCm' | 'lengthCm' | 'weightGrams';

const DIMENSIONS: readonly { key: DimensionKey; label: string; step: string }[] = [
  { key: 'heightCm', label: 'Altura (cm)', step: 'any' },
  { key: 'widthCm', label: 'Largura (cm)', step: 'any' },
  { key: 'lengthCm', label: 'Comprimento (cm)', step: 'any' },
  { key: 'weightGrams', label: 'Peso (g)', step: '1' },
];

/** Medidas do SKU físico no corpo da API: só quando as quatro foram preenchidas. */
function completeDimensions(
  dimensions: Readonly<Record<DimensionKey, number | null>> | null,
): Record<DimensionKey, number> | null {
  if (!dimensions) {
    return null;
  }
  const { heightCm, widthCm, lengthCm, weightGrams } = dimensions;
  return heightCm !== null && widthCm !== null && lengthCm !== null && weightGrams !== null
    ? { heightCm, widthCm, lengthCm, weightGrams }
    : null;
}

const FIELD_LABELS: Readonly<Record<string, string>> = {
  sellerSku: 'Código do SKU',
  physical: 'SKU físico',
  'dimensions.heightCm': 'Altura',
  'dimensions.widthCm': 'Largura',
  'dimensions.lengthCm': 'Comprimento',
  'dimensions.weightGrams': 'Peso',
};

let nextId = 0;

/**
 * Offcanvas "Adicionar SKU" da aba de SKUs: o código do seller, os atributos de variação da categoria (pelo tipo de
 * cada um), a opção de SKU físico e, se for físico, as dimensões e o peso. Grava com `POST /products/{id}/skus` e o
 * `If-Match` do produto, confirmando antes quando o servidor avisa que salvar devolve o produto à moderação.
 */
@Component({
  selector: 'app-add-sku-offcanvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ConfirmModalComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormOffcanvasComponent,
    FormSelectDirective,
    FormTextDirective,
    ReactiveFormsModule,
  ],
  template: `
    <app-form-offcanvas
      (reload)="reloadProduct()"
      (save)="save()"
      [(visible)]="visible"
      [fieldLabels]="fieldLabels"
      [problem]="problem()"
      [reloading]="reloading()"
      [saving]="saving()"
      [showFieldErrors]="true"
      problemHeading="Não foi possível adicionar o SKU"
      saveLabel="Adicionar"
      title="Adicionar SKU"
    >
      <div [formGroup]="form">
        <div class="mb-3">
          <label [for]="ids.sellerSku" cLabel>Código do SKU</label>
          <input
            [attr.aria-describedby]="ids.sellerSku + '-help'"
            [id]="ids.sellerSku"
            [valid]="invalid(form.controls.sellerSku) ? false : undefined"
            autocomplete="off"
            cFormControl
            formControlName="sellerSku"
            type="text"
          />
          <div [id]="ids.sellerSku + '-help'" cFormText>O código que a loja usa para o SKU, como na planilha.</div>
          @if (message(form.controls.sellerSku); as text) {
            <div class="invalid-feedback d-block">{{ text }}</div>
          }
        </div>

        @if (variantFields().length) {
          <fieldset class="mb-3" formGroupName="variantAttributes">
            <legend class="fs-6 fw-semibold">Variação</legend>
            @for (field of variantFields(); track field.code) {
              <div class="mb-3">
                @if (field.type === 'BOOLEAN') {
                  <c-form-check [switch]="true">
                    <input [formControlName]="field.code" [id]="variantId(field.code)" cFormCheckInput type="checkbox" />
                    <label [for]="variantId(field.code)" cFormCheckLabel>
                      {{ field.label }}
                      @if (field.required) {
                        <span aria-hidden="true" class="text-danger">*</span>
                        <span class="visually-hidden">(obrigatório para enviar)</span>
                      }
                    </label>
                  </c-form-check>
                } @else {
                  <label [for]="variantId(field.code)" cLabel>
                    {{ field.label }}
                    @if (field.required) {
                      <span aria-hidden="true" class="text-danger">*</span>
                      <span class="visually-hidden">(obrigatório para enviar)</span>
                    }
                  </label>
                  @switch (field.type) {
                    @case ('OPTION') {
                      <select [formControlName]="field.code" [id]="variantId(field.code)" cSelect>
                        <option value="">Escolha</option>
                        @for (option of field.options; track option) {
                          <option [value]="option">{{ option }}</option>
                        }
                      </select>
                    }
                    @case ('INTEGER') {
                      <input [formControlName]="field.code" [id]="variantId(field.code)" cFormControl step="1" type="number" />
                    }
                    @case ('DECIMAL') {
                      <input [formControlName]="field.code" [id]="variantId(field.code)" cFormControl step="any" type="number" />
                    }
                    @default {
                      <input [formControlName]="field.code" [id]="variantId(field.code)" cFormControl type="text" />
                    }
                  }
                }
                @if (variantMessage(field.code); as text) {
                  <div class="invalid-feedback d-block">{{ text }}</div>
                }
              </div>
            }
          </fieldset>
        }

        <c-form-check [switch]="true" class="mb-3">
          <input [id]="ids.physical" cFormCheckInput formControlName="physical" type="checkbox" />
          <label [for]="ids.physical" cFormCheckLabel>SKU físico</label>
        </c-form-check>

        @if (physical()) {
          <fieldset formGroupName="dimensions">
            <legend class="fs-6 fw-semibold">Dimensões e peso</legend>
            <div class="row g-3">
              @for (dimension of dimensions; track dimension.key) {
                <div class="col-sm-6">
                  <label [for]="dimensionId(dimension.key)" cLabel>{{ dimension.label }}</label>
                  <input
                    [formControlName]="dimension.key"
                    [id]="dimensionId(dimension.key)"
                    [step]="dimension.step"
                    cFormControl
                    class="text-end"
                    inputmode="decimal"
                    type="number"
                  />
                  @if (dimensionMessage(dimension.key); as text) {
                    <div class="invalid-feedback d-block">{{ text }}</div>
                  }
                </div>
              }
            </div>
          </fieldset>
        }
      </div>
    </app-form-offcanvas>

    <app-confirm-modal
      (confirm)="write()"
      [(visible)]="confirmOpen"
      [busy]="saving()"
      [confirmColor]="returnsToModeration.color"
      [confirmLabel]="returnsToModeration.confirmLabel"
      [title]="returnsToModeration.title"
    >
      <p class="fw-semibold mb-2">{{ form.controls.sellerSku.value }}</p>
      <p class="mb-0">{{ returnsToModeration.message }}</p>
    </app-confirm-modal>
  `,
})
export class AddSkuOffcanvasComponent {
  readonly visible = model(false);
  /** Atributos de variação da categoria do produto. */
  readonly fields = input<readonly AttributeField[]>([]);

  readonly added = output<void>();

  readonly store = inject(ProductStore);
  readonly #toast = inject(ToastService);

  readonly #prefix = `app-add-sku-${nextId++}`;
  readonly ids = { sellerSku: `${this.#prefix}-seller-sku`, physical: `${this.#prefix}-physical` };
  readonly dimensions = DIMENSIONS;
  readonly fieldLabels = FIELD_LABELS;
  readonly returnsToModeration = SAVE_RETURNS_TO_MODERATION;

  readonly form = new FormGroup({
    sellerSku: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(SkuRequestSchema, 'sellerSku'))],
    }),
    variantAttributes: new FormGroup<Record<string, FormControl<AttributeValue>>>({}),
    physical: new FormControl(false, { nonNullable: true }),
    dimensions: new FormGroup({
      heightCm: new FormControl<number | null>(null, [
        schemaValidator(fieldRules(DimensionsDtoSchema, 'heightCm')),
      ]),
      widthCm: new FormControl<number | null>(null, [
        schemaValidator(fieldRules(DimensionsDtoSchema, 'widthCm')),
      ]),
      lengthCm: new FormControl<number | null>(null, [
        schemaValidator(fieldRules(DimensionsDtoSchema, 'lengthCm')),
      ]),
      weightGrams: new FormControl<number | null>(null, [
        schemaValidator(fieldRules(DimensionsDtoSchema, 'weightGrams')),
      ]),
    }),
  });
  readonly physical = signal(false);

  readonly saving = signal(false);
  readonly reloading = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly confirmOpen = signal(false);
  /** Campos de variação com controle já criado no formulário; o template só mostra estes. */
  readonly variantFields = signal<readonly AttributeField[]>([]);

  constructor() {
    this.form.controls.dimensions.disable();
    this.form.controls.physical.valueChanges.pipe(takeUntilDestroyed()).subscribe((physical) => {
      this.physical.set(physical);
      if (physical) {
        this.form.controls.dimensions.enable();
      } else {
        this.form.controls.dimensions.disable();
      }
    });
    effect(() => {
      const fields = this.fields();
      untracked(() => this.#buildVariants(fields));
    });
    effect(() => {
      if (this.visible()) {
        untracked(() => this.#start());
      }
    });
  }

  variantId(code: string): string {
    return `${this.#prefix}-variant-${code}`;
  }

  dimensionId(key: DimensionKey): string {
    return `${this.#prefix}-${key}`;
  }

  invalid(control: FormControl<unknown>): boolean {
    return control.touched && control.invalid;
  }

  message(control: FormControl<unknown>): string | null {
    return control.touched ? controlMessage(control) : null;
  }

  variantMessage(code: string): string | null {
    const control = this.form.controls.variantAttributes.controls[code];
    return control?.touched ? controlMessage(control) : null;
  }

  dimensionMessage(key: DimensionKey): string | null {
    const control = this.form.controls.dimensions.controls[key];
    return control.touched ? controlMessage(control) : null;
  }

  save(): void {
    if (this.saving()) {
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
    const id = this.store.product()?.id;
    if (!id || this.saving()) {
      return;
    }
    const returnsToModeration = this.store.rules().saveReturnsToModeration;
    const value = this.form.getRawValue();
    const dimensions = value.physical ? value.dimensions : null;
    this.saving.set(true);
    this.problem.set(null);
    try {
      await apiData(
        addSku({
          path: { id },
          headers: ifMatch(this.store.version()),
          body: {
            sellerSku: value.sellerSku.trim(),
            variantAttributes: attributeMap(this.variantFields(), value.variantAttributes),
            physical: value.physical,
            dimensions: completeDimensions(dimensions),
          },
        }),
      );
      await this.store.reload();
      this.#toast.success(
        returnsToModeration
          ? 'SKU adicionado. O produto voltou para a moderação.'
          : 'SKU adicionado.',
      );
      this.visible.set(false);
      this.added.emit();
    } catch (error) {
      const problem = toProblem(error);
      const unmatched =
        problem.status === 400 ? applyServerErrors(this.form, problem) : (problem.errors ?? []);
      this.problem.set({ ...problem, errors: unmatched });
    } finally {
      this.saving.set(false);
      this.confirmOpen.set(false);
    }
  }

  /** Conflito de edição: lê a versão atual do produto; o que foi digitado continua nos campos. */
  async reloadProduct(): Promise<void> {
    this.reloading.set(true);
    try {
      if (await this.store.reload()) {
        this.problem.set(null);
      }
    } finally {
      this.reloading.set(false);
    }
  }

  #start(): void {
    this.#buildVariants(this.fields());
    this.form.reset({ sellerSku: '', physical: false });
    this.problem.set(null);
  }

  #buildVariants(fields: readonly AttributeField[]): void {
    const controls: Record<string, FormControl<AttributeValue>> = {};
    for (const field of fields) {
      controls[field.code] = new FormControl<AttributeValue>(toControlValue(field, undefined));
    }
    this.form.setControl('variantAttributes', new FormGroup(controls));
    this.variantFields.set(fields);
  }
}
