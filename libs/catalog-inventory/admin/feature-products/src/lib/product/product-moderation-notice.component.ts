import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { AlertComponent, AlertHeadingDirective, ButtonDirective } from '@coreui/angular';
import { productHistoryResource, type ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { DateTimePipe } from '@marketplace/shared-admin-ui';
import { type Problem, problemMessages, toProblem } from '@marketplace/shared-web-util';

import { decisionOf, lastTransition, type ModerationDecision } from './product-history';

interface Notice {
  readonly color: 'warning' | 'danger';
  readonly heading: string;
  readonly at: string | undefined;
  readonly decision: ModerationDecision;
}

/**
 * Motivo da última decisão de moderação em destaque no topo do produto: a rejeição que devolveu o rascunho
 * (`lastRejection`) ou, no produto bloqueado, o último bloqueio do histórico. Mostra os rótulos da época e o
 * complemento separados, sem interpretar o texto único `reason`. Se o histórico não carregar, avisa a falha com
 * Tentar de novo, para o bloqueio não aparecer sem motivo.
 */
@Component({
  selector: 'app-product-moderation-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent, AlertHeadingDirective, ButtonDirective, DateTimePipe],
  template: `
    @if (notice(); as notice) {
      <c-alert [color]="notice.color" role="status">
        <h2 cAlertHeading class="h6">
          {{ notice.heading }}
          @if (notice.at) {
            <span class="fw-normal">em {{ notice.at | dateTime }}</span>
          }
        </h2>
        @if (notice.decision.labels.length) {
          <p class="mb-1">Motivos: {{ notice.decision.labels.join('; ') }}</p>
        }
        @if (notice.decision.note) {
          <p class="mb-0">Complemento do moderador: {{ notice.decision.note }}</p>
        }
        @if (!notice.decision.labels.length && !notice.decision.note) {
          <p class="mb-0">A moderação não registrou o motivo.</p>
        }
      </c-alert>
    } @else if (historyProblem(); as problem) {
      <c-alert color="danger" role="alert">
        <h2 cAlertHeading class="h6">Bloqueado pela moderação</h2>
        <p class="mb-1">Não foi possível carregar o motivo do bloqueio.</p>
        @if (problemDetail()) {
          <p class="small mb-1">{{ problemDetail() }}</p>
        }
        <button (click)="retry()" cButton color="danger" size="sm" type="button" variant="outline">Tentar de novo</button>
      </c-alert>
    }
  `
})
export class ProductModerationNoticeComponent {
  readonly product = input<ProductResponse | null | undefined>();

  readonly #blocked = computed(() => this.product()?.status === 'BLOCKED');
  readonly #id = computed(() => this.product()?.id);
  readonly #history = productHistoryResource(() => {
    const id = this.#id();
    return id && this.#blocked() ? { path: { id } } : undefined;
  });

  readonly notice = computed<Notice | null>(() => {
    const product = this.product();
    if (!product) {
      return null;
    }
    if (this.#blocked()) {
      const block = this.#history.hasValue() ? lastTransition(this.#history.value(), 'BLOCK') : undefined;
      return block
        ? {
          color: 'danger',
          heading: 'Bloqueado pela moderação',
          at: block.at,
          decision: decisionOf(block.reasonLabels, block.note)
        }
        : null;
    }
    const rejection = product.lastRejection;
    return rejection
      ? {
        color: 'warning',
        heading: 'Rejeitado na moderação',
        at: rejection.at,
        decision: decisionOf(rejection.reasonLabels, rejection.note)
      }
      : null;
  });

  /** Falha ao ler o histórico do produto bloqueado, onde está o motivo do bloqueio. */
  readonly historyProblem = computed<Problem | null>(() => {
    const error = this.#blocked() ? this.#history.error() : undefined;
    return error ? toProblem(error) : null;
  });
  readonly problemDetail = computed(() => {
    const problem = this.historyProblem();
    return problem ? problemMessages(problem).join(' ') : '';
  });

  retry(): void {
    this.#history.reload();
  }
}
