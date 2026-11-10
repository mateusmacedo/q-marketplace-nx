import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { BadgeComponent, TableDirective } from '@coreui/angular';

import { type AttributeRow, attributeTypeLabel } from './category-model';

/**
 * Tabela do template com os atributos de uma categoria: rótulo com o código na linha de apoio, tipo com as opções,
 * obrigatório e variante em badges e, nos herdados, a categoria de origem.
 */
@Component({
  selector: 'app-category-attributes-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent, TableDirective],
  template: `
    @if (rows().length) {
      <div class="table-responsive">
        <table [hover]="true" [striped]="true" cTable class="mb-0 border align-middle">
          <thead class="text-nowrap text-truncate">
            <tr>
              <th class="bg-body-tertiary" scope="col">Atributo</th>
              <th class="bg-body-tertiary" scope="col">Tipo</th>
              <th class="bg-body-tertiary" scope="col">Regras</th>
              @if (showOrigin()) {
                <th class="bg-body-tertiary" scope="col">Herdado de</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.code) {
              <tr>
                <td>
                  <div>{{ row.label }}</div>
                  <div class="small text-body-secondary">{{ row.code }}</div>
                </td>
                <td>
                  <div>{{ typeLabel(row.type) }}</div>
                  @if (row.options.length) {
                    <div class="small text-body-secondary">{{ row.options.join(', ') }}</div>
                  }
                </td>
                <td>
                  @if (row.required || row.variant) {
                    @if (row.required) {
                      <c-badge class="me-1" color="primary">Obrigatório</c-badge>
                    }
                    @if (row.variant) {
                      <c-badge color="info">Variante do SKU</c-badge>
                    }
                  } @else {
                    <span class="text-body-secondary">–</span>
                  }
                </td>
                @if (showOrigin()) {
                  <td>{{ row.origin }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    } @else {
      <p class="text-body-secondary mb-0">{{ empty() }}</p>
    }
  `,
})
export class CategoryAttributesTableComponent {
  readonly rows = input.required<readonly AttributeRow[]>();
  readonly showOrigin = input(false);
  /** Texto da tabela sem atributos. */
  readonly empty = input('Nenhum.');

  readonly typeLabel = attributeTypeLabel;
}
