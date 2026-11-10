import type {
  ImportLineResponse,
  ImportResponse,
  ImportStatus,
} from '@marketplace/catalog-inventory-admin-data-access';

/** Prefixo das chamadas do cliente gerado nos testes (`provideCatalogInventoryApi`). */
export const API = '/api/catalog-inventory';

const realSetTimeout = globalThis.setTimeout;

/**
 * Espera uma volta do laço de eventos com o `setTimeout` real, para as promessas do cliente gerado andarem mesmo com
 * os timers falsos do Vitest ligados.
 */
export function settle(): Promise<void> {
  return new Promise((resolve) => realSetTimeout(resolve, 0));
}

/** Lote como `GET /products/imports/{id}` devolve, com as contagens tiradas das linhas. */
export function importReport(
  status: ImportStatus,
  options: { id?: string; processed?: number; total?: number; lines?: ImportLineResponse[] } = {},
): ImportResponse {
  const lines = options.lines ?? [];
  const count = (outcome: string) => lines.filter((line) => line.outcome === outcome).length;
  return {
    id: options.id ?? 'b1',
    status,
    totalLines: options.total ?? 100,
    processedLines: options.processed ?? lines.length,
    counts: {
      created: count('CREATED'),
      updated: count('UPDATED'),
      unchanged: count('UNCHANGED'),
      failed: count('FAILED'),
    },
    createdAt: '2026-11-08T17:20:00Z',
    startedAt: null,
    finishedAt: null,
    lines,
  };
}

/** O mesmo lote como `GET /products/imports/{id}?lines=false` devolve: as contagens das linhas, sem elas. */
export function importProgress(...args: Parameters<typeof importReport>): ImportResponse {
  return { ...importReport(...args), lines: null };
}
