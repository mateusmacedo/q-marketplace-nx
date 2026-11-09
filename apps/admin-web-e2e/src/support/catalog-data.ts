import { apiAs } from './api-client';
import { E2E_ENV } from './env';
import type { DemoUser } from './users';

export type AttributeType = 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'OPTION';

export interface Attribute {
  readonly code: string;
  readonly label: string;
  readonly type: AttributeType;
  readonly required?: boolean;
  readonly variant?: boolean;
  readonly options?: readonly string[];
}

export interface Category {
  readonly id: string;
  readonly parentId: string | null;
  readonly code: string;
  readonly name: string;
  readonly active: boolean;
  readonly usable: boolean;
  readonly version: number;
  readonly attributes: readonly Attribute[];
}

export type ModerationDecision = 'APPROVE' | 'REJECT' | 'BLOCK';

export interface ModerationReason {
  readonly code: string;
  readonly label: string;
  readonly decisions: readonly ModerationDecision[];
  readonly position: number;
  readonly active: boolean;
  readonly version: number;
}

export type ProductStatus =
  | 'DRAFT'
  | 'PENDING_MODERATION'
  | 'ACTIVE'
  | 'PAUSED_BY_SELLER'
  | 'OUT_OF_STOCK'
  | 'BLOCKED'
  | 'ARCHIVED';

export interface Money {
  readonly amount: number;
  readonly currency: string;
}

export interface Dimensions {
  readonly heightCm: number;
  readonly widthCm: number;
  readonly lengthCm: number;
  readonly weightGrams: number;
}

export interface SkuInput {
  readonly sellerSku: string;
  readonly variantAttributes?: Readonly<Record<string, string>>;
  readonly physical?: boolean;
  readonly dimensions?: Dimensions;
}

export interface Sku {
  readonly id: string;
  readonly sellerSku: string;
  readonly variantAttributes: Readonly<Record<string, string>>;
  readonly physical: boolean;
  readonly offer?: { readonly salePrice: Money; readonly listPrice?: Money | null; readonly version: number } | null;
  readonly stock?: { readonly onHand: number; readonly reserved: number; readonly available: number; readonly version: number } | null;
}

export interface Rejection {
  readonly reason: string;
  readonly reasonCodes: readonly string[];
  readonly note?: string | null;
}

export interface Product {
  readonly id: string;
  readonly storeId: string;
  readonly categoryId: string;
  readonly currency: string;
  readonly title: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly status: ProductStatus;
  readonly version: number;
  readonly skus: readonly Sku[];
  /** Códigos das ações que o chamador pode executar agora (`SUBMIT`, `APPROVE`...). */
  readonly actions: readonly string[];
  readonly publishingIssues?: readonly { readonly code: string; readonly message: string }[] | null;
  readonly lastRejection?: Rejection | null;
}

export interface StoreEligibility {
  readonly storeId: string;
  readonly status: 'PENDING' | 'APPROVED' | 'REJECTED';
  readonly canPublish: boolean;
  readonly visibleInStorefront: boolean;
}

/** Decisão do moderador: ao menos um motivo pronto ativo da decisão ou o complemento (`note`). */
export interface ModerationDecisionInput {
  readonly reasonCodes?: readonly string[];
  readonly note?: string;
}

/** Atributo de produto obrigatório da categoria padrão; `createReadyProduct` preenche com `Acme`. */
export const PRODUCT_ATTRIBUTE: Attribute = { code: 'marca', label: 'Marca', type: 'TEXT', required: true, variant: false };

/** Atributo de variação da categoria padrão; cada SKU escolhe uma das opções. */
export const VARIANT_ATTRIBUTE: Attribute = {
  code: 'tamanho',
  label: 'Tamanho',
  type: 'OPTION',
  required: true,
  variant: true,
  options: ['P', 'M', 'G']
};

export const DEFAULT_DIMENSIONS: Dimensions = { heightCm: 3, widthCm: 25, lengthCm: 30, weightGrams: 200 };

let sequence = 0;

/**
 * Sufixo único entre execuções e entre workers, só com letras minúsculas: serve em título, código de categoria, SKU e
 * no código de motivo, que o contrato limita a letras e hífens.
 */
export function uniqueSuffix(): string {
  sequence += 1;
  const worker = Number(process.env['TEST_PARALLEL_INDEX'] ?? 0);
  const random = Math.floor(Math.random() * 26 ** 2);
  return `${letters(Date.now())}${letters(worker, 1)}${letters(sequence, 2)}${letters(random, 2)}`;
}

/** Número em base 26 com letras; com `width`, fica com exatamente essa largura, para os campos não se confundirem. */
function letters(value: number, width?: number): string {
  let rest = width === undefined ? value : value % 26 ** width;
  let text = '';
  do {
    text = String.fromCharCode(97 + (rest % 26)) + text;
    rest = Math.floor(rest / 26);
  } while (rest > 0);
  return width === undefined ? text : text.padStart(width, 'a');
}

export interface CategorySpec {
  readonly name?: string;
  readonly code?: string;
  readonly parentId?: string;
  /** Padrão: `PRODUCT_ATTRIBUTE` e `VARIANT_ATTRIBUTE`. */
  readonly attributes?: readonly Attribute[];
}

/** Categoria ativa criada pelo `admin-demo`, por padrão com um atributo de produto obrigatório e um de variação OPTION. */
export async function createCategory(spec: CategorySpec = {}): Promise<Category> {
  const suffix = uniqueSuffix();
  return apiAs('admin-demo').post<Category>('/categories', {
    code: spec.code ?? `teste-${suffix}`,
    name: spec.name ?? `Categoria E2E ${suffix}`,
    parentId: spec.parentId,
    attributes: spec.attributes ?? [PRODUCT_ATTRIBUTE, VARIANT_ATTRIBUTE]
  });
}

export interface ModerationReasonSpec {
  /** Padrão: só `REJECT`. */
  readonly decisions?: readonly ('REJECT' | 'BLOCK')[];
  readonly label?: string;
  readonly code?: string;
}

/** Motivo pronto ativo criado pelo `admin-demo`; entra no fim da lista do moderador. */
export async function createModerationReason(spec: ModerationReasonSpec = {}): Promise<ModerationReason> {
  const suffix = uniqueSuffix();
  return apiAs('admin-demo').post<ModerationReason>('/moderation/reasons', {
    code: spec.code ?? `teste-${suffix}`,
    label: spec.label ?? `Motivo E2E ${suffix}`,
    decisions: spec.decisions ?? ['REJECT']
  });
}

/**
 * Desativa o motivo pelo `admin-demo`, para a lista do moderador não crescer a cada execução. Motivo não tem exclusão:
 * desativar é o fim de vida dele.
 */
export async function deactivateModerationReason(code: string): Promise<ModerationReason> {
  const admin = apiAs('admin-demo');
  const current = await admin.get<ModerationReason>(`/moderation/reasons/${encodeURIComponent(code)}`);
  return admin.post<ModerationReason>(`/moderation/reasons/${encodeURIComponent(code)}/deactivate`, undefined, {
    ifMatch: current.version
  });
}

export interface DraftProductSpec {
  readonly category: Category | string;
  readonly title?: string;
  readonly description?: string;
  readonly attributes?: Readonly<Record<string, string>>;
  readonly skus?: readonly SkuInput[];
}

/** Produto em rascunho do `seller-demo`; sem `attributes` nem `skus`, fica com as pendências de publicação abertas. */
export async function createDraftProduct(spec: DraftProductSpec): Promise<Product> {
  return apiAs('seller-demo').post<Product>('/products', {
    categoryId: idOf(spec.category),
    currency: 'BRL',
    title: spec.title ?? `Produto E2E ${uniqueSuffix()}`,
    description: spec.description,
    attributes: spec.attributes,
    skus: spec.skus
  });
}

export interface ReadyProductSpec {
  /** Categoria com `PRODUCT_ATTRIBUTE` e `VARIANT_ATTRIBUTE`, como a de `createCategory()`. */
  readonly category: Category | string;
  readonly title?: string;
  /** Opção do atributo de variação do único SKU; padrão `P`. */
  readonly variant?: string;
  readonly salePrice?: number;
  readonly listPrice?: number;
  readonly onHand?: number;
}

/**
 * Rascunho do `seller-demo` pronto para o envio: atributo obrigatório preenchido e um SKU físico com oferta (preço de
 * e por) e estoque. Devolve o produto relido, com a oferta e o saldo no SKU e sem pendências de publicação.
 */
export async function createReadyProduct(spec: ReadyProductSpec): Promise<Product> {
  const seller = apiAs('seller-demo');
  const suffix = uniqueSuffix();
  const draft = await createDraftProduct({
    category: spec.category,
    title: spec.title ?? `Produto E2E ${suffix}`,
    attributes: { [PRODUCT_ATTRIBUTE.code]: 'Acme' },
    skus: [
      {
        sellerSku: `E2E-${suffix}`,
        variantAttributes: { [VARIANT_ATTRIBUTE.code]: spec.variant ?? 'P' },
        physical: true,
        dimensions: DEFAULT_DIMENSIONS
      }
    ]
  });
  const sku = draft.skus[0];
  await seller.put(`/offers/${sku.id}`, {
    listPrice: { amount: spec.listPrice ?? 79.9, currency: 'BRL' },
    salePrice: { amount: spec.salePrice ?? 59.9, currency: 'BRL' }
  });
  await seller.put(`/inventory/skus/${sku.id}`, { onHand: spec.onHand ?? 5 }, { ifMatch: sku.stock?.version ?? 0 });
  const ready = await getProduct(draft.id);
  if (ready.publishingIssues?.length) {
    throw new Error(`O produto ${ready.id} ficou com pendências: ${ready.publishingIssues.map(issue => issue.message).join('; ')}`);
  }
  return ready;
}

/** Produto como o usuário o lê agora (padrão `seller-demo`); o moderador e o admin leem o de qualquer loja do tenant. */
export async function getProduct(product: Product | string, user: DemoUser = 'seller-demo'): Promise<Product> {
  return apiAs(user).get<Product>(`/products/${idOf(product)}`);
}

/** Envia para a moderação pelo `seller-demo`, com a versão atual no `If-Match`. Devolve o produto em PENDING_MODERATION. */
export async function submitProduct(product: Product | string): Promise<Product> {
  const current = await getProduct(product);
  return apiAs('seller-demo').post<Product>(`/products/${current.id}/submit`, undefined, { ifMatch: current.version });
}

/** Produto pronto já enviado: o ponto de partida da fila de moderação. */
export async function createPendingProduct(spec: ReadyProductSpec): Promise<Product> {
  return submitProduct(await createReadyProduct(spec));
}

/** Aprova pelo `moderator-demo`; exige a loja aprovada (o global setup garante a do `seller-demo`). */
export async function approveProduct(product: Product | string): Promise<Product> {
  return moderate(product, 'approve');
}

export async function rejectProduct(product: Product | string, decision: ModerationDecisionInput): Promise<Product> {
  return moderate(product, 'reject', decision);
}

export async function blockProduct(product: Product | string, decision: ModerationDecisionInput): Promise<Product> {
  return moderate(product, 'block', decision);
}

/** Elegibilidade da loja, lida pelo `admin-demo`; padrão, a loja do `seller-demo`. */
export async function getStoreEligibility(storeId: string = E2E_ENV.sellerStoreId): Promise<StoreEligibility> {
  return apiAs('admin-demo').get<StoreEligibility>(`/stores/${storeId}/eligibility`);
}

async function moderate(
  product: Product | string,
  action: 'approve' | 'reject' | 'block',
  decision?: ModerationDecisionInput
): Promise<Product> {
  const moderator = apiAs('moderator-demo');
  const current = await getProduct(product, 'moderator-demo');
  return moderator.post<Product>(`/moderation/products/${current.id}/${action}`, decision, { ifMatch: current.version });
}

function idOf(entity: { readonly id: string } | string): string {
  return typeof entity === 'string' ? entity : entity.id;
}
