import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  ButtonDirective,
  ButtonGroupComponent,
  ButtonToolbarComponent,
  SpinnerComponent,
} from '@coreui/angular';
import { plural } from '@marketplace/shared-web-util';

/** Botão da barra de lote. */
export interface BatchAction {
  readonly id: string;
  /** Rótulo do botão ("Pausar"). */
  readonly label: string;
  /** Quantos selecionados aceitam a ação no status atual; em 0, o botão fica desabilitado. */
  readonly count: number;
  /** Rótulo durante a chamada ("Pausando"). */
  readonly runningLabel?: string;
  readonly color?: 'primary' | 'secondary' | 'success' | 'warning' | 'danger' | 'info';
}

/**
 * Barra de ações em lote do template (`btn-toolbar` com `btn-group`), acima da tabela quando há seleção. Cada botão
 * mostra quantos selecionados aceitam a ação. Durante a chamada em lote, o botão da ação mostra o spinner e a barra
 * inteira fica travada; o resultado de cada item vai para o `app-batch-result-alert`.
 */
@Component({
  selector: 'app-batch-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective, ButtonGroupComponent, ButtonToolbarComponent, SpinnerComponent],
  template: `
    @if (selectedCount() > 0 || running()) {
      <c-button-toolbar aria-label="Ações em lote" class="mb-3 align-items-center gap-2" role="toolbar">
        <span class="fw-semibold me-1">{{ selectedText() }}</span>
        <c-button-group aria-label="Ações para os selecionados" role="group">
          @for (action of actions(); track action.id) {
            <button
              (click)="run.emit(action.id)"
              [color]="action.color ?? 'primary'"
              [disabled]="action.count === 0 || !!running()"
              cButton
              type="button"
              variant="outline"
            >
              @if (running() === action.id) {
                <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
                {{ action.runningLabel ?? action.label }}…
              } @else {
                {{ action.label }} ({{ action.count }})
              }
            </button>
          }
        </c-button-group>
        <button (click)="clear.emit()" [disabled]="!!running()" cButton color="link" type="button">Limpar seleção</button>
        <span class="visually-hidden" role="status">{{ statusText() }}</span>
      </c-button-toolbar>
    }
  `,
})
export class BatchBarComponent {
  readonly selectedCount = input.required<number>();
  readonly actions = input<readonly BatchAction[]>([]);
  /** Id da ação em execução; enquanto houver, a barra fica travada. */
  readonly running = input<string | null | undefined>();

  /** Ação escolhida; a tela confirma, quando a ação pede, e chama o endpoint em lote. */
  readonly run = output<string>();
  readonly clear = output<void>();

  readonly selectedText = computed(() =>
    plural(this.selectedCount(), 'selecionado', 'selecionados'),
  );
  readonly statusText = computed(() => {
    const action = this.actions().find((item) => item.id === this.running());
    return action ? `${action.runningLabel ?? action.label} ${this.selectedText()}…` : '';
  });
}
