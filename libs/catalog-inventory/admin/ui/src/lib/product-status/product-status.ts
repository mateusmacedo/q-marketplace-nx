/**
 * Status do produto como a API devolve. `OUT_OF_STOCK` não é gravado: é o `ACTIVE` sem saldo, calculado na leitura
 * (ADR 012). Tipo próprio desta lib, que não importa o `data-access` (ADR 029).
 */
export type ProductStatus =
  | 'DRAFT'
  | 'PENDING_MODERATION'
  | 'ACTIVE'
  | 'OUT_OF_STOCK'
  | 'PAUSED_BY_SELLER'
  | 'BLOCKED'
  | 'ARCHIVED';

/** Ordem das abas da lista de produtos. */
export const PRODUCT_STATUSES: readonly ProductStatus[] = [
  'DRAFT',
  'PENDING_MODERATION',
  'ACTIVE',
  'OUT_OF_STOCK',
  'PAUSED_BY_SELLER',
  'BLOCKED',
  'ARCHIVED',
];

/** Rótulo e cores do badge de um status. */
export interface ProductStatusLook {
  readonly label: string;
  readonly color: string;
  readonly textColor?: string;
}

const LOOKS: Readonly<Record<ProductStatus, ProductStatusLook>> = {
  DRAFT: { label: 'Rascunho', color: 'secondary' },
  PENDING_MODERATION: { label: 'Em moderação', color: 'info' },
  ACTIVE: { label: 'Ativo', color: 'success' },
  OUT_OF_STOCK: { label: 'Sem estoque', color: 'danger' },
  PAUSED_BY_SELLER: { label: 'Pausado', color: 'warning' },
  BLOCKED: { label: 'Bloqueado', color: 'dark' },
  ARCHIVED: { label: 'Arquivado', color: 'light', textColor: 'dark' },
};

/** Rótulo do status ("Em moderação"); status desconhecido volta como veio. */
export function productStatusLabel(status: string): string {
  return LOOKS[status as ProductStatus]?.label ?? status;
}

/** Cor do badge sólido do status, e a cor do texto quando o fundo é claro. */
export function productStatusLook(status: string): ProductStatusLook {
  return LOOKS[status as ProductStatus] ?? { label: status, color: 'secondary' };
}
