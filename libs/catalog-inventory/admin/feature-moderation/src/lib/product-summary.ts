import type {
  CategoryResponse,
  EffectiveAttributeDto,
  ProductTransition,
  SellerStatus,
  SkuResponse,
  StatusChangeResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  CATEGORY_PATH_SEPARATOR,
  transitionActorLabel,
} from '@marketplace/catalog-inventory-admin-ui';

/** Atributo do produto com o rótulo da categoria, para o resumo do detalhe ("Material: Algodão"). */
export interface AttributeRow {
  readonly code: string;
  readonly label: string;
  readonly value: string;
}

const BOOLEAN_VALUES: Readonly<Record<string, string>> = { true: 'Sim', false: 'Não' };

/**
 * Atributos preenchidos, na ordem dos atributos efetivos da categoria (`effectiveAttributes`, da raiz para baixo), com
 * o rótulo de cada um. O valor que a categoria não define mais vem no fim, com o código como rótulo.
 */
export function attributeRows(
  values: Readonly<Record<string, string>> | null | undefined,
  attributes: readonly EffectiveAttributeDto[] | null | undefined,
): AttributeRow[] {
  const filled = Object.entries(values ?? {}).filter(([, value]) => value !== '');
  const known = new Map(
    (attributes ?? [])
      .filter((attribute) => attribute.code)
      .map((attribute) => [attribute.code, attribute]),
  );
  const position = (code: string) => {
    const index = (attributes ?? []).findIndex((attribute) => attribute.code === code);
    return index < 0 ? Number.MAX_SAFE_INTEGER : index;
  };
  filled.sort(([a], [b]) => position(a) - position(b));
  return filled.map(([code, value]) => {
    const attribute = known.get(code);
    return { code, label: attribute?.label || code, value: displayValue(value, attribute) };
  });
}

/** Valores da variação do SKU na ordem dos atributos da categoria ("P / Azul"). */
export function variantText(
  values: Readonly<Record<string, string>> | null | undefined,
  attributes: readonly EffectiveAttributeDto[] | null | undefined,
): string {
  return attributeRows(values, attributes)
    .map((row) => row.value)
    .join(' / ');
}

/** Caminho da categoria, da raiz até ela ("Vestuário › Camisas"), como o servidor o monta em `ancestors`. */
export function categoryPath(
  category: Pick<CategoryResponse, 'ancestors' | 'name'> | null | undefined,
): string {
  if (!category?.name) {
    return '';
  }
  return [...(category.ancestors ?? []).map((ancestor) => ancestor.name ?? ''), category.name]
    .filter(Boolean)
    .join(CATEGORY_PATH_SEPARATOR);
}

/** Início do UUID para caber na linha ("3f2a91c0…"); o completo vai no título do elemento. */
export function shortId(id: string | null | undefined): string {
  return id ? `${id.slice(0, 8)}…` : '';
}

/** Quantos SKUs têm saldo disponível, para o número em bloco do detalhe. */
export function skusInStock(skus: readonly SkuResponse[] | null | undefined): number {
  return (skus ?? []).filter((sku) => (sku.stock?.available ?? 0) > 0).length;
}

const TRANSITION_LABELS: Readonly<Record<ProductTransition, string>> = {
  CREATE: 'Criado',
  SUBMIT: 'Enviado para moderação',
  APPROVE: 'Aprovado',
  REJECT: 'Rejeitado',
  BLOCK: 'Bloqueado',
  UNBLOCK: 'Desbloqueado',
  PAUSE: 'Pausado',
  RESUME: 'Reativado',
  ARCHIVE: 'Arquivado',
  REVIEW_REQUIRED: 'Devolvido à moderação depois de uma edição',
};

/** Item do histórico como o detalhe mostra: o que aconteceu, quando, quem fez e os motivos da decisão. */
export interface HistoryEntry {
  readonly label: string;
  readonly at?: string;
  readonly actor?: string;
  /** Rótulos da época dos motivos prontos marcados; o painel não interpreta o texto derivado (`reason`). */
  readonly reasons: readonly string[];
  readonly note?: string;
}

/** Histórico do mais recente ao mais antigo, com os motivos da época e o complemento separados (ADR 030). */
export function historyEntries(
  changes: readonly StatusChangeResponse[] | null | undefined,
): HistoryEntry[] {
  return [...(changes ?? [])].reverse().map((change) => ({
    label: (change.transition && TRANSITION_LABELS[change.transition]) || 'Mudança de situação',
    at: change.at,
    actor: transitionActorLabel(change),
    reasons: (change.reasonLabels ?? []).map((reason) => reason.label ?? '').filter(Boolean),
    note: change.note?.trim() || undefined,
  }));
}

/** Rótulo e cor do badge da situação da loja no KYB/KYC. */
export interface SellerStatusLook {
  readonly label: string;
  readonly color: 'warning' | 'success' | 'danger' | 'secondary';
}

const SELLER_LOOKS: Readonly<Record<SellerStatus, SellerStatusLook>> = {
  PENDING: { label: 'Em análise', color: 'warning' },
  APPROVED: { label: 'Aprovada', color: 'success' },
  REJECTED: { label: 'Reprovada', color: 'danger' },
};

/** Situação da loja; status desconhecido aparece como veio, em cinza. */
export function sellerStatusLook(status: string | null | undefined): SellerStatusLook {
  return (
    SELLER_LOOKS[status as SellerStatus] ?? { label: status || 'Sem situação', color: 'secondary' }
  );
}

function displayValue(value: string, attribute: EffectiveAttributeDto | undefined): string {
  return attribute?.type === 'BOOLEAN' ? (BOOLEAN_VALUES[value] ?? value) : value;
}
