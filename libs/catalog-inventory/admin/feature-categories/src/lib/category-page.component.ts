import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  resource,
  signal,
  untracked,
  viewChild
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  CardHeaderComponent,
  ColComponent,
  RowComponent
} from '@coreui/angular';
import { cilBan, cilCheckCircle, cilMove, cilPlus, cilTrash } from '@coreui/icons';
import {
  type CategoryResponse,
  deleteCategory,
  getCategory,
  reviseCategory
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  type ActionMenuChoice,
  ActionMenuComponent,
  type ActionMenuItem,
  ConfirmModalComponent,
  EditConflictAlertComponent,
  ErrorStateComponent,
  type LeaveGuarded,
  leaveWarning,
  LoadingStateComponent,
  ProblemAlertComponent,
  ToastService,
  warnBeforeUnload
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  apiDataWithVersion,
  isEditConflict,
  type Problem,
  problemReasons,
  toProblem,
  type Versioned
} from '@marketplace/shared-web-util';
import { map } from 'rxjs';

import { CategoryAttributesTableComponent } from './category-attributes-table.component';
import { CategoryEvents } from './category-events';
import { CategoryFormComponent, type CategoryFormResult } from './category-form.component';
import {
  canDelete,
  categoryPath,
  inheritedAttributeRows,
  ownAttributeRows,
  parentPath,
  resourceProblem
} from './category-model';
import { CategoryMoveModalComponent } from './category-move-modal.component';

const HAS_PRODUCTS = 'category-has-products';

/**
 * Detalhe da categoria (`/plataforma/categorias/:id`), ao lado da lista por nível: nome, situação, caminho, código e
 * nível vindos da API, os atributos próprios e os herdados e as ações **Editar**, Mover para…, Nova subcategoria,
 * Desativar ou Ativar e Excluir. Toda gravação vai com o `If-Match` da versão lida.
 */
@Component({
  selector: 'app-category-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ActionMenuComponent,
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    CardHeaderComponent,
    CategoryAttributesTableComponent,
    CategoryFormComponent,
    CategoryMoveModalComponent,
    ColComponent,
    ConfirmModalComponent,
    EditConflictAlertComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    ProblemAlertComponent,
    RowComponent
  ],
  template: `
    @if (current(); as category) {
      <c-card class="mb-4">
        <c-card-body>
          <c-row class="g-2 mb-3">
            <c-col sm="7">
              <h2 class="h4 card-title mb-0">
                {{ category.name }}
                <c-badge [color]="category.active ? 'success' : 'secondary'">
                  {{ category.active ? 'Ativa' : 'Inativa' }}
                </c-badge>
              </h2>
              <div class="small text-body-secondary">
                {{ path() }} | {{ category.code }} | nível {{ category.level }}
                @if (category.active && category.usable === false) {
                  | não aceita produtos novos, porque uma categoria acima está inativa
                }
              </div>
            </c-col>
            <c-col sm="5">
              <app-action-menu
                (action)="onAction($event)"
                [disabled]="busy()"
                [items]="menu()"
                [label]="'Ações de ' + category.name"
                class="float-end"
              />
              <button (click)="edit($event)" [disabled]="busy()" cButton class="float-end me-3" color="primary" type="button">
                Editar
              </button>
            </c-col>
          </c-row>
          @if (isConflict(actionProblem())) {
            <app-edit-conflict-alert (reload)="reload()" [busy]="reloading()" />
          } @else if (actionProblem()) {
            <app-problem-alert [problem]="actionProblem()" heading="Não foi possível ativar a categoria" />
          }
          @if (!deletable()) {
            <p class="small text-body-secondary mb-0">Para excluir, primeiro exclua ou mova as subcategorias.</p>
          }
        </c-card-body>
      </c-card>
      <c-card class="mb-4">
        <c-card-header>Atributos próprios</c-card-header>
        <c-card-body>
          <app-category-attributes-table [rows]="ownAttributes()" empty="Nenhum atributo próprio." />
        </c-card-body>
      </c-card>
      <c-card class="mb-4">
        <c-card-header>Atributos herdados</c-card-header>
        <c-card-body>
          <app-category-attributes-table [rows]="inheritedAttributes()" [showOrigin]="true" empty="Nenhum atributo herdado." />
        </c-card-body>
      </c-card>
    } @else {
      <c-card class="mb-4">
        <c-card-body>
          @if (loadProblem()) {
            <app-error-state (retry)="category.reload()" [problem]="loadProblem()" message="Não foi possível carregar a categoria." />
          } @else {
            <app-loading-state label="Carregando a categoria…" />
          }
        </c-card-body>
      </c-card>
    }

    <app-category-form (reloaded)="category.set($event)" (saved)="onSaved($event)" />
    <app-category-move-modal (moved)="onMoved($event)" (reloaded)="category.set($event)" [category]="versioned()" />

    <app-confirm-modal
      (confirm)="setActive(false)"
      [(visible)]="deactivating"
      [busy]="busy()"
      [returnFocus]="returnFocus()"
      confirmColor="danger"
      confirmLabel="Desativar"
      title="Desativar categoria"
    >
      @if (isConflict(modalProblem())) {
        <app-edit-conflict-alert (reload)="reload()" [busy]="reloading()" />
      } @else if (modalProblem()) {
        <app-problem-alert [problem]="modalProblem()" heading="Não foi possível desativar a categoria" />
      }
      <p class="mb-0">
        <strong>{{ current()?.name }}</strong>
        {{ current()?.childCount ? 'e as subcategorias deixam' : 'deixa' }} de aceitar produtos novos.
      </p>
    </app-confirm-modal>

    <app-confirm-modal
      (confirm)="remove()"
      [(visible)]="deleting"
      [busy]="busy()"
      [returnFocus]="returnFocus()"
      confirmColor="danger"
      confirmLabel="Excluir"
      title="Excluir categoria"
    >
      @if (isConflict(modalProblem())) {
        <app-edit-conflict-alert (reload)="reload()" [busy]="reloading()" />
      } @else if (modalProblem()) {
        <app-problem-alert [problem]="modalProblem()" heading="Não foi possível excluir" />
        @if (hasProducts() && current()?.active) {
          <button (click)="deactivateInstead()" cButton class="mb-3" color="danger" size="sm" type="button" variant="outline">
            Desativar a categoria
          </button>
        }
      }
      <p class="mb-0">
        <strong>{{ current()?.name }}</strong> ({{ current()?.code }}) será excluída definitivamente, junto com os
        atributos próprios. Não há como desfazer.
      </p>
    </app-confirm-modal>
  `
})
export class CategoryPageComponent implements LeaveGuarded {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #events = inject(CategoryEvents);
  readonly #toast = inject(ToastService);

  readonly form = viewChild.required(CategoryFormComponent);
  readonly mover = viewChild.required(CategoryMoveModalComponent);

  readonly id = toSignal(this.#route.paramMap.pipe(map(params => params.get('id') ?? '')), { requireSync: true });

  /** Categoria com a versão do `ETag`, para o `If-Match` das gravações. */
  readonly category = resource({
    params: () => ({ id: this.id() }),
    loader: ({ params }) => apiDataWithVersion(getCategory({ path: { id: params.id } }))
  });

  readonly versioned = computed(() => (this.category.hasValue() ? this.category.value() : undefined));
  readonly current = computed(() => this.versioned()?.data);
  readonly loadProblem = computed(() => resourceProblem(this.category.error()));
  readonly path = computed(() => {
    const category = this.current();
    return category ? categoryPath(category) : '';
  });
  readonly ownAttributes = computed(() => {
    const category = this.current();
    return category ? ownAttributeRows(category) : [];
  });
  readonly inheritedAttributes = computed(() => {
    const category = this.current();
    return category ? inheritedAttributeRows(category) : [];
  });
  readonly deletable = computed(() => {
    const category = this.current();
    return !category || canDelete(category);
  });
  readonly menu = computed<ActionMenuItem[]>(() => {
    const category = this.current();
    if (!category) {
      return [];
    }
    return [
      { id: 'MOVE', label: 'Mover para…', icon: cilMove },
      { id: 'NEW_CHILD', label: 'Nova subcategoria', icon: cilPlus },
      category.active
        ? { id: 'DEACTIVATE', label: 'Desativar', icon: cilBan }
        : { id: 'ACTIVATE', label: 'Ativar', icon: cilCheckCircle },
      ...(this.deletable() ? [{ id: 'DELETE', label: 'Excluir', icon: cilTrash, danger: true }] : [])
    ];
  });

  readonly busy = signal(false);
  readonly reloading = signal(false);
  readonly deactivating = signal(false);
  readonly deleting = signal(false);
  /** Recusa de Ativar, que não tem modal. */
  readonly actionProblem = signal<Problem | null>(null);
  /** Recusa de Desativar ou Excluir, mostrada no modal da confirmação. */
  readonly modalProblem = signal<Problem | null>(null);
  readonly returnFocus = signal<HTMLElement | null>(null);
  readonly hasProducts = computed(() => problemReasons(this.modalProblem()).some(reason => reason.code === HAS_PRODUCTS));

  readonly leaveWarning = computed(() => leaveWarning({ unsaved: this.form().dirty() }));

  constructor() {
    warnBeforeUnload(this.leaveWarning);
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.#events.openId.set(id);
        this.actionProblem.set(null);
        this.modalProblem.set(null);
        this.deactivating.set(false);
        this.deleting.set(false);
      });
    });
    inject(DestroyRef).onDestroy(() => this.#events.openId.set(null));
    effect(() => {
      if (this.#events.detailStamp() > 0) {
        untracked(() => this.category.reload());
      }
    });
  }

  isConflict(problem: Problem | null): boolean {
    return isEditConflict(problem);
  }

  edit(event: Event): void {
    const category = this.versioned();
    if (category) {
      this.form().open({ mode: 'edit', category }, event.currentTarget as HTMLElement);
    }
  }

  onAction(choice: ActionMenuChoice): void {
    const category = this.current();
    if (!category) {
      return;
    }
    this.actionProblem.set(null);
    this.modalProblem.set(null);
    this.returnFocus.set(choice.toggle);
    switch (choice.id) {
      case 'MOVE':
        this.mover().open(choice.toggle);
        break;
      case 'NEW_CHILD':
        this.form().open(
          { mode: 'create', parent: { id: category.id ?? '', name: category.name ?? '', path: this.path() } },
          choice.toggle
        );
        break;
      case 'ACTIVATE':
        void this.setActive(true);
        break;
      case 'DEACTIVATE':
        this.deactivating.set(true);
        break;
      case 'DELETE':
        this.deleting.set(true);
        break;
    }
  }

  /**
   * Ativa sem confirmação ou desativa depois do modal, com `PUT /categories/{id}` e a versão lida. Se a pessoa abriu
   * outra categoria antes da resposta, só a lista é atualizada.
   */
  async setActive(active: boolean): Promise<void> {
    const versioned = this.versioned();
    const id = versioned?.data.id;
    if (!versioned || !id || this.busy()) {
      return;
    }
    const category = versioned.data;
    this.busy.set(true);
    this.actionProblem.set(null);
    this.modalProblem.set(null);
    try {
      const saved = await apiDataWithVersion(
        reviseCategory({
          path: { id },
          body: { name: category.name ?? '', attributes: category.attributes ?? [], active },
          ...(versioned.version ? { headers: { 'If-Match': versioned.version } } : {})
        })
      );
      if (this.id() === id) {
        this.category.set(saved);
        this.deactivating.set(false);
        this.#toast.success(`"${category.name}" ${active ? 'ativada' : 'desativada'}.`);
      }
      this.#events.listChanged();
    } catch (error) {
      if (this.id() === id) {
        (active ? this.actionProblem : this.modalProblem).set(toProblem(error));
      }
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Exclui a categoria vazia; depois, o detalhe passa para a categoria pai. Se a pessoa abriu outra categoria antes da
   * resposta, só a lista é atualizada.
   */
  async remove(): Promise<void> {
    const versioned = this.versioned();
    const id = versioned?.data.id;
    if (!versioned || !id || this.busy()) {
      return;
    }
    const category = versioned.data;
    this.busy.set(true);
    this.modalProblem.set(null);
    try {
      await apiData(
        deleteCategory({
          path: { id },
          ...(versioned.version ? { headers: { 'If-Match': versioned.version } } : {})
        })
      );
      this.#events.left(id);
      this.#events.listChanged();
      if (this.id() !== id) {
        return;
      }
      this.deleting.set(false);
      this.#toast.success(`"${category.name}" excluída.`);
      await this.#router.navigate(category.parentId ? ['..', category.parentId] : ['..'], {
        relativeTo: this.#route,
        queryParamsHandling: 'preserve'
      });
    } catch (error) {
      if (this.id() === id) {
        this.modalProblem.set(toProblem(error));
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Saída sugerida pela recusa `category-has-products`: trocar a exclusão pela desativação. */
  deactivateInstead(): void {
    this.modalProblem.set(null);
    this.deleting.set(false);
    this.deactivating.set(true);
  }

  /** "Carregar versão atual" do conflito: lê a versão nova e limpa a recusa, se a mesma categoria continua aberta. */
  async reload(): Promise<void> {
    const id = this.current()?.id;
    if (!id) {
      return;
    }
    this.reloading.set(true);
    try {
      const current = await apiDataWithVersion(getCategory({ path: { id } }));
      if (this.id() === id) {
        this.category.set(current);
        this.actionProblem.set(null);
        this.modalProblem.set(null);
      }
    } catch (error) {
      if (this.id() === id) {
        (this.deactivating() || this.deleting() ? this.modalProblem : this.actionProblem).set(toProblem(error));
      }
    } finally {
      this.reloading.set(false);
    }
  }

  onSaved(result: CategoryFormResult): void {
    if (result.mode === 'edit') {
      this.category.set(result.category);
      this.#toast.success('Categoria salva.');
    } else {
      this.#toast.success('Categoria criada.');
      this.category.reload();
      this.#events.reveal({ parentId: result.parentId, id: result.id });
    }
    this.#events.listChanged();
  }

  onMoved(moved: Versioned<CategoryResponse>): void {
    if (moved.data.id) {
      this.#events.left(moved.data.id);
    }
    this.category.set(moved);
    this.#toast.success(`"${moved.data.name}" movida para ${parentPath(moved.data) ?? 'a raiz'}.`);
    this.#events.listChanged();
  }
}
