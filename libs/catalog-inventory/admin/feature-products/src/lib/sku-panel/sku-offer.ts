import type {
  OfferResponse,
  PromotionResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { type Instant } from '@marketplace/shared-web-util';

/** Fase da promoção que o servidor calculou pelo relógio dele (`PromotionResponse.phase`). */
export type PromotionPhase = NonNullable<PromotionResponse['phase']>;

/** Promoção da lista do painel: as vigentes e as agendadas, que ainda podem ser canceladas. */
export interface ListedPromotion {
  readonly promotion: PromotionResponse;
  readonly phase: Exclude<PromotionPhase, 'ENDED'>;
}

/**
 * As promoções vigentes e agendadas da oferta, da que começa primeiro à última. A fase vem do servidor; a encerrada e a
 * sem fase conhecida ficam de fora, porque só o servidor sabe se ainda dá para cancelar.
 */
export function listedPromotions(offer: OfferResponse | null | undefined): ListedPromotion[] {
  return (offer?.promotions ?? [])
    .filter(
      (promotion): promotion is PromotionResponse & { phase: ListedPromotion['phase'] } =>
        promotion.phase === 'ACTIVE' || promotion.phase === 'SCHEDULED',
    )
    .map((promotion) => ({ promotion, phase: promotion.phase }))
    .sort(
      (left, right) =>
        (toMillis(left.promotion.startsAt) ?? 0) - (toMillis(right.promotion.startsAt) ?? 0),
    );
}

/** Rótulo da fase no badge. */
export function promotionPhaseLabel(phase: ListedPromotion['phase']): string {
  return phase === 'ACTIVE' ? 'Vigente' : 'Agendada';
}

/** Valor em dinheiro do formulário para a API: o número digitado com a moeda do produto, ou nada quando vazio. */
export function moneyOf(
  amount: number | null | undefined,
  currency: string,
): { amount: number; currency: string } | null {
  return amount === null || amount === undefined || Number.isNaN(amount)
    ? null
    : { amount, currency };
}

/** Indica se o físico digitado fica abaixo do que está reservado, para o aviso embaixo do campo. */
export function belowReserved(
  onHand: number | null | undefined,
  reserved: number | null | undefined,
): boolean {
  return typeof onHand === 'number' && typeof reserved === 'number' && onHand < reserved;
}

function toMillis(instant: Instant | null | undefined): number | undefined {
  if (instant === null || instant === undefined || instant === '') {
    return undefined;
  }
  const millis =
    instant instanceof Date
      ? instant.getTime()
      : typeof instant === 'number'
        ? instant
        : Date.parse(instant);
  return Number.isFinite(millis) ? millis : undefined;
}
