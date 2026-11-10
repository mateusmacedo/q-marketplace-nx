import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  ButtonCloseDirective,
  ButtonDirective,
  FormControlDirective,
  FormLabelDirective,
  FormTextDirective,
  ModalBodyComponent,
  ModalComponent,
  ModalFooterComponent,
  ModalHeaderComponent,
  ModalTitleDirective,
  SpinnerComponent,
} from '@coreui/angular';
import {
  type CategorySummary,
  createProduct,
  CreateProductRequestSchema,
  listCategoryTree,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  buildCategoryOptions,
  type CategoryNode,
  CategorySelectComponent,
} from '@marketplace/catalog-inventory-admin-ui';
import {
  applyServerErrors,
  controlMessage,
  ProblemAlertComponent,
  schemaValidator,
  ToastService,
} from '@marketplace/shared-admin-ui';
import { apiData, fieldRules, type Problem, toProblem } from '@marketplace/shared-web-util';

const FIELD_LABELS: Readonly<Record<string, string>> = {
  categoryId: 'Categoria',
  title: 'Título',
  currency: 'Moeda',
};

/**
 * Novo produto (proposta de design, tela 3): modal com categoria, título e moeda. A categoria sai de um `form-select`
 * com o caminho de cada categoria usável (`GET /categories/tree?usable=true`). Ao criar (`POST /products`), a tela
 * abre o produto na aba Conteúdo para completar os atributos.
 */
@Component({
  selector: 'app-new-product-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonCloseDirective,
    ButtonDirective,
    CategorySelectComponent,
    FormControlDirective,
    FormLabelDirective,
    FormTextDirective,
    ModalBodyComponent,
    ModalComponent,
    ModalFooterComponent,
    ModalHeaderComponent,
    ModalTitleDirective,
    ProblemAlertComponent,
    ReactiveFormsModule,
    SpinnerComponent,
  ],
  template: `
    <c-modal [(visible)]="visible" aria-labelledby="new-product-title" id="new-product-modal">
      <c-modal-header>
        <h5 cModalTitle id="new-product-title">Novo produto</h5>
        <button (click)="close()" [disabled]="saving()" aria-label="Fechar" cButtonClose></button>
      </c-modal-header>
      <form (ngSubmit)="save()" [formGroup]="form" novalidate>
        <c-modal-body>
          @if (categoriesProblem(); as problem) {
            <app-problem-alert [problem]="problem" heading="Não foi possível carregar as categorias" />
            <button (click)="loadCategories()" cButton class="mb-3" color="primary" size="sm" type="button" variant="outline">
              Tentar de novo
            </button>
          }
          @if (problem(); as problem) {
            <app-problem-alert [fieldLabels]="fieldLabels" [problem]="problem" heading="Não foi possível criar o produto" />
          }
          <div class="mb-3">
            <label cLabel for="new-product-category">Categoria</label>
            <app-category-select
              [describedBy]="categoryHelpId()"
              [invalid]="!!message('categoryId')"
              [options]="categoryOptions()"
              [placeholder]="categoriesLoading() ? 'Carregando categorias…' : 'Escolha a categoria'"
              formControlName="categoryId"
              selectId="new-product-category"
            />
            @if (message('categoryId'); as text) {
              <div class="invalid-feedback d-block" id="new-product-category-error">{{ text }}</div>
            } @else if (noCategories()) {
              <div cFormText id="new-product-category-help">
                Nenhuma categoria aceita produtos agora. Peça a quem administra o catálogo para criar ou ativar uma.
              </div>
            }
          </div>
          <div class="mb-3">
            <label cLabel for="new-product-title-input">Título</label>
            <input
              [attr.aria-describedby]="message('title') ? 'new-product-title-error' : null"
              [valid]="message('title') ? false : undefined"
              cFormControl
              formControlName="title"
              id="new-product-title-input"
              type="text"
            />
            @if (message('title'); as text) {
              <div class="invalid-feedback d-block" id="new-product-title-error">{{ text }}</div>
            }
          </div>
          <div>
            <label cLabel for="new-product-currency">Moeda</label>
            <input
              (input)="upperCurrency($event)"
              [attr.aria-describedby]="message('currency') ? 'new-product-currency-error' : 'new-product-currency-help'"
              [valid]="message('currency') ? false : undefined"
              autocomplete="off"
              cFormControl
              class="text-uppercase"
              formControlName="currency"
              id="new-product-currency"
              type="text"
            />
            @if (message('currency'); as text) {
              <div class="invalid-feedback d-block" id="new-product-currency-error">{{ text }}</div>
            }
            <div cFormText id="new-product-currency-help">
              Código de três letras (ISO 4217), como BRL. A moeda não muda depois da criação.
            </div>
          </div>
        </c-modal-body>
        <c-modal-footer>
          <button (click)="close()" [disabled]="saving()" cButton color="secondary" type="button">Cancelar</button>
          <button [disabled]="saving()" cButton color="primary" type="submit">
            @if (saving()) {
              <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />
            }
            Criar produto
          </button>
        </c-modal-footer>
      </form>
    </c-modal>
  `,
})
export class NewProductModalComponent {
  readonly #toast = inject(ToastService);

  readonly visible = model(false);
  /** Produto criado em rascunho; a tela abre a aba Conteúdo dele. */
  readonly created = output<ProductResponse>();

  readonly form = new FormGroup({
    categoryId: new FormControl<string | null>(null, {
      validators: [schemaValidator(fieldRules(CreateProductRequestSchema, 'categoryId'))],
    }),
    title: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(CreateProductRequestSchema, 'title'))],
    }),
    currency: new FormControl('', {
      nonNullable: true,
      validators: [schemaValidator(fieldRules(CreateProductRequestSchema, 'currency'))],
    }),
  });

  readonly fieldLabels = FIELD_LABELS;
  readonly saving = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly categories = signal<readonly CategorySummary[]>([]);
  readonly categoriesLoading = signal(false);
  readonly categoriesProblem = signal<Problem | null>(null);
  readonly categoryOptions = computed(() =>
    buildCategoryOptions(this.categories().flatMap(toCategoryNode)),
  );
  readonly noCategories = computed(
    () =>
      !this.categoriesLoading() && !this.categoriesProblem() && this.categoryOptions().length === 0,
  );
  readonly categoryHelpId = computed(() =>
    this.noCategories() ? 'new-product-category-help' : null,
  );

  /** Aumenta a cada mudança dos erros dos campos, para a tela redesenhar as mensagens. */
  readonly #validation = signal(0);

  constructor() {
    effect(() => {
      if (this.visible()) {
        untracked(() => this.#open());
      }
    });
    this.form.events.subscribe(() => this.#validation.update((count) => count + 1));
  }

  /** Mensagem do campo depois de tocado: a do servidor, a do limite do contrato ou a de obrigatório. */
  message(name: keyof typeof this.form.controls): string | null {
    this.#validation();
    const control = this.form.controls[name];
    return control.touched ? controlMessage(control) : null;
  }

  upperCurrency(event: Event): void {
    const field = event.target as HTMLInputElement;
    const upper = field.value.toUpperCase();
    if (upper !== field.value) {
      this.form.controls.currency.setValue(upper);
    }
  }

  close(): void {
    if (!this.saving()) {
      this.visible.set(false);
    }
  }

  async loadCategories(): Promise<void> {
    const control = this.form.controls.categoryId;
    this.categoriesLoading.set(true);
    this.categoriesProblem.set(null);
    control.disable();
    try {
      const nodes: CategorySummary[] = [];
      let cursor: string | undefined;
      do {
        const page = await apiData(
          listCategoryTree({ query: { usable: true, ...(cursor ? { cursor } : {}) } }),
        );
        nodes.push(...(page.items ?? []));
        cursor = page.nextCursor ?? undefined;
      } while (cursor);
      this.categories.set(nodes);
    } catch (error) {
      this.categoriesProblem.set(toProblem(error));
    } finally {
      this.categoriesLoading.set(false);
      control.enable();
    }
  }

  async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.saving() || this.categoriesLoading() || this.form.invalid) {
      return;
    }
    const { categoryId, title, currency } = this.form.getRawValue();
    this.saving.set(true);
    this.problem.set(null);
    try {
      const data: unknown = await apiData(
        createProduct({ body: { categoryId: categoryId ?? '', title: title.trim(), currency } }),
      );
      this.#toast.success(
        'Produto criado em rascunho. Complete o conteúdo para enviar à moderação.',
      );
      this.visible.set(false);
      this.created.emit(isProduct(data) ? data : {});
    } catch (error) {
      const problem = toProblem(error);
      const unmatched = applyServerErrors(this.form, problem);
      this.problem.set(
        problem.errors?.length && !unmatched.length ? null : { ...problem, errors: unmatched },
      );
    } finally {
      this.saving.set(false);
    }
  }

  #open(): void {
    this.form.reset({ categoryId: null, title: '', currency: '' });
    this.problem.set(null);
    void this.loadCategories();
  }
}

function toCategoryNode(category: CategorySummary): CategoryNode[] {
  if (!category.id || !category.name || category.usable === false) {
    return [];
  }
  return [
    {
      id: category.id,
      name: category.name,
      ancestors: (category.ancestors ?? []).map((ancestor) => ({
        id: ancestor.id ?? '',
        name: ancestor.name ?? '',
      })),
      level: category.level,
      usable: category.usable,
      childCount: category.childCount,
    },
  ];
}

// NOTE: o contrato declara o 201 de `POST /products` sem corpo, mas o servidor responde o produto criado.
function isProduct(value: unknown): value is ProductResponse {
  return (
    typeof value === 'object' && value !== null && typeof (value as ProductResponse).id === 'string'
  );
}
