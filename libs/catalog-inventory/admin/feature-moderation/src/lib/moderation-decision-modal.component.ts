import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import {
  ButtonCloseDirective,
  ButtonDirective,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormTextDirective,
  ModalBodyComponent,
  ModalComponent,
  ModalFooterComponent,
  ModalHeaderComponent,
  ModalTitleDirective,
  SpinnerComponent
} from '@coreui/angular';
import { DecisionRequestSchema } from '@marketplace/catalog-inventory-admin-data-access';
import { EditConflictAlertComponent, LoadingStateComponent, ProblemAlertComponent } from '@marketplace/shared-admin-ui';
import {
  checkFieldRules,
  fieldErrors,
  fieldRules,
  isEditConflict,
  type Problem,
  problemCode,
  ruleMessage
} from '@marketplace/shared-web-util';

import { decisionPresentation } from './moderation-decisions';
import { moderationReasonsResource, type ReasonDecision, reasonOptions } from './moderation-reasons';

/** Motivo da rejeição ou do bloqueio, no formato do corpo de `POST .../reject`, `.../block` e do lote. */
export interface DecisionReason {
  readonly reasonCodes: string[];
  readonly note: string | null;
}

const NOTE_RULES = fieldRules(DecisionRequestSchema, 'note');
const INVALID_REASON = 'invalid-moderation-reason';

let nextId = 0;

/**
 * Modal de rejeitar ou bloquear, de um produto ou do lote: os motivos do tenant que valem para a decisão, o complemento
 * com o contador do limite do contrato e o efeito. A tela grava e fecha; a recusa volta por `problem`, e a de um motivo
 * que mudou no meio recarrega a lista e mantém o que foi digitado.
 */
@Component({
  selector: 'app-moderation-decision-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonCloseDirective,
    ButtonDirective,
    EditConflictAlertComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormTextDirective,
    LoadingStateComponent,
    ModalBodyComponent,
    ModalComponent,
    ModalFooterComponent,
    ModalHeaderComponent,
    ModalTitleDirective,
    ProblemAlertComponent,
    SpinnerComponent
  ],
  template: `
    <c-modal
      (visibleChange)="!$event && close()"
      [attr.aria-labelledby]="titleId"
      [backdrop]="busy() ? 'static' : true"
      [id]="modalId"
      [keyboard]="!busy()"
      [visible]="!!decision()"
    >
      <c-modal-header>
        <h5 [id]="titleId" cModalTitle>{{ title() }}</h5>
        <button (click)="close()" [disabled]="busy()" aria-label="Fechar" cButtonClose></button>
      </c-modal-header>
      <c-modal-body>
        @if (conflict()) {
          <app-edit-conflict-alert (reload)="reload.emit()" [busy]="reloading()" />
        } @else if (problem()) {
          <app-problem-alert [problem]="problem()" [showFieldErrors]="false" heading="Não foi possível concluir a decisão" />
        }
        <fieldset class="mb-3">
          <legend class="form-label fs-6">Motivos</legend>
          @if (reasons.isLoading()) {
            <app-loading-state [rows]="3" label="Carregando os motivos…" />
          } @else if (reasons.error()) {
            <p class="small text-body-secondary mb-0">
              Não foi possível carregar os motivos.
              <button (click)="reasons.reload()" cButton class="p-0 align-baseline" color="link" size="sm" type="button">
                Tentar de novo
              </button>
            </p>
          } @else if (!options().length) {
            <p class="small text-body-secondary mb-0">
              Nenhum motivo pronto vale para esta decisão. O administrador do catálogo cadastra os motivos em Plataforma ›
              Motivos de moderação; até lá, decida com o complemento.
            </p>
          } @else {
            @for (reason of options(); track reason.code) {
              <c-form-check>
                <input
                  (change)="toggle(reason.code ?? '', $event)"
                  [checked]="selected().has(reason.code ?? '')"
                  [disabled]="busy()"
                  [id]="modalId + '-reason-' + $index"
                  cFormCheckInput
                  type="checkbox"
                />
                <label [for]="modalId + '-reason-' + $index" cFormCheckLabel>{{ reason.label }}</label>
              </c-form-check>
            }
          }
          @if (reasonCodesError()) {
            <div class="invalid-feedback d-block">{{ reasonCodesError() }}</div>
          }
        </fieldset>
        <label [for]="noteId" cLabel>
          Complemento
          @if (noteRequired()) {
            <span class="text-body-secondary small">(obrigatório sem motivo marcado)</span>
          }
        </label>
        <textarea
          (blur)="touched.set(true)"
          (input)="changeNote($event)"
          [attr.aria-describedby]="counterId"
          [attr.maxlength]="noteMax ?? null"
          [disabled]="busy()"
          [id]="noteId"
          [valid]="noteMessage() ? false : undefined"
          [value]="note()"
          cFormControl
          rows="3"
        ></textarea>
        <div class="d-flex justify-content-between gap-3">
          <div>
            @if (noteMessage(); as message) {
              <div class="invalid-feedback d-block">{{ message }}</div>
            }
          </div>
          @if (noteMax !== undefined) {
            <div [id]="counterId" cFormText class="text-nowrap">{{ note().length }} de {{ noteMax }}</div>
          }
        </div>
        <p class="mb-0 mt-3">{{ effect() }}</p>
      </c-modal-body>
      <c-modal-footer>
        <button (click)="close()" [disabled]="busy()" cButton color="secondary" type="button">Cancelar</button>
        <button
          (click)="submit()"
          [color]="presentation().color"
          [disabled]="busy() || reloading() || reasons.isLoading()"
          cButton
          type="button"
        >
          @if (busy()) {
            <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
          }
          {{ presentation().label }}
        </button>
      </c-modal-footer>
    </c-modal>
  `
})
export class ModerationDecisionModalComponent {
  /** Decisão do modal aberto; `null` fecha. */
  readonly decision = input<ReasonDecision | null>(null);
  /** Nome do produto ou, no lote, a contagem ("3 produtos"). */
  readonly subject = input.required<string>();
  /** Decisão em lote: o título leva a contagem, e o efeito fala dos produtos. */
  readonly batch = input(false);
  /** Gravação em andamento: trava o modal e mostra o spinner. */
  readonly busy = input(false);
  /** Recusa da última gravação; o conflito de edição abre o aviso com "Carregar versão atual". */
  readonly problem = input<Problem | null | undefined>();
  /** Carregando a versão atual depois do conflito: o confirmar fica travado até ela chegar. */
  readonly reloading = input(false);

  /** A pessoa confirmou a decisão com os motivos marcados e o complemento. */
  readonly confirm = output<DecisionReason>();
  /** O modal foi fechado sem decidir; a tela limpa a decisão. */
  readonly closed = output<void>();
  /** Pedido da versão atual do produto depois do conflito de edição. */
  readonly reload = output<void>();

  readonly modalId = `app-moderation-decision-${nextId++}`;
  readonly titleId = `${this.modalId}-title`;
  readonly noteId = `${this.modalId}-note`;
  readonly counterId = `${this.modalId}-counter`;
  readonly noteMax = NOTE_RULES.maxLength;

  readonly reasons = moderationReasonsResource(this.decision);
  readonly selected = signal<ReadonlySet<string>>(new Set());
  readonly note = signal('');
  readonly touched = signal(false);

  readonly presentation = computed(() => decisionPresentation(this.decision() ?? 'REJECT'));
  readonly title = computed(() => {
    const label = this.presentation().label;
    return this.batch() ? `${label} ${this.subject()}` : `${label} "${this.subject()}"`;
  });
  readonly effect = computed(() => (this.batch() ? this.presentation().batchEffect : this.presentation().effect));
  readonly options = computed(() => {
    const decision = this.decision();
    return decision && this.reasons.hasValue() ? reasonOptions(this.reasons.value(), decision) : [];
  });
  /** Motivos marcados que continuam na lista; um motivo que saiu dela depois de recarregar não vai no pedido. */
  readonly chosen = computed(() =>
    this.options()
      .map(reason => reason.code ?? '')
      .filter(code => this.selected().has(code))
  );
  readonly noteRequired = computed(() => this.chosen().length === 0);
  readonly conflict = computed(() => isEditConflict(this.problem()));
  readonly noteMessage = computed(() => {
    const server = fieldErrors(this.problem())['note'];
    if (server) {
      return server;
    }
    const violation = checkFieldRules({ ...NOTE_RULES, required: this.noteRequired() }, this.note());
    if (!violation || !this.touched()) {
      return null;
    }
    return violation.kind === 'required' ? 'Escreva o complemento ou marque um motivo.' : ruleMessage(violation);
  });
  readonly reasonCodesError = computed(() => fieldErrors(this.problem())['reasonCodes'] ?? null);

  constructor() {
    effect(() => {
      if (this.decision()) {
        untracked(() => {
          this.selected.set(new Set());
          this.note.set('');
          this.touched.set(false);
        });
      }
    });
    effect(() => {
      const problem = this.problem();
      if (problem && problemCode(problem) === INVALID_REASON) {
        untracked(() => this.reasons.reload());
      }
    });
  }

  toggle(code: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.selected.update(current => {
      const next = new Set(current);
      if (checked) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  changeNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
  }

  submit(): void {
    this.touched.set(true);
    if (this.busy() || this.reloading() || checkFieldRules({ ...NOTE_RULES, required: this.noteRequired() }, this.note())) {
      return;
    }
    this.confirm.emit({ reasonCodes: this.chosen(), note: this.note().trim() ? this.note() : null });
  }

  close(): void {
    if (!this.busy() && this.decision()) {
      this.closed.emit();
    }
  }
}
