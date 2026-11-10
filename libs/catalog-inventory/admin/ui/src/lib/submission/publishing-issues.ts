import { translateReason } from '@marketplace/shared-web-util';

/**
 * Pendência de publicação como a API devolve em `publishingIssues` do produto e em `POST /products/{id}/check`: o
 * código, os valores do caso, o texto do servidor e o campo onde ela se resolve. Tipo próprio desta lib, que não
 * importa o `data-access` (ADR 029).
 */
export interface PublishingIssue {
  readonly code?: string;
  readonly params?: readonly string[];
  readonly message?: string;
  /** Caminho do campo: `categoryId`, `attributes.<código>`, `skus`, `skus[<sku>].offer` ou `skus[<sku>].variantAttributes.<código>`. */
  readonly field?: string;
}

/** Aba da tela do produto onde a pendência se resolve. */
export type ChecklistTab = 'conteudo' | 'skus';

/** Pendência do checklist, com o texto e o lugar onde ela se resolve. */
export interface ChecklistItem {
  readonly code?: string;
  readonly message: string;
  readonly tab: ChecklistTab;
  /** Campo do formulário: `categoryId`, `attributes.<código>`, `offer` ou `variantAttributes.<código>`. */
  readonly field?: string;
  /** SKU da pendência, nas pendências da aba de SKUs. */
  readonly sellerSku?: string;
}

const SKU_FIELD = /^skus(?:\[([^\]]+)\])?(?:\.(.+))?$/;

/** Transforma as pendências do servidor em itens do checklist, com a aba e o campo tirados do caminho `field`. */
export function checklistItems(
  issues: readonly PublishingIssue[] | null | undefined,
): ChecklistItem[] {
  return (issues ?? []).map((issue) => ({
    code: issue.code,
    message: translateReason(issue),
    ...issueTarget(issue.field),
  }));
}

/** Aba, campo e SKU do caminho que o servidor devolve; sem caminho, a pendência fica na aba de conteúdo. */
export function issueTarget(
  field: string | null | undefined,
): Pick<ChecklistItem, 'tab' | 'field' | 'sellerSku'> {
  const sku = SKU_FIELD.exec(field ?? '');
  if (sku) {
    const [, sellerSku, rest] = sku;
    return { tab: 'skus', ...(sellerSku ? { sellerSku } : {}), ...(rest ? { field: rest } : {}) };
  }
  return field ? { tab: 'conteudo', field } : { tab: 'conteudo' };
}
