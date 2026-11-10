import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ButtonDirective,
  FormControlDirective,
  InputGroupComponent,
  InputGroupTextDirective,
  NavComponent,
  NavItemComponent,
  NavLinkDirective,
} from '@coreui/angular';
import { cilSearch } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';
import type { ProductStatus } from '@marketplace/catalog-inventory-admin-ui';

import { SEARCH_MAX_LENGTH, STATUS_TABS } from './product-list-query';

/**
 * Abas de status e busca das listas de produtos. As abas são links que trocam o `status` da URL e voltam à primeira
 * página; a busca vai para a URL ao enviar. Filtros a mais, como a Loja, entram por projeção ao lado da busca.
 */
@Component({
  selector: 'app-product-list-filters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    FormControlDirective,
    IconDirective,
    InputGroupComponent,
    InputGroupTextDirective,
    NavComponent,
    NavItemComponent,
    NavLinkDirective,
    RouterLink,
  ],
  template: `
    <c-nav aria-label="Status dos produtos" class="mb-3" variant="tabs">
      @for (tab of tabs; track tab.label) {
        <c-nav-item>
          <a
            [active]="tab.status === status()"
            [attr.aria-current]="tab.status === status() ? 'page' : null"
            [queryParams]="{ status: tab.status, cursor: null }"
            [routerLink]="[]"
            cNavLink
            queryParamsHandling="merge"
          >
            {{ tab.label }}
          </a>
        </c-nav-item>
      }
    </c-nav>
    <div class="row g-2 mb-3">
      <form (submit)="submit($event)" class="col-md-6 col-xl-5" role="search">
        <c-input-group>
          <span cInputGroupText><svg [cIcon]="searchIcon" aria-hidden="true"></svg></span>
          <input
            (input)="typed($event)"
            [attr.aria-describedby]="shownError() ? 'product-search-error' : null"
            [attr.maxlength]="maxLength"
            [valid]="shownError() ? false : undefined"
            [value]="draft()"
            aria-label="Buscar por título ou SKU"
            cFormControl
            name="q"
            placeholder="Buscar por título ou SKU"
            type="search"
          />
          <button cButton color="secondary" type="submit" variant="outline">Buscar</button>
        </c-input-group>
        @if (shownError(); as message) {
          <div class="invalid-feedback d-block" id="product-search-error">{{ message }}</div>
        }
      </form>
      <ng-content />
    </div>
  `,
})
export class ProductListFiltersComponent {
  /** Aba ativa; `null` é Todos. */
  readonly status = input<ProductStatus | null>(null);
  /** Busca aplicada, da URL. */
  readonly q = input('');
  /** Recusa do servidor para a busca aplicada (o 400 de `q`), mostrada embaixo do campo. */
  readonly error = input<string | null>(null);

  /** Busca enviada; vazia tira o filtro. */
  readonly searched = output<string>();

  readonly tabs = STATUS_TABS;
  readonly searchIcon = cilSearch;
  readonly maxLength = SEARCH_MAX_LENGTH;
  readonly draft = linkedSignal(() => this.q());
  readonly #tooLong = signal(false);
  readonly shownError = computed(() =>
    this.#tooLong() ? `A busca aceita até ${SEARCH_MAX_LENGTH} caracteres.` : this.error(),
  );

  typed(event: Event): void {
    this.#tooLong.set(false);
    this.draft.set((event.target as HTMLInputElement).value);
  }

  submit(event: Event): void {
    event.preventDefault();
    const q = this.draft().trim();
    if (q.length > SEARCH_MAX_LENGTH) {
      this.#tooLong.set(true);
      return;
    }
    this.searched.emit(q);
  }
}
