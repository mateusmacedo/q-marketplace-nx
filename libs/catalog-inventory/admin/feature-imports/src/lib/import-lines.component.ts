import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  BadgeComponent,
  ButtonDirective,
  ButtonGroupComponent,
  FormCheckLabelDirective,
  TableDirective
} from '@coreui/angular';
import type { ImportLineResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { CursorPaginationComponent, EmptyStateComponent } from '@marketplace/shared-admin-ui';
import { PAGE_SIZES } from '@marketplace/shared-web-util';

import { isFailedLine, lineTexts, outcomePresentation } from './import-presentation';

const DEFAULT_LINES_PER_PAGE = 50;

let nextId = 0;

/**
 * Resultado de cada linha do lote, na ordem do arquivo, com o filtro **Só falhas**. A linha que criou ou alterou um
 * produto leva a ele, e a recusa mostra a mensagem e os motivos do servidor, nunca o código. As linhas vêm todas de
 * uma vez, então a tabela as divide em páginas no próprio painel.
 */
@Component({
  selector: 'app-import-lines',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    ButtonDirective,
    ButtonGroupComponent,
    CursorPaginationComponent,
    EmptyStateComponent,
    FormCheckLabelDirective,
    RouterLink,
    TableDirective
  ],
  styles: '.numeric { font-variant-numeric: tabular-nums; }',
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <h3 class="h6 mb-0 me-auto">Linhas</h3>
      <c-button-group aria-label="Linhas mostradas" role="group" size="sm">
        <input
          (change)="showOnlyFailures(false)"
          [checked]="!onlyFailures()"
          [id]="ids.all"
          [name]="ids.name"
          autocomplete="off"
          class="btn-check"
          type="radio"
        />
        <label [for]="ids.all" cButton cFormCheckLabel color="secondary" variant="outline">Todas</label>
        <input
          (change)="showOnlyFailures(true)"
          [checked]="onlyFailures()"
          [id]="ids.failures"
          [name]="ids.name"
          autocomplete="off"
          class="btn-check"
          type="radio"
        />
        <label [for]="ids.failures" cButton cFormCheckLabel color="secondary" variant="outline">Só falhas</label>
      </c-button-group>
    </div>
    @if (rows().length) {
      <table [hover]="true" [responsive]="true" [striped]="true" align="middle" cTable class="mb-0 border">
        <thead class="text-nowrap text-truncate">
          <tr>
            <th class="bg-body-tertiary text-end" scope="col">Linha</th>
            <th class="bg-body-tertiary" scope="col">SKU</th>
            <th class="bg-body-tertiary" scope="col">Resultado</th>
            <th class="bg-body-tertiary" scope="col">Mensagem</th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.line.lineNumber) {
            <tr>
              <td class="text-end numeric">{{ row.line.lineNumber }}</td>
              <td class="text-nowrap">
                @if (row.line.productId) {
                  <a [routerLink]="['/loja/produtos', row.line.productId]">{{ row.line.sellerSku || 'Abrir produto' }}</a>
                } @else if (row.line.sellerSku) {
                  {{ row.line.sellerSku }}
                } @else {
                  <span class="text-body-secondary">Sem SKU</span>
                }
              </td>
              <td>
                <c-badge [color]="row.outcome.color">{{ row.outcome.label }}</c-badge>
              </td>
              <td>
                @if (row.texts.message) {
                  <div>{{ row.texts.message }}</div>
                }
                @if (row.texts.reasons.length) {
                  <ul class="mb-0 ps-3 small">
                    @for (reason of row.texts.reasons; track $index) {
                      <li>{{ reason }}</li>
                    }
                  </ul>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <app-empty-state [message]="emptyMessage()" />
    }
    @if (paged()) {
      <app-cursor-pagination
        (cursorChange)="goTo($event)"
        (limitChange)="resize($event)"
        [cursor]="cursor()"
        [limit]="pageSize()"
        [nextCursor]="nextCursor()"
      />
    }
  `
})
export class ImportLinesComponent {
  /** Linhas já processadas, em ordem de linha, como `GET /products/imports/{id}` devolve. */
  readonly lines = input<readonly ImportLineResponse[]>([]);

  readonly ids = filterIds(`app-import-lines-${nextId++}`);

  readonly onlyFailures = signal(false);
  readonly pageSize = signal(DEFAULT_LINES_PER_PAGE);
  readonly #offset = signal(0);

  readonly filtered = computed(() => (this.onlyFailures() ? this.lines().filter(isFailedLine) : this.lines()));
  readonly rows = computed(() =>
    this.filtered()
      .slice(this.#offset(), this.#offset() + this.pageSize())
      .map(line => ({ line, outcome: outcomePresentation(line.outcome), texts: lineTexts(line) }))
  );
  readonly cursor = computed(() => (this.#offset() ? String(this.#offset()) : undefined));
  readonly nextCursor = computed(() => {
    const next = this.#offset() + this.pageSize();
    return next < this.filtered().length ? String(next) : undefined;
  });
  readonly paged = computed(() => this.filtered().length > PAGE_SIZES[0] || this.#offset() > 0);
  readonly emptyMessage = computed(() =>
    this.onlyFailures() ? 'Nenhuma linha com falha.' : 'Nenhuma linha processada ainda.'
  );

  showOnlyFailures(only: boolean): void {
    this.onlyFailures.set(only);
    this.#offset.set(0);
  }

  goTo(cursor: string | undefined): void {
    this.#offset.set(Number(cursor) || 0);
  }

  resize(size: number): void {
    this.pageSize.set(size);
    this.#offset.set(0);
  }
}

function filterIds(name: string) {
  return { name, all: `${name}-all`, failures: `${name}-failures` };
}
