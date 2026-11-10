import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  RowComponent,
  TableDirective,
} from '@coreui/angular';
import { cilBan, cilCheckCircle, cilPencil, cilPlus } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import {
  activateModerationReason,
  deactivateModerationReason,
  listModerationReasonsResource,
  type ModerationReasonResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  type ActionMenuChoice,
  ActionMenuComponent,
  type ActionMenuItem,
  ConfirmModalComponent,
  EditConflictAlertComponent,
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
  ProblemAlertComponent,
  ToastService,
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  isEditConflict,
  type Problem,
  toProblem,
  versionTag,
} from '@marketplace/shared-web-util';

import { decisionPresentation } from './moderation-decisions';
import {
  ModerationReasonFormComponent,
  type ReasonSaved,
} from './moderation-reason-form.component';

type ReasonAction = 'EDIT' | 'DEACTIVATE' | 'ACTIVATE';

const EDIT: ActionMenuItem = { id: 'EDIT', label: 'Editar', icon: cilPencil };
const DEACTIVATE: ActionMenuItem = {
  id: 'DEACTIVATE',
  label: 'Desativar',
  icon: cilBan,
  danger: true,
};
const ACTIVATE: ActionMenuItem = { id: 'ACTIVATE', label: 'Reativar', icon: cilCheckCircle };

const SAVED_MESSAGES: Readonly<Record<ReasonSaved, string>> = {
  created: 'Motivo criado. Ele já aparece para o moderador.',
  updated: 'Motivo atualizado.',
};

/**
 * Motivos de moderação do tenant (`/plataforma/motivos-de-moderacao`, ADR 030): a lista inteira de
 * `GET /moderation/reasons?includeInactive=true`, na ordem do servidor, com novo, editar, desativar e reativar. Não há
 * excluir, porque o código está no histórico e nos eventos.
 */
@Component({
  selector: 'app-moderation-reasons-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .numeric {
      font-variant-numeric: tabular-nums;
    }
  `,
  imports: [
    ActionMenuComponent,
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    ConfirmModalComponent,
    EditConflictAlertComponent,
    EmptyStateComponent,
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    IconDirective,
    LoadingStateComponent,
    ModerationReasonFormComponent,
    ProblemAlertComponent,
    RowComponent,
    TableDirective,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <c-row class="mb-3 g-3">
          <c-col [sm]="6">
            <h1 class="h4 card-title mb-0">Motivos de moderação</h1>
            <div class="small text-body-secondary">Os motivos prontos que o moderador marca ao rejeitar ou bloquear.</div>
          </c-col>
          <c-col [sm]="6">
            <button (click)="create()" cButton class="float-end" color="primary" type="button">
              <svg [cIcon]="plusIcon" aria-hidden="true" class="me-1"></svg>
              Novo motivo
            </button>
            <c-form-check [switch]="true" class="float-end me-3 mt-2">
              <input
                (change)="toggleInactive($event)"
                [checked]="showInactive()"
                cFormCheckInput
                id="moderation-reasons-show-inactive"
                type="checkbox"
              />
              <label cFormCheckLabel for="moderation-reasons-show-inactive">Mostrar inativos</label>
            </c-form-check>
          </c-col>
        </c-row>

        @if (actionProblem(); as problem) {
          @if (conflict()) {
            <app-edit-conflict-alert (reload)="reloadList()" [busy]="reasons.isLoading()" />
          } @else {
            <app-problem-alert [problem]="problem" heading="Não foi possível reativar o motivo" />
          }
        }

        @if (reasons.isLoading() && !reasons.hasValue()) {
          <app-loading-state label="Carregando os motivos…" />
        } @else if (reasons.error()) {
          <app-error-state (retry)="reasons.reload()" [problem]="loadProblem()" message="Não foi possível carregar os motivos." />
        } @else if (!all().length) {
          <app-empty-state message="Nenhum motivo cadastrado. Sem motivos, o moderador decide só com o complemento.">
            <button (click)="create()" cButton color="primary" type="button" variant="outline">Criar motivo</button>
          </app-empty-state>
        } @else if (!rows().length) {
          <app-empty-state message="Nenhum motivo ativo. Ligue Mostrar inativos para ver os desativados." />
        } @else {
          <div class="table-responsive">
            <table [attr.aria-busy]="reasons.isLoading()" [hover]="true" [striped]="true" align="middle" cTable class="mb-0 border">
              <thead class="text-nowrap text-truncate">
                <tr>
                  <th class="bg-body-tertiary" scope="col">Motivo</th>
                  <th class="bg-body-tertiary" scope="col">Decisões</th>
                  <th class="bg-body-tertiary" scope="col">Situação</th>
                  <th class="bg-body-tertiary text-end" scope="col">Posição</th>
                  <th class="bg-body-tertiary" scope="col"><span class="visually-hidden">Ações</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.reason.code) {
                  <tr>
                    <td class="text-break">
                      <div>{{ row.reason.label }}</div>
                      <div class="small text-body-secondary">{{ row.reason.code }}</div>
                    </td>
                    <td>
                      @for (decision of row.decisions; track decision.code) {
                        <c-badge [color]="decision.color" class="me-1">{{ decision.label }}</c-badge>
                      }
                    </td>
                    <td>
                      <c-badge [color]="row.reason.active ? 'success' : 'secondary'">
                        {{ row.reason.active ? 'Ativo' : 'Inativo' }}
                      </c-badge>
                    </td>
                    <td class="text-end numeric">{{ row.reason.position }}</td>
                    <td class="text-end">
                      <app-action-menu
                        (action)="onAction(row.reason, $event)"
                        [disabled]="busyCode() === row.reason.code || reasons.isLoading()"
                        [items]="row.menu"
                        [label]="'Ações de ' + row.reason.label"
                      />
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </c-card-body>
    </c-card>

    <app-moderation-reason-form
      (duplicate)="showDuplicate()"
      (saved)="onSaved($event)"
      [(visible)]="formOpen"
      [code]="formCode()"
      [returnFocus]="returnFocus()"
    />

    <app-confirm-modal
      (confirm)="deactivate()"
      [(visible)]="deactivateOpen"
      [busy]="deactivating()"
      [confirmDisabled]="reasons.isLoading()"
      [returnFocus]="returnFocus()"
      [title]="deactivateTitle()"
      confirmColor="danger"
      confirmLabel="Desativar"
    >
      <p class="mb-0">
        O motivo some da lista do moderador e não vale em decisões novas. As decisões passadas continuam com o rótulo da
        época.
      </p>
      @if (deactivateProblem(); as problem) {
        <div class="mt-3">
          @if (deactivateConflict()) {
            <app-edit-conflict-alert (reload)="reloadForDeactivate()" [busy]="reasons.isLoading()" />
          } @else {
            <app-problem-alert [problem]="problem" heading="Não foi possível desativar o motivo" />
          }
        </div>
      }
    </app-confirm-modal>
  `,
})
export class ModerationReasonsPageComponent {
  readonly #toast = inject(ToastService);

  readonly reasons = listModerationReasonsResource(() => ({ query: { includeInactive: true } }));
  /** Começa desligado: a lista mostra os ativos, que são os que o moderador vê. */
  readonly showInactive = signal(false);

  readonly all = computed<readonly ModerationReasonResponse[]>(() =>
    this.reasons.hasValue() ? this.reasons.value() : [],
  );
  readonly rows = computed(() =>
    this.all()
      .filter((reason) => this.showInactive() || reason.active !== false)
      .map((reason) => ({
        reason,
        decisions: (reason.decisions ?? []).map(decisionPresentation),
        menu: [EDIT, reason.active === false ? ACTIVATE : DEACTIVATE],
      })),
  );
  readonly loadProblem = computed(() =>
    this.reasons.error() ? toProblem(this.reasons.error()) : null,
  );

  readonly formOpen = signal(false);
  readonly formCode = signal<string | null>(null);
  readonly returnFocus = signal<HTMLElement | null>(null);

  readonly deactivateOpen = signal(false);
  readonly #deactivateCode = signal<string | null>(null);
  /** Motivo do modal de desativar, lido da lista atual para gravar com a versão mais recente. */
  readonly deactivateTarget = computed(() =>
    this.all().find((reason) => reason.code === this.#deactivateCode()),
  );
  readonly deactivateTitle = computed(() => `Desativar "${this.deactivateTarget()?.label ?? ''}"?`);
  readonly deactivating = signal(false);
  readonly deactivateProblem = signal<Problem | null>(null);
  readonly deactivateConflict = computed(() => isEditConflict(this.deactivateProblem()));

  /** Motivo sendo reativado; o menu dele fica travado. */
  readonly busyCode = signal<string | null>(null);
  readonly actionProblem = signal<Problem | null>(null);
  readonly conflict = computed(() => isEditConflict(this.actionProblem()));

  readonly plusIcon = cilPlus;

  /** Problema que sai quando a lista recarregada depois do conflito chegar. */
  #clearOnReload: 'action' | 'deactivate' | null = null;

  constructor() {
    effect(() => {
      const status = this.reasons.status();
      untracked(() => {
        if (!this.#clearOnReload || (status !== 'resolved' && status !== 'error')) {
          return;
        }
        if (status === 'resolved') {
          (this.#clearOnReload === 'action' ? this.actionProblem : this.deactivateProblem).set(
            null,
          );
        }
        this.#clearOnReload = null;
      });
    });
  }

  toggleInactive(event: Event): void {
    this.showInactive.set((event.target as HTMLInputElement).checked);
  }

  create(): void {
    this.actionProblem.set(null);
    this.returnFocus.set(null);
    this.formCode.set(null);
    this.formOpen.set(true);
  }

  onAction(reason: ModerationReasonResponse, choice: ActionMenuChoice): void {
    const code = reason.code;
    if (!code) {
      return;
    }
    this.actionProblem.set(null);
    this.returnFocus.set(choice.toggle);
    switch (choice.id as ReasonAction) {
      case 'EDIT':
        this.formCode.set(code);
        this.formOpen.set(true);
        break;
      case 'DEACTIVATE':
        this.#deactivateCode.set(code);
        this.deactivateProblem.set(null);
        this.deactivateOpen.set(true);
        break;
      case 'ACTIVATE':
        void this.activate(reason);
        break;
    }
  }

  onSaved(saved: ReasonSaved): void {
    this.#toast.success(SAVED_MESSAGES[saved]);
    this.reasons.reload();
  }

  /** O código já existe, talvez num motivo inativo: liga "Mostrar inativos" para a pessoa reativar o existente. */
  showDuplicate(): void {
    this.showInactive.set(true);
    this.reasons.reload();
  }

  async deactivate(): Promise<void> {
    const reason = this.deactivateTarget();
    if (!reason?.code || this.deactivating() || this.reasons.isLoading()) {
      return;
    }
    this.deactivating.set(true);
    this.deactivateProblem.set(null);
    try {
      await apiData(
        deactivateModerationReason({
          path: { code: reason.code },
          headers: ifMatch(reason.version),
        }),
      );
      this.deactivateOpen.set(false);
      this.#toast.success('Motivo desativado. Ele não aparece mais para o moderador.');
      this.reasons.reload();
    } catch (error) {
      this.deactivateProblem.set(toProblem(error));
    } finally {
      this.deactivating.set(false);
    }
  }

  async activate(reason: ModerationReasonResponse): Promise<void> {
    const code = reason.code;
    if (!code || this.busyCode() || this.reasons.isLoading()) {
      return;
    }
    this.busyCode.set(code);
    try {
      await apiData(activateModerationReason({ path: { code }, headers: ifMatch(reason.version) }));
      this.#toast.success('Motivo reativado. Ele volta a aparecer para o moderador.');
      this.reasons.reload();
    } catch (error) {
      this.actionProblem.set(toProblem(error));
    } finally {
      this.busyCode.set(null);
    }
  }

  /** Traz a versão atual depois do conflito ao reativar; o aviso sai quando ela chega. */
  reloadList(): void {
    this.#clearOnReload = 'action';
    this.reasons.reload();
  }

  /**
   * Traz a versão atual do motivo depois do conflito; o modal continua aberto para confirmar de novo, com a confirmação
   * travada até a versão nova chegar.
   */
  reloadForDeactivate(): void {
    this.#clearOnReload = 'deactivate';
    this.reasons.reload();
  }
}

function ifMatch(version: number | undefined): { 'If-Match': string } | undefined {
  const tag = versionTag(version);
  return tag ? { 'If-Match': tag } : undefined;
}
