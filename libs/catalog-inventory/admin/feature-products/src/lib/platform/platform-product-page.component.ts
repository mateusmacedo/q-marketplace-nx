import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import {
  CardBodyComponent,
  CardComponent,
  TabDirective,
  TabPanelComponent,
  TabsComponent,
  TabsContentComponent,
  TabsListComponent
} from '@coreui/angular';
import { ErrorStateComponent, LoadingStateComponent } from '@marketplace/shared-admin-ui';
import { map } from 'rxjs';

import { ProductContentTabComponent } from '../product/product-content-tab.component';
import { ProductHeaderComponent } from '../product/product-header.component';
import { ProductHistoryTabComponent } from '../product/product-history-tab.component';
import { ProductSkusTabComponent } from '../product/product-skus-tab.component';
import { ProductStore } from '../product/product-store';
import { PRODUCT_TABS } from '../product/product-tabs';

/**
 * Produto de qualquer loja, só leitura (`/plataforma/produtos/:id`), para quem tem `can.readTenantCatalog`: o mesmo
 * detalhe da loja, com as abas no componente de abas do template, e o menu com o que o servidor devolve em `actions`,
 * como o **Desbloquear** do moderador no produto bloqueado. Conteúdo, SKUs, preço e estoque ficam só leitura porque o
 * servidor não oferece `EDIT`, `ADD_SKU`, `EDIT_OFFER` nem `EDIT_STOCK` a quem só lê o catálogo.
 */
@Component({
  selector: 'app-platform-product-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ProductStore],
  imports: [
    CardBodyComponent,
    CardComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    ProductContentTabComponent,
    ProductHeaderComponent,
    ProductHistoryTabComponent,
    ProductSkusTabComponent,
    TabDirective,
    TabPanelComponent,
    TabsComponent,
    TabsContentComponent,
    TabsListComponent
  ],
  template: `
    @if (store.product()) {
      <app-product-header [showPrimary]="false" [showStore]="true" />
      <c-tabs [(activeItemKey)]="activeTab">
        <c-tabs-list aria-label="Seções do produto" class="mb-3" variant="tabs">
          @for (tab of tabs; track tab.path) {
            <button [itemKey]="tab.path" cTab type="button">{{ tab.label }}</button>
          }
        </c-tabs-list>
        <c-tabs-content>
          <c-tab-panel itemKey="conteudo">
            @if (activeTab() === 'conteudo') {
              <app-product-content-tab />
            }
          </c-tab-panel>
          <c-tab-panel itemKey="skus">
            @if (activeTab() === 'skus') {
              <app-product-skus-tab />
            }
          </c-tab-panel>
          <c-tab-panel itemKey="historico">
            @if (activeTab() === 'historico') {
              <app-product-history-tab />
            }
          </c-tab-panel>
        </c-tabs-content>
      </c-tabs>
    } @else if (store.problem(); as problem) {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title mb-0">Produto</h1>
          <app-error-state (retry)="retry()" [problem]="problem" message="Não foi possível abrir o produto." />
        </c-card-body>
      </c-card>
    } @else {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title mb-0">Produto</h1>
          <app-loading-state [rows]="3" label="Carregando o produto…" />
        </c-card-body>
      </c-card>
    }
  `
})
export class PlatformProductPageComponent {
  readonly store = inject(ProductStore);
  readonly #route = inject(ActivatedRoute);

  readonly tabs = PRODUCT_TABS;
  readonly activeTab = signal<string | number>('conteudo');
  readonly #id = toSignal(this.#route.paramMap.pipe(map(params => params.get('id'))), { initialValue: null });

  constructor() {
    effect(() => {
      const id = this.#id();
      if (id) {
        untracked(() => void this.store.load(id));
      }
    });
  }

  retry(): void {
    const id = this.#id();
    if (id) {
      void this.store.load(id);
    }
  }
}
