import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { cilArrowBottom, cilArrowTop } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import { formatSort, nextSort, parseSort, type SortDirection } from '@marketplace/shared-web-util';

/**
 * Cabeçalho ordenável, a única parte própria das listas (ADR 029): `<th app-sort-header="updatedAt">` ganha o
 * `aria-sort` e o botão com a seta. A coluna ativa inverte a ordem; outra coluna começa pela ordem padrão dela. A ordem
 * sai no formato da URL e da API (`-updatedAt`), e a lista recomeça do primeiro cursor.
 */
@Component({
  selector: 'th[app-sort-header]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconDirective],
  host: { '[attr.aria-sort]': 'ariaSort()' },
  template: `
    <button
      (click)="toggle()"
      class="btn btn-link p-0 text-reset fw-semibold text-decoration-none text-nowrap"
      type="button"
    >
      <ng-content />
      <svg [cIcon]="icon()" [class.text-body-tertiary]="!active()" aria-hidden="true" class="ms-1" size="sm"></svg>
    </button>
  `,
})
export class SortHeaderComponent {
  /** Campo de ordenação da coluna na API. */
  readonly field = input.required<string>({ alias: 'app-sort-header' });
  /** Ordem atual da lista, como está na URL. */
  readonly sort = input<string | null | undefined>();
  /** Ordem ao escolher a coluna: crescente para texto, decrescente para datas. */
  readonly firstDirection = input<SortDirection>('asc');

  readonly sortChange = output<string>();

  readonly #state = computed(() => parseSort(this.sort()));
  readonly active = computed(() => this.#state()?.field === this.field());
  readonly ariaSort = computed(() => {
    if (!this.active()) {
      return 'none';
    }
    return this.#state()?.direction === 'asc' ? 'ascending' : 'descending';
  });
  readonly icon = computed(() => (this.ariaSort() === 'ascending' ? cilArrowTop : cilArrowBottom));

  toggle(): void {
    this.sortChange.emit(formatSort(nextSort(this.#state(), this.field(), this.firstDirection())));
  }
}
