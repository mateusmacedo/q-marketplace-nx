import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { AlertComponent, ButtonDirective, CardBodyComponent, CardComponent } from '@coreui/angular';
import { cilPlus } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  batchProducts,
  getStoreEligibilityResource,
  listProductsResource,
  type ProductBatchAction,
  ProductBatchRequestSchema,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  abortOnDestroy,
  AdminSession,
  BatchBarComponent,
  type BatchFailure,
  batchFailures,
  BatchResultAlertComponent,
  ConfirmModalComponent,
  CursorPaginationComponent,
  EditConflictAlertComponent,
  EmptyStateComponent,
  ErrorStateComponent,
  type LeaveGuarded,
  leaveWarning,
  ListSelection,
  LoadingStateComponent,
  ProblemAlertComponent,
  serverActionMenu,
  ToastService,
  warnBeforeUnload,
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  batchRunCounts,
  batchSummary,
  fieldRules,
  plural,
  type Problem,
  runBatch,
  toProblem,
} from '@marketplace/shared-web-util';

import { NewProductModalComponent } from '../new-product/new-product-modal.component';
import {
  PRODUCT_BATCH_PRESENTATIONS,
  type ProductActionPresentation,
  productActionPresentation,
  productActionPresentations,
} from '../product-actions';
import { ProductActionRunner } from './product-action-runner';
import { ProductListFiltersComponent } from './product-list-filters.component';
import {
  emptyListMessage,
  productListRequest,
  searchError,
  selectionScope,
} from './product-list-query';
import { injectProductListRoute } from './product-list-route';
import { type ProductRowAction, ProductTableComponent } from './product-table.component';

/** Tamanho de cada chamada de `POST /products/batch`, lido do contrato (`maxItems` de `items`). */
const BATCH_SIZE = fieldRules(ProductBatchRequestSchema, 'items').maxItems as number;

const LIST_ACTIONS = productActionPresentations('list');

/** Ação que pede o modal de confirmação antes da chamada: de um produto ou dos selecionados. */
type PendingConfirmation =
  | {
      readonly kind: 'item';
      readonly product: ProductResponse;
      readonly action: ProductActionPresentation;
    }
  | { readonly kind: 'batch'; readonly action: ProductActionPresentation; readonly count: number };

/**
 * Produtos da loja (`/loja/produtos`, proposta de design, tela 2): abas de status, busca, ordem pelo cabeçalho e
 * paginação por cursor na URL, seleção com ações em lote (`POST /products/batch`) e o menu "..." de cada produto com o
 * que o servidor devolve em `actions` e `blockedActions`.
 */
@Component({
  selector: 'app-product-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    BatchBarComponent,
    BatchResultAlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ConfirmModalComponent,
    CursorPaginationComponent,
    EditConflictAlertComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    IconDirective,
    LoadingStateComponent,
    NewProductModalComponent,
    ProblemAlertComponent,
    ProductListFiltersComponent,
    ProductTableComponent,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <div class="mb-3">
          <button (click)="openNewProduct()" cButton class="float-end" color="primary" type="button">
            <svg [cIcon]="plusIcon" aria-hidden="true" class="me-1"></svg>
            Novo produto
          </button>
          <h1 class="h4 card-title mb-0">Produtos</h1>
          <div class="small text-body-secondary">Os produtos da sua loja, do rascunho à vitrine.</div>
        </div>

        @if (storeNotApproved()) {
          <c-alert color="warning">
            Sua loja ainda não foi aprovada. Você pode cadastrar produtos, mas eles só são aprovados depois do KYB/KYC.
          </c-alert>
        }
        @if (runner.conflict()) {
          <app-edit-conflict-alert (reload)="reloadAfterConflict()" [busy]="products.isLoading()" />
        }
        @if (runner.failure(); as failure) {
          <app-problem-alert [heading]="failure.heading" [problem]="failure.problem" />
        }
        @if (batchProblem(); as problem) {
          <app-problem-alert
            [problem]="problem"
            heading="O lote parou antes do fim. Os produtos sem resposta continuam selecionados."
          />
        }
        <app-batch-result-alert
          (dismiss)="failures.set([])"
          [failures]="failures()"
          title="Alguns produtos não foram processados"
        />

        <app-product-list-filters
          (searched)="search($event)"
          [error]="searchError()"
          [q]="query().q"
          [status]="query().status"
        />

        <app-batch-bar
          (clear)="selection.clear()"
          (run)="startBatch($event)"
          [actions]="batchActions()"
          [running]="running()"
          [selectedCount]="selection.count()"
        />

        @if (searchError()) {
          <app-empty-state message="Corrija a busca para ver os produtos.">
            <button (click)="search('')" cButton color="link" type="button">Limpar busca</button>
          </app-empty-state>
        } @else if (loadProblem(); as problem) {
          <app-error-state (retry)="products.reload()" [problem]="problem" message="Não foi possível carregar os produtos." />
        } @else if (!page()) {
          <app-loading-state label="Carregando produtos…" />
        } @else if (!items().length) {
          <app-empty-state [message]="emptyMessage()">
            @if (query().q) {
              <button (click)="search('')" cButton color="link" type="button">Limpar busca</button>
            } @else {
              <button (click)="openNewProduct()" cButton color="primary" type="button">Criar produto</button>
            }
          </app-empty-state>
        } @else {
          <app-product-table
            (action)="onAction($event)"
            (sortChange)="listRoute.navigate({ sort: $event, cursor: null })"
            [busyId]="runner.busyId()"
            [menuDisabled]="running() !== null"
            [menu]="menu"
            [products]="items()"
            [selectionDisabled]="running() !== null"
            [selection]="selection"
            [sort]="query().sort"
          />
        }

        @if (!loadProblem()) {
          <app-cursor-pagination
            (cursorChange)="listRoute.navigate({ cursor: $event ?? null })"
            (limitChange)="listRoute.navigate({ limit: $event, cursor: null })"
            [cursor]="query().cursor"
            [disabled]="products.isLoading() || running() !== null"
            [limit]="query().limit"
            [nextCursor]="page()?.nextCursor"
          />
        }
      </c-card-body>
    </c-card>

    <app-confirm-modal
      (confirm)="confirm()"
      (visibleChange)="!$event && confirming.set(null)"
      [busy]="runner.busyId() !== null"
      [confirmColor]="confirming()?.action.confirmation?.color ?? 'primary'"
      [confirmLabel]="confirming()?.action.confirmation?.confirmLabel ?? 'Confirmar'"
      [returnFocus]="returnFocus()"
      [title]="confirmTitle()"
      [visible]="confirming() !== null"
    >
      @if (confirming(); as pending) {
        @if (pending.kind === 'item') {
          <p class="mb-0">
            <strong>{{ pending.product.title }}</strong>: {{ pending.action.confirmation?.message }}
          </p>
        } @else {
          <p class="mb-0">{{ pending.action.confirmation?.batchMessage }}</p>
        }
      }
    </app-confirm-modal>

    <app-new-product-modal (created)="openCreated($event)" [(visible)]="newProductOpen" />
  `,
})
export class ProductListPageComponent implements LeaveGuarded {
  readonly #session = inject(AdminSession);
  readonly #toast = inject(ToastService);
  readonly #abort = abortOnDestroy();

  readonly listRoute = injectProductListRoute();
  readonly query = this.listRoute.query;
  readonly products = listProductsResource(() => ({ query: productListRequest(this.query()) }));
  readonly page = computed(() => (this.products.hasValue() ? this.products.value() : undefined));
  readonly items = computed(() => this.page()?.items ?? []);
  readonly loadProblem = computed(() => {
    const error = this.products.error();
    return error ? toProblem(error) : null;
  });
  readonly searchError = computed(() => searchError(this.loadProblem()));
  readonly emptyMessage = computed(() => emptyListMessage(this.query()));

  readonly eligibility = getStoreEligibilityResource(() => {
    const storeId = this.#session.storeId();
    return storeId ? { path: { storeId } } : undefined;
  });
  /** Só a resposta do servidor liga o aviso; sem ela, como numa falha da consulta, a tela segue sem aviso. */
  readonly storeNotApproved = computed(
    () => this.eligibility.hasValue() && this.eligibility.value()?.canPublish === false,
  );

  readonly selection = new ListSelection<ProductResponse>({ id: (product) => product.id ?? '' });
  readonly batchActions = computed(() => this.selection.batchActions(PRODUCT_BATCH_PRESENTATIONS));
  readonly running = signal<ProductBatchAction | null>(null);
  readonly failures = signal<readonly BatchFailure[]>([]);
  readonly batchProblem = signal<Problem | null>(null);

  readonly runner = new ProductActionRunner();
  readonly confirming = signal<PendingConfirmation | null>(null);
  readonly returnFocus = signal<HTMLElement | null>(null);
  readonly confirmTitle = computed(() => {
    const pending = this.confirming();
    if (!pending) {
      return '';
    }
    const confirmation = pending.action.confirmation;
    return pending.kind === 'item'
      ? (confirmation?.title ?? pending.action.label)
      : `${confirmation?.confirmLabel ?? pending.action.label} ${plural(pending.count, 'produto', 'produtos')}`;
  });

  readonly newProductOpen = signal(false);
  readonly plusIcon = cilPlus;
  readonly menu = (product: ProductResponse) => serverActionMenu(product, LIST_ACTIONS);
  readonly leaveWarning = computed(() => leaveWarning({ batchRunning: this.running() !== null }));

  constructor() {
    warnBeforeUnload(this.leaveWarning);
    effect(() => {
      const page = this.page();
      if (page) {
        // WHY: o `setPage` lê e grava a seleção; fora do `untracked`, o efeito voltaria a rodar sem parar.
        untracked(() => this.selection.setPage(page.items ?? [], selectionScope(this.query())));
      }
    });
    effect(() => {
      this.query();
      untracked(() => this.#dismissAlerts());
    });
  }

  search(q: string): void {
    this.listRoute.navigate({ q: q || null, cursor: null });
  }

  openNewProduct(): void {
    this.newProductOpen.set(true);
  }

  openCreated(product: ProductResponse): void {
    if (product.id) {
      this.listRoute.open([product.id, 'conteudo']);
    } else {
      this.products.reload();
    }
  }

  onAction({ product, choice }: ProductRowAction): void {
    const action = productActionPresentation(choice.id);
    if (!action) {
      return;
    }
    if (action.code === 'EDIT') {
      this.listRoute.open([product.id, 'conteudo']);
      return;
    }
    if (action.confirmation) {
      this.returnFocus.set(choice.toggle);
      this.confirming.set({ kind: 'item', product, action });
      return;
    }
    void this.#runItem(product, action);
  }

  startBatch(id: string): void {
    const action = productActionPresentation(id);
    if (!action) {
      return;
    }
    if (action.confirmation) {
      this.returnFocus.set(null);
      this.confirming.set({ kind: 'batch', action, count: this.selection.countFor(id) });
      return;
    }
    void this.#runBatch(action);
  }

  async confirm(): Promise<void> {
    const pending = this.confirming();
    if (!pending) {
      return;
    }
    if (pending.kind === 'batch') {
      this.confirming.set(null);
      await this.#runBatch(pending.action);
      return;
    }
    await this.#runItem(pending.product, pending.action);
    this.confirming.set(null);
  }

  reloadAfterConflict(): void {
    this.runner.dismiss();
    this.products.reload();
  }

  async #runItem(product: ProductResponse, action: ProductActionPresentation): Promise<void> {
    this.#dismissBatchAlerts();
    if (await this.runner.run(product, action)) {
      this.products.reload();
    }
  }

  async #runBatch(presentation: ProductActionPresentation): Promise<void> {
    const action = presentation.code as ProductBatchAction;
    const items = this.selection.accepting(action);
    const ignored = this.selection.count() - items.length;
    this.#dismissAlerts();
    this.running.set(action);
    const run = await runBatch({
      items,
      id: (product) => product.id ?? '',
      size: BATCH_SIZE,
      signal: this.#abort,
      send: (group) =>
        apiData(
          batchProducts({
            body: {
              action,
              items: group.map((product) => ({ id: product.id ?? '', version: product.version })),
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
        (product) => product.id ?? '',
        (product) => product.title ?? '',
      ),
    );
    this.batchProblem.set(run.error ?? null);
    const counts = batchRunCounts(run, ignored);
    const summary = batchSummary(counts, presentation.done);
    if (counts.failed) {
      this.#toast.warning(summary);
    } else {
      this.#toast.success(summary);
    }
    this.products.reload();
  }

  #dismissAlerts(): void {
    this.runner.dismiss();
    this.#dismissBatchAlerts();
  }

  #dismissBatchAlerts(): void {
    this.failures.set([]);
    this.batchProblem.set(null);
  }
}
