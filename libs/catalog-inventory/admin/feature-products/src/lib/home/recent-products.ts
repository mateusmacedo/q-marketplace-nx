import type { MoneyDto, ProductResponse } from '@marketplace/catalog-inventory-admin-data-access';

/** Uma linha da tabela dos produtos atualizados por último. */
export interface RecentProductRow {
  readonly id: string;
  readonly title: string;
  /** Inicial do título, no avatar. */
  readonly initial: string;
  readonly status: string;
  readonly category?: string;
  readonly skuCount: number;
  /** Disponível para venda, somado nos SKUs. */
  readonly available: number;
  /** Físico, somado nos SKUs. */
  readonly onHand: number;
  /** Disponível sobre o físico, de 0 a 100, para a barra. */
  readonly availablePercent: number;
  readonly stockColor: 'success' | 'warning' | 'danger';
  /** Menor preço vigente (`effectivePrice`) entre os SKUs com oferta. */
  readonly lowestPrice?: MoneyDto;
  readonly updatedAt?: string;
}

/**
 * Disponível sobre o físico, de 0 a 100. Vai como `value` do `c-progress` com o máximo padrão: no CoreUI 5.7.42 a barra
 * interna do `c-progress` não recebe o `max`.
 */
export function availablePercent(
  available: number | null | undefined,
  onHand: number | null | undefined,
): number {
  const total = Math.max(0, onHand ?? 0);
  return total > 0 ? Math.min(100, Math.round((Math.max(0, available ?? 0) * 100) / total)) : 0;
}

/** Monta a linha do produto com o nome da categoria, quando ele já chegou. */
export function recentProductRow(product: ProductResponse, category?: string): RecentProductRow {
  const skus = product.skus ?? [];
  const available = skus.reduce((sum, sku) => sum + Math.max(0, sku.stock?.available ?? 0), 0);
  const onHand = skus.reduce((sum, sku) => sum + Math.max(0, sku.stock?.onHand ?? 0), 0);
  const percent = availablePercent(available, onHand);
  const title = product.title ?? '';
  return {
    id: product.id ?? '',
    title,
    initial: (Array.from(title.trim())[0] ?? '?').toLocaleUpperCase('pt-BR'),
    status: product.status ?? '',
    category,
    skuCount: skus.length,
    available,
    onHand,
    availablePercent: percent,
    stockColor: percent > 50 ? 'success' : percent > 0 ? 'warning' : 'danger',
    lowestPrice: lowestPrice(product),
    updatedAt: product.updatedAt,
  };
}

function lowestPrice(product: ProductResponse): MoneyDto | undefined {
  return (product.skus ?? [])
    .map((sku) => sku.offer?.effectivePrice)
    .filter((price): price is MoneyDto => price !== undefined && price !== null)
    .reduce<MoneyDto | undefined>(
      (lowest, price) => (!lowest || price.amount < lowest.amount ? price : lowest),
      undefined,
    );
}
