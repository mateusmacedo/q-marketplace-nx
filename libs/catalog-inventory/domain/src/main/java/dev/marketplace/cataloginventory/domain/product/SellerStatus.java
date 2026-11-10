package dev.marketplace.cataloginventory.domain.product;

/**
 * Situação KYB/KYC da loja vista pelo catálogo; só {@code APPROVED} pode ter
 * produto ativo.
 */
public enum SellerStatus {
  PENDING, APPROVED, REJECTED;

  /** A loja pode ter produto ativo e visível: só {@code APPROVED}. */
  public boolean isEligible() {
    return this == APPROVED;
  }
}
