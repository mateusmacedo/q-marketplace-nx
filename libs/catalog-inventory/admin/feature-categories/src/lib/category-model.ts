import {
  type AttributeType,
  AttributeTypeSchema,
  type CategoryAncestor,
  type CategoryBatchAction,
  CategoryBatchRequestSchema,
  type CategoryPage,
  type CategoryResponse,
  type CategorySummary,
  type EffectiveAttributeDto,
  listCategories,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  CATEGORY_PATH_SEPARATOR,
  type CategoryNode,
} from '@marketplace/catalog-inventory-admin-ui';
import type { BatchActionPresentation } from '@marketplace/shared-admin-ui';
import {
  apiData,
  type BatchVerb,
  fieldPath,
  fieldRules,
  isProblem,
  type Problem,
  toProblem,
} from '@marketplace/shared-web-util';

/** Tipos de atributo na ordem do contrato (`AttributeType`). */
export const ATTRIBUTE_TYPES: readonly AttributeType[] = AttributeTypeSchema.enum;

/** Rótulo de cada tipo de atributo; o tipo é enum fechado do contrato, com um componente no painel (ADR 031). */
export const ATTRIBUTE_TYPE_LABELS: Readonly<Record<AttributeType, string>> = {
  TEXT: 'Texto',
  INTEGER: 'Inteiro',
  DECIMAL: 'Decimal',
  BOOLEAN: 'Sim ou não',
  OPTION: 'Opção',
};

export function attributeTypeLabel(type: string | undefined): string {
  return (type && ATTRIBUTE_TYPE_LABELS[type as AttributeType]) || type || '';
}

/** Rótulo da raiz no breadcrumb e no mover. */
export const ROOT_LABEL = 'Raiz';

/** Passo do caminho de uma categoria, da raiz até ela. */
export interface CategoryStep {
  readonly id: string;
  readonly name: string;
}

/** Caminho da raiz até a própria categoria, montado com os `ancestors` que o servidor devolve. */
export function categoryTrail(
  category: Pick<CategoryResponse, 'id' | 'name' | 'ancestors'>,
): CategoryStep[] {
  return [...(category.ancestors ?? []), category].map((step) => ({
    id: step.id ?? '',
    name: step.name ?? '',
  }));
}

/** Caminho exibido ("Vestuário › Camisas"). */
export function categoryPath(
  category: Pick<CategoryResponse, 'id' | 'name' | 'ancestors'>,
): string {
  return categoryTrail(category)
    .map((step) => step.name)
    .join(CATEGORY_PATH_SEPARATOR);
}

/** Caminho até o pai ("Vestuário › Camisas"), ou `null` na raiz. */
export function parentPath(category: Pick<CategoryResponse, 'ancestors'>): string | null {
  const ancestors = category.ancestors ?? [];
  return ancestors.length
    ? ancestors.map((ancestor) => ancestor.name ?? '').join(CATEGORY_PATH_SEPARATOR)
    : null;
}

/** Linha da tabela de atributos do detalhe. */
export interface AttributeRow {
  readonly code: string;
  readonly label: string;
  readonly type: string;
  readonly required: boolean;
  readonly variant: boolean;
  readonly options: readonly string[];
  /** Categoria que define o atributo herdado. */
  readonly origin?: string;
}

/** Atributos próprios da categoria, como o servidor os guarda. */
export function ownAttributeRows(category: CategoryResponse): AttributeRow[] {
  return (category.attributes ?? []).map((attribute) => attributeRow(attribute));
}

/**
 * Atributos herdados: os de `effectiveAttributes` definidos por outra categoria, da raiz para baixo, com o nome da
 * categoria de origem lido dos `ancestors`.
 */
export function inheritedAttributeRows(category: CategoryResponse): AttributeRow[] {
  const names = new Map(
    (category.ancestors ?? []).map((ancestor: CategoryAncestor) => [ancestor.id, ancestor.name]),
  );
  return (category.effectiveAttributes ?? [])
    .filter((attribute) => attribute.categoryId !== category.id)
    .map((attribute) => attributeRow(attribute, names.get(attribute.categoryId) ?? ''));
}

function attributeRow(attribute: EffectiveAttributeDto, origin?: string): AttributeRow {
  return {
    code: attribute.code ?? '',
    label: attribute.label ?? '',
    type: attribute.type ?? '',
    required: attribute.required === true,
    variant: attribute.variant === true,
    options: attribute.options ?? [],
    ...(origin === undefined ? {} : { origin }),
  };
}

/** Categoria da lista no formato do seletor de `admin/ui`, com o caminho e os campos calculados pelo servidor. */
export function toCategoryNode(category: CategorySummary): CategoryNode {
  return {
    id: category.id ?? '',
    name: category.name ?? '',
    ancestors: (category.ancestors ?? []).map((ancestor) => ({
      id: ancestor.id ?? '',
      name: ancestor.name ?? '',
    })),
    level: category.level,
    usable: category.usable,
    childCount: category.childCount,
  };
}

/**
 * Lê todas as páginas de uma listagem por cursor, seguindo o `nextCursor` até a última. O tamanho da página fica com o
 * padrão do servidor.
 */
export async function loadAllPages(
  page: (cursor: string | undefined) => Promise<CategoryPage>,
): Promise<CategorySummary[]> {
  const items: CategorySummary[] = [];
  let cursor: string | undefined;
  do {
    const current = await page(cursor);
    items.push(...(current.items ?? []));
    cursor = current.nextCursor ?? undefined;
  } while (cursor);
  return items;
}

/** Categorias de um nível (`GET /categories?parentId=`), todas as páginas; sem pai, as raízes. */
export function loadLevel(parentId: string | null): Promise<CategorySummary[]> {
  return loadAllPages((cursor) =>
    apiData(
      listCategories({
        query: { ...(parentId ? { parentId } : {}), ...(cursor ? { cursor } : {}) },
      }),
    ),
  );
}

/**
 * Indica se a categoria pode ser excluída pelo que o painel sabe: sem subcategorias. A recusa por produtos
 * (`category-has-products`) fica com o servidor.
 */
export function canDelete(category: Pick<CategorySummary, 'childCount'>): boolean {
  // WHY: cópia pendente (ADR 031); saída: campo `deletable` calculado pelo servidor na resposta da categoria.
  return category.childCount === 0;
}

/**
 * Se a categoria selecionada entra no lote da ação. As categorias não trazem `actions`: a apresentação conta as inativas
 * para Ativar, as ativas para Desativar e todas para Excluir; o resto o servidor decide item a item.
 */
export function acceptsCategoryAction(category: CategorySummary, action: string): boolean {
  switch (action as CategoryBatchAction) {
    case 'ACTIVATE':
      return category.active === false;
    case 'DEACTIVATE':
      return category.active === true;
    case 'DELETE':
      // WHY: o servidor exclui o lote das subcategorias para as categorias pai, então um ramo vazio selecionado inteiro
      // sai numa chamada; a pai que ainda tiver subcategorias volta recusada, com o motivo.
      return true;
    default:
      return false;
  }
}

/** Botões da barra de lote, na ordem da proposta. */
export const CATEGORY_BATCH_ACTIONS: readonly BatchActionPresentation[] = [
  { id: 'ACTIVATE', label: 'Ativar', runningLabel: 'Ativando', color: 'success' },
  { id: 'DEACTIVATE', label: 'Desativar', runningLabel: 'Desativando', color: 'secondary' },
  { id: 'DELETE', label: 'Excluir', runningLabel: 'Excluindo', color: 'danger' },
];

/** Particípio de cada ação do lote, para o resumo do toast. */
export const CATEGORY_BATCH_VERBS: Readonly<Record<CategoryBatchAction, BatchVerb>> = {
  ACTIVATE: { one: 'ativada', many: 'ativadas', feminine: true },
  DEACTIVATE: { one: 'desativada', many: 'desativadas', feminine: true },
  DELETE: { one: 'excluída', many: 'excluídas', feminine: true },
};

/**
 * Problema de uma leitura feita por `resource`. O Angular embrulha o que não é `Error` num erro com o valor em `cause`,
 * e o problema RFC 9457 que o `apiData` lança fica lá.
 */
export function resourceProblem(error: unknown): Problem | null {
  if (!error) {
    return null;
  }
  const cause = error instanceof Error ? (error as { cause?: unknown }).cause : undefined;
  return toProblem(isProblem(cause) ? cause : error);
}

/** Itens por chamada de `POST /categories/batch`, lido do contrato. */
export function categoryBatchSize(): number {
  const size = fieldRules(CategoryBatchRequestSchema, 'items').maxItems;
  if (!size) {
    throw new Error('O contrato não declara o maxItems do lote de categorias');
  }
  return size;
}

export function isCategoryBatchAction(action: string): action is CategoryBatchAction {
  return Object.prototype.hasOwnProperty.call(CATEGORY_BATCH_VERBS, action);
}

const FIELD_NAMES: Readonly<Record<string, string>> = {
  name: 'Nome',
  code: 'Código',
  parentId: 'Categoria pai',
  attributes: 'Atributos',
  label: 'rótulo',
  type: 'tipo',
  required: 'obrigatório',
  variant: 'variante do SKU',
  options: 'opções',
};

/**
 * Nome do campo de um erro do 400 que não tem controle no formulário, para a lista do alerta:
 * `attributes[1].options[0]` vira "Atributo 2, opções".
 */
export function categoryFieldLabel(field: string): string {
  const [head = '', index, part] = fieldPath(field);
  if (head === 'attributes' && index !== undefined && /^\d+$/.test(index)) {
    const position = `Atributo ${Number(index) + 1}`;
    return part ? `${position}, ${FIELD_NAMES[part] ?? part}` : position;
  }
  return FIELD_NAMES[head] ?? field;
}
