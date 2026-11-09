import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { AlertComponent } from '@coreui/angular';

/** Item do lote que a API recusou, com o nome que a pessoa reconhece e os motivos já traduzidos. */
export interface BatchFailure {
  readonly id: string;
  readonly label: string;
  readonly messages: readonly string[];
}

/**
 * Alerta acima da lista com os itens do lote que falharam, cada um com o motivo traduzido. Os itens que falharam
 * continuam selecionados para uma nova tentativa; o resumo do lote vai num toast.
 */
@Component({
  selector: 'app-batch-result-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent],
  template: `
    @if (failures().length) {
      <c-alert (visibleChange)="!$event && dismiss.emit()" ariaCloseLabel="Fechar" color="danger" dismissible role="alert">
        <p class="fw-semibold mb-2">{{ title() }}</p>
        <ul class="mb-0 ps-3">
          @for (failure of failures(); track failure.id) {
            <li>
              <strong>{{ failure.label }}</strong>: {{ failure.messages.join(' ') }}
            </li>
          }
        </ul>
      </c-alert>
    }
  `
})
export class BatchResultAlertComponent {
  readonly failures = input<readonly BatchFailure[]>([]);
  readonly title = input('Alguns itens não foram processados');

  /** O alerta foi fechado; a tela limpa a lista de falhas. */
  readonly dismiss = output<void>();
}
