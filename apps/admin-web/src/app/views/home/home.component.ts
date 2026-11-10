import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, resource } from '@angular/core';
import { CardBodyComponent, CardComponent } from '@coreui/angular';
import {
  AdminSession,
  allowsAccess,
  ErrorStateComponent,
  LoadingStateComponent,
  type SessionGrants,
} from '@marketplace/shared-admin-ui';

import { navItems } from '../../layout/default-layout/_nav';

/**
 * Página inicial (proposta de design, seção "1. Início"). Monta o painel do catálogo que `feature-products` exporta,
 * carregado sob demanda como as outras telas, com os endereços do menu que a sessão abre. O Chart.js só carrega nesta
 * rota.
 */
@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CardBodyComponent,
    CardComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    NgComponentOutlet,
  ],
  template: `
    <h1 class="visually-hidden">Início</h1>
    @if (dashboard.value(); as component) {
      <ng-container [ngComponentOutletInputs]="{ links: links() }" [ngComponentOutlet]="component" />
    } @else if (dashboard.error()) {
      <c-card>
        <c-card-body>
          <app-error-state (retry)="dashboard.reload()" message="Não foi possível abrir a página inicial." />
        </c-card-body>
      </c-card>
    } @else {
      <c-card>
        <c-card-body>
          <app-loading-state label="Carregando a página inicial…" />
        </c-card-body>
      </c-card>
    }
  `,
})
export class HomeComponent {
  readonly #session = inject(AdminSession);

  readonly dashboard = resource({
    loader: () =>
      import('@marketplace/catalog-inventory-admin-feature-products').then(
        (m) => m.HomeDashboardComponent,
      ),
  });

  /** Rotas do menu que a sessão abre, para os atalhos não levarem a uma tela barrada. */
  readonly links = computed(() => {
    const grants = this.#session.grants();
    return {
      storeProducts: allowedUrl(grants, '/loja/produtos'),
      tenantProducts: allowedUrl(grants, '/plataforma/produtos'),
      moderationQueue: allowedUrl(grants, '/moderacao/fila'),
      categories: allowedUrl(grants, '/plataforma/categorias'),
    };
  });
}

function allowedUrl(grants: SessionGrants, url: string): string | undefined {
  const item = navItems.find((entry) => entry.url === url);
  return item && allowsAccess(grants, item) ? url : undefined;
}
