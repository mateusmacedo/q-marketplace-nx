import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BadgeComponent, FormCheckInputDirective, SpinnerComponent, TableDirective, TooltipDirective } from '@coreui/angular';
import type { ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { ProductStatusBadgeComponent } from '@marketplace/catalog-inventory-admin-ui';
import {
  type ActionMenuChoice,
  ActionMenuComponent,
  type ActionMenuItem,
  DateTimePipe,
  type ListSelection,
  RelativeTimePipe,
  SortHeaderComponent
} from '@marketplace/shared-admin-ui';

import { rejectionSummary, shortId } from './product-list-query';

/** Ação escolhida no menu "..." de uma linha. */
export interface ProductRowAction {
  readonly product: ProductResponse;
  readonly choice: ActionMenuChoice;
}

/**
 * Tabela das listas de produtos, na marcação da tabela de usuários do dashboard do template: título como link, status
 * com o badge Rejeitado, SKUs, datas e o menu "..." com o que o servidor devolve em `actions`. As duas listas usam a
 * mesma tabela; a da loja liga a seleção em lote, e a da plataforma, a coluna Loja.
 */
@Component({
  selector: 'app-product-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ActionMenuComponent,
    BadgeComponent,
    DateTimePipe,
    FormCheckInputDirective,
    ProductStatusBadgeComponent,
    RelativeTimePipe,
    RouterLink,
    SortHeaderComponent,
    SpinnerComponent,
    TableDirective,
    TooltipDirective
  ],
  styles: '.tabular-nums { font-variant-numeric: tabular-nums; }',
  template: `
    <div class="table-responsive">
      <table [hover]="true" [striped]="true" align="middle" cTable class="mb-0 border">
        <thead class="text-nowrap text-truncate">
          <tr>
            @if (selection(); as selected) {
              <th class="bg-body-tertiary" scope="col">
                <input
                  (change)="selected.togglePage()"
                  [checked]="selected.pageChecked()"
                  [disabled]="selectionDisabled()"
                  [indeterminate]="selected.pageIndeterminate()"
                  aria-label="Selecionar a página"
                  cFormCheckInput
                  type="checkbox"
                />
              </th>
            }
            <th (sortChange)="sortChange.emit($event)" [sort]="sort()" app-sort-header="title" class="bg-body-tertiary" scope="col">
              Produto
            </th>
            @if (showStore()) {
              <th class="bg-body-tertiary" scope="col">Loja</th>
            }
            <th class="bg-body-tertiary" scope="col">Status</th>
            <th class="bg-body-tertiary text-end" scope="col">SKUs</th>
            <th
              (sortChange)="sortChange.emit($event)"
              [sort]="sort()"
              app-sort-header="createdAt"
              class="bg-body-tertiary d-none d-xl-table-cell"
              firstDirection="desc"
              scope="col"
            >
              Criado
            </th>
            <th
              (sortChange)="sortChange.emit($event)"
              [sort]="sort()"
              app-sort-header="updatedAt"
              class="bg-body-tertiary"
              firstDirection="desc"
              scope="col"
            >
              Atualizado
            </th>
            <th class="bg-body-tertiary" scope="col"><span class="visually-hidden">Ações</span></th>
          </tr>
        </thead>
        <tbody>
          @for (product of products(); track product.id) {
            <tr>
              @if (selection(); as selected) {
                <td>
                  <input
                    (change)="selected.toggle(product)"
                    [attr.aria-label]="'Selecionar ' + product.title"
                    [checked]="selected.isSelected(product)"
                    [disabled]="selectionDisabled()"
                    cFormCheckInput
                    type="checkbox"
                  />
                </td>
              }
              <td>
                <a [routerLink]="[product.id]" class="fw-semibold">{{ product.title }}</a>
              </td>
              @if (showStore()) {
                <td class="text-nowrap">
                  <span [cTooltip]="product.storeId ?? ''" class="font-monospace" tabindex="0">
                    <span aria-hidden="true">{{ shortStore(product) }}</span>
                    <span class="visually-hidden">{{ product.storeId }}</span>
                  </span>
                </td>
              }
              <td class="text-nowrap">
                <app-product-status-badge [status]="product.status ?? ''" />
                @if (rejections().get(product.id ?? ''); as rejection) {
                  <c-badge [cTooltip]="rejection" class="ms-1" color="danger" tabindex="0">
                    Rejeitado<span class="visually-hidden">. {{ rejection }}</span>
                  </c-badge>
                }
              </td>
              <td class="text-end">
                <strong class="tabular-nums">{{ product.skus?.length ?? 0 }}</strong>
              </td>
              <td class="d-none d-xl-table-cell text-nowrap">
                <div class="small text-body-secondary">{{ product.createdAt | relativeTime }}</div>
                <div class="fw-semibold tabular-nums">{{ product.createdAt | dateTime: 'short' }}</div>
              </td>
              <td class="text-nowrap">
                <div class="small text-body-secondary">{{ product.updatedAt | relativeTime }}</div>
                <div class="fw-semibold tabular-nums">{{ product.updatedAt | dateTime: 'short' }}</div>
              </td>
              <td class="text-end text-nowrap">
                @if (busyId() === product.id) {
                  <c-spinner class="me-2" label="Gravando" size="sm" />
                }
                <app-action-menu
                  (action)="action.emit({ product, choice: $event })"
                  [disabled]="menuDisabled() || busyId() === product.id"
                  [items]="menus().get(product.id ?? '') ?? []"
                  [label]="'Ações de ' + product.title"
                />
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `
})
export class ProductTableComponent {
  readonly products = input.required<readonly ProductResponse[]>();
  /** Ordem atual, como está na URL (`-updatedAt`). */
  readonly sort = input.required<string>();
  /** Itens do menu "..." de cada produto, montados do que o servidor devolveu (`serverActionMenu`). */
  readonly menu = input.required<(product: ProductResponse) => ActionMenuItem[]>();
  /** Seleção em lote; sem ela, a tabela não mostra as caixas. */
  readonly selection = input<ListSelection<ProductResponse> | null>(null);
  /** Trava as caixas durante o lote. */
  readonly selectionDisabled = input(false);
  /** Mostra a coluna Loja, com o início do UUID e o completo no tooltip. */
  readonly showStore = input(false);
  /** Produto com a ação em andamento. */
  readonly busyId = input<string | null>(null);
  /** Trava todos os menus, como durante o lote. */
  readonly menuDisabled = input(false);

  readonly sortChange = output<string>();
  readonly action = output<ProductRowAction>();

  readonly menus = computed(() => {
    const menu = this.menu();
    return new Map(this.products().map(product => [product.id ?? '', menu(product)]));
  });
  readonly rejections = computed(
    () => new Map(this.products().map(product => [product.id ?? '', rejectionSummary(product.lastRejection)]))
  );

  shortStore(product: ProductResponse): string {
    return shortId(product.storeId);
  }
}
