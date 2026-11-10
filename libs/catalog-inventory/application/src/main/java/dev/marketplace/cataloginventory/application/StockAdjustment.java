package dev.marketplace.cataloginventory.application;

import java.util.UUID;

/**
 * Ajuste do estoque físico de um SKU para o valor absoluto {@code onHand}, com o tenant, a loja e o produto a que o SKU
 * pertence.
 */
public record StockAdjustment(UUID tenantId, UUID storeId, UUID productId, UUID skuId, int onHand) {
}
