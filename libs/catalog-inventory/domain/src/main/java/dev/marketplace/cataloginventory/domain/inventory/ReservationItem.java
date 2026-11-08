package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.Money;
import java.util.UUID;

/**
 * Item reservado com o snapshot de preço (valor e versão) vigente no momento da
 * reserva.
 */
public record ReservationItem(UUID skuId, UUID productId, UUID storeId, int quantity, Money unitPrice,
        long priceVersion) {
}
