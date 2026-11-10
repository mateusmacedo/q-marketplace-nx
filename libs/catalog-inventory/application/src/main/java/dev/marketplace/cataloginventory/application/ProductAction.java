package dev.marketplace.cataloginventory.application;

/**
 * O que o chamador pode fazer com um produto, calculado por
 * {@link ProductActions}. Os nomes saem na API como lista aberta, e o cliente
 * ignora o que não conhece.
 */
public enum ProductAction {
  EDIT, CHANGE_CATEGORY, ADD_SKU, REMOVE_SKU, EDIT_OFFER, EDIT_STOCK, SUBMIT, PAUSE, RESUME, ARCHIVE, APPROVE, REJECT, BLOCK, UNBLOCK
}
