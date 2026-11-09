import { ChangeDetectionStrategy, Component, computed, input, model, output, resource, signal } from '@angular/core';
import { FormLabelDirective, FormTextDirective } from '@coreui/angular';
import {
  type CategoryResponse,
  getCategory,
  listCategoryTree,
  moveCategory
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  buildCategoryOptions,
  CATEGORY_ROOT,
  categoryDescendants,
  CategorySelectComponent
} from '@marketplace/catalog-inventory-admin-ui';
import {
  ConfirmModalComponent,
  EditConflictAlertComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  ProblemAlertComponent
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  apiDataWithVersion,
  isEditConflict,
  type Problem,
  toProblem,
  type Versioned
} from '@marketplace/shared-web-util';

import { loadAllPages, resourceProblem, ROOT_LABEL, toCategoryNode } from './category-model';

let nextId = 0;

/**
 * Modal **Mover para…** com o `form-select` de caminhos de `admin/ui`, sem a própria categoria, as descendentes e o pai
 * atual, e com a opção "Raiz". O painel não conhece a profundidade máxima da árvore: as recusas `category-cycle`,
 * `category-too-deep` e `attribute-conflict` aparecem no modal com a mensagem do servidor.
 */
@Component({
  selector: 'app-category-move-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CategorySelectComponent,
    ConfirmModalComponent,
    EditConflictAlertComponent,
    ErrorStateComponent,
    FormLabelDirective,
    FormTextDirective,
    LoadingStateComponent,
    ProblemAlertComponent
  ],
  template: `
    <app-confirm-modal
      (confirm)="move()"
      [(visible)]="visible"
      [busy]="saving()"
      [returnFocus]="returnFocus()"
      [title]="title()"
      confirmLabel="Mover"
    >
      @if (conflict()) {
        <app-edit-conflict-alert (reload)="reload()" [busy]="reloading()" />
      } @else if (problem()) {
        <app-problem-alert [problem]="problem()" heading="Não foi possível mover a categoria" />
      }
      @if (tree.isLoading()) {
        <app-loading-state [rows]="2" label="Carregando as categorias…" />
      } @else if (tree.error()) {
        <app-error-state (retry)="tree.reload()" [problem]="treeProblem()" message="Não foi possível carregar as categorias." />
      } @else {
        <label [for]="selectId" cLabel>Novo lugar</label>
        <app-category-select
          [(value)]="destination"
          [describedBy]="selectId + '-help'"
          [excluded]="excluded()"
          [invalid]="!!choiceError()"
          [options]="options()"
          [rootLabel]="rootLabel()"
          [selectId]="selectId"
          placeholder="Escolha o novo pai ou a raiz"
        />
        @if (choiceError()) {
          <div class="invalid-feedback d-block">{{ choiceError() }}</div>
        }
        <div [id]="selectId + '-help'" cFormText>As subcategorias vão junto.</div>
      }
    </app-confirm-modal>
  `
})
export class CategoryMoveModalComponent {
  readonly visible = model(false);
  /** Categoria movida, com a versão lida para o `If-Match`. */
  readonly category = input<Versioned<CategoryResponse> | null | undefined>();

  readonly moved = output<Versioned<CategoryResponse>>();
  /** Versão atual lida depois do conflito de edição, para a tela atualizar o detalhe. */
  readonly reloaded = output<Versioned<CategoryResponse>>();

  readonly selectId = `app-category-move-${nextId++}`;
  readonly destination = signal<string | null>(null);
  readonly choiceError = signal<string | null>(null);
  readonly saving = signal(false);
  readonly reloading = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly #returnFocus = signal<HTMLElement | null>(null);
  readonly returnFocus = this.#returnFocus.asReadonly();

  /** Árvore inteira do tenant, lida a cada abertura do modal. */
  readonly tree = resource({
    params: () => (this.visible() ? { open: true } : undefined),
    loader: () => loadAllPages(cursor => apiData(listCategoryTree({ query: cursor ? { cursor } : {} })))
  });

  readonly #nodes = computed(() => (this.tree.hasValue() ? this.tree.value().map(toCategoryNode) : []));
  readonly options = computed(() => buildCategoryOptions(this.#nodes()));
  readonly excluded = computed(() => {
    const current = this.category()?.data;
    if (!current?.id) {
      return [];
    }
    return [current.id, ...categoryDescendants(this.#nodes(), current.id), ...(current.parentId ? [current.parentId] : [])];
  });
  readonly rootLabel = computed(() => (this.category()?.data.parentId ? ROOT_LABEL : null));
  readonly title = computed(() => `Mover ${this.category()?.data.name ?? 'categoria'}`);
  readonly conflict = computed(() => isEditConflict(this.problem()));
  readonly treeProblem = computed(() => resourceProblem(this.tree.error()));

  /** Abre o modal sem destino escolhido. */
  open(returnFocus?: HTMLElement | null): void {
    this.#returnFocus.set(returnFocus ?? null);
    this.destination.set(null);
    this.choiceError.set(null);
    this.problem.set(null);
    this.visible.set(true);
  }

  async move(): Promise<void> {
    const current = this.category();
    const destination = this.destination();
    if (!current?.data.id || this.saving()) {
      return;
    }
    if (!destination) {
      this.choiceError.set('Escolha o novo pai ou a raiz.');
      return;
    }
    this.choiceError.set(null);
    this.problem.set(null);
    this.saving.set(true);
    try {
      const moved = await apiDataWithVersion(
        moveCategory({
          path: { id: current.data.id },
          body: { parentId: destination === CATEGORY_ROOT ? null : destination },
          ...(current.version ? { headers: { 'If-Match': current.version } } : {})
        })
      );
      this.visible.set(false);
      this.moved.emit(moved);
    } catch (error) {
      this.problem.set(toProblem(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** "Carregar versão atual" do conflito: lê a versão nova e mantém o destino escolhido. */
  async reload(): Promise<void> {
    const id = this.category()?.data.id;
    if (!id) {
      return;
    }
    this.reloading.set(true);
    try {
      const fresh = await apiDataWithVersion(getCategory({ path: { id } }));
      this.problem.set(null);
      this.reloaded.emit(fresh);
    } catch (error) {
      this.problem.set(toProblem(error));
    } finally {
      this.reloading.set(false);
    }
  }
}
