import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  ButtonDirective,
  DropdownComponent,
  DropdownItemDirective,
  DropdownMenuDirective,
  DropdownToggleDirective,
  TooltipDirective
} from '@coreui/angular';
import { cilOptions } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import { actionState, type ServerActions } from '@marketplace/shared-web-util';

/** Item do menu "...". */
export interface ActionMenuItem {
  readonly id: string;
  readonly label: string;
  /** Ícone do `@coreui/icons` (`cilPencil`). */
  readonly icon?: string[];
  /** Ação destrutiva, em vermelho (arquivar, excluir, bloquear). */
  readonly danger?: boolean;
  /** Motivo de a ação estar barrada; com ele, o item aparece desabilitado e o motivo vai no tooltip. */
  readonly disabledReason?: string;
}

/** Ação escolhida, com o botão do menu para o modal devolver o foco (`returnFocus` do `app-confirm-modal`). */
export interface ActionMenuChoice {
  readonly id: string;
  readonly toggle: HTMLElement;
}

/** Apresentação de uma ação que o servidor devolve em `actions`; a tela guarda só isto (ADR 031). */
export type ActionPresentation = Omit<ActionMenuItem, 'disabledReason'>;

/**
 * Itens do menu a partir das ações que o servidor calculou, na ordem das apresentações: as de `actions` liberadas, as
 * de `blockedActions` desabilitadas com o motivo do servidor, e as demais de fora. Código que a tela não apresenta é
 * ignorado.
 */
export function serverActionMenu(
  source: ServerActions | null | undefined,
  presentations: readonly ActionPresentation[]
): ActionMenuItem[] {
  return presentations.flatMap(presentation => {
    const state = actionState(source, presentation.id);
    if (state.allowed) {
      return [presentation];
    }
    return state.blockedReason === undefined ? [] : [{ ...presentation, disabledReason: state.blockedReason }];
  });
}

/**
 * Menu "..." de ações do item, com o dropdown do template (o mesmo dos indicadores do dashboard), na última coluna da
 * lista e no cabeçalho da tela do item. Sem itens, o menu some.
 */
@Component({
  selector: 'app-action-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    DropdownComponent,
    DropdownItemDirective,
    DropdownMenuDirective,
    DropdownToggleDirective,
    IconDirective,
    TooltipDirective
  ],
  template: `
    @if (items().length) {
      <c-dropdown [popperOptions]="{ strategy: 'fixed' }" alignment="end" variant="btn-group">
        <button
          #toggle
          [attr.aria-label]="label()"
          [caret]="false"
          [disabled]="disabled()"
          cButton
          cDropdownToggle
          class="p-0"
          color="transparent"
          type="button"
        >
          <svg [cIcon]="optionsIcon" aria-hidden="true"></svg>
        </button>
        <ul cDropdownMenu>
          @for (item of items(); track item.id) {
            @if (item.disabledReason) {
              <li [cTooltip]="item.disabledReason" cTooltipPlacement="left">
                <button cDropdownItem disabled type="button">
                  @if (item.icon) {
                    <svg [cIcon]="item.icon" aria-hidden="true" class="me-2"></svg>
                  }
                  {{ item.label }}
                  <span class="visually-hidden">: {{ item.disabledReason }}</span>
                </button>
              </li>
            } @else {
              <li>
                <button (click)="choose(item.id, toggle)" [class.text-danger]="item.danger" cDropdownItem type="button">
                  @if (item.icon) {
                    <svg [cIcon]="item.icon" aria-hidden="true" class="me-2"></svg>
                  }
                  {{ item.label }}
                </button>
              </li>
            }
          }
        </ul>
      </c-dropdown>
    }
  `
})
export class ActionMenuComponent {
  readonly items = input<readonly ActionMenuItem[]>([]);
  /** Nome do botão para leitores de tela, com o item: "Ações de Camiseta Basic". */
  readonly label = input('Ações');
  /** Trava o menu durante uma gravação do item. */
  readonly disabled = input(false);

  readonly action = output<ActionMenuChoice>();

  readonly optionsIcon = cilOptions;

  choose(id: string, toggle: HTMLElement): void {
    this.action.emit({ id, toggle });
  }
}
