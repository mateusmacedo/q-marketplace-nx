import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import {
  BadgeComponent,
  CardBodyComponent,
  CardComponent,
  ListGroupDirective,
  ListGroupItemDirective
} from '@coreui/angular';
import { productHistoryResource, type StatusChangeResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { productStatusLabel, transitionActorLabel } from '@marketplace/catalog-inventory-admin-ui';
import {
  DateTimePipe,
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  RelativeTimePipe
} from '@marketplace/shared-admin-ui';
import { toProblem } from '@marketplace/shared-web-util';

import { moderationDecision, newestFirst, transitionLabel } from './product-history';
import { ProductStore } from './product-store';

/**
 * Aba Histórico do produto: as transições de `GET /products/{id}/history` (de, para, quem e quando), da mais recente
 * para a mais antiga, no `list-group` do template. A rejeição e o bloqueio mostram em destaque os motivos marcados, com
 * os rótulos da época (`reasonLabels`), e o complemento (`note`), separados e sem interpretar o texto derivado `reason`.
 */
@Component({
  selector: 'app-product-history-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    CardBodyComponent,
    CardComponent,
    DateTimePipe,
    EmptyStateComponent,
    ErrorStateComponent,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    RelativeTimePipe
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <h2 class="h4 card-title mb-0">Histórico</h2>
        <div class="small text-body-secondary mb-3">Mudanças de status e decisões de moderação, da mais recente à mais antiga.</div>
        @if (problem(); as problem) {
          <app-error-state (retry)="history.reload()" [problem]="problem" message="Não foi possível carregar o histórico." />
        } @else if (history.hasValue()) {
          @if (entries().length) {
            <ul cListGroup>
              @for (entry of entries(); track $index) {
                <li [color]="highlight(entry)" cListGroupItem>
                  <div class="d-flex flex-wrap justify-content-between gap-2">
                    <div>
                      <span class="fw-semibold">{{ label(entry) }}</span>
                      @if (entry.from && entry.to && entry.from !== entry.to) {
                        <span class="text-body-secondary">
                          de {{ statusLabel(entry.from) }} para {{ statusLabel(entry.to) }}
                        </span>
                      } @else if (entry.to) {
                        <span class="text-body-secondary">
                          em {{ statusLabel(entry.to) }}
                        </span>
                      }
                    </div>
                    <div class="small text-body-secondary text-nowrap">
                      {{ entry.at | dateTime }} ({{ entry.at | relativeTime }})
                    </div>
                  </div>
                  @if (actor(entry); as actor) {
                    <div class="small text-body-secondary">por {{ actor }}</div>
                  }
                  @if (decision(entry); as decision) {
                    <div class="mt-2">
                      @if (decision.labels.length) {
                        <div class="small fw-semibold">Motivos</div>
                        <div class="d-flex flex-wrap gap-1 mb-1">
                          @for (reason of decision.labels; track $index) {
                            <c-badge color="secondary">{{ reason }}</c-badge>
                          }
                        </div>
                      }
                      @if (decision.note) {
                        <div class="small fw-semibold">Complemento do moderador</div>
                        <p class="mb-0">{{ decision.note }}</p>
                      }
                      @if (!decision.labels.length && !decision.note) {
                        <div class="small">A moderação não registrou o motivo.</div>
                      }
                    </div>
                  }
                </li>
              }
            </ul>
          } @else {
            <app-empty-state message="Nenhuma mudança registrada." />
          }
        } @else {
          <app-loading-state [rows]="4" label="Carregando o histórico…" />
        }
      </c-card-body>
    </c-card>
  `
})
export class ProductHistoryTabComponent {
  readonly store = inject(ProductStore);

  /** Relê o histórico quando a versão do produto muda, como depois de uma ação no cabeçalho. */
  readonly #id = computed(() => this.store.product()?.id);
  readonly history = productHistoryResource(() => {
    const id = this.#id();
    this.store.version();
    return id ? { path: { id } } : undefined;
  });
  readonly problem = computed(() => {
    const error = this.history.error();
    return error ? toProblem(error) : null;
  });
  readonly entries = computed(() => (this.history.hasValue() ? newestFirst(this.history.value()) : []));

  readonly statusLabel = productStatusLabel;
  readonly label = (entry: StatusChangeResponse) => transitionLabel(entry.transition);
  readonly decision = moderationDecision;
  readonly actor = transitionActorLabel;

  /** Cor do item: a rejeição e o bloqueio em destaque. */
  highlight(entry: StatusChangeResponse): string | undefined {
    return entry.transition === 'BLOCK' ? 'danger' : entry.transition === 'REJECT' ? 'warning' : undefined;
  }
}
