import { computed, signal } from '@angular/core';
import { allowsAction, type ServerActions } from '@marketplace/shared-web-util';

import type { BatchAction } from './batch-bar.component';

export interface ListSelectionOptions<T> {
  /** Identificador estável do item. */
  readonly id: (item: T) => string;
  /**
   * Se o item aceita a ação. O padrão segue `actions` do item, que o servidor calcula (ADR 031); a tela sem `actions`,
   * como Categorias, passa a própria regra de apresentação.
   */
  readonly accepts?: (item: T, action: string) => boolean;
}

/** Apresentação de um botão da barra de lote; a contagem vem da seleção. */
export type BatchActionPresentation = Omit<BatchAction, 'count'>;

/** Desfecho do lote que a seleção precisa: os itens gravados saem dela. */
export interface CompletedBatch<T> {
  readonly succeeded: readonly { readonly item: T }[];
}

/**
 * Seleção de uma lista com ações em lote (proposta de design, "Seleção e operações em lote"). A caixa do cabeçalho
 * marca a página, com estado parcial; a seleção continua ao trocar de página e é limpa quando muda o recorte da lista
 * (filtro, busca, ordem ou itens por página). Guarda os itens, e não só os ids, para contar as ações de cada um.
 */
export class ListSelection<T> {
  readonly #id: (item: T) => string;
  readonly #accepts: (item: T, action: string) => boolean;
  readonly #selected = signal<ReadonlyMap<string, T>>(new Map());
  readonly #page = signal<readonly T[]>([]);
  #scope: string | undefined;

  /** Selecionados, na ordem em que foram marcados. */
  readonly items = computed(() => [...this.#selected().values()]);
  readonly count = computed(() => this.#selected().size);
  /** Caixa do cabeçalho marcada: a página tem itens e todos estão selecionados. */
  readonly pageChecked = computed(() => {
    const page = this.#page();
    return page.length > 0 && page.every(item => this.#selected().has(this.#id(item)));
  });
  /** Caixa do cabeçalho parcial: só parte da página está selecionada. */
  readonly pageIndeterminate = computed(
    () => !this.pageChecked() && this.#page().some(item => this.#selected().has(this.#id(item)))
  );

  constructor(options: ListSelectionOptions<T>) {
    this.#id = options.id;
    this.#accepts = options.accepts ?? ((item, action) => allowsAction(item as ServerActions, action));
  }

  /**
   * Recebe a página carregada e o recorte que a produziu (os parâmetros da lista; `cursor` é ignorado). Recorte novo
   * limpa a seleção; na mesma lista, os selecionados da página são trocados pela versão recém-carregada, com as ações
   * e a versão atuais.
   */
  setPage(items: readonly T[], scope: Readonly<Record<string, unknown>> = {}): void {
    const key = scopeKey(scope);
    if (this.#scope !== undefined && this.#scope !== key) {
      this.#selected.set(new Map());
    }
    this.#scope = key;
    this.#page.set(items);
    const fresh = new Map(items.map(item => [this.#id(item), item]));
    if ([...this.#selected().keys()].some(id => fresh.has(id))) {
      this.#selected.update(selected => new Map([...selected].map(([id, item]) => [id, fresh.get(id) ?? item])));
    }
  }

  isSelected(item: T): boolean {
    return this.#selected().has(this.#id(item));
  }

  toggle(item: T, selected = !this.isSelected(item)): void {
    const id = this.#id(item);
    this.#selected.update(current => {
      const next = new Map(current);
      if (selected) {
        next.set(id, item);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  /** Marca ou desmarca a página inteira, como a caixa do cabeçalho ou "Selecionar todas deste nível". */
  togglePage(selected = !this.pageChecked()): void {
    this.#selected.update(current => {
      const next = new Map(current);
      for (const item of this.#page()) {
        if (selected) {
          next.set(this.#id(item), item);
        } else {
          next.delete(this.#id(item));
        }
      }
      return next;
    });
  }

  clear(): void {
    this.#selected.set(new Map());
  }

  /** Selecionados que aceitam a ação, os que vão no lote; os outros entram no resultado como ignorados. */
  accepting(action: string): T[] {
    return this.items().filter(item => this.#accepts(item, action));
  }

  /** Quantos selecionados aceitam a ação, para o botão da barra ("Pausar (2)"). */
  countFor(action: string): number {
    return this.accepting(action).length;
  }

  /** Botões da barra de lote com a contagem de cada ação, na ordem das apresentações. */
  batchActions(presentations: readonly BatchActionPresentation[]): BatchAction[] {
    return presentations.map(presentation => ({ ...presentation, count: this.countFor(presentation.id) }));
  }

  /** Tira da seleção os itens gravados; as falhas e os sem resposta continuam para uma nova tentativa. */
  completeBatch(run: CompletedBatch<T>): void {
    this.remove(run.succeeded.map(success => this.#id(success.item)));
  }

  /**
   * Tira da seleção os itens pelo id, como o que foi decidido, excluído ou movido fora do lote: eles saem da página
   * recarregada, e a seleção guardaria a versão e as ações antigas.
   */
  remove(ids: readonly string[]): void {
    const gone = new Set(ids);
    if ([...this.#selected().keys()].some(id => gone.has(id))) {
      this.#selected.update(current => new Map([...current].filter(([id]) => !gone.has(id))));
    }
  }
}

function scopeKey(scope: Readonly<Record<string, unknown>>): string {
  const entries = Object.entries(scope)
    .filter(([name, value]) => name !== 'cursor' && value !== undefined && value !== null && value !== '')
    .map(([name, value]) => [name, Array.isArray(value) ? value.map(String) : String(value)] as const)
    .sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(entries);
}
