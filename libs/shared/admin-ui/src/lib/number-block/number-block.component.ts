import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type NumberBlockColor = 'primary' | 'secondary' | 'success' | 'info' | 'warning' | 'danger';

/**
 * Número em bloco do card "Traffic & Sales" do dashboard: borda colorida à esquerda, rótulo pequeno e valor em
 * destaque. Usado na importação, na fila e no painel do SKU.
 */
@Component({
  selector: 'app-number-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div [class]="classes()">
      <div class="text-body-secondary text-truncate small">{{ label() }}</div>
      <div class="fs-5 fw-semibold">{{ value() }}</div>
    </div>
  `,
})
export class NumberBlockComponent {
  readonly label = input.required<string>();
  readonly value = input<string | number | null | undefined>();
  readonly color = input<NumberBlockColor>('info');

  readonly classes = computed(
    () => `border-start border-start-4 border-start-${this.color()} py-1 px-3`,
  );
}
