import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { AlertComponent, ButtonDirective, SpinnerComponent } from '@coreui/angular';

/**
 * Aviso de conflito de edição (412 ou 409 `concurrent-update`, ver `isEditConflict`). O que a pessoa digitou continua
 * no formulário até ela pedir a versão atual, para que possa copiar o que precisa.
 */
@Component({
  selector: 'app-edit-conflict-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent, ButtonDirective, SpinnerComponent],
  template: `
    <c-alert color="warning" role="alert">
      <strong>Outra pessoa alterou este registro depois que você abriu a tela.</strong>
      O que você digitou continua nos campos.
      <button (click)="reload.emit()" [disabled]="busy()" cButton class="mt-2 d-block" color="warning" size="sm" type="button">
        @if (busy()) {
          <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
        }
        Carregar versão atual
      </button>
    </c-alert>
  `
})
export class EditConflictAlertComponent {
  /** Carregando a versão atual: trava o botão e mostra o spinner. */
  readonly busy = input(false);

  readonly reload = output<void>();
}
