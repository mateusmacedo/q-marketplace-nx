import { ChangeDetectionStrategy, Component, computed, effect, input, model, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormTextDirective
} from '@coreui/angular';
import {
  createModerationReason,
  CreateModerationReasonRequestSchema,
  getModerationReason,
  type ModerationDecision,
  reviseModerationReason,
  ReviseModerationReasonRequestSchema
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  applyServerErrors,
  controlMessage,
  ErrorStateComponent,
  FormOffcanvasComponent,
  LoadingStateComponent,
  SCHEMA_ERROR,
  schemaValidator
} from '@marketplace/shared-admin-ui';
import {
  apiData,
  apiDataWithVersion,
  fieldRules,
  type Problem,
  problemCode,
  type RuleViolation,
  toProblem
} from '@marketplace/shared-web-util';

import { DECISIONS } from './moderation-decisions';

/** Resultado da gravação, para o toast da lista. */
export type ReasonSaved = 'created' | 'updated';

type ReasonField = 'code' | 'label' | 'decisions' | 'position';

const CREATE_RULES = {
  code: fieldRules(CreateModerationReasonRequestSchema, 'code'),
  label: fieldRules(CreateModerationReasonRequestSchema, 'label'),
  decisions: fieldRules(CreateModerationReasonRequestSchema, 'decisions'),
  position: fieldRules(CreateModerationReasonRequestSchema, 'position')
};
const REVISE_RULES = {
  label: fieldRules(ReviseModerationReasonRequestSchema, 'label'),
  decisions: fieldRules(ReviseModerationReasonRequestSchema, 'decisions'),
  position: fieldRules(ReviseModerationReasonRequestSchema, 'position')
};
const CODE_HINT = CreateModerationReasonRequestSchema.properties.code.description;
const DUPLICATE_CODE = 'duplicate-moderation-reason-code';
const FIELD_LABELS: Readonly<Record<string, string>> = {
  code: 'Código',
  label: 'Rótulo',
  decisions: 'Decisões',
  position: 'Posição'
};

/**
 * Offcanvas de novo motivo e de editar motivo (proposta de design, seção "11. Motivos de moderação"). Na edição, lê o
 * motivo com o ETag e grava com `If-Match`; o código fica só leitura. Os limites dos campos vêm do contrato, e o 400,
 * o 409 e o 422 do servidor continuam sendo a palavra final.
 */
@Component({
  selector: 'app-moderation-reason-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormOffcanvasComponent,
    FormTextDirective,
    LoadingStateComponent,
    ReactiveFormsModule
  ],
  template: `
    <app-form-offcanvas
      (reload)="load()"
      (save)="save()"
      [(visible)]="visible"
      [canSave]="ready()"
      [fieldLabels]="fieldLabels"
      [problem]="ready() ? problem() : null"
      [reloading]="loading()"
      [returnFocus]="returnFocus()"
      [saveLabel]="editing() ? 'Salvar' : 'Criar motivo'"
      [saving]="saving()"
      [showFieldErrors]="showFieldErrors()"
      [subtitle]="code() ?? undefined"
      [title]="editing() ? 'Editar motivo' : 'Novo motivo'"
      problemHeading="Não foi possível salvar o motivo"
    >
      @if (!ready()) {
        @if (problem()) {
          <app-error-state (retry)="load()" [problem]="problem()" message="Não foi possível carregar o motivo." />
        } @else {
          <app-loading-state [rows]="4" label="Carregando o motivo…" />
        }
      } @else {
        <div [formGroup]="form">
          <div class="mb-3">
            <label cLabel for="moderation-reason-code">Código</label>
            <input
              [attr.maxlength]="rules().code?.maxLength ?? null"
              [readOnly]="editing()"
              [valid]="invalid('code') ? false : undefined"
              aria-describedby="moderation-reason-code-help"
              autocomplete="off"
              cFormControl
              formControlName="code"
              id="moderation-reason-code"
              type="text"
            />
            @if (invalid('code')) {
              <div class="invalid-feedback d-block">{{ message('code') }}</div>
            }
            <div cFormText id="moderation-reason-code-help">{{ editing() ? 'O código não muda depois de criado.' : codeHint }}</div>
          </div>
          <div class="mb-3">
            <label cLabel for="moderation-reason-label">Rótulo</label>
            <input
              [attr.maxlength]="rules().label.maxLength ?? null"
              [valid]="invalid('label') ? false : undefined"
              cFormControl
              formControlName="label"
              id="moderation-reason-label"
              type="text"
            />
            @if (invalid('label')) {
              <div class="invalid-feedback d-block">{{ message('label') }}</div>
            }
            @if (editing()) {
              <div cFormText>As decisões já tomadas guardam o rótulo da época; o rótulo novo vale para as próximas.</div>
            }
          </div>
          <fieldset class="mb-3">
            <legend class="form-label fs-6">Decisões</legend>
            @for (option of decisionOptions; track option.code) {
              <c-form-check [inline]="true">
                <input
                  (blur)="form.controls.decisions.markAsTouched()"
                  (change)="toggleDecision(option.code, $event)"
                  [checked]="hasDecision(option.code)"
                  [id]="'moderation-reason-decision-' + option.code"
                  cFormCheckInput
                  type="checkbox"
                />
                <label [for]="'moderation-reason-decision-' + option.code" cFormCheckLabel>{{ option.label }}</label>
              </c-form-check>
            }
            @if (invalid('decisions')) {
              <div class="invalid-feedback d-block">{{ message('decisions') }}</div>
            }
          </fieldset>
          <div class="mb-3">
            <label cLabel for="moderation-reason-position">Posição</label>
            <input
              [attr.max]="rules().position.maximum ?? null"
              [attr.min]="rules().position.minimum ?? null"
              [valid]="invalid('position') ? false : undefined"
              aria-describedby="moderation-reason-position-help"
              cFormControl
              formControlName="position"
              id="moderation-reason-position"
              step="1"
              type="number"
            />
            @if (invalid('position')) {
              <div class="invalid-feedback d-block">{{ message('position') }}</div>
            }
            <div cFormText id="moderation-reason-position-help">
              {{
                editing()
                  ? 'Ordem na lista do moderador; no empate, vale o código.'
                  : 'Opcional. Sem posição, o motivo entra no fim da lista.'
              }}
            </div>
          </div>
        </div>
      }
    </app-form-offcanvas>
  `
})
export class ModerationReasonFormComponent {
  readonly visible = model(false);
  /** Código do motivo em edição; `null` cria um motivo novo. */
  readonly code = input<string | null>(null);
  /** Onde pôr o foco ao fechar, quando o item do menu que abriu o painel some. */
  readonly returnFocus = input<HTMLElement | null | undefined>();

  readonly saved = output<ReasonSaved>();
  /** O código já existe no tenant, talvez num motivo inativo; a lista liga "Mostrar inativos". */
  readonly duplicate = output<void>();

  readonly codeHint = CODE_HINT;
  readonly fieldLabels = FIELD_LABELS;
  readonly decisionOptions = DECISIONS.filter(decision => decision.code !== 'APPROVE');

  readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true }),
    label: new FormControl('', { nonNullable: true }),
    decisions: new FormControl<ModerationDecision[]>([], { nonNullable: true }),
    position: new FormControl<number | null>(null)
  });

  readonly editing = computed(() => !!this.code());
  readonly rules = computed(() => (this.editing() ? { ...REVISE_RULES, code: undefined } : CREATE_RULES));
  readonly loading = signal(false);
  /** O formulário tem os dados para editar: na criação, logo ao abrir; na edição, depois de ler o motivo. */
  readonly ready = signal(false);
  readonly saving = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly showFieldErrors = signal(false);

  #version: string | undefined;
  #request = 0;

  constructor() {
    effect(() => {
      const open = this.visible();
      const code = this.code();
      if (open) {
        untracked(() => this.#open(code));
      }
    });
  }

  hasDecision(code: ModerationDecision): boolean {
    return this.form.controls.decisions.value.includes(code);
  }

  toggleDecision(code: ModerationDecision, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const control = this.form.controls.decisions;
    const others = control.value.filter(decision => decision !== code);
    control.setValue(checked ? [...others, code] : others);
    control.markAsTouched();
  }

  invalid(name: ReasonField): boolean {
    const control = this.form.controls[name];
    return control.touched && control.invalid;
  }

  message(name: ReasonField): string | null {
    const control = this.form.controls[name];
    const violation = control.errors?.[SCHEMA_ERROR] as RuleViolation | undefined;
    if (name === 'decisions' && (violation?.kind === 'required' || violation?.kind === 'minItems')) {
      return 'Marque pelo menos uma decisão.';
    }
    return controlMessage(control);
  }

  /** Lê o motivo em edição com o ETag; depois do conflito, traz a versão atual por cima do que foi digitado. */
  async load(): Promise<void> {
    const code = this.code();
    if (!code) {
      return;
    }
    const request = ++this.#request;
    this.loading.set(true);
    try {
      const { data, version } = await apiDataWithVersion(getModerationReason({ path: { code } }));
      if (request !== this.#request) {
        return;
      }
      this.#version = version;
      this.form.reset({
        code: data.code ?? code,
        label: data.label ?? '',
        decisions: [...(data.decisions ?? [])],
        position: data.position ?? null
      });
      this.problem.set(null);
      this.showFieldErrors.set(false);
      this.ready.set(true);
    } catch (error) {
      if (request === this.#request) {
        this.problem.set(toProblem(error));
      }
    } finally {
      if (request === this.#request) {
        this.loading.set(false);
      }
    }
  }

  async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving() || !this.ready()) {
      return;
    }
    const { code, label, decisions, position } = this.form.getRawValue();
    const editing = this.code();
    this.saving.set(true);
    this.problem.set(null);
    try {
      if (editing) {
        await apiData(
          reviseModerationReason({
            path: { code: editing },
            headers: this.#version ? { 'If-Match': this.#version } : undefined,
            body: { label, decisions, position: Number(position) }
          })
        );
      } else {
        await apiData(
          createModerationReason({ body: { code, label, decisions, ...(position === null ? {} : { position }) } })
        );
      }
      this.saved.emit(editing ? 'updated' : 'created');
      this.visible.set(false);
    } catch (error) {
      this.#refused(toProblem(error));
    } finally {
      this.saving.set(false);
    }
  }

  #refused(problem: Problem): void {
    if (problemCode(problem) === DUPLICATE_CODE) {
      this.duplicate.emit();
    }
    const unmatched = applyServerErrors(this.form, problem);
    this.showFieldErrors.set(unmatched.length > 0);
    this.problem.set(unmatched.length ? { ...problem, errors: unmatched } : problem);
  }

  #open(code: string | null): void {
    this.#request++;
    this.#version = undefined;
    this.problem.set(null);
    this.showFieldErrors.set(false);
    this.saving.set(false);
    this.loading.set(false);
    const rules = code ? REVISE_RULES : CREATE_RULES;
    this.form.controls.code.setValidators(code ? [] : [schemaValidator(CREATE_RULES.code)]);
    this.form.controls.label.setValidators([schemaValidator(rules.label)]);
    this.form.controls.decisions.setValidators([schemaValidator(rules.decisions)]);
    this.form.controls.position.setValidators([schemaValidator(rules.position)]);
    this.form.reset({ code: code ?? '', label: '', decisions: [], position: null });
    this.ready.set(!code);
    if (code) {
      void this.load();
    }
  }
}
