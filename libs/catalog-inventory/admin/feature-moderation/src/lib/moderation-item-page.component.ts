import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  BadgeComponent,
  ButtonDirective,
  CalloutComponent,
  CardBodyComponent,
  CardComponent,
  CardHeaderComponent,
  ColComponent,
  ListGroupDirective,
  ListGroupItemDirective,
  RowComponent,
  SpinnerComponent,
  TableDirective,
} from '@coreui/angular';
import {
  approveProduct,
  blockProduct,
  getCategoryResource,
  getProductResource,
  getStoreEligibilityResource,
  type ModerationDecision,
  productHistoryResource,
  rejectProduct,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ProductStatusBadgeComponent } from '@marketplace/catalog-inventory-admin-ui';
import {
  AdminSession,
  DateTimePipe,
  EditConflictAlertComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  MoneyPipe,
  NumberBlockComponent,
  ProblemAlertComponent,
  RelativeTimePipe,
  ToastService,
} from '@marketplace/shared-admin-ui';
import {
  actionState,
  apiData,
  isEditConflict,
  type Problem,
  responseVersion,
  toProblem,
  translateReason,
  versionTag,
} from '@marketplace/shared-web-util';
import { map } from 'rxjs';

import {
  type DecisionReason,
  ModerationDecisionModalComponent,
} from './moderation-decision-modal.component';
import { decisionPresentation, DECISIONS } from './moderation-decisions';
import { ModerationQueueStore } from './moderation-queue.store';
import type { ReasonDecision } from './moderation-reasons';
import {
  attributeRows,
  categoryPath,
  historyEntries,
  sellerStatusLook,
  shortId,
  skusInStock,
  variantText,
} from './product-summary';

/** Capacidade que libera a situação de qualquer loja (`GET /stores/{storeId}/eligibility`). */
const READ_SELLER_ELIGIBILITY = 'READ_SELLER_ELIGIBILITY';

/**
 * Detalhe do produto na fila (`/moderacao/fila/:id`), ao lado da lista: o produto lido de `GET /products/{id}`, com
 * as decisões que o servidor libera ou barra, a categoria, os atributos, os SKUs com preço e saldo, a situação da loja
 * e o histórico. Aprovar não pede confirmação; rejeitar e bloquear abrem o modal de motivos. Depois da decisão, o
 * próximo produto da fila abre sozinho.
 */
@Component({
  selector: 'app-moderation-item-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .numeric {
      font-variant-numeric: tabular-nums;
    }
    .description {
      white-space: pre-line;
    }
  `,
  imports: [
    BadgeComponent,
    ButtonDirective,
    CalloutComponent,
    CardBodyComponent,
    CardComponent,
    CardHeaderComponent,
    ColComponent,
    DateTimePipe,
    EditConflictAlertComponent,
    ErrorStateComponent,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    ModerationDecisionModalComponent,
    MoneyPipe,
    NumberBlockComponent,
    ProblemAlertComponent,
    ProductStatusBadgeComponent,
    RelativeTimePipe,
    RouterLink,
    RowComponent,
    SpinnerComponent,
    TableDirective,
  ],
  template: `
    <a class="d-inline-block d-lg-none mb-3" queryParamsHandling="preserve" routerLink="..">‹ Voltar para a fila</a>
    @if (item(); as product) {
      <c-card class="mb-4">
        <c-card-body>
          <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
            <div class="text-break">
              <h1 class="h4 card-title mb-0">{{ product.title }}</h1>
              <div class="small text-body-secondary">
                <span [title]="product.storeId ?? ''">Loja {{ shortId(product.storeId) }}</span>
                @if (path()) {
                  | {{ path() }}
                }
              </div>
            </div>
            @if (product.status) {
              <app-product-status-badge [status]="product.status" />
            }
          </div>
          @if (store(); as eligibility) {
            <div class="mt-2">
              Loja: <c-badge [color]="storeLook().color">{{ storeLook().label }}</c-badge>
              @if (eligibility.canPublish === false) {
                <div class="small text-body-secondary mt-1">
                  Os produtos desta loja só são aprovados depois da aprovação no KYB/KYC.
                </div>
              }
            </div>
          }
          <c-row class="g-3 mt-1">
            <c-col [sm]="4">
              <app-number-block [value]="product.skus?.length ?? 0" color="info" label="SKUs" />
            </c-col>
            <c-col [sm]="4">
              <app-number-block [value]="inStock()" color="success" label="SKUs com saldo" />
            </c-col>
            <c-col [sm]="4">
              <app-number-block [value]="product.moderationQueuedAt | relativeTime" color="warning" label="Entrou na fila" />
            </c-col>
          </c-row>

          <div class="mt-4">
            @if (conflict()) {
              <app-edit-conflict-alert (reload)="reloadProduct()" [busy]="product$.isLoading()" />
            } @else if (problem()) {
              <app-problem-alert [problem]="problem()" heading="Não foi possível aprovar" />
            }
            <div aria-label="Decisões" class="d-flex flex-wrap gap-2" role="group">
              @for (action of actions(); track action.code) {
                <button
                  (click)="decide(action.code)"
                  [attr.aria-describedby]="action.state.allowed ? null : blockedId(action.code)"
                  [color]="action.color"
                  [disabled]="!action.state.allowed || saving() !== null || product$.isLoading()"
                  cButton
                  type="button"
                >
                  @if (saving() === action.code) {
                    <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
                  }
                  {{ action.label }}
                </button>
              } @empty {
                <span class="text-body-secondary">Nenhuma decisão disponível para este produto.</span>
              }
            </div>
            @for (action of blocked(); track action.code) {
              <div [id]="blockedId(action.code)" class="small text-body-secondary mt-2">
                <strong>{{ action.label }}:</strong> {{ action.state.blockedReason }}
              </div>
            }
            @if (issues().length) {
              <c-callout class="mb-0" color="warning">
                <strong>Pendências de publicação</strong>
                <ul class="mb-0 ps-3 small">
                  @for (issue of issues(); track $index) {
                    <li>{{ issue }}</li>
                  }
                </ul>
              </c-callout>
            }
          </div>
        </c-card-body>
      </c-card>

      <c-card class="mb-4">
        <c-card-header>Conteúdo</c-card-header>
        <c-card-body>
          @if (product.description) {
            <p class="description">{{ product.description }}</p>
          }
          @if (attributes().length) {
            <dl class="row mb-0">
              @for (attribute of attributes(); track attribute.code) {
                <dt class="col-sm-4 fw-normal text-body-secondary">{{ attribute.label }}</dt>
                <dd class="col-sm-8">{{ attribute.value }}</dd>
              }
            </dl>
          } @else {
            <p class="text-body-secondary mb-0">Nenhum atributo preenchido.</p>
          }
        </c-card-body>
      </c-card>

      <c-card class="mb-4">
        <c-card-header>SKUs</c-card-header>
        <c-card-body>
          <div class="table-responsive">
            <table [hover]="true" [striped]="true" align="middle" cTable class="mb-0 border">
              <thead class="text-nowrap text-truncate">
                <tr>
                  <th class="bg-body-tertiary" scope="col">SKU</th>
                  <th class="bg-body-tertiary" scope="col">Variação</th>
                  <th class="bg-body-tertiary text-end" scope="col">Preço</th>
                  <th class="bg-body-tertiary text-end" scope="col">Disponível</th>
                </tr>
              </thead>
              <tbody>
                @for (sku of product.skus ?? []; track sku.id) {
                  <tr>
                    <td class="text-break">{{ sku.sellerSku }}</td>
                    <td>{{ variant(sku.variantAttributes) || '—' }}</td>
                    <td class="text-end numeric">
                      @if (sku.offer; as offer) {
                        <strong>{{ offer.effectivePrice?.amount | money: offer.effectivePrice?.currency ?? product.currency }}</strong>
                        @if (offer.onPromotion) {
                          <div class="small text-body-secondary">
                            Em promoção | de {{ offer.salePrice?.amount | money: offer.salePrice?.currency ?? product.currency }}
                          </div>
                        } @else if (offer.listPrice) {
                          <div class="small text-body-secondary">
                            <s>{{ offer.listPrice.amount | money: offer.listPrice.currency }}</s>
                          </div>
                        }
                      } @else {
                        <span class="text-body-secondary">Sem oferta</span>
                      }
                    </td>
                    <td class="text-end numeric">
                      @if (sku.stock; as stock) {
                        <strong>{{ stock.available }}</strong>
                        <div class="small text-body-secondary">de {{ stock.onHand }} em estoque</div>
                      } @else {
                        <span class="text-body-secondary">—</span>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td class="text-center text-body-secondary" colspan="4">Nenhum SKU cadastrado.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </c-card-body>
      </c-card>

      <c-card class="mb-4">
        <c-card-header>Histórico</c-card-header>
        @if (history.isLoading()) {
          <c-card-body><app-loading-state [rows]="3" label="Carregando o histórico…" /></c-card-body>
        } @else if (history.error()) {
          <c-card-body>
            <app-error-state (retry)="history.reload()" [problem]="historyProblem()" message="Não foi possível carregar o histórico." />
          </c-card-body>
        } @else {
          <ul [flush]="true" cListGroup class="mb-0">
            @for (entry of historyItems(); track $index) {
              <li cListGroupItem>
                <div class="d-flex flex-wrap justify-content-between gap-2">
                  <strong>{{ entry.label }}</strong>
                  <span class="small text-body-secondary text-nowrap">{{ entry.at | dateTime }}</span>
                </div>
                @if (entry.reasons.length) {
                  <div class="small">Motivos: {{ entry.reasons.join(', ') }}</div>
                }
                @if (entry.note) {
                  <div class="small description">Complemento: {{ entry.note }}</div>
                }
                @if (entry.actor) {
                  <div class="small text-body-secondary">Por {{ entry.actor }}</div>
                }
              </li>
            } @empty {
              <li cListGroupItem class="text-body-secondary">Nenhum registro no histórico.</li>
            }
          </ul>
        }
      </c-card>

      <app-moderation-decision-modal
        (closed)="modalDecision.set(null)"
        (confirm)="decideWithReason($event)"
        (reload)="reloadProduct()"
        [busy]="saving() !== null"
        [decision]="modalDecision()"
        [problem]="modalProblem()"
        [reloading]="product$.isLoading()"
        [subject]="product.title ?? ''"
      />
    } @else if (product$.error()) {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title mb-0">Produto em moderação</h1>
          <app-error-state (retry)="product$.reload()" [problem]="loadProblem()" message="Não foi possível carregar o produto." />
        </c-card-body>
      </c-card>
    } @else {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title">Produto em moderação</h1>
          <app-loading-state label="Carregando o produto…" />
        </c-card-body>
      </c-card>
    }
  `,
})
export class ModerationItemPageComponent {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #toast = inject(ToastService);
  readonly #session = inject(AdminSession);
  readonly #queue = inject(ModerationQueueStore, { optional: true });

  readonly id = toSignal(
    this.#route.paramMap.pipe(map((params) => params.get('id') ?? undefined)),
    { requireSync: true },
  );

  readonly product$ = getProductResource(() => {
    const id = this.id();
    return id ? { path: { id } } : undefined;
  });
  readonly item = computed(() => (this.product$.hasValue() ? this.product$.value() : undefined));
  /** Versão da leitura concluída; enquanto a versão atual carrega, nenhuma decisão sai. */
  readonly version = computed(() =>
    this.product$.status() === 'resolved' && this.product$.hasValue()
      ? (versionTag(this.product$.value().version) ?? responseVersion(this.product$.headers()))
      : undefined,
  );

  readonly #categoryId = computed(() => this.item()?.categoryId);
  readonly #storeId = computed(() => this.item()?.storeId);
  readonly category = getCategoryResource(() => {
    const id = this.#categoryId();
    return id ? { path: { id } } : undefined;
  });
  readonly history = productHistoryResource(() => {
    const id = this.id();
    return id ? { path: { id } } : undefined;
  });
  readonly eligibility = getStoreEligibilityResource(() => {
    const storeId = this.#storeId();
    return storeId && this.#session.hasCapability(READ_SELLER_ELIGIBILITY)
      ? { path: { storeId } }
      : undefined;
  });

  readonly saving = signal<ModerationDecision | null>(null);
  /** Recusa ou conflito da aprovação, no alerta do detalhe. */
  readonly problem = signal<Problem | null>(null);
  readonly modalDecision = signal<ReasonDecision | null>(null);
  readonly modalProblem = signal<Problem | null>(null);

  readonly conflict = computed(() => isEditConflict(this.problem()));
  readonly loadProblem = computed(() =>
    this.product$.error() ? toProblem(this.product$.error()) : null,
  );
  readonly historyProblem = computed(() =>
    this.history.error() ? toProblem(this.history.error()) : null,
  );

  readonly #effectiveAttributes = computed(() =>
    this.category.hasValue() ? this.category.value().effectiveAttributes : undefined,
  );
  readonly path = computed(() =>
    this.category.hasValue() ? categoryPath(this.category.value()) : '',
  );
  readonly attributes = computed(() =>
    attributeRows(this.item()?.attributes, this.#effectiveAttributes()),
  );
  readonly inStock = computed(() => skusInStock(this.item()?.skus));
  readonly store = computed(() =>
    this.eligibility.hasValue() ? this.eligibility.value() : undefined,
  );
  readonly storeLook = computed(() => sellerStatusLook(this.store()?.status));
  readonly historyItems = computed(() =>
    historyEntries(this.history.hasValue() ? this.history.value() : []),
  );
  readonly issues = computed(() =>
    (this.item()?.publishingIssues ?? []).map((issue) => translateReason(issue)),
  );

  /** Decisões que o servidor libera (`actions`) ou barra com o motivo (`blockedActions`); as demais não aparecem. */
  readonly actions = computed(() =>
    DECISIONS.map((presentation) => ({
      ...presentation,
      state: actionState(this.item(), presentation.code),
    })).filter((action) => action.state.allowed || action.state.blockedReason !== undefined),
  );
  readonly blocked = computed(() => this.actions().filter((action) => !action.state.allowed));

  readonly shortId = shortId;

  #awaitingReload = false;

  constructor() {
    effect(() => {
      this.id();
      untracked(() => {
        this.#awaitingReload = false;
        this.problem.set(null);
        this.modalDecision.set(null);
        this.modalProblem.set(null);
      });
    });
    effect(() => {
      const status = this.product$.status();
      untracked(() => {
        if (!this.#awaitingReload || (status !== 'resolved' && status !== 'error')) {
          return;
        }
        this.#awaitingReload = false;
        if (status === 'resolved') {
          this.problem.set(null);
          this.modalProblem.set(null);
        }
      });
    });
  }

  variant(values: Readonly<Record<string, string>> | null | undefined): string {
    return variantText(values, this.#effectiveAttributes());
  }

  blockedId(code: string): string {
    return `moderation-blocked-${code.toLowerCase()}`;
  }

  decide(code: ModerationDecision): void {
    this.problem.set(null);
    if (code === 'APPROVE') {
      void this.approve();
      return;
    }
    this.modalProblem.set(null);
    this.modalDecision.set(code);
  }

  async approve(): Promise<void> {
    const id = this.item()?.id;
    const headers = this.#ifMatch();
    if (!id || !headers || this.saving()) {
      return;
    }
    this.saving.set('APPROVE');
    try {
      await apiData(approveProduct({ path: { id }, headers }));
      this.#toast.success(decisionPresentation('APPROVE').successMessage);
      await this.#advance(id);
    } catch (error) {
      if (this.id() === id) {
        this.problem.set(toProblem(error));
      }
    } finally {
      this.saving.set(null);
    }
  }

  async decideWithReason(reason: DecisionReason): Promise<void> {
    const decision = this.modalDecision();
    const id = this.item()?.id;
    const headers = this.#ifMatch();
    if (!decision || !id || !headers || this.saving()) {
      return;
    }
    this.saving.set(decision);
    this.modalProblem.set(null);
    try {
      const options = { path: { id }, headers, body: reason };
      await apiData(decision === 'REJECT' ? rejectProduct(options) : blockProduct(options));
      if (this.id() === id) {
        this.modalDecision.set(null);
      }
      this.#toast.success(decisionPresentation(decision).successMessage);
      await this.#advance(id);
    } catch (error) {
      if (this.id() === id) {
        this.modalProblem.set(toProblem(error));
      }
    } finally {
      this.saving.set(null);
    }
  }

  /**
   * Carrega a versão atual depois do conflito; a decisão aberta no modal continua para nova tentativa. O aviso só sai
   * quando a versão nova chega, e as decisões ficam travadas até lá.
   */
  reloadProduct(): void {
    this.#awaitingReload = true;
    this.product$.reload();
  }

  #ifMatch(): { 'If-Match': string } | undefined {
    const version = this.version();
    return version ? { 'If-Match': version } : undefined;
  }

  async #advance(id: string): Promise<void> {
    if (this.#queue) {
      await this.#queue.afterDecision([id]);
    } else {
      await this.#router.navigate(['..'], {
        relativeTo: this.#route,
        queryParamsHandling: 'preserve',
      });
    }
  }
}
