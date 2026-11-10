package dev.marketplace.cataloginventory.domain.offer;

/** Fase de uma promoção num instante: antes do início, dentro da janela ou depois do fim. */
public enum PromotionPhase {
  SCHEDULED, ACTIVE, ENDED
}
