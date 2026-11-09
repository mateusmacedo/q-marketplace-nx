import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ButtonDirective } from '@coreui/angular';
import { type Problem, problemMessages } from '@marketplace/shared-web-util';

/** Erro ao carregar uma lista ou um registro, com o motivo e o botão da ação ("Tentar de novo", por padrão). */
@Component({
  selector: 'app-error-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective],
  template: `
    <div class="text-center py-5" role="alert">
      <p class="mb-1">{{ message() }}</p>
      @if (detail()) {
        <p class="small text-body-secondary mb-3">{{ detail() }}</p>
      }
      <button (click)="retry.emit()" cButton class="mt-2" color="primary" type="button" variant="outline">
        {{ actionLabel() }}
      </button>
    </div>
  `
})
export class ErrorStateComponent {
  readonly message = input('Não foi possível carregar os dados.');
  /** Problema da chamada; a mensagem dele (do servidor, com `detail` e `title` de reserva) aparece embaixo do texto. */
  readonly problem = input<Problem | null | undefined>();
  /** Texto do botão, para quando a saída não é repetir a chamada, como "Entrar de novo". */
  readonly actionLabel = input('Tentar de novo');

  readonly retry = output<void>();

  readonly detail = computed(() => {
    const problem = this.problem();
    return problem ? problemMessages(problem).join(' ') : '';
  });
}
