package dev.marketplace.cataloginventory.security;

/**
 * Papéis esperados no claim de grupos do token (ver ADR 007 em
 * docs/decisoes.md).
 */
public final class Roles {
  public static final String SELLER = "seller";
  public static final String CATALOG_ADMIN = "catalog-admin";
  public static final String CATALOG_MODERATOR = "catalog-moderator";
  /** Contas de serviço do checkout e do OMS que reservam e baixam estoque. */
  public static final String CATALOG_RESERVATIONS = "catalog-reservations";

  private Roles() {
  }
}
