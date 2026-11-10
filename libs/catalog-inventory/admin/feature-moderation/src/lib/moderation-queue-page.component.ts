import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  type OnInit,
  signal,
} from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import {
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  RowComponent,
} from '@coreui/angular';
import {
  batchModerateProducts,
  type ModerationDecision,
  ModerationBatchRequestSchema,
  type ProductBatchResult,
  type ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  abortOnDestroy,
  BatchBarComponent,
  type BatchActionPresentation,
  type BatchFailure,
  batchFailures,
  BatchResultAlertComponent,
  ConfirmModalComponent,
  CursorPaginationComponent,
  EmptyStateComponent,
  ErrorStateComponent,
  type LeaveGuarded,
  leaveWarning,
  LoadingStateComponent,
  ProblemAlertComponent,
  RelativeTimePipe,
  ToastService,
  warnBeforeUnload,
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  type BatchRun,
  batchRunCounts,
  batchSummary,
  fieldRules,
  plural,
  type Problem,
  runBatch,
} from '@marketplace/shared-web-util';

import {
  type DecisionReason,
  ModerationDecisionModalComponent,
} from './moderation-decision-modal.component';
import { decisionPresentation, DECISIONS } from './moderation-decisions';
import { ModerationQueueStore } from './moderation-queue.store';
import type { ReasonDecision } from './moderation-reasons';
import { shortId } from './product-summary';

type QueueBatchRun = BatchRun<ProductResponse, ProductBatchResult>;

const BATCH_SIZE = fieldRules(ModerationBatchRequestSchema, 'items').maxItems ?? 1;

/**
 * Fila de moderação (`/moderacao/fila`): os produtos de `GET /moderation/products`, os mais antigos na fila primeiro,
 * com o detalhe do item aberto ao lado (abaixo de `lg`, em telas separadas) e a seleção para decidir em lote por
 * `POST /moderation/products/batch`. Cada botão do lote conta os selecionados que trazem a decisão em `actions`.
 */
@Component({
  selector: 'app-moderation-queue-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ModerationQueueStore],
  imports: [
    BatchBarComponent,
    BatchResultAlertComponent,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    ConfirmModalComponent,
    CursorPaginationComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    ModerationDecisionModalComponent,
    ProblemAlertComponent,
    RelativeTimePipe,
    RouterLink,
    RouterOutlet,
    RowComponent,
  ],
  template: `
    <c-row>
      <c-col [class.d-none]="!!store.openId()" [lg]="5" [xl]="4" class="d-lg-block">
        <c-card class="mb-4">
          <c-card-body>
            <div class="mb-3">
              <h1 class="h4 card-title mb-0">Fila de produtos</h1>
              <div class="small text-body-secondary">Os mais antigos na fila primeiro</div>
            </div>
            <app-batch-bar
              (clear)="store.selection.clear()"
              (run)="startBatch($event)"
              [actions]="store.selection.batchActions(batchActions)"
              [running]="running()"
              [selectedCount]="store.selection.count()"
            />
            <app-batch-result-alert
              (dismiss)="failures.set([])"
              [failures]="failures()"
              title="Alguns produtos não foram decididos"
            />
            @if (batchProblem()) {
              <app-problem-alert [problem]="batchProblem()" heading="O lote parou antes do fim" />
            }
            @if (!store.loaded()) {
              <app-loading-state label="Carregando a fila…" />
            } @else if (store.problem()) {
              <app-error-state (retry)="store.reload()" [problem]="store.problem()" message="Não foi possível carregar a fila." />
            } @else if (!store.items().length) {
              <app-empty-state message="Nenhum produto aguardando moderação." />
            } @else {
              <c-form-check class="mb-2">
                <input
                  (change)="store.selection.togglePage()"
                  [checked]="store.selection.pageChecked()"
                  [disabled]="running() !== null"
                  [indeterminate]="store.selection.pageIndeterminate()"
                  cFormCheckInput
                  id="moderation-queue-select-page"
                  type="checkbox"
                />
                <label cFormCheckLabel for="moderation-queue-select-page">Selecionar a página</label>
              </c-form-check>
              <div [attr.aria-busy]="store.loading()" aria-label="Produtos na fila" cListGroup role="list">
                @for (product of store.items(); track product.id) {
                  <div [active]="product.id === store.openId()" cListGroupItem class="d-flex align-items-start gap-2" role="listitem">
                    <input
                      (change)="store.selection.toggle(product)"
                      [attr.aria-label]="'Selecionar ' + (product.title ?? 'produto')"
                      [checked]="store.selection.isSelected(product)"
                      [disabled]="running() !== null"
                      cFormCheckInput
                      class="mt-1 flex-shrink-0"
                      type="checkbox"
                    />
                    <div class="text-break">
                      <a
                        [attr.aria-current]="product.id === store.openId() ? 'true' : null"
                        [routerLink]="[product.id]"
                        class="fw-semibold text-reset"
                        queryParamsHandling="preserve"
                      >
                        {{ product.title }}
                      </a>
                      <div [class.text-body-secondary]="product.id !== store.openId()" class="small">
                        <span [title]="product.storeId ?? ''">Loja {{ shortId(product.storeId) }}</span>
                        | {{ product.moderationQueuedAt | relativeTime }}
                      </div>
                    </div>
                  </div>
                }
              </div>
            }
            <app-cursor-pagination
              (cursorChange)="store.changePage({ cursor: $event ?? null })"
              (limitChange)="store.changePage({ limit: $event, cursor: null })"
              [cursor]="store.cursor()"
              [disabled]="store.loading() || running() !== null"
              [limit]="store.limit()"
              [nextCursor]="store.nextCursor()"
            />
          </c-card-body>
        </c-card>
      </c-col>
      <c-col [class.d-none]="!store.openId()" [lg]="7" [xl]="8" class="d-lg-block">
        <router-outlet />
        @if (!store.openId()) {
          <c-card class="mb-4">
            <c-card-body class="text-center text-body-secondary py-5">Escolha um produto da fila para ver o detalhe.</c-card-body>
          </c-card>
        }
      </c-col>
    </c-row>

    <app-confirm-modal
      (confirm)="approveSelected()"
      [(visible)]="approveOpen"
      [busy]="running() === 'APPROVE'"
      confirmColor="success"
      confirmLabel="Aprovar"
      title="Aprovar em lote"
    >
      <p class="mb-0">Aprovar {{ approveCount() }}? {{ approveEffect }}</p>
      @if (approveProblem()) {
        <app-problem-alert [problem]="approveProblem()" class="d-block mt-3" heading="Não foi possível aprovar" />
      }
    </app-confirm-modal>

    <app-moderation-decision-modal
      (closed)="batchDecision.set(null)"
      (confirm)="decideSelected($event)"
      [batch]="true"
      [busy]="running() !== null"
      [decision]="batchDecision()"
      [problem]="batchDecisionProblem()"
      [subject]="batchDecisionCount()"
    />
  `,
})
export class ModerationQueuePageComponent implements LeaveGuarded, OnInit {
  readonly store = inject(ModerationQueueStore);
  readonly #toast = inject(ToastService);
  readonly #abort = abortOnDestroy();

  readonly batchActions: readonly BatchActionPresentation[] = DECISIONS.map((decision) => ({
    id: decision.code,
    label: decision.label,
    runningLabel: decision.runningLabel,
    color: decision.color,
  }));
  readonly approveEffect = decisionPresentation('APPROVE').batchEffect;

  /** Decisão do lote em andamento; trava a barra, a seleção e a paginação. */
  readonly running = signal<ModerationDecision | null>(null);
  readonly failures = signal<readonly BatchFailure[]>([]);
  /** Chamada do lote que falhou inteira depois de outras terem dado certo, no alerta acima da lista. */
  readonly batchProblem = signal<Problem | null>(null);
  readonly approveOpen = signal(false);
  readonly approveProblem = signal<Problem | null>(null);
  readonly batchDecision = signal<ReasonDecision | null>(null);
  readonly batchDecisionProblem = signal<Problem | null>(null);

  readonly approveCount = computed(() =>
    plural(this.store.selection.countFor('APPROVE'), 'produto', 'produtos'),
  );
  readonly batchDecisionCount = computed(() => {
    const decision = this.batchDecision();
    return decision ? plural(this.store.selection.countFor(decision), 'produto', 'produtos') : '';
  });
  readonly leaveWarning = computed(() => leaveWarning({ batchRunning: this.running() !== null }));

  readonly shortId = shortId;

  constructor() {
    warnBeforeUnload(this.leaveWarning);
  }

  ngOnInit(): void {
    void this.store.refreshBadge();
  }

  startBatch(action: string): void {
    this.failures.set([]);
    this.batchProblem.set(null);
    if (action === 'APPROVE') {
      this.approveProblem.set(null);
      this.approveOpen.set(true);
    } else if (action === 'REJECT' || action === 'BLOCK') {
      this.batchDecisionProblem.set(null);
      this.batchDecision.set(action);
    }
  }

  async approveSelected(): Promise<void> {
    const outcome = await this.#execute('APPROVE');
    if (outcome?.stopped) {
      this.approveProblem.set(outcome.stopped);
      return;
    }
    this.approveOpen.set(false);
    if (outcome) {
      await this.#report('APPROVE', outcome.run, outcome.ignored);
    }
  }

  async decideSelected(reason: DecisionReason): Promise<void> {
    const decision = this.batchDecision();
    if (!decision) {
      return;
    }
    const outcome = await this.#execute(decision, reason);
    if (outcome?.stopped) {
      this.batchDecisionProblem.set(outcome.stopped);
      return;
    }
    this.batchDecision.set(null);
    if (outcome) {
      await this.#report(decision, outcome.run, outcome.ignored);
    }
  }

  /**
   * Envia os selecionados que aceitam a decisão. Quando a primeira chamada falha inteira, nada foi decidido, e o
   * problema volta em `stopped` para o modal mostrar sem fechar, como na decisão de um produto.
   */
  async #execute(
    action: ModerationDecision,
    reason?: DecisionReason,
  ): Promise<{ run: QueueBatchRun; ignored: number; stopped?: Problem } | null> {
    const items = this.store.selection.accepting(action);
    if (!items.length || this.running()) {
      return null;
    }
    const ignored = this.store.selection.count() - items.length;
    this.running.set(action);
    try {
      const run = await runBatch<ProductResponse, ProductBatchResult>({
        items,
        id: (product) => product.id ?? '',
        size: BATCH_SIZE,
        signal: this.#abort,
        send: (group) =>
          apiData(
            batchModerateProducts({
              body: {
                action,
                items: group.map((product) => ({ id: product.id ?? '', version: product.version })),
                ...reason,
              },
            }),
          ).then((response) => response.results),
      });
      const nothingDecided = !run.succeeded.length && !run.failed.length;
      return { run, ignored, stopped: nothingDecided && run.error ? run.error : undefined };
    } finally {
      this.running.set(null);
    }
  }

  async #report(action: ModerationDecision, run: QueueBatchRun, ignored: number): Promise<void> {
    // NOTE: a pessoa saiu da tela no meio do lote; o resumo e a recarga não têm mais onde aparecer.
    if (this.#abort.aborted) {
      return;
    }
    this.store.selection.completeBatch(run);
    this.failures.set(
      batchFailures(
        run.failed,
        (product) => product.id ?? '',
        (product) => product.title || 'Produto sem título',
      ),
    );
    this.batchProblem.set(run.error ?? null);
    const counts = batchRunCounts(run, ignored);
    this.#toast.show(batchSummary(counts, decisionPresentation(action).done), {
      kind: counts.succeeded ? 'success' : 'warning',
    });
    await this.store.afterDecision(run.succeeded.map((success) => success.item.id ?? ''));
  }
}
