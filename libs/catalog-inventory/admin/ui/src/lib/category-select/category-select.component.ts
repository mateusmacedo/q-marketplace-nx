import { ChangeDetectionStrategy, Component, computed, forwardRef, input, model, signal } from '@angular/core';
import { type ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { FormSelectDirective } from '@coreui/angular';

import type { CategoryOption } from './category-options';

/** Valor da opção "Raiz" (`rootLabel`), para mover a categoria para o topo da árvore. */
export const CATEGORY_ROOT = '__root__';

let nextId = 0;

/**
 * `form-select` de categoria com o caminho completo de cada opção, na ordem da árvore. Usado em novo produto, trocar
 * categoria e mover categoria: a feature monta a lista (`buildCategoryOptions`) e passa as exclusões, como a categoria
 * atual ou as descendentes. Funciona com `[(value)]` ou com formulários reativos.
 */
@Component({
  selector: 'app-category-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormSelectDirective],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => CategorySelectComponent), multi: true }],
  template: `
    <select
      (blur)="touched()"
      (change)="select($event)"
      [attr.aria-describedby]="describedBy()"
      [disabled]="isDisabled()"
      [id]="selectId()"
      [valid]="invalid() ? false : undefined"
      cSelect
    >
      <option [selected]="!value()" disabled value="">{{ placeholder() }}</option>
      @if (rootLabel()) {
        <option [selected]="value() === root" [value]="root">{{ rootLabel() }}</option>
      }
      @for (option of visibleOptions(); track option.id) {
        <option [selected]="option.id === value()" [value]="option.id">
          {{ option.path }}{{ option.usable === false ? ' (inativa)' : '' }}
        </option>
      }
    </select>
  `
})
export class CategorySelectComponent implements ControlValueAccessor {
  /** Opções com o caminho montado, na ordem em que aparecem. */
  readonly options = input.required<readonly CategoryOption[]>();
  /** Ids que não podem ser escolhidos (categoria atual, descendentes, pai atual). */
  readonly excluded = input<readonly string[]>([]);
  /** Id escolhido; `CATEGORY_ROOT` quando a opção "Raiz" foi escolhida. */
  readonly value = model<string | null>(null);
  readonly placeholder = input('Escolha a categoria');
  /** Rótulo da opção de raiz, só no mover. */
  readonly rootLabel = input<string | null>(null);
  readonly selectId = input(`app-category-select-${nextId++}`);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);
  readonly disabled = input(false);

  readonly root = CATEGORY_ROOT;
  readonly visibleOptions = computed(() => {
    const excluded = new Set(this.excluded());
    return this.options().filter(option => !excluded.has(option.id));
  });

  readonly #formDisabled = signal(false);
  readonly isDisabled = computed(() => this.disabled() || this.#formDisabled());
  #onChange: (value: string | null) => void = () => undefined;
  #onTouched: () => void = () => undefined;

  select(event: Event): void {
    const value = (event.target as HTMLSelectElement).value || null;
    this.value.set(value);
    this.#onChange(value);
  }

  touched(): void {
    this.#onTouched();
  }

  writeValue(value: string | null): void {
    this.value.set(value ?? null);
  }

  registerOnChange(fn: (value: string | null) => void): void {
    this.#onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.#onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.#formDisabled.set(disabled);
  }
}
