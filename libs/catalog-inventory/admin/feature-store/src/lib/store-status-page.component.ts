import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import {
  BadgeComponent,
  CalloutComponent,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  RowComponent,
} from '@coreui/angular';
import { getStoreEligibilityResource } from '@marketplace/catalog-inventory-admin-data-access';
import {
  AdminSession,
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
} from '@marketplace/shared-admin-ui';
import { toProblem } from '@marketplace/shared-web-util';

import { sellerStatusLook, storeEffects } from './store-situation';

/**
 * Situação da loja no KYB/KYC (`/loja/situacao`): callout com o status de `GET /stores/{storeId}/eligibility`. A tela
 * guarda só o rótulo e a cor do status; o efeito vem de `canPublish` e `visibleInStorefront`, que o servidor calcula.
 */
@Component({
  selector: 'app-store-status-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    CalloutComponent,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    RowComponent,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <c-row class="g-2 mb-3">
          <c-col sm="5">
            <h1 class="h4 card-title mb-0">Situação da loja</h1>
            <div class="small text-body-secondary">{{ subtitle() }}</div>
          </c-col>
        </c-row>
        @if (!storeId()) {
          <app-empty-state message="Sua sessão não tem uma loja. Peça acesso ao administrador da plataforma." />
        } @else if (problem(); as problem) {
          <app-error-state
            (retry)="eligibility.reload()"
            [problem]="problem"
            message="Não foi possível carregar a situação da loja."
          />
        } @else if (situation(); as situation) {
          <c-callout [color]="situation.look.color" class="mt-0 mb-0">
            <div class="mb-2">
              <c-badge [color]="situation.look.color">{{ situation.look.label }}</c-badge>
            </div>
            @for (effect of situation.effects; track effect; let last = $last) {
              <p [class.mb-0]="last" [class.mb-1]="!last">{{ effect }}</p>
            }
          </c-callout>
        } @else {
          <app-loading-state [rows]="3" label="Carregando a situação da loja…" />
        }
      </c-card-body>
    </c-card>
  `,
})
export class StoreStatusPageComponent {
  readonly #session = inject(AdminSession);

  readonly storeId = this.#session.storeId;

  readonly eligibility = getStoreEligibilityResource(() => {
    const storeId = this.storeId();
    return storeId ? { path: { storeId } } : undefined;
  });

  readonly subtitle = computed(() => {
    const storeId = this.storeId();
    return storeId ? `KYB/KYC da loja ${storeId.slice(0, 8)}` : 'KYB/KYC da loja';
  });

  readonly problem = computed(() => {
    const error = this.eligibility.error();
    return error ? toProblem(error) : undefined;
  });

  readonly situation = computed(() => {
    if (!this.eligibility.hasValue()) {
      return undefined;
    }
    const eligibility = this.eligibility.value();
    return { look: sellerStatusLook(eligibility.status), effects: storeEffects(eligibility) };
  });
}
