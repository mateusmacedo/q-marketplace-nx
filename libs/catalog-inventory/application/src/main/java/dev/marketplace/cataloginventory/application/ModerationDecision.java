package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.ProductTransition;

/**
 * Decisão do moderador sobre um produto: aprovar, rejeitar ou bloquear, com a
 * transição que ela registra. Só rejeitar e bloquear levam motivo.
 */
public enum ModerationDecision {
  APPROVE(ProductTransition.APPROVE), REJECT(ProductTransition.REJECT), BLOCK(ProductTransition.BLOCK);

  private final ProductTransition transition;

  ModerationDecision(ProductTransition transition) {
    this.transition = transition;
  }

  public ProductTransition transition() {
    return transition;
  }
}
