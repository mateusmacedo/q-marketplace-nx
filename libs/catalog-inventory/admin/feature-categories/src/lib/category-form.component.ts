import { ChangeDetectionStrategy, Component, computed, model, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  type AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import {
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ChipInputComponent,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  FormTextDirective,
  RowComponent,
} from '@coreui/angular';
import { cilPlus } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  type AttributeDto,
  AttributeDtoSchema,
  type AttributeType,
  type CategoryResponse,
  createCategory,
  CreateCategoryRequestSchema,
  getCategory,
  reviseCategory,
  ReviseCategoryRequestSchema,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  controlMessage,
  FormOffcanvasComponent,
  schemaValidator,
  SERVER_ERROR,
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  apiDataWithVersion,
  type FieldError,
  fieldRules,
  type Problem,
  problemCode,
  problemMessages,
  toProblem,
  type Versioned,
} from '@marketplace/shared-web-util';
import { map } from 'rxjs';

import {
  ATTRIBUTE_TYPE_LABELS,
  ATTRIBUTE_TYPES,
  categoryFieldLabel,
  type CategoryStep,
} from './category-model';

/** Pai da categoria nova, com o caminho para mostrar no formulário. */
export interface CategoryFormParent extends CategoryStep {
  readonly path: string;
}

/** O que o formulário abre: uma categoria nova (raiz ou subcategoria) ou a edição de uma existente. */
export type CategoryFormTarget =
  | { readonly mode: 'create'; readonly parent: CategoryFormParent | null }
  | { readonly mode: 'edit'; readonly category: Versioned<CategoryResponse> };

/** Gravação concluída. A criação devolve o id da categoria criada, lido do corpo do 201, quando ele vem. */
export type CategoryFormResult =
  | { readonly mode: 'create'; readonly parentId: string | null; readonly id: string | undefined }
  | { readonly mode: 'edit'; readonly category: Versioned<CategoryResponse> };

type AttributeForm = FormGroup<{
  code: FormControl<string>;
  label: FormControl<string>;
  type: FormControl<AttributeType>;
  required: FormControl<boolean>;
  variant: FormControl<boolean>;
  options: FormControl<string[]>;
}>;

const CREATE_RULES = {
  name: fieldRules(CreateCategoryRequestSchema, 'name'),
  code: fieldRules(CreateCategoryRequestSchema, 'code'),
  attributes: fieldRules(CreateCategoryRequestSchema, 'attributes'),
};

const REVISE_RULES = {
  name: fieldRules(ReviseCategoryRequestSchema, 'name'),
  attributes: fieldRules(ReviseCategoryRequestSchema, 'attributes'),
};

const ATTRIBUTE_RULES = {
  code: fieldRules(AttributeDtoSchema, 'code'),
  label: fieldRules(AttributeDtoSchema, 'label'),
  type: fieldRules(AttributeDtoSchema, 'type'),
  options: fieldRules(AttributeDtoSchema, 'options'),
};

const DUPLICATE_CODE = 'duplicate-category-code';

let nextId = 0;

/**
 * Offcanvas de **Editar**, **Nova subcategoria** e da categoria nova, com nome, código e a lista de atributos próprios
 * (código, rótulo, tipo, obrigatório, variante e opções). Os limites vêm do contrato; o 400 vai para os campos, o 422
 * para o alerta, e o 412 ou o 409 `concurrent-update` abrem o conflito com "Carregar versão atual".
 */
@Component({
  selector: 'app-category-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ChipInputComponent,
    ColComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormOffcanvasComponent,
    FormSelectDirective,
    FormTextDirective,
    IconDirective,
    ReactiveFormsModule,
    RowComponent,
  ],
  template: `
    <app-form-offcanvas
      (reload)="reload()"
      (save)="save()"
      [(visible)]="visible"
      [fieldLabels]="fieldLabels()"
      [problem]="alertProblem()"
      [problemHeading]="problemHeading()"
      [reloading]="reloading()"
      [returnFocus]="returnFocus()"
      [saveLabel]="saveLabel()"
      [saving]="saving()"
      [showFieldErrors]="unmatchedErrors().length > 0"
      [subtitle]="subtitle()"
      [title]="title()"
      width="min(40rem, 92vw)"
    >
      <div [formGroup]="form">
        <div class="mb-3">
          <label [for]="ids.name" cLabel>Nome</label>
          <input
            [attr.aria-describedby]="message(form.controls.name) ? ids.name + '-error' : null"
            [id]="ids.name"
            [valid]="message(form.controls.name) ? false : undefined"
            autocomplete="off"
            cFormControl
            formControlName="name"
          />
          @if (message(form.controls.name); as text) {
            <div [id]="ids.name + '-error'" class="invalid-feedback d-block">{{ text }}</div>
          }
        </div>
        <div class="mb-3">
          <label [for]="ids.code" cLabel>Código</label>
          <input
            [attr.aria-describedby]="ids.code + '-help'"
            [id]="ids.code"
            [valid]="message(form.controls.code) ? false : undefined"
            autocomplete="off"
            cFormControl
            formControlName="code"
          />
          @if (message(form.controls.code); as text) {
            <div class="invalid-feedback d-block">{{ text }}</div>
          }
          <div [id]="ids.code + '-help'" cFormText>
            {{ editing() ? 'O código não muda depois da criação.' : 'Depois de criada, a categoria não muda de código.' }}
          </div>
        </div>

        <h6 class="mt-4">Atributos próprios</h6>
        @if (message(form.controls.attributes); as text) {
          <div class="invalid-feedback d-block mb-2">{{ text }}</div>
        }
        <div formArrayName="attributes">
          @for (group of form.controls.attributes.controls; track group; let index = $index) {
            <c-card [formGroupName]="index" class="mb-3">
              <c-card-body>
                <div class="d-flex justify-content-between align-items-center mb-2">
                  <span class="fw-semibold">Atributo {{ index + 1 }}</span>
                  <button
                    (click)="removeAttribute(index)"
                    [attr.aria-label]="'Remover o atributo ' + (index + 1)"
                    cButton
                    class="p-0 text-danger"
                    color="link"
                    type="button"
                  >
                    Remover
                  </button>
                </div>
                <c-row class="g-3">
                  <c-col sm="6">
                    <label [for]="attributeId(index, 'label')" cLabel>Rótulo</label>
                    <input
                      [id]="attributeId(index, 'label')"
                      [valid]="message(group.controls.label) ? false : undefined"
                      autocomplete="off"
                      cFormControl
                      formControlName="label"
                    />
                    @if (message(group.controls.label); as text) {
                      <div class="invalid-feedback d-block">{{ text }}</div>
                    }
                  </c-col>
                  <c-col sm="6">
                    <label [for]="attributeId(index, 'code')" cLabel>Código</label>
                    <input
                      [id]="attributeId(index, 'code')"
                      [valid]="message(group.controls.code) ? false : undefined"
                      autocomplete="off"
                      cFormControl
                      formControlName="code"
                    />
                    @if (message(group.controls.code); as text) {
                      <div class="invalid-feedback d-block">{{ text }}</div>
                    }
                  </c-col>
                  <c-col sm="6">
                    <label [for]="attributeId(index, 'type')" cLabel>Tipo</label>
                    <select
                      [id]="attributeId(index, 'type')"
                      [valid]="message(group.controls.type) ? false : undefined"
                      cSelect
                      formControlName="type"
                    >
                      @for (type of types; track type) {
                        <option [value]="type">{{ typeLabels[type] }}</option>
                      }
                    </select>
                    @if (message(group.controls.type); as text) {
                      <div class="invalid-feedback d-block">{{ text }}</div>
                    }
                  </c-col>
                  <c-col sm="6">
                    <c-form-check [switch]="true" class="mt-sm-4">
                      <input [id]="attributeId(index, 'required')" cFormCheckInput formControlName="required" type="checkbox" />
                      <label [for]="attributeId(index, 'required')" cFormCheckLabel>Obrigatório</label>
                    </c-form-check>
                    <c-form-check [switch]="true">
                      <input [id]="attributeId(index, 'variant')" cFormCheckInput formControlName="variant" type="checkbox" />
                      <label [for]="attributeId(index, 'variant')" cFormCheckLabel>Variante do SKU</label>
                    </c-form-check>
                  </c-col>
                  @if (group.controls.type.value === 'OPTION') {
                    <c-col xs="12">
                      <label [for]="attributeId(index, 'options')" cLabel>Opções</label>
                      <c-chip-input
                        (touch)="group.controls.options.markAsTouched()"
                        (valueChange)="setOptions(group, $event)"
                        [aria-describedby]="attributeId(index, 'options') + '-help'"
                        [id]="attributeId(index, 'options')"
                        [invalid]="!!message(group.controls.options)"
                        [touched]="group.controls.options.touched"
                        [value]="group.controls.options.value"
                        ariaAddedAnnouncement="incluída"
                        ariaRemoveLabel="Remover"
                        ariaRemovedAnnouncement="removida"
                      />
                      @if (message(group.controls.options); as text) {
                        <div class="invalid-feedback d-block">{{ text }}</div>
                      }
                      <div [id]="attributeId(index, 'options') + '-help'" cFormText>
                        Tecle Enter ou vírgula depois de cada opção.
                      </div>
                    </c-col>
                  }
                </c-row>
              </c-card-body>
            </c-card>
          } @empty {
            <p class="text-body-secondary">Nenhum atributo próprio.</p>
          }
        </div>
        <button (click)="addAttribute()" cButton class="px-0" color="link" type="button">
          <svg [cIcon]="plusIcon" aria-hidden="true" class="me-1"></svg>
          Adicionar atributo
        </button>
      </div>
    </app-form-offcanvas>
  `,
})
export class CategoryFormComponent {
  readonly visible = model(false);

  /** Gravação concluída; a tela fecha o resto (lista, detalhe e toast). */
  readonly saved = output<CategoryFormResult>();
  /** Versão atual lida depois do conflito de edição, para a tela atualizar o detalhe. */
  readonly reloaded = output<Versioned<CategoryResponse>>();

  readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    code: new FormControl('', { nonNullable: true }),
    attributes: new FormArray<AttributeForm>([]),
  });

  readonly types = ATTRIBUTE_TYPES;
  readonly typeLabels = ATTRIBUTE_TYPE_LABELS;
  readonly plusIcon = cilPlus;
  readonly #idBase = `app-category-form-${nextId++}`;
  readonly ids = { name: `${this.#idBase}-name`, code: `${this.#idBase}-code` };

  readonly #target = signal<CategoryFormTarget | null>(null);
  readonly #returnFocus = signal<HTMLElement | null>(null);
  readonly saving = signal(false);
  readonly reloading = signal(false);
  readonly #problem = signal<Problem | null>(null);
  readonly unmatchedErrors = signal<FieldError[]>([]);

  readonly #dirty = toSignal(this.form.events.pipe(map(() => this.form.dirty)), {
    initialValue: false,
  });
  /** O formulário aberto tem alterações não salvas, para o aviso de saída da tela. */
  readonly dirty = computed(() => this.visible() && this.#dirty());

  readonly returnFocus = this.#returnFocus.asReadonly();
  readonly editing = computed(() => this.#target()?.mode === 'edit');
  readonly title = computed(() => {
    const target = this.#target();
    if (target?.mode === 'edit') {
      return `Editar ${target.category.data.name ?? ''}`;
    }
    return target?.parent ? 'Nova subcategoria' : 'Nova categoria raiz';
  });
  readonly subtitle = computed(() => {
    const target = this.#target();
    if (target?.mode === 'edit') {
      return target.category.data.code;
    }
    return target?.parent ? `Dentro de ${target.parent.path}` : undefined;
  });
  readonly saveLabel = computed(() => (this.editing() ? 'Salvar' : 'Criar categoria'));
  readonly problemHeading = computed(() =>
    this.editing() ? 'Não foi possível salvar' : 'Não foi possível criar a categoria',
  );
  readonly alertProblem = computed(() => {
    const problem = this.#problem();
    const unmatched = this.unmatchedErrors();
    return problem && unmatched.length ? { ...problem, errors: unmatched } : problem;
  });
  readonly fieldLabels = computed(() =>
    Object.fromEntries(
      this.unmatchedErrors().flatMap(({ field }) =>
        field ? [[field, categoryFieldLabel(field)]] : [],
      ),
    ),
  );

  /** Abre o formulário com os valores do alvo; o que foi digitado antes é descartado. */
  open(target: CategoryFormTarget, returnFocus?: HTMLElement | null): void {
    this.#returnFocus.set(returnFocus ?? null);
    this.#load(target);
    this.visible.set(true);
  }

  addAttribute(): void {
    this.form.controls.attributes.push(attributeForm());
    this.form.controls.attributes.markAsDirty();
  }

  removeAttribute(index: number): void {
    this.form.controls.attributes.removeAt(index);
    this.form.controls.attributes.markAsDirty();
  }

  setOptions(group: AttributeForm, options: string[]): void {
    group.controls.options.setValue(options);
    group.controls.options.markAsDirty();
  }

  attributeId(index: number, field: string): string {
    return `${this.#idBase}-attribute-${index}-${field}`;
  }

  /** Mensagem embaixo do campo tocado: a do servidor, a do limite do contrato ou a do Angular. */
  message(control: AbstractControl): string | null {
    return control.touched ? controlMessage(control) : null;
  }

  async save(): Promise<void> {
    const target = this.#target();
    if (!target || this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.#setProblem(null);
    try {
      this.saved.emit(
        target.mode === 'create'
          ? await this.#create(target.parent)
          : await this.#revise(target.category),
      );
      this.form.markAsPristine();
      this.visible.set(false);
    } catch (error) {
      this.#fail(toProblem(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** "Carregar versão atual" do conflito: troca o que foi digitado pela versão do servidor. */
  async reload(): Promise<void> {
    const target = this.#target();
    if (target?.mode !== 'edit') {
      this.#setProblem(null);
      return;
    }
    this.reloading.set(true);
    try {
      const fresh = await apiDataWithVersion(
        getCategory({ path: { id: target.category.data.id ?? '' } }),
      );
      this.#load({ mode: 'edit', category: fresh });
      this.reloaded.emit(fresh);
    } catch (error) {
      this.#setProblem(toProblem(error));
    } finally {
      this.reloading.set(false);
    }
  }

  async #create(parent: CategoryFormParent | null): Promise<CategoryFormResult> {
    const value = this.form.getRawValue();
    const created = await apiData(
      createCategory({
        body: {
          ...(parent ? { parentId: parent.id } : {}),
          code: value.code.trim(),
          name: value.name.trim(),
          attributes: this.#attributes(),
        },
      }),
    );
    return { mode: 'create', parentId: parent?.id ?? null, id: createdId(created) };
  }

  async #revise(current: Versioned<CategoryResponse>): Promise<CategoryFormResult> {
    const category = await apiDataWithVersion(
      reviseCategory({
        path: { id: current.data.id ?? '' },
        body: {
          name: this.form.getRawValue().name.trim(),
          attributes: this.#attributes(),
          active: current.data.active !== false,
        },
        ...(current.version ? { headers: { 'If-Match': current.version } } : {}),
      }),
    );
    this.#target.set({ mode: 'edit', category });
    return { mode: 'edit', category };
  }

  #attributes(): AttributeDto[] {
    return this.form.controls.attributes.getRawValue().map((attribute) => ({
      code: attribute.code.trim(),
      label: attribute.label.trim(),
      type: attribute.type,
      required: attribute.required,
      variant: attribute.variant,
      ...(attribute.type === 'OPTION' ? { options: attribute.options } : {}),
    }));
  }

  #load(target: CategoryFormTarget): void {
    this.#target.set(target);
    this.#setProblem(null);
    const { name, code, attributes } = this.form.controls;
    const rules = target.mode === 'edit' ? REVISE_RULES : CREATE_RULES;
    name.setValidators(schemaValidator(rules.name));
    code.setValidators(target.mode === 'edit' ? null : schemaValidator(CREATE_RULES.code));
    attributes.setValidators(schemaValidator(rules.attributes));
    const category = target.mode === 'edit' ? target.category.data : null;
    const values = (category?.attributes ?? []).map(attributeValue);
    // NOTE: reaproveita os grupos que já existem, para a versão recarregada no conflito não recriar todos os cartões.
    while (attributes.length > values.length) {
      attributes.removeAt(attributes.length - 1, { emitEvent: false });
    }
    while (attributes.length < values.length) {
      attributes.push(attributeForm(), { emitEvent: false });
    }
    this.form.reset({ name: category?.name ?? '', code: category?.code ?? '', attributes: values });
    if (target.mode === 'edit') {
      code.disable();
    } else {
      code.enable();
    }
  }

  #fail(problem: Problem): void {
    if (problemCode(problem) === DUPLICATE_CODE && this.form.controls.code.enabled) {
      const code = this.form.controls.code;
      code.setErrors({ ...code.errors, [SERVER_ERROR]: problemMessages(problem).join(' ') });
      code.markAsTouched();
      this.#setProblem(null);
      return;
    }
    this.#problem.set(problem);
    this.unmatchedErrors.set(applyServerErrors(this.form, problem));
  }

  #setProblem(problem: Problem | null): void {
    this.#problem.set(problem);
    this.unmatchedErrors.set([]);
  }
}

function attributeValue(attribute: AttributeDto): Required<AttributeDto> {
  return {
    code: attribute.code ?? '',
    label: attribute.label ?? '',
    type: attribute.type ?? 'TEXT',
    required: attribute.required === true,
    variant: attribute.variant === true,
    options: [...(attribute.options ?? [])],
  };
}

function attributeForm(): AttributeForm {
  return new FormGroup({
    code: new FormControl('', {
      nonNullable: true,
      validators: schemaValidator(ATTRIBUTE_RULES.code),
    }),
    label: new FormControl('', {
      nonNullable: true,
      validators: schemaValidator(ATTRIBUTE_RULES.label),
    }),
    type: new FormControl<AttributeType>('TEXT', {
      nonNullable: true,
      validators: schemaValidator(ATTRIBUTE_RULES.type),
    }),
    required: new FormControl(false, { nonNullable: true }),
    variant: new FormControl(false, { nonNullable: true }),
    options: new FormControl<string[]>([], {
      nonNullable: true,
      validators: schemaValidator(ATTRIBUTE_RULES.options),
    }),
  });
}

// WHY: o contrato não declara o corpo do 201 de `POST /categories`, mas o servidor devolve a `CategoryResponse` criada.
function createdId(body: unknown): string | undefined {
  const id =
    typeof body === 'object' && body !== null ? (body as { readonly id?: unknown }).id : undefined;
  return typeof id === 'string' && id ? id : undefined;
}
