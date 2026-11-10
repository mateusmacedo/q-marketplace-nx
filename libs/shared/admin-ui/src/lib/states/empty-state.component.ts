import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Estado vazio de uma lista: o texto e a ação projetada ("Nenhum produto em rascunho." com o botão "Criar produto").
 */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="text-center text-body-secondary py-5">
      <p class="mb-2">{{ message() }}</p>
      <ng-content />
    </div>
  `,
})
export class EmptyStateComponent {
  readonly message = input.required<string>();
}
