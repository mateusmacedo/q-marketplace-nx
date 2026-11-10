import type { ParamMap } from '@angular/router';
import type {
  ListProductsData,
  RejectionResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { PRODUCT_STATUSES, type ProductStatus } from '@marketplace/catalog-inventory-admin-ui';
import { fieldErrors, type PageSize, type Problem, toPageSize } from '@marketplace/shared-web-util';

type ListProductsQuery = NonNullable<ListProductsData['query']>;

/** Ordem que `GET /products` aceita, como o contrato a declara (`title`, `createdAt` ou `updatedAt`, com `-`). */
export type ProductSort = NonNullable<ListProductsQuery['sort']>;

// INVARIANT: o `satisfies` confere estas chaves contra o enum fechado do contrato; uma ordem nova quebra a compilação.
const SORTS = {
  title: true,
  '-title': true,
  createdAt: true,
  '-createdAt': true,
  updatedAt: true,
  '-updatedAt': true,
} as const satisfies Record<ProductSort, true>;

// WHY: cópia pendente (ADR 031); saída: maxLength do parâmetro q no schema gerado.
/** Tamanho máximo da busca; o 400 do servidor continua sendo a palavra final. */
export const SEARCH_MAX_LENGTH = 100;

/** Recusa da busca no 400 de `GET /products` (o erro do campo `q`), para mostrar embaixo do campo. */
export function searchError(problem: Problem | null | undefined): string | null {
  return problem?.status === 400 ? (fieldErrors(problem)['q'] ?? null) : null;
}

/** Ordem da lista sem `sort` na URL: Atualizado, do mais recente para o mais antigo (proposta de design). */
export const DEFAULT_PRODUCT_SORT: ProductSort = '-updatedAt';

/** Recorte da lista lido da URL, já normalizado: o que vai para a API e o que a tela mostra. */
export interface ProductListQuery {
  /** Aba de status; `null` é Todos. */
  readonly status: ProductStatus | null;
  /** Busca no título e no código do SKU; vazia não filtra. */
  readonly q: string;
  readonly sort: ProductSort;
  readonly limit: PageSize;
  /** Cursor da página aberta; `null` é a primeira página. */
  readonly cursor: string | null;
  /** Loja do filtro de Produtos de todas as lojas; `null` mostra todas. */
  readonly storeId: string | null;
}

/** Aba de status da lista, com o rótulo no plural das abas. */
export interface StatusTab {
  readonly status: ProductStatus | null;
  readonly label: string;
}

const TAB_LABELS: Readonly<Record<ProductStatus, string>> = {
  DRAFT: 'Rascunho',
  PENDING_MODERATION: 'Em moderação',
  ACTIVE: 'Ativos',
  OUT_OF_STOCK: 'Sem estoque',
  PAUSED_BY_SELLER: 'Pausados',
  BLOCKED: 'Bloqueados',
  ARCHIVED: 'Arquivados',
};

/** Abas da lista, na ordem dos status, com Todos primeiro. */
export const STATUS_TABS: readonly StatusTab[] = [
  { status: null, label: 'Todos' },
  ...PRODUCT_STATUSES.map((status) => ({ status, label: TAB_LABELS[status] })),
];

const EMPTY_BY_STATUS: Readonly<Record<ProductStatus, string>> = {
  DRAFT: 'em rascunho',
  PENDING_MODERATION: 'em moderação',
  ACTIVE: 'ativo',
  OUT_OF_STOCK: 'sem estoque',
  PAUSED_BY_SELLER: 'pausado',
  BLOCKED: 'bloqueado',
  ARCHIVED: 'arquivado',
};

/**
 * Lê o recorte da URL (`?status=DRAFT&q=camiseta&sort=-createdAt&limit=50&cursor=...&storeId=...`). Status e ordem
 * que a lista não conhece voltam ao padrão, e o tamanho de página fora da lista volta ao padrão.
 */
export function readProductListQuery(params: ParamMap): ProductListQuery {
  const status = params.get('status');
  const sort = params.get('sort');
  return {
    status: PRODUCT_STATUSES.includes(status as ProductStatus) ? (status as ProductStatus) : null,
    q: params.get('q')?.trim() ?? '',
    sort:
      sort !== null && Object.prototype.hasOwnProperty.call(SORTS, sort)
        ? (sort as ProductSort)
        : DEFAULT_PRODUCT_SORT,
    limit: toPageSize(params.get('limit')),
    cursor: params.get('cursor') || null,
    storeId: params.get('storeId')?.trim() || null,
  };
}

/** Parâmetros de `GET /products` para o recorte, sem os vazios. */
export function productListRequest(query: ProductListQuery): ListProductsQuery {
  return {
    sort: query.sort,
    limit: query.limit,
    ...(query.status ? { status: query.status } : {}),
    ...(query.q ? { q: query.q } : {}),
    ...(query.cursor ? { cursor: query.cursor } : {}),
    ...(query.storeId ? { storeId: query.storeId } : {}),
  };
}

/** Recorte que a seleção em lote acompanha: mudar qualquer um destes limpa a seleção; o cursor não conta. */
export function selectionScope(query: ProductListQuery): Readonly<Record<string, unknown>> {
  return {
    status: query.status,
    q: query.q,
    sort: query.sort,
    limit: query.limit,
    storeId: query.storeId,
  };
}

/** Texto do estado vazio para o recorte ("Nenhum produto em rascunho."). */
export function emptyListMessage(query: ProductListQuery): string {
  if (query.q) {
    return `Nenhum produto encontrado para “${query.q}”.`;
  }
  const inStore = query.storeId ? ' nesta loja' : '';
  if (query.status) {
    return `Nenhum produto ${EMPTY_BY_STATUS[query.status]}${inStore}.`;
  }
  return query.storeId ? 'Nenhum produto nesta loja.' : 'Nenhum produto cadastrado.';
}

/**
 * Texto do tooltip do badge Rejeitado: os rótulos da época (`reasonLabels`) e o complemento (`note`), separados. O
 * código do motivo nunca aparece; `null` quando o produto não voltou da moderação.
 */
export function rejectionSummary(rejection: RejectionResponse | null | undefined): string | null {
  if (!rejection) {
    return null;
  }
  const labels = (rejection.reasonLabels ?? [])
    .map((reason) => reason.label?.trim() ?? '')
    .filter(Boolean);
  const note = rejection.note?.trim();
  const heading = labels.length === 1 ? 'Motivo' : 'Motivos';
  const parts = [
    labels.length ? `${heading}: ${labels.join(', ')}` : '',
    note ? `Complemento: ${note}` : '',
  ].filter(Boolean);
  return parts.length ? parts.join('. ') : 'Rejeitado na moderação, sem motivo informado.';
}

/** Início do UUID para a coluna estreita ("3f2a9c1e…"); o completo vai no tooltip. */
export function shortId(id: string | null | undefined): string {
  return id ? `${id.slice(0, 8)}…` : '';
}
