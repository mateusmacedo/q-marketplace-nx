import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  resource,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, RouterOutlet } from '@angular/router';
import {
  BadgeComponent,
  BreadcrumbComponent,
  BreadcrumbItemComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  RowComponent,
} from '@coreui/angular';
import { cilChevronRight, cilPlus } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  batchCategories,
  type CategoryBatchAction,
  type CategoryBatchResult,
  type CategorySummary,
  getCategory,
  getCategoryStats,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  abortOnDestroy,
  BatchBarComponent,
  batchFailures,
  type BatchFailure,
  BatchResultAlertComponent,
  ConfirmModalComponent,
  EmptyStateComponent,
  ErrorStateComponent,
  type LeaveGuarded,
  leaveWarning,
  ListSelection,
  LoadingStateComponent,
  ProblemAlertComponent,
  ToastService,
  warnBeforeUnload,
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  batchRunCounts,
  batchSummary,
  plural,
  type Problem,
  runBatch,
} from '@marketplace/shared-web-util';
import { map } from 'rxjs';

import { CategoryEvents, type CreatedCategory } from './category-events';
import { CategoryFormComponent, type CategoryFormResult } from './category-form.component';
import {
  acceptsCategoryAction,
  CATEGORY_BATCH_ACTIONS,
  CATEGORY_BATCH_VERBS,
  categoryBatchSize,
  categoryPath,
  categoryTrail,
  isCategoryBatchAction,
  loadLevel,
  resourceProblem,
} from './category-model';

/** Texto do modal de confirmação das ações em lote que pedem confirmação. */
const BATCH_CONFIRMATIONS: Readonly<Partial<Record<CategoryBatchAction, string>>> = {
  DEACTIVATE: 'Elas e as subcategorias deixam de aceitar produtos novos.',
  DELETE:
    'As categorias são excluídas definitivamente, das subcategorias para as categorias pai. As recusadas continuam ' +
    'selecionadas, com o motivo. Não há como desfazer.',
};

/**
 * Categorias por nível (`/plataforma/categorias`), com o detalhe ao lado (abaixo de `lg`, empilhado). A lista do nível
 * vem de `GET /categories?parentId=`, o nível fica na URL (`parentId`) e o breadcrumb volta. **Selecionar** mostra as
 * caixas e a barra de lote, que chama `POST /categories/batch` em grupos do tamanho do contrato.
 */
@Component({
  selector: 'app-categories-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    BatchBarComponent,
    BatchResultAlertComponent,
    BreadcrumbComponent,
    BreadcrumbItemComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    CategoryFormComponent,
    ColComponent,
    ConfirmModalComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    IconDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    ProblemAlertComponent,
    RouterLink,
    RouterOutlet,
    RowComponent,
  ],
  template: `
    <app-batch-result-alert
      (dismiss)="failures.set([])"
      [failures]="failures()"
      title="Algumas categorias não foram processadas"
    />
    @if (batchProblem()) {
      <app-problem-alert [problem]="batchProblem()" heading="O lote parou antes do fim" />
    }
    <c-row>
      <c-col lg="5">
        <c-card class="mb-4">
          <c-card-body>
            <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
              <div>
                <h1 class="h4 card-title mb-0">Categorias</h1>
                <div class="small text-body-secondary">{{ summary() }}</div>
              </div>
              <div class="d-flex gap-2">
                <button
                  (click)="toggleSelecting()"
                  [attr.aria-pressed]="selecting()"
                  [disabled]="!!running()"
                  cButton
                  color="secondary"
                  type="button"
                  variant="outline"
                >
                  {{ selecting() ? 'Concluir seleção' : 'Selecionar' }}
                </button>
                <button (click)="create($event)" [disabled]="!canCreate()" cButton color="primary" type="button">
                  <svg [cIcon]="plusIcon" aria-hidden="true" class="me-2"></svg>
                  {{ parentId() ? 'Subcategoria' : 'Categoria raiz' }}
                </button>
              </div>
            </div>

            <c-breadcrumb ariaLabel="Nível da categoria" class="mb-3">
              <c-breadcrumb-item [active]="!parentId()" [linkProps]="rootLink" [url]="[]">Raiz</c-breadcrumb-item>
              @for (step of trail(); track step.id; let last = $last) {
                <c-breadcrumb-item [active]="last" [linkProps]="{ queryParams: { parentId: step.id } }" [url]="[]">
                  {{ step.name }}
                </c-breadcrumb-item>
              }
            </c-breadcrumb>

            @if (selecting() && items().length) {
              <c-form-check class="mb-2">
                <input
                  (change)="selection.togglePage()"
                  [checked]="selection.pageChecked()"
                  [disabled]="!!running()"
                  [indeterminate]="selection.pageIndeterminate()"
                  cFormCheckInput
                  id="app-categories-select-level"
                  type="checkbox"
                />
                <label cFormCheckLabel for="app-categories-select-level">Selecionar todas deste nível</label>
              </c-form-check>
            }
            <app-batch-bar
              (clear)="selection.clear()"
              (run)="onBatch($event)"
              [actions]="selection.batchActions(batchActions)"
              [running]="running()"
              [selectedCount]="selection.count()"
            />

            @if (level.hasValue()) {
              @if (items().length) {
                <ul cListGroup class="mb-0">
                  @for (category of items(); track category.id) {
                    <li
                      [active]="category.id === openId()"
                      cListGroupItem
                      class="list-group-item-action d-flex gap-2 align-items-center"
                    >
                      @if (selecting()) {
                        <input
                          (change)="selection.toggle(category)"
                          [attr.aria-label]="'Selecionar ' + category.name"
                          [checked]="selection.isSelected(category)"
                          [disabled]="!!running()"
                          cFormCheckInput
                          class="mt-0 flex-shrink-0"
                          type="checkbox"
                        />
                      }
                      <a
                        [attr.aria-current]="category.id === openId() ? 'true' : null"
                        [routerLink]="[category.id]"
                        class="text-reset text-decoration-none flex-grow-1"
                        queryParamsHandling="preserve"
                      >
                        <div>
                          {{ category.name }}
                          @if (category.active === false) {
                            <c-badge color="secondary">Inativa</c-badge>
                          }
                        </div>
                        <small [class.text-body-secondary]="category.id !== openId()">
                          {{ category.code }} | {{ attributeCount(category) }}
                        </small>
                      </a>
                      @if (category.childCount) {
                        <a
                          [attr.aria-label]="childrenLabel(category)"
                          [color]="category.id === openId() ? 'light' : 'secondary'"
                          [queryParams]="{ parentId: category.id }"
                          [routerLink]="[]"
                          [variant]="category.id === openId() ? undefined : 'outline'"
                          cButton
                          shape="rounded-pill"
                          size="sm"
                        >
                          {{ category.childCount }}
                          <svg [cIcon]="chevronIcon" aria-hidden="true" size="sm"></svg>
                        </a>
                      }
                    </li>
                  }
                </ul>
              } @else {
                <app-empty-state [message]="parentId() ? 'Nenhuma subcategoria neste nível.' : 'Nenhuma categoria cadastrada.'">
                  <button (click)="create($event)" [disabled]="!canCreate()" cButton color="primary" type="button" variant="outline">
                    {{ parentId() ? 'Criar subcategoria' : 'Criar categoria raiz' }}
                  </button>
                </app-empty-state>
              }
            } @else if (levelProblem()) {
              <app-error-state (retry)="level.reload()" [problem]="levelProblem()" message="Não foi possível carregar as categorias." />
            } @else {
              <app-loading-state label="Carregando as categorias…" />
            }
          </c-card-body>
        </c-card>
      </c-col>
      <c-col lg="7">
        <router-outlet (activate)="detailOpen.set(true)" (deactivate)="detailOpen.set(false)" />
        @if (!detailOpen()) {
          <c-card class="mb-4">
            <c-card-body>
              <app-empty-state message="Selecione uma categoria." />
            </c-card-body>
          </c-card>
        }
      </c-col>
    </c-row>

    <app-category-form (saved)="onSaved($event)" />

    <app-confirm-modal
      (confirm)="confirmBatch()"
      (visibleChange)="!$event && pendingBatch.set(null)"
      [title]="batchTitle()"
      [confirmLabel]="batchLabel()"
      [visible]="!!pendingBatch()"
      confirmColor="danger"
    >
      <p class="mb-0">{{ batchWarning() }}</p>
    </app-confirm-modal>
  `,
})
export class CategoriesPageComponent implements LeaveGuarded {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #events = inject(CategoryEvents);
  readonly #toast = inject(ToastService);
  readonly #abort = abortOnDestroy();
  readonly #batchSize = categoryBatchSize();

  readonly form = viewChild.required(CategoryFormComponent);

  readonly plusIcon = cilPlus;
  readonly chevronIcon = cilChevronRight;
  readonly batchActions = CATEGORY_BATCH_ACTIONS;
  readonly rootLink = { queryParams: { parentId: null } };

  /** Nível aberto: o pai das categorias listadas, ou `null` na raiz. */
  readonly parentId = toSignal(
    this.#route.queryParamMap.pipe(map((params) => params.get('parentId') || null)),
    {
      requireSync: true,
    },
  );

  /** Categorias do nível, todas as páginas, com o pai que as produziu. */
  readonly level = resource({
    params: () => ({ parentId: this.parentId() }),
    loader: async ({ params }) => ({
      parentId: params.parentId,
      items: await loadLevel(params.parentId),
    }),
  });
  /** Pai do nível aberto, para o breadcrumb e para criar a subcategoria. */
  readonly parent = resource({
    params: () => {
      const id = this.parentId();
      return id ? { id } : undefined;
    },
    loader: ({ params }) => apiData(getCategory({ path: { id: params.id } })),
  });
  readonly stats = resource({ loader: () => apiData(getCategoryStats()) });

  readonly items = computed(() => (this.level.hasValue() ? this.level.value().items : []));
  readonly levelProblem = computed(() => resourceProblem(this.level.error()));
  readonly trail = computed(() =>
    this.parent.hasValue() && this.parent.value() ? categoryTrail(this.parent.value()) : [],
  );
  readonly canCreate = computed(
    () => !this.parentId() || (this.parent.hasValue() && !!this.parent.value()),
  );
  readonly summary = computed(() => {
    if (!this.stats.hasValue()) {
      return '';
    }
    const stats = this.stats.value();
    return `${plural(stats.total ?? 0, 'categoria', 'categorias')} | ${plural(stats.roots ?? 0, 'raiz', 'raízes')}`;
  });
  readonly openId = this.#events.openId.asReadonly();
  readonly detailOpen = signal(false);

  readonly selection = new ListSelection<CategorySummary>({
    id: (category) => category.id ?? '',
    accepts: acceptsCategoryAction,
  });
  readonly selecting = signal(false);
  readonly running = signal<CategoryBatchAction | null>(null);
  readonly pendingBatch = signal<CategoryBatchAction | null>(null);
  readonly failures = signal<BatchFailure[]>([]);
  readonly batchProblem = signal<Problem | null>(null);
  readonly batchTitle = computed(() => {
    const action = this.pendingBatch();
    if (!action) {
      return '';
    }
    const count = plural(this.selection.countFor(action), 'categoria', 'categorias');
    return action === 'DELETE' ? `Excluir ${count}` : `Desativar ${count}`;
  });
  readonly batchLabel = computed(() =>
    this.pendingBatch() === 'DELETE' ? 'Excluir' : 'Desativar',
  );
  readonly batchWarning = computed(() => {
    const action = this.pendingBatch();
    return action ? (BATCH_CONFIRMATIONS[action] ?? '') : '';
  });

  readonly leaveWarning = computed(() =>
    leaveWarning({ batchRunning: this.running() !== null, unsaved: this.form().dirty() }),
  );

  constructor() {
    warnBeforeUnload(this.leaveWarning);
    effect(() => {
      const items = this.items();
      untracked(() => this.selection.setPage(items));
    });
    effect(() => {
      const gone = this.#events.gone();
      if (gone) {
        untracked(() => this.selection.remove([gone.id]));
      }
    });
    effect(() => {
      if (this.#events.listStamp() > 0) {
        untracked(() => {
          this.level.reload();
          this.parent.reload();
          this.stats.reload();
        });
      }
    });
    effect(() => {
      if (this.#events.created()) {
        untracked(() => {
          const created = this.#events.takeCreated();
          if (created) {
            void this.#reveal(created);
          }
        });
      }
    });
  }

  attributeCount(category: CategorySummary): string {
    return plural(category.attributeCount ?? 0, 'atributo próprio', 'atributos próprios');
  }

  childrenLabel(category: CategorySummary): string {
    return category.childCount === 1
      ? `Abrir a subcategoria de ${category.name}`
      : `Abrir as ${category.childCount} subcategorias de ${category.name}`;
  }

  /** Categoria nova no nível aberto: raiz na raiz, subcategoria do pai dentro de um nível. */
  create(event: Event): void {
    const parent = this.parent.hasValue() ? this.parent.value() : undefined;
    const target =
      this.parentId() && parent?.id
        ? { id: parent.id, name: parent.name ?? '', path: categoryPath(parent) }
        : null;
    this.form().open({ mode: 'create', parent: target }, event.currentTarget as HTMLElement);
  }

  toggleSelecting(): void {
    if (this.selecting()) {
      this.selection.clear();
    }
    this.selecting.update((selecting) => !selecting);
  }

  onSaved(result: CategoryFormResult): void {
    if (result.mode === 'create') {
      this.#toast.success('Categoria criada.');
      this.#events.reveal({ parentId: result.parentId, id: result.id });
      this.#events.listChanged();
    }
  }

  /** Ativar vai direto; Desativar e Excluir pedem confirmação. */
  onBatch(action: string): void {
    if (!isCategoryBatchAction(action)) {
      return;
    }
    if (BATCH_CONFIRMATIONS[action]) {
      this.pendingBatch.set(action);
    } else {
      void this.runBatch(action);
    }
  }

  confirmBatch(): void {
    const action = this.pendingBatch();
    this.pendingBatch.set(null);
    if (action) {
      void this.runBatch(action);
    }
  }

  /**
   * Envia os selecionados que aceitam a ação em grupos de `POST /categories/batch`, cada item com a versão lida. Os
   * gravados saem da seleção, as falhas ficam com o motivo do servidor, e a lista e o detalhe são lidos de novo.
   */
  async runBatch(action: CategoryBatchAction): Promise<void> {
    const items = this.selection.accepting(action);
    const ignored = this.selection.count() - items.length;
    this.running.set(action);
    this.failures.set([]);
    this.batchProblem.set(null);
    const run = await runBatch<CategorySummary, CategoryBatchResult>({
      items,
      id: (category) => category.id ?? '',
      size: this.#batchSize,
      signal: this.#abort,
      send: (group) =>
        apiData(
          batchCategories({
            body: {
              action,
              items: group.map((category) => ({
                id: category.id ?? '',
                version: category.version,
              })),
            },
          }),
        ).then((response) => response.results),
    });
    this.running.set(null);
    if (this.#abort.aborted) {
      return;
    }
    this.selection.completeBatch(run);
    this.failures.set(
      batchFailures(
        run.failed,
        (category) => category.id ?? '',
        (category) => category.name ?? '',
      ),
    );
    this.batchProblem.set(run.error ?? null);
    const counts = batchRunCounts(run, ignored);
    const summary = batchSummary(counts, CATEGORY_BATCH_VERBS[action]);
    if (counts.failed) {
      this.#toast.warning(summary);
    } else {
      this.#toast.success(summary);
    }
    const openId = this.openId();
    if (
      action === 'DELETE' &&
      openId &&
      run.succeeded.some((success) => success.item.id === openId)
    ) {
      await this.#router.navigate(['.'], {
        relativeTo: this.#route,
        queryParamsHandling: 'preserve',
      });
    }
    this.#events.listChanged();
    this.#events.detailChanged();
  }

  /** Mostra a categoria criada: abre o nível do pai e o detalhe dela, pelo id que a criação devolveu. */
  async #reveal(created: CreatedCategory): Promise<void> {
    await this.#router.navigate(created.id ? [created.id] : [], {
      relativeTo: this.#route,
      queryParams: { parentId: created.parentId },
    });
  }
}
