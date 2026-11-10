import type { ImportLineResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { GENERIC_REASON_MESSAGE, problemMessages } from '@marketplace/shared-web-util';

/** Cor do badge, entre as do template. */
export type BadgeColor = 'success' | 'info' | 'secondary' | 'danger';

/** Rótulo e cor de um código do servidor; só apresentação (ADR 031). */
export interface Presentation {
  readonly label: string;
  readonly color: BadgeColor;
}

const STATUSES: Readonly<Record<string, Presentation>> = {
  PENDING: { label: 'Na fila', color: 'secondary' },
  PROCESSING: { label: 'Processando', color: 'info' },
  COMPLETED: { label: 'Concluída', color: 'success' },
};

const OUTCOMES: Readonly<Record<string, Presentation>> = {
  CREATED: { label: 'Criada', color: 'success' },
  UPDATED: { label: 'Atualizada', color: 'info' },
  UNCHANGED: { label: 'Sem mudança', color: 'secondary' },
  FAILED: { label: 'Falha', color: 'danger' },
};

const FAILED = 'FAILED';

/** Situação do lote; o status que o painel não conhece aparece com o valor cru, em cinza. */
export function statusPresentation(status: string | null | undefined): Presentation {
  return presentation(STATUSES, status);
}

/** Resultado da linha; o resultado que o painel não conhece aparece com o valor cru, em cinza. */
export function outcomePresentation(outcome: string | null | undefined): Presentation {
  return presentation(OUTCOMES, outcome);
}

/**
 * Indica lote em andamento: o painel consulta o lote enquanto ele está `PENDING` ou `PROCESSING`, e qualquer outro
 * status, conhecido ou não, conta como terminal (proposta de design, Importações).
 */
export function isImportRunning(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export function isFailedLine(line: ImportLineResponse): boolean {
  return line.outcome === FAILED;
}

/** Textos da linha: a `message` do servidor e, embaixo, cada motivo de `reasonDetails` que não a repete. */
export interface LineTexts {
  readonly message: string | undefined;
  readonly reasons: readonly string[];
}

/**
 * Textos de uma linha para a tela. A recusa por regra de negócio traz um motivo por item de `reasonDetails`, com o
 * texto do servidor; a falha sem nenhum texto mostra a mensagem genérica, e o `code` cru nunca aparece.
 */
export function lineTexts(line: ImportLineResponse): LineTexts {
  const message = line.message?.trim() || undefined;
  const reasons = line.reasonDetails?.length
    ? problemMessages({ reasonDetails: line.reasonDetails, detail: message }).filter(
        (text) => text !== message,
      )
    : [];
  if (!message && !reasons.length && isFailedLine(line)) {
    return { message: GENERIC_REASON_MESSAGE, reasons };
  }
  return { message, reasons };
}

/** Id do lote no `Location` da resposta 202 (`.../products/imports/{id}`): o último trecho do caminho. */
export function locationId(location: string | null | undefined): string | undefined {
  const path = location?.split(/[?#]/)[0] ?? '';
  const segment = path.split('/').findLast(Boolean);
  return segment ? decodeURIComponent(segment) : undefined;
}

/**
 * Andamento em porcentagem inteira, arredondado para baixo para não marcar 100 antes da última linha. Vai como `value`
 * do `c-progress` com o máximo padrão: no CoreUI 5.7.42 a barra interna do `c-progress` não recebe o `max`.
 */
export function progressPercent(
  processed: number | null | undefined,
  total: number | null | undefined,
): number {
  return total ? Math.min(100, Math.floor(((processed ?? 0) * 100) / total)) : 0;
}

/** "1 linha", "800 linhas". */
export function countLines(count: number): string {
  return `${count} ${count === 1 ? 'linha' : 'linhas'}`;
}

function presentation(
  table: Readonly<Record<string, Presentation>>,
  code: string | null | undefined,
): Presentation {
  if (code && Object.prototype.hasOwnProperty.call(table, code)) {
    return table[code];
  }
  return { label: code || 'Desconhecido', color: 'secondary' };
}
