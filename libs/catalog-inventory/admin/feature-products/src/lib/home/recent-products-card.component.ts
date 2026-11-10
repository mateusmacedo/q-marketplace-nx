import { ChangeDetectionStrategy, Component, computed, input, resource } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AvatarComponent,
  CardBodyComponent,
  CardComponent,
  CardHeaderComponent,
  ProgressComponent,
  TableDirective,
} from '@coreui/angular';
import { cilTags } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  listCategories,
  listProductsResource,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  ProductStatusBadgeComponent,
  productStatusLook,
} from '@marketplace/catalog-inventory-admin-ui';
import {
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  MoneyPipe,
  RelativeTimePipe,
} from '@marketplace/shared-admin-ui';
import { apiData, formatInteger, toProblem } from '@marketplace/shared-web-util';

import { type HomeLinks, productsLink } from './home-links';
import { type RecentProductRow, recentProductRow } from './recent-products';

/** Linhas da tabela, como as 6 da tabela de usuários do dashboard do template. */
const RECENT_PRODUCTS = 6;

/**
 * Tabela dos produtos atualizados por último (`GET /products?sort=-updatedAt`), a tabela de usuários do dashboard do
 * template: avatar com a inicial e o ponto da cor do status, produto com categoria e SKUs, status, estoque disponível
 * sobre o físico com barra, menor preço vigente e "Atualizado há…". O título abre o produto na lista que a sessão tem.
 */
@Component({
  selector: 'app-recent-products-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'd-block' },
  imports: [
    AvatarComponent,
    CardBodyComponent,
    CardComponent,
    CardHeaderComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    IconDirective,
    LoadingStateComponent,
    MoneyPipe,
    ProductStatusBadgeComponent,
    ProgressComponent,
    RelativeTimePipe,
    RouterLink,
    TableDirective,
  ],
  styles: `
    .numeric {
      font-variant-numeric: tabular-nums;
    }
  `,
  template: `
    <c-card class="mb-4">
      <c-card-header>Produtos atualizados por último</c-card-header>
      <c-card-body>
        @if (problem(); as problem) {
          <app-error-state
            (retry)="products.reload()"
            [problem]="problem"
            message="Não foi possível carregar os produtos."
          />
        } @else if (!products.hasValue()) {
          <app-loading-state label="Carregando os produtos…" />
        } @else if (!rows().length) {
          <app-empty-state message="Nenhum produto cadastrado ainda." />
        } @else {
          <table [hover]="true" [responsive]="true" [striped]="true" align="middle" cTable class="mb-0 border">
            <thead class="text-nowrap text-truncate">
              <tr>
                <th class="bg-body-tertiary text-center">
                  <svg [cIcon]="tagsIcon" aria-hidden="true"></svg>
                  <span class="visually-hidden">Inicial</span>
                </th>
                <th class="bg-body-tertiary">Produto</th>
                <th class="bg-body-tertiary text-center">Status</th>
                <th class="bg-body-tertiary">Estoque</th>
                <th class="bg-body-tertiary text-end">Preço</th>
                <th class="bg-body-tertiary">Atividade</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.id) {
                <tr>
                  <td class="text-center">
                    <c-avatar
                      [status]="statusColor(row.status)"
                      aria-hidden="true"
                      class="bg-body-secondary text-body-secondary fw-semibold"
                      size="md"
                    >
                      {{ row.initial }}
                    </c-avatar>
                  </td>
                  <td>
                    <div>
                      <a [routerLink]="[productsUrl(), row.id]">{{ row.title }}</a>
                    </div>
                    <div class="small text-body-secondary text-nowrap">
                      <span>{{ detailText(row) }}</span>
                    </div>
                  </td>
                  <td class="text-center">
                    <app-product-status-badge [status]="row.status" />
                  </td>
                  <td>
                    <div class="d-flex justify-content-between">
                      <div class="float-start numeric">
                        <strong>{{ availableText(row) }}</strong>
                      </div>
                      <div class="float-end ms-1 text-nowrap numeric">
                        <small class="text-body-secondary">de {{ formatCount(row.onHand) }} no físico</small>
                      </div>
                    </div>
                    <c-progress
                      [color]="row.stockColor"
                      [value]="row.availablePercent"
                      aria-label="Disponível sobre o físico"
                      thin
                    />
                  </td>
                  <td class="text-end text-nowrap numeric">
                    @if (row.lowestPrice; as price) {
                      <strong>{{ price.amount | money: price.currency }}</strong>
                    } @else {
                      <span class="text-body-secondary">Sem preço</span>
                    }
                  </td>
                  <td>
                    <div class="small text-body-secondary">Atualizado</div>
                    <div class="fw-semibold text-nowrap">{{ row.updatedAt | relativeTime }}</div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </c-card-body>
    </c-card>
  `,
})
export class RecentProductsCardComponent {
  readonly links = input.required<HomeLinks>();

  protected readonly tagsIcon = cilTags;

  readonly productsUrl = computed(() => productsLink(this.links()));

  readonly products = listProductsResource(() =>
    this.productsUrl() ? { query: { sort: '-updatedAt', limit: RECENT_PRODUCTS } } : undefined,
  );

  readonly problem = computed(() => {
    const error = this.products.error();
    return error ? toProblem(error) : undefined;
  });

  readonly #items = computed(() =>
    this.products.hasValue() ? (this.products.value().items ?? []) : [],
  );

  readonly #categoryIds = computed(
    () => [
      ...new Set(
        this.#items().flatMap((product) => (product.categoryId ? [product.categoryId] : [])),
      ),
    ],
    {
      equal: (left, right) =>
        left.length === right.length && left.every((id, index) => id === right[index]),
    },
  );

  /**
   * Nome de cada categoria da página, numa chamada só (`GET /categories?ids=`). Sem a resposta, ou sem a categoria
   * nela, a linha fica sem o nome e mostra só os SKUs.
   */
  readonly #categoryNames = resource({
    params: () => (this.#categoryIds().length ? this.#categoryIds() : undefined),
    loader: async ({ params }) => {
      const page = await apiData(listCategories({ query: { ids: params } }));
      return new Map(
        (page.items ?? []).flatMap((category) =>
          category.id ? [[category.id, category.name] as const] : [],
        ),
      );
    },
  });

  readonly rows = computed<RecentProductRow[]>(() => {
    const names = this.#categoryNames.hasValue() ? this.#categoryNames.value() : undefined;
    return this.#items().map((product) =>
      recentProductRow(product, product.categoryId ? names?.get(product.categoryId) : undefined),
    );
  });

  protected statusColor(status: string): string {
    return productStatusLook(status).color;
  }

  protected detailText(row: RecentProductRow): string {
    const skus = `${formatInteger(row.skuCount)} ${row.skuCount === 1 ? 'SKU' : 'SKUs'}`;
    return row.category ? `${row.category} | ${skus}` : skus;
  }

  protected availableText(row: RecentProductRow): string {
    return `${formatInteger(row.available)} ${row.available === 1 ? 'disponível' : 'disponíveis'}`;
  }

  protected formatCount(value: number): string {
    return formatInteger(value);
  }
}
