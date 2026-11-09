import { ChangeDetectionStrategy, Component, computed, effect, linkedSignal, signal, untracked } from '@angular/core';
import {
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  FormControlDirective,
  FormTextDirective,
  InputGroupComponent,
  InputGroupTextDirective
} from '@coreui/angular';
import { listProductsResource, type ProductResponse, UUIDSchema } from '@marketplace/catalog-inventory-admin-data-access';
import {
  CursorPaginationComponent,
  EditConflictAlertComponent,
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  ProblemAlertComponent,
  serverActionMenu
} from '@marketplace/shared-admin-ui';
import { checkFieldRules, ruleMessage, toProblem } from '@marketplace/shared-web-util';

import { productActionPresentation, productActionPresentations } from '../product-actions';
import { ProductActionRunner } from '../product-list/product-action-runner';
import { ProductListFiltersComponent } from '../product-list/product-list-filters.component';
import { emptyListMessage, productListRequest, searchError } from '../product-list/product-list-query';
import { injectProductListRoute } from '../product-list/product-list-route';
import { type ProductRowAction, ProductTableComponent } from '../product-list/product-table.component';

const PLATFORM_ACTIONS = productActionPresentations('platform');

/** Formato do ID da loja, lido do contrato (`UUID`), para avisar antes de filtrar; o 400 continua decidindo. */
const STORE_ID_RULES = { required: false, pattern: UUIDSchema.pattern } as const;

/**
 * Produtos de todas as lojas (`/plataforma/produtos`, proposta de design, tela 10): a mesma lista de Produtos da loja,
 * só leitura, com o filtro Loja por ID e a coluna com o início do UUID. O menu "..." mostra o que o servidor devolve em
 * `actions` para quem consulta, como o Desbloquear do moderador.
 */
@Component({
  selector: 'app-platform-product-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    CursorPaginationComponent,
    EditConflictAlertComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    FormControlDirective,
    FormTextDirective,
    InputGroupComponent,
    InputGroupTextDirective,
    LoadingStateComponent,
    ProblemAlertComponent,
    ProductListFiltersComponent,
    ProductTableComponent
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <div class="mb-3">
          <h1 class="h4 card-title mb-0">Produtos de todas as lojas</h1>
          <div class="small text-body-secondary">Consulta dos produtos de todas as lojas do catálogo.</div>
        </div>

        @if (runner.conflict()) {
          <app-edit-conflict-alert (reload)="reloadAfterConflict()" [busy]="products.isLoading()" />
        }
        @if (runner.failure(); as failure) {
          <app-problem-alert [heading]="failure.heading" [problem]="failure.problem" />
        }

        <app-product-list-filters
          (searched)="search($event)"
          [error]="searchError()"
          [q]="query().q"
          [status]="query().status"
        >
          <form (submit)="filterStore($event)" class="col-md-6 col-xl-5" novalidate>
            <c-input-group>
              <label cInputGroupText for="platform-store-filter">Loja</label>
              <input
                (input)="typedStore($event)"
                [attr.aria-describedby]="storeError() ? 'platform-store-filter-error' : 'platform-store-filter-help'"
                [valid]="storeError() ? false : undefined"
                [value]="storeDraft()"
                autocomplete="off"
                cFormControl
                class="font-monospace"
                id="platform-store-filter"
                name="storeId"
                placeholder="ID completo da loja (UUID)"
                type="text"
              />
              <button cButton color="secondary" type="submit" variant="outline">Filtrar</button>
              @if (query().storeId) {
                <button (click)="clearStore()" cButton color="secondary" type="button" variant="outline">Todas</button>
              }
            </c-input-group>
            @if (storeError(); as error) {
              <div class="invalid-feedback d-block" id="platform-store-filter-error">{{ error }}</div>
            } @else {
              <div cFormText id="platform-store-filter-help">Vazio mostra os produtos de todas as lojas.</div>
            }
          </form>
        </app-product-list-filters>

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
            }
          </app-empty-state>
        } @else {
          <app-product-table
            (action)="onAction($event)"
            (sortChange)="listRoute.navigate({ sort: $event, cursor: null })"
            [busyId]="runner.busyId()"
            [menu]="menu"
            [products]="items()"
            [showStore]="true"
            [sort]="query().sort"
          />
        }

        @if (!loadProblem()) {
          <app-cursor-pagination
            (cursorChange)="listRoute.navigate({ cursor: $event ?? null })"
            (limitChange)="listRoute.navigate({ limit: $event, cursor: null })"
            [cursor]="query().cursor"
            [disabled]="products.isLoading()"
            [limit]="query().limit"
            [nextCursor]="page()?.nextCursor"
          />
        }
      </c-card-body>
    </c-card>
  `
})
export class PlatformProductListPageComponent {
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

  readonly storeDraft = linkedSignal(() => this.query().storeId ?? '');
  readonly storeError = signal<string | null>(null);

  readonly runner = new ProductActionRunner();
  readonly menu = (product: ProductResponse) => serverActionMenu(product, PLATFORM_ACTIONS);

  constructor() {
    effect(() => {
      this.query();
      untracked(() => {
        this.runner.dismiss();
        this.storeError.set(null);
      });
    });
  }

  search(q: string): void {
    this.listRoute.navigate({ q: q || null, cursor: null });
  }

  typedStore(event: Event): void {
    this.storeDraft.set((event.target as HTMLInputElement).value);
    this.storeError.set(null);
  }

  filterStore(event: Event): void {
    event.preventDefault();
    const storeId = this.storeDraft().trim();
    const violation = checkFieldRules(STORE_ID_RULES, storeId);
    if (violation) {
      this.storeError.set(`${ruleMessage(violation)} Cole o ID completo da loja.`);
      return;
    }
    this.listRoute.navigate({ storeId: storeId || null, cursor: null });
  }

  clearStore(): void {
    this.storeDraft.set('');
    this.listRoute.navigate({ storeId: null, cursor: null });
  }

  onAction({ product, choice }: ProductRowAction): void {
    const action = productActionPresentation(choice.id);
    if (action) {
      void this.runner.run(product, action).then(done => done && this.products.reload());
    }
  }

  reloadAfterConflict(): void {
    this.runner.dismiss();
    this.products.reload();
  }
}
