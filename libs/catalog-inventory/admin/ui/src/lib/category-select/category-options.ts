/** Ancestral da categoria como a API devolve, da raiz até o pai. */
export interface CategoryAncestorNode {
  readonly id: string;
  readonly name: string;
}

/**
 * Categoria como a feature a recebe da API, sem depender do `data-access`. O caminho, o nível, a usabilidade e a
 * contagem de subcategorias vêm calculados pelo servidor (`ancestors`, `level`, `usable` e `childCount`).
 */
export interface CategoryNode {
  readonly id: string;
  readonly name: string;
  /** Caminho da raiz até o pai; vazio na raiz. */
  readonly ancestors?: readonly CategoryAncestorNode[];
  /** Nível na árvore, a partir de 1 na raiz. */
  readonly level?: number;
  /** A categoria e os ancestrais estão ativos. */
  readonly usable?: boolean;
  /** Subcategorias diretas. */
  readonly childCount?: number;
}

/** Opção do seletor de categoria, com o caminho completo já montado. */
export interface CategoryOption {
  readonly id: string;
  /** Caminho dos nomes, da raiz até a categoria ("Vestuário › Camisas"). */
  readonly path: string;
  /** Nível na árvore, a partir de 1 na raiz. */
  readonly depth?: number;
  /** A categoria e os ancestrais estão ativos; inativa aparece marcada e só serve para mover. */
  readonly usable?: boolean;
  readonly childCount?: number;
}

/** Separador do caminho nos seletores. */
export const CATEGORY_PATH_SEPARATOR = ' › ';

/**
 * Monta as opções na ordem da árvore (cada categoria seguida das subcategorias, irmãs em ordem alfabética), com o
 * caminho exibido a partir dos ancestrais que o servidor devolve. Nível, usabilidade e subcategorias vêm como vieram.
 */
export function buildCategoryOptions(nodes: readonly CategoryNode[]): CategoryOption[] {
  return [...nodes]
    .map((node) => ({ node, trail: [...(node.ancestors ?? []), node] }))
    .sort((left, right) => compareTrails(left.trail, right.trail))
    .map(({ node, trail }) => ({
      id: node.id,
      path: trail.map((step) => step.name).join(CATEGORY_PATH_SEPARATOR),
      depth: node.level,
      usable: node.usable,
      childCount: node.childCount,
    }));
}

/** Ids das descendentes da categoria, para tirá-las do destino ao mover. */
export function categoryDescendants(nodes: readonly CategoryNode[], id: string): string[] {
  return nodes
    .filter((node) => node.ancestors?.some((ancestor) => ancestor.id === id))
    .map((node) => node.id);
}

// WHY: comparar nível a nível pelo nome e, no empate, pelo id mantém juntas as subcategorias de irmãs homônimas.
function compareTrails(
  left: readonly CategoryAncestorNode[],
  right: readonly CategoryAncestorNode[],
): number {
  for (let index = 0; index < Math.min(left.length, right.length); index++) {
    const byName = left[index].name.localeCompare(right[index].name, 'pt-BR');
    if (byName) {
      return byName;
    }
    if (left[index].id !== right[index].id) {
      return left[index].id < right[index].id ? -1 : 1;
    }
  }
  return left.length - right.length;
}
