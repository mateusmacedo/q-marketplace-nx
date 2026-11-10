import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  inject,
  input,
  model,
  output,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import {
  ButtonCloseDirective,
  ButtonDirective,
  OffcanvasBodyComponent,
  OffcanvasComponent,
  OffcanvasHeaderComponent,
  OffcanvasTitleDirective,
  SpinnerComponent,
} from '@coreui/angular';
import { isEditConflict, type Problem } from '@marketplace/shared-web-util';

import { EditConflictAlertComponent } from '../problem/edit-conflict-alert.component';
import { ProblemAlertComponent } from '../problem/problem-alert.component';

let nextId = 0;

/**
 * Offcanvas à direita do formulário médio (proposta de design, "Formulários"), com os campos por projeção dentro do
 * `form`, Cancelar e salvar com spinner. No topo, o conflito de edição com "Carregar versão atual" ou as mensagens do
 * servidor. Enquanto salva, não fecha; ao fechar, devolve o foco ao botão que o abriu (ou ao `returnFocus`).
 */
@Component({
  selector: 'app-form-offcanvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // WHY: no CoreUI 5.7.42 o `c-offcanvas` põe cabeçalho e corpo num `div` do foco preso, fora do flex do painel; sem
  // `display: contents` o corpo não rola e o fim de um formulário longo, com o botão de salvar, fica fora da tela.
  encapsulation: ViewEncapsulation.None,
  styles: 'app-form-offcanvas .offcanvas > div:not(.cdk-focus-trap-anchor) { display: contents; }',
  imports: [
    ButtonCloseDirective,
    ButtonDirective,
    EditConflictAlertComponent,
    OffcanvasBodyComponent,
    OffcanvasComponent,
    OffcanvasHeaderComponent,
    OffcanvasTitleDirective,
    ProblemAlertComponent,
    SpinnerComponent,
  ],
  template: `
    <c-offcanvas
      (visibleChange)="visible.set($event)"
      [attr.aria-labelledby]="titleId"
      [backdrop]="saving() ? 'static' : true"
      [keyboard]="!saving()"
      [style.--cui-offcanvas-width]="width()"
      [visible]="visible()"
      placement="end"
    >
      <c-offcanvas-header>
        <div>
          <h5 [id]="titleId" cOffcanvasTitle>{{ title() }}</h5>
          @if (subtitle()) {
            <small class="text-body-secondary">{{ subtitle() }}</small>
          }
        </div>
        <button (click)="cancel()" [disabled]="saving()" aria-label="Fechar" cButtonClose></button>
      </c-offcanvas-header>
      <c-offcanvas-body>
        <form (submit)="submit($event)" [id]="formId" novalidate>
          @if (conflict()) {
            <app-edit-conflict-alert (reload)="reload.emit()" [busy]="reloading()" />
          } @else if (problem()) {
            <app-problem-alert
              [fieldLabels]="fieldLabels()"
              [heading]="problemHeading()"
              [problem]="problem()"
              [showFieldErrors]="showFieldErrors()"
            />
          }
          <ng-content />
          <div class="d-flex justify-content-end gap-2 mt-4">
            <button (click)="cancel()" [disabled]="saving()" cButton color="secondary" type="button">Cancelar</button>
            <button [disabled]="saving() || !canSave()" cButton color="primary" type="submit">
              @if (saving()) {
                <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
              }
              {{ saveLabel() }}
            </button>
          </div>
        </form>
      </c-offcanvas-body>
    </c-offcanvas>
  `,
})
export class FormOffcanvasComponent {
  readonly visible = model(false);
  readonly title = input.required<string>();
  /** Linha de apoio do título, como o código do SKU. */
  readonly subtitle = input<string | undefined>();
  readonly saveLabel = input('Salvar');
  /** Gravação em andamento: trava os botões, mostra o spinner e impede fechar. */
  readonly saving = input(false);
  /** Libera o botão de salvar; a tela passa, por exemplo, a validade do formulário. */
  readonly canSave = input(true);
  /** Problema da última gravação; o conflito de edição abre o aviso com "Carregar versão atual". */
  readonly problem = input<Problem | null | undefined>();
  /** Frase antes das mensagens do problema, como "Não foi possível salvar". */
  readonly problemHeading = input<string | undefined>();
  /** Nome de cada campo, para a lista dos campos inválidos do alerta. */
  readonly fieldLabels = input<Readonly<Record<string, string>>>({});
  /**
   * Lista no alerta os campos inválidos do 400. Desligado por padrão, porque cada campo mostra o próprio erro
   * (`applyServerErrors`); ligue quando a tela passa só os erros sem campo no formulário.
   */
  readonly showFieldErrors = input(false);
  /** Carregando a versão atual depois do conflito. */
  readonly reloading = input(false);
  /** Largura do painel; no celular, quase a tela toda. */
  readonly width = input('min(32rem, 92vw)');
  /** Onde pôr o foco ao fechar, quando o botão que abriu o painel some (item de dropdown). */
  readonly returnFocus = input<HTMLElement | null | undefined>();

  /** Envio do formulário, pelo botão de salvar ou pelo Enter num campo. */
  readonly save = output<void>();
  readonly cancelled = output<void>();
  /** Pedido da versão atual depois do conflito de edição; o que foi digitado continua nos campos até a tela recarregar. */
  readonly reload = output<void>();

  readonly formId = `app-form-offcanvas-${nextId++}`;
  readonly titleId = `${this.formId}-title`;
  readonly conflict = computed(() => isEditConflict(this.problem()));

  readonly #document = inject(DOCUMENT);
  #opener: HTMLElement | null = null;
  #wasVisible = false;

  constructor() {
    effect(() => {
      const visible = this.visible();
      if (visible && !this.#wasVisible) {
        const active = this.#document.activeElement;
        this.#opener =
          active instanceof HTMLElement && active !== this.#document.body ? active : null;
      }
      if (!visible && this.#wasVisible) {
        const target = untracked(this.returnFocus) ?? this.#opener;
        // WHY: espera a transição do offcanvas do CoreUI, que tira o foco do painel ao terminar de fechar.
        setTimeout(() => target?.focus());
      }
      this.#wasVisible = visible;
    });
  }

  submit(event: Event): void {
    event.preventDefault();
    if (!this.saving() && this.canSave()) {
      this.save.emit();
    }
  }

  cancel(): void {
    this.visible.set(false);
    this.cancelled.emit();
  }
}
