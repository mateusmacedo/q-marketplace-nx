import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ColDirective, PlaceholderAnimationDirective, PlaceholderDirective } from '@coreui/angular';

const WIDTHS = [12, 10, 11, 8, 9, 7];

/** Carregamento com o `Placeholder` do CoreUI: linhas que piscam no lugar do conteúdo. */
@Component({
  selector: 'app-loading-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ColDirective, PlaceholderAnimationDirective, PlaceholderDirective],
  template: `
    <div aria-busy="true" class="py-2" role="status">
      <span class="visually-hidden">{{ label() }}</span>
      @for (width of widths(); track $index) {
        <p aria-hidden="true" cPlaceholderAnimation="glow" class="mb-2">
          <span [cCol]="width" cPlaceholder></span>
        </p>
      }
    </div>
  `
})
export class LoadingStateComponent {
  readonly rows = input(5);
  /** Texto para leitor de tela. */
  readonly label = input('Carregando…');

  readonly widths = computed(() => Array.from({ length: this.rows() }, (_, index) => WIDTHS[index % WIDTHS.length]));
}
