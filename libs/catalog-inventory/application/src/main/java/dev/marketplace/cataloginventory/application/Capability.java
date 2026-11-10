package dev.marketplace.cataloginventory.application;

/**
 * Permissões de negócio do catálogo; o adaptador de segurança traduz papéis do
 * token nelas.
 */
public enum Capability {
  MANAGE_OWN_PRODUCTS, MODERATE_PRODUCTS, MANAGE_TAXONOMY, READ_TENANT_CATALOG, READ_SELLER_ELIGIBILITY, MANAGE_RESERVATIONS, MANAGE_MODERATION_REASONS
}
