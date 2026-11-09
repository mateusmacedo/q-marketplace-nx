export type SortDirection = 'asc' | 'desc';

/** Ordem de uma lista: a coluna e o sentido. Na URL vira `updatedAt` (crescente) ou `-updatedAt` (decrescente). */
export interface SortState {
  readonly field: string;
  readonly direction: SortDirection;
}

/** Lê a ordem da URL (`-updatedAt`); texto vazio ou ausente devolve `null`. */
export function parseSort(value: string | null | undefined): SortState | null {
  const text = value?.trim();
  if (!text || text === '-') {
    return null;
  }
  return text.startsWith('-') ? { field: text.slice(1), direction: 'desc' } : { field: text, direction: 'asc' };
}

/** Escreve a ordem no formato da URL e da API. */
export function formatSort(sort: SortState): string {
  return sort.direction === 'desc' ? `-${sort.field}` : sort.field;
}

/**
 * Ordem depois do clique no cabeçalho: a coluna ativa inverte o sentido, e outra coluna começa no sentido padrão dela
 * (texto em ordem crescente, datas da mais recente para a mais antiga).
 */
export function nextSort(current: SortState | null, field: string, firstDirection: SortDirection): SortState {
  if (current?.field === field) {
    return { field, direction: current.direction === 'asc' ? 'desc' : 'asc' };
  }
  return { field, direction: firstDirection };
}
