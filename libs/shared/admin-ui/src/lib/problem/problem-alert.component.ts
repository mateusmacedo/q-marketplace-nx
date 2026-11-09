import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { AlertComponent } from '@coreui/angular';
import { type Problem, problemMessages } from '@marketplace/shared-web-util';

/**
 * Alerta de um problema RFC 9457 no topo do formulário ou da tela: a mensagem de cada motivo, vinda do servidor em
 * `reasonDetails` (ou o `detail` e o `title` do problema), e a lista dos campos inválidos. O código cru nunca aparece.
 */
@Component({
  selector: 'app-problem-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent],
  template: `
    @if (problem()) {
      <c-alert color="danger" role="alert">
        @if (heading()) {
          <p class="fw-semibold mb-1">{{ heading() }}</p>
        }
        @if (messages().length === 1) {
          <p class="mb-0">{{ messages()[0] }}</p>
        } @else {
          <ul class="mb-0 ps-3">
            @for (message of messages(); track $index) {
              <li>{{ message }}</li>
            }
          </ul>
        }
        @if (showFieldErrors() && fieldErrors().length) {
          <ul class="mb-0 mt-2 ps-3">
            @for (error of fieldErrors(); track $index) {
              <li>
                @if (error.label) {<strong>{{ error.label }}</strong>: }{{ error.message }}
              </li>
            }
          </ul>
        }
      </c-alert>
    }
  `
})
export class ProblemAlertComponent {
  readonly problem = input<Problem | null | undefined>();
  /** Nome de cada campo do formulário, para a lista dos campos inválidos. */
  readonly fieldLabels = input<Readonly<Record<string, string>>>({});
  /** Frase antes das mensagens, como "Não foi possível salvar". */
  readonly heading = input<string | undefined>();
  /** Lista também os campos inválidos; desligue quando o formulário já mostra cada erro embaixo do campo. */
  readonly showFieldErrors = input(true);

  readonly messages = computed(() => {
    const problem = this.problem();
    return problem ? problemMessages(problem) : [];
  });
  /** Campos inválidos com mensagem; o erro sem campo aparece sem o nome do campo. */
  readonly fieldErrors = computed(() =>
    (this.problem()?.errors ?? []).flatMap(({ field, message }) =>
      message ? [{ label: field ? this.fieldLabel(field) : null, message }] : []
    )
  );

  fieldLabel(field: string): string {
    return this.fieldLabels()[field] ?? field;
  }
}
