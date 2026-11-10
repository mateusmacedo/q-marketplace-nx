import {
  type CategoryAncestor,
  type CategoryResponse,
  type CategorySummary,
  listCategoryTree,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  buildCategoryOptions,
  CATEGORY_PATH_SEPARATOR,
  type CategoryNode,
  type CategoryOption,
} from '@marketplace/catalog-inventory-admin-ui';
import { apiData } from '@marketplace/shared-web-util';

/** Categoria da API no formato do seletor de `admin/ui`, que não importa o `data-access`. */
export function categoryNode(category: CategorySummary | CategoryResponse): CategoryNode {
  return {
    id: category.id ?? '',
    name: category.name ?? '',
    ancestors: (category.ancestors ?? []).map(ancestorNode),
    level: category.level,
    usable: category.usable,
    childCount: category.childCount,
  };
}

/** Caminho da categoria, da raiz até ela ("Vestuário › Camisas"), com os ancestrais que o servidor devolve. */
export function categoryPath(
  category: CategoryResponse | CategorySummary | null | undefined,
): string {
  if (!category) {
    return '';
  }
  return [...(category.ancestors ?? []).map((ancestor) => ancestor.name ?? ''), category.name ?? '']
    .filter(Boolean)
    .join(CATEGORY_PATH_SEPARATOR);
}

/**
 * Opções do seletor com as categorias que aceitam produtos (`usable=true`), na ordem da árvore. Lê todas as páginas de
 * `GET /categories/tree`, com o tamanho de página padrão do servidor.
 */
export async function loadUsableCategories(signal?: AbortSignal): Promise<CategoryOption[]> {
  const items: CategorySummary[] = [];
  let cursor: string | undefined;
  do {
    const page = await apiData(
      listCategoryTree({ query: { usable: true, ...(cursor ? { cursor } : {}) } }),
    );
    if (signal?.aborted) {
      return [];
    }
    items.push(...(page.items ?? []));
    cursor = page.nextCursor ?? undefined;
  } while (cursor);
  return buildCategoryOptions(items.map(categoryNode));
}

function ancestorNode(ancestor: CategoryAncestor): { id: string; name: string } {
  return { id: ancestor.id ?? '', name: ancestor.name ?? '' };
}
