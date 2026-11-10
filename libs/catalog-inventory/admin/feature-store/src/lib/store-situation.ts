import type {
  EligibilityResponse,
  SellerStatus,
} from '@marketplace/catalog-inventory-admin-data-access';

/** Rótulo e cor do callout da situação. */
export interface SellerStatusLook {
  readonly label: string;
  readonly color: 'warning' | 'success' | 'danger' | 'secondary';
}

const LOOKS: Readonly<Record<SellerStatus, SellerStatusLook>> = {
  PENDING: { label: 'Em análise', color: 'warning' },
  APPROVED: { label: 'Aprovada', color: 'success' },
  REJECTED: { label: 'Reprovada', color: 'danger' },
};

/** Rótulo e cor do status da loja no KYB/KYC; status desconhecido aparece como veio, em cinza. */
export function sellerStatusLook(status: SellerStatus | string | undefined): SellerStatusLook {
  return LOOKS[status as SellerStatus] ?? { label: status ?? 'Sem situação', color: 'secondary' };
}

/**
 * Efeitos da situação para os produtos da loja (proposta de design, seção "7. Situação da loja"). O front não
 * deduz o efeito do status: ele vem dos campos que o servidor calcula, `canPublish` e `visibleInStorefront`.
 */
export function storeEffects(
  eligibility: Pick<EligibilityResponse, 'canPublish' | 'visibleInStorefront'>,
): string[] {
  return [
    eligibility.canPublish
      ? 'Produtos aprovados vão para a vitrine, e os pausados podem ser reativados.'
      : 'Você pode cadastrar produtos, mas eles só são aprovados e reativados depois da aprovação no KYB/KYC.',
    eligibility.visibleInStorefront
      ? 'Os produtos ativos aparecem na vitrine.'
      : 'Os produtos não aparecem na vitrine.',
  ];
}
